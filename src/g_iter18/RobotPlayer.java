package g_iter18;

import battlecode.common.*;
import java.util.Random;

/**
 * The current implementation of our Battlecode 2022 bot.
 *
 * This file evolves over time as the training loop in TRAINING_ALGORITHM.md runs.
 * Each accepted iteration is snapshotted into src/<name>/ and added to the
 * opponent set; git history is the "undo" mechanism for rejected changes.
 *
 * ITERATION 3: keep Iteration 1's soldier COMBAT logic verbatim; change only
 *   what a soldier does with no enemy in sight -- from "mirror of my own
 *   position" to ONE army-wide objective (threatened Archon > known enemy
 *   Archon > mirror of our first Archon start). Miners flee combat units;
 *   Archons track a home-threat flag.
 *
 * ITERATION 4 (solution to the verified Iteration-3 hypothesis: "greedy pather
 *   can't get around the big rubble-wall blocks on obstacle-dense maps, so the
 *   army splinters and is destroyed piecemeal -- 50 idle miners, ~10 live
 *   soldiers, Archons annihilated r804 on pillars").
 *   Attempt 1 (navigation only) improved but still lost pillars/B; attempt 2:
 *     (a) 8-direction scored pather -- rubble avoidance + heading momentum, so
 *         the army traces obstacle edges as one body (see moveToward);
 *     (b) hard miner cap so the build budget goes to the army, not to 50 idle
 *         miners.
 *
 * ITERATION 5 (solution to the verified Iteration-4 hypothesis: "the fixed cap
 *   of 16 starves lead income on larger/obstacle maps -- after killing 2 of 3
 *   enemy Archons by r370 our soldiers collapse 11->1 while the enemy's uncapped
 *   economy rebuilds to 25, annihilating us r711 on pillars").
 *   Attempt 1 (map-scaled cap + "keep mining while lead >= 120") lost fast on
 *   lead-rich maps -- dropped. Attempt 2: miners ramp with time to the map cap.
 *
 * ITERATION 6 (solution to the verified Iteration-5 hypothesis: "the time-ramp
 *   dribbles miners in all game, so economy AND army lag a 'cap then 100% army'
 *   opponent -- g_iter4 hit 70 soldiers by r150 vs our 13, annihilation r150 on
 *   maptestsmall"): drop the ramp entirely. Build straight to a modest
 *   map-scaled cap (W*H/45, clamped 16..34 -- always >= g_iter4's flat 16), then
 *   pure army. --metrics confirmed our early economy had been *worse* than a
 *   flat 16. See minerCap() and runArchon().
 */
public strictfp class RobotPlayer {

    static final Random rng = new Random(6147);
    static final Direction[] DIRS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    static final int SA_MINERS = 1, SA_SOLDIERS = 2, SA_ATTACKS = 3, SA_BUILDS = 4;
    static final int SA_OUR_ARCHON_0 = 5;      // 5..8
    static final int SA_ENEMY_ARCHON_0 = 20;   // 20..23
    static final int SA_HOME_THREAT = 30;      // packed loc of a threatened Archon, 0 = none
    static final int SA_LEAD_0 = 9;            // 9..16  -- packed locs of known rich lead tiles, 0 = empty
    static final int SA_LEAD_N = 8;
    static final int SA_STAMP = 17;            // round the per-round shared state was last rolled
    static final int SA_MIN_ACC = 18, SA_SOL_ACC = 19;  // this-round live-count accumulators
    static final int SA_FOCUS = 24;            // packed loc of the army's focus-fire target, 0 = none
    static final int SA_ENEMY_SEEN = 25;       // last round any unit sighted an enemy combat unit
    static final int SA_ECON_THREAT = 26, SA_ECON_RND = 27;  // ITERATION 22: packed loc + round a
                                                // raided Miner last cried for help, 0 = none

    // ITERATION 7: --metrics on every strong opponent (sample_camelcase,
    // sample_afinals) across our weak maps shows one doctrine -- ~10-16 Miners,
    // Soldiers built continuously from ~r30, never a long pure-Miner opening.
    // Our area-scaled cap of 16-34 built far more Miners than most maps' lead
    // rewards, starving the army in the window that decides the game
    // (highway/valley/maze were 0/6 vs the ancestor pool). Use the small
    // doctrine cap by default -- but on lead-DENSE maps (maptestsmall: lots of
    // lead right next to spawn) more Miners genuinely pay off, so an Archon that
    // senses a rich home area on turn 1 latches a high cap.
    static boolean richHome = false;
    static int curArchons = 2;
    static int maxLeadMiners = -1;    // per-Archon Miner quota, fixed on turn 1
    static int myMinersSpawned = 0;   // Miners this Archon has built

    static boolean publishedStart = false;
    static int W, H;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        W = rc.getMapWidth();
        H = rc.getMapHeight();
        while (true) {
            try {
                census(rc);
                RobotInfo[] foes = rc.senseNearbyRobots(rc.getType().visionRadiusSquared,
                                                        rc.getTeam().opponent());
                // ITERATION 16: any unit that sights an enemy combat unit stamps
                // the round -- the Archons use this to cut the Miner opening
                // short and rush army, like camelcase's danger-target check.
                for (RobotInfo e : foes) if (combat(e.type) || e.type == RobotType.SAGE) {
                    if (rc.readSharedArray(SA_ENEMY_SEEN) < rc.getRoundNum())
                        rc.writeSharedArray(SA_ENEMY_SEEN, rc.getRoundNum());
                    break;
                }
                if (rc.getType() != RobotType.MINER) {
                    reportEnemyArchons(rc, foes);
                    checkHomeThreat(rc, foes);
                }
                switch (rc.getType()) {
                    case ARCHON:  runArchon(rc, foes);  break;
                    case MINER:   runMiner(rc, foes);   break;
                    case SOLDIER: runSoldier(rc, foes); break;
                    default:      break;
                }
            } catch (GameActionException e) {
                System.out.println(rc.getType() + " GameActionException"); e.printStackTrace();
            } catch (Exception e) {
                System.out.println(rc.getType() + " Exception"); e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    // ITERATION 12: real per-round census -- SA_MINERS/SA_SOLDIERS become
    // accurate ALIVE counts (was cumulative-ever, which let the build rule stop
    // replacing dead Soldiers vs a strong opponent). Every unit bumps a
    // this-round accumulator; the first unit to act each round publishes last
    // round's total and resets, and also clears SA_FOCUS so the army re-picks a
    // focus-fire target each round.
    static void census(RobotController rc) throws GameActionException {
        int r = rc.getRoundNum();
        if (rc.readSharedArray(SA_STAMP) != r) {
            rc.writeSharedArray(SA_STAMP, r);
            rc.writeSharedArray(SA_MINERS,   rc.readSharedArray(SA_MIN_ACC));
            rc.writeSharedArray(SA_SOLDIERS, rc.readSharedArray(SA_SOL_ACC));
            rc.writeSharedArray(SA_MIN_ACC, 0);
            rc.writeSharedArray(SA_SOL_ACC, 0);
            rc.writeSharedArray(SA_FOCUS, 0);
        }
        RobotType t = rc.getType();
        if (t == RobotType.MINER) bump(rc, SA_MIN_ACC);
        else if (combat(t))       bump(rc, SA_SOL_ACC);
    }
    static void bump(RobotController rc, int slot) throws GameActionException {
        int v = rc.readSharedArray(slot);
        if (v < 65535) rc.writeSharedArray(slot, v + 1);
    }

    // ------------------------------------------------------------- shared array
    static int pack(MapLocation l) { return 1 + l.x * 64 + l.y; }
    static MapLocation unpack(int v) { v--; return new MapLocation(v / 64, v % 64); }
    static boolean combat(RobotType t) {
        return t == RobotType.SOLDIER || t == RobotType.SAGE || t == RobotType.WATCHTOWER;
    }

    static void publishStart(RobotController rc) throws GameActionException {
        if (publishedStart) return;
        publishedStart = true;
        int me = pack(rc.getLocation());
        for (int s = SA_OUR_ARCHON_0; s < SA_OUR_ARCHON_0 + 4; s++) {
            int v = rc.readSharedArray(s);
            if (v == me) return;
            if (v == 0) { rc.writeSharedArray(s, me); return; }
        }
    }

    static void reportEnemyArchons(RobotController rc, RobotInfo[] foes) throws GameActionException {
        for (RobotInfo r : foes) {
            if (r.type != RobotType.ARCHON) continue;
            int p = pack(r.location), free = -1; boolean have = false;
            for (int s = SA_ENEMY_ARCHON_0; s < SA_ENEMY_ARCHON_0 + 4; s++) {
                int v = rc.readSharedArray(s);
                if (v == p) { have = true; break; }
                if (v == 0 && free < 0) free = s;
            }
            if (!have && free >= 0) rc.writeSharedArray(free, p);
        }
        for (int s = SA_ENEMY_ARCHON_0; s < SA_ENEMY_ARCHON_0 + 4; s++) {
            int v = rc.readSharedArray(s); if (v == 0) continue;
            MapLocation loc = unpack(v);
            if (rc.getLocation().isWithinDistanceSquared(loc, 2) && rc.canSenseLocation(loc)) {
                RobotInfo h = rc.senseRobotAtLocation(loc);
                if (h == null || h.type != RobotType.ARCHON || h.team == rc.getTeam())
                    rc.writeSharedArray(s, 0);
            }
        }
    }

    static void checkHomeThreat(RobotController rc, RobotInfo[] foes) throws GameActionException {
        for (int s = SA_OUR_ARCHON_0; s < SA_OUR_ARCHON_0 + 4; s++) {
            int v = rc.readSharedArray(s); if (v == 0) continue;
            MapLocation a = unpack(v); int near = 0;
            for (RobotInfo e : foes)
                if (combat(e.type) && e.location.isWithinDistanceSquared(a, 29)) near++;
            if (near >= 2) { rc.writeSharedArray(SA_HOME_THREAT, v); return; }
        }
    }

    /** the single point the whole army heads for when nobody has an enemy in sight */
    static MapLocation armyObjective(RobotController rc) throws GameActionException {
        int th = rc.readSharedArray(SA_HOME_THREAT);
        if (th != 0) return unpack(th);
        // ITERATION 22: checkHomeThreat only watches the ~5.4-tile radius
        // around a known Archon, so a raid on Miners mining 8-15+ tiles out
        // (Iteration 7's own note on lead-sparse maps) was invisible to it --
        // the g_iter6/chessboard raid (Iteration 20) killed Miners unopposed
        // with zero defensive response. A raided Miner cries for help directly
        // (see runMiner); honor that for a short window.
        int et = rc.readSharedArray(SA_ECON_THREAT);
        if (et != 0 && rc.getRoundNum() - rc.readSharedArray(SA_ECON_RND) < 40) return unpack(et);
        for (int s = SA_ENEMY_ARCHON_0; s < SA_ENEMY_ARCHON_0 + 4; s++) {
            int v = rc.readSharedArray(s); if (v != 0) return unpack(v);
        }
        int a0 = rc.readSharedArray(SA_OUR_ARCHON_0);
        if (a0 != 0) { MapLocation o = unpack(a0); return new MapLocation(W - 1 - o.x, H - 1 - o.y); }
        MapLocation me = rc.getLocation();
        return new MapLocation(W - 1 - me.x, H - 1 - me.y);
    }

    // ----------------------------------------------------------------- ARCHON
    static void runArchon(RobotController rc, RobotInfo[] foes) throws GameActionException {
        publishStart(rc);

        int th = rc.readSharedArray(SA_HOME_THREAT);
        if (th != 0 && unpack(th).isWithinDistanceSquared(rc.getLocation(), 4)) {
            int near = 0; for (RobotInfo e : foes) if (combat(e.type)) near++;
            if (near == 0) rc.writeSharedArray(SA_HOME_THREAT, 0);
        }

        curArchons = rc.getArchonCount();
        // ITERATION 16: our opening was catastrophically greedy vs the benchmark
        // bots -- on maze we built 14 Miners (the shared cap) over ~160 rounds
        // and ZERO Soldiers while sample_camelcase built 4 Miners and rushed us
        // with Soldiers from r20 (annihilation r250). Match camelcase: EACH
        // Archon spawns only `max(lead tiles it can touch, 5)` Miners -- fixed
        // on turn 1 -- then builds army; on lead-dense spawns (richHome) allow
        // more. The shared census still keeps totals honest for the raid-
        // replacement floor below.
        if (maxLeadMiners < 0) {
            int leadTiles = rc.senseNearbyLocationsWithLead(2, 1).length;   // 9-tile spawn cluster
            int homeLead = 0;
            for (MapLocation l : rc.senseNearbyLocationsWithLead(rc.getType().visionRadiusSquared, 1))
                homeLead += rc.senseLead(l);
            richHome = homeLead > 600;
            maxLeadMiners = richHome ? 18 : Math.max(leadTiles, 5);
        }
        int miners = rc.readSharedArray(SA_MINERS);
        // opening: this Archon fills its own Miner quota, then pure army --
        // but the moment an enemy combat unit is sighted anywhere, cut the
        // Miner opening (keep only ~4 each) and rush army. Late floor: if raids
        // drop the team below the floor, top back up.
        // contact cut: rush army when an enemy combat unit is near -- but NOT on
        // a lead-dense spawn (maptestsmall), where the economy is the whole game.
        boolean contact = !richHome
                && rc.getRoundNum() - rc.readSharedArray(SA_ENEMY_SEEN) < 60;
        int quota = contact ? 3 : maxLeadMiners;
        // ITERATION 20: the flat floor of 6 was a fixed, permanent economy cap
        // for the rest of any game once a raid dropped the team below it -- on
        // the g_iter6/chessboard loss (a 2000-round stalemate, no fast kill
        // either way) our Miners crashed 10->2 to a raid around r420-480, then
        // sat at exactly 6 for the remaining ~1500 rounds while g_iter6's
        // larger long-game economy (its old map-scaled cap, still 18+ miners at
        // r560) kept compounding -- 422 Soldiers to our 7 by r2000. Attempt 1
        // gated the ramp on !contact, but a long grinding game has the enemy
        // continuously in vision (SA_ENEMY_SEEN keeps refreshing), so contact
        // never goes false and the floor never moved -- confirmed on a re-run
        // (still flat at 5-7 through r2000). The floor is a distinct, ongoing
        // replenishment mechanism from the opening `quota` (which already
        // handles the early-rush response); let it climb with round number
        // unconditionally, capped at 20.
        // ITERATION 23 (attempt 2): r/200 cap 20 was too slow to matter on a
        // g_iter6/valley loss (economy already declining 15->8-10 by r700-800
        // while the floor sat at 9-10, always trailing). Attempt 1 (r/50 cap
        // 30) fixed that specific game's economy but caused a real peer
        // regression -- g_iter14/15/16 dropped to 40-45% (Gauntlet 22) -- most
        // likely because diverting build turns to extra Miners has a real
        // opportunity cost in games we're already winning on combat alone, and
        // the aggressive ramp paid that cost everywhere, not just in games that
        // needed it. Split the difference: r/100 cap 25.
        int floor = Math.min(6 + rc.getRoundNum() / 100, 25);
        boolean needMiners = myMinersSpawned < quota || miners < floor;
        RobotType want = needMiners ? RobotType.MINER : RobotType.SOLDIER;
        Direction best = null; int bestR = Integer.MAX_VALUE;
        for (Direction d : DIRS) {
            if (!rc.canBuildRobot(want, d)) continue;
            int r = rc.senseRubble(rc.getLocation().add(d));
            if (r < bestR) { bestR = r; best = d; }
        }

        // ITERATION 14: our Archon never used its action to REPAIR -- so
        // Iteration 12's retreat-to-heal sent wounded Soldiers home to an Archon
        // that just ignored them. An Archon and a build share one action; spend
        // it healing a hurt friendly droid in range (most-wounded first, combat
        // units before Miners) whenever there is one, else build. A healed
        // veteran beats a fresh recruit and denies the enemy a kill.
        RobotInfo heal = null;
        for (RobotInfo a : rc.senseNearbyRobots(RobotType.ARCHON.actionRadiusSquared, rc.getTeam())) {
            if (a.type == RobotType.ARCHON || a.type.isBuilding()) continue;
            if (a.health >= a.type.getMaxHealth(a.level) - 6) continue;
            if (heal == null
                    || (combat(a.type) && !combat(heal.type))
                    || (combat(a.type) == combat(heal.type) && a.health < heal.health))
                heal = a;
        }
        if (heal != null && rc.canRepair(heal.location)) {
            rc.repair(heal.location);
            rc.setIndicatorString("repair " + heal.type + "@" + heal.location);
        } else if (best != null) {
            rc.buildRobot(want, best); bump(rc, SA_BUILDS);
            if (want == RobotType.MINER) myMinersSpawned++;
        }
        report(rc);
    }

    // ------------------------------------------------------------------ MINER
    static void runMiner(RobotController rc, RobotInfo[] foes) throws GameActionException {
        MapLocation me = rc.getLocation();
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation t = me.translate(dx, dy);
                while (rc.canMineGold(t)) rc.mineGold(t);
                while (rc.canMineLead(t) && rc.senseLead(t) > 1) rc.mineLead(t);
            }

        RobotInfo threat = null; int td = Integer.MAX_VALUE;
        for (RobotInfo e : foes) if (combat(e.type)) {
            int d = me.distanceSquaredTo(e.location);
            if (d < td) { td = d; threat = e; }
        }
        if (threat != null) {
            // ITERATION 22: cry for help -- the army has no other way to learn
            // a raid is happening far from any known Archon (see armyObjective).
            rc.writeSharedArray(SA_ECON_THREAT, pack(me));
            rc.writeSharedArray(SA_ECON_RND, rc.getRoundNum());
            moveToward(rc, me.add(threat.location.directionTo(me)));
            return;
        }

        MapLocation goal = null; int bestLead = 0;
        for (MapLocation l : rc.senseNearbyLocationsWithLead(rc.getType().visionRadiusSquared, 2)) {
            int lead = rc.senseLead(l);
            if (lead > bestLead) { bestLead = lead; goal = l; }
        }
        // ITERATION 7: on lead-sparse maps (jellyfish/intersection/valley/pillars)
        // the deposits are 8-15 tiles from spawn -- out of a Miner's vision -- so
        // idle Miners random-walked in the home corner and the economy never
        // started. Broadcast rich tiles we find; idle Miners head for the
        // nearest known deposit instead of wandering.
        if (bestLead >= 10 && goal != null) publishLead(rc, goal);
        if (goal != null && !goal.equals(me)) { moveToward(rc, goal); report(rc); return; }

        MapLocation beacon = nearestLead(rc, me);
        if (beacon != null) moveToward(rc, beacon);
        else moveExplore(rc);
        report(rc);
    }

    static void publishLead(RobotController rc, MapLocation l) throws GameActionException {
        int p = pack(l), free = -1;
        for (int s = SA_LEAD_0; s < SA_LEAD_0 + SA_LEAD_N; s++) {
            int v = rc.readSharedArray(s);
            if (v == p) return;
            if (v == 0) { if (free < 0) free = s; continue; }
            if (unpack(v).isWithinDistanceSquared(l, 8)) return;   // nearby tile already flagged
        }
        if (free >= 0) rc.writeSharedArray(free, p);
    }

    static MapLocation nearestLead(RobotController rc, MapLocation me) throws GameActionException {
        MapLocation best = null; int bd = Integer.MAX_VALUE;
        for (int s = SA_LEAD_0; s < SA_LEAD_0 + SA_LEAD_N; s++) {
            int v = rc.readSharedArray(s); if (v == 0) continue;
            MapLocation l = unpack(v);
            if (rc.canSenseLocation(l) && rc.senseLead(l) < 6) { rc.writeSharedArray(s, 0); continue; }
            int d = me.distanceSquaredTo(l);
            if (d < bd) { bd = d; best = l; }
        }
        return best;
    }

    // ---------------------------------------------------------------- SOLDIER
    static MapLocation nearestHomeArchon(RobotController rc, MapLocation me) throws GameActionException {
        MapLocation best = null; int bd = Integer.MAX_VALUE;
        for (int s = SA_OUR_ARCHON_0; s < SA_OUR_ARCHON_0 + 4; s++) {
            int v = rc.readSharedArray(s); if (v == 0) continue;
            MapLocation l = unpack(v);
            int d = me.distanceSquaredTo(l);
            if (d < bd) { bd = d; best = l; }
        }
        return best;
    }

    /** better focus-fire target: enemy Archon first, then lower HP */
    static boolean betterTarget(RobotInfo a, RobotInfo b) {
        if (b == null) return true;
        if (a == null) return false;
        boolean aA = a.type == RobotType.ARCHON, bA = b.type == RobotType.ARCHON;
        if (aA != bA) return aA;
        return a.health < b.health;
    }

    static void runSoldier(RobotController rc, RobotInfo[] foes) throws GameActionException {
        MapLocation me = rc.getLocation();

        // local best target (Iteration 1 rule: enemy Archon first, else weakest)
        RobotInfo target = null;
        for (RobotInfo f : foes) if (betterTarget(f, target)) target = f;

        // ITERATION 12 -- FOCUS FIRE: the army concentrates damage on one enemy
        // via SA_FOCUS so it dies fast and stops firing back. Each Soldier
        // promotes its own pick if it beats the current focus (or the focus is
        // dead/gone); everyone shoots the shared focus when it is in range.
        int fv = rc.readSharedArray(SA_FOCUS);
        MapLocation fl = fv == 0 ? null : unpack(fv);
        RobotInfo fbot = (fl != null && rc.canSenseLocation(fl)) ? rc.senseRobotAtLocation(fl) : null;
        boolean fEnemy = fbot != null && fbot.team == rc.getTeam().opponent();
        if (fl != null && !fEnemy && rc.canSenseLocation(fl)) { rc.writeSharedArray(SA_FOCUS, 0); fl = null; }
        if (target != null && (fl == null || !fEnemy || betterTarget(target, fbot))) {
            rc.writeSharedArray(SA_FOCUS, pack(target.location));
            fl = target.location; fEnemy = true;
        }
        if (fEnemy && rc.canAttack(fl)) {
            // ITERATION 21: --metrics on a fresh sample_camelcase/maptestsmall
            // loss showed a real per-soldier attack-rate gap even at comparable
            // troop counts (r100-200: ours ~0.24 attacks/soldier/round vs
            // camelcase's ~0.35, ~1.47x). camelcase's Robot.tryAttack
            // repositions to a lower-rubble adjacent tile (still in action
            // range) before firing whenever able -- move and attack are
            // separate per-turn resources, so this costs nothing and speeds up
            // next turn's cooldown recovery. We never did this.
            repositionForRubble(rc, fl);
            if (rc.canAttack(fl)) { rc.attack(fl); bump(rc, SA_ATTACKS); }
            rc.setIndicatorString("focus " + fl);
            return;
        }

        // ITERATION 18 (was 15): --metrics + --indicators on the g_iter12 loss to
        // sample_camelcase/maptestsmall showed our 31-soldier army ground to 0
        // over 42 rounds while camelcase lost ~1 soldier in return -- of 501
        // sampled A-soldier-turns in the crash window, 28% were "heal" (this
        // retreat) and only 7% were actually attacking. camelcase's own Soldier
        // only retreats at HP<10, or HP<16 AND already within ~6 tiles of home
        // (Robot.java distanceToArchon<34) -- it never pulls a moderately-wounded
        // soldier out of a fight far from home the way HP<=15-anywhere did.
        // Raise the bar to critical-only (<=10) so soldiers keep trading instead
        // of abandoning an away-from-home fight at the first scratch.
        MapLocation home = nearestHomeArchon(rc, me);
        boolean nearHome = home != null && me.isWithinDistanceSquared(home, 20);
        if (rc.getHealth() <= 10 && home != null && !nearHome) {
            if (target != null && rc.canAttack(target.location)) { rc.attack(target.location); bump(rc, SA_ATTACKS); }
            moveToward(rc, home);
            if (target != null && rc.canAttack(target.location)) { rc.attack(target.location); bump(rc, SA_ATTACKS); }
            rc.setIndicatorString("heal");
            return;
        }

        if (target != null && rc.canAttack(target.location)) {
            rc.attack(target.location); bump(rc, SA_ATTACKS);
            rc.setIndicatorString("attack " + target.type);
            return;
        }
        if (target != null) {
            moveToward(rc, target.location);
            if (rc.canAttack(target.location)) { rc.attack(target.location); bump(rc, SA_ATTACKS); }
            rc.setIndicatorString("advance " + target.location);
            return;
        }
        // ITERATION 19: --indicators on the g_iter13 loss to
        // sample_camelcase/maptestsmall showed idle soldiers ignore SA_FOCUS --
        // the shared focus-fire location engaged soldiers already broadcast --
        // and always march to the static armyObjective guess instead, so once a
        // fight starts only the units already in it fight while reinforcements
        // keep walking to a stale point (62% "objective" state, 9% actually
        // attacking in the sample). Head for a live fight when one is known; it
        // isn't speculative like armyObjective, so skip the mass-gate below.
        int liveFocus = rc.readSharedArray(SA_FOCUS);
        if (liveFocus != 0) {
            moveToward(rc, unpack(liveFocus));
            rc.setIndicatorString("reinforce " + unpack(liveFocus));
            return;
        }
        // ---- no enemy in sight, no live fight: march to the army objective ----
        MapLocation obj = armyObjective(rc);

        // ITERATION 9: the Dijkstra army trickled forward one Soldier at a time
        // toward the *speculative* objective (enemy mirror), so on open maps
        // (chessboard) it left home undefended and got its lead elements chewed
        // up. If the objective is only a guess (no home threat, no *sighted*
        // enemy Archon), don't advance alone -- wait until at least 3 friendly
        // Soldiers are in vision, so the army rolls forward as a mass. Once an
        // enemy Archon is actually sighted, everyone commits regardless.
        boolean known = rc.readSharedArray(SA_HOME_THREAT) != 0
                || (rc.readSharedArray(SA_ECON_THREAT) != 0
                    && rc.getRoundNum() - rc.readSharedArray(SA_ECON_RND) < 40);
        for (int s = SA_ENEMY_ARCHON_0; !known && s < SA_ENEMY_ARCHON_0 + 4; s++)
            if (rc.readSharedArray(s) != 0) known = true;
        int rnd = rc.getRoundNum();
        if (!known && rnd > 60 && rnd < 250 && !me.isWithinDistanceSquared(obj, 25)) {
            int nFriend = 0;
            for (RobotInfo a : rc.senseNearbyRobots(rc.getType().visionRadiusSquared, rc.getTeam()))
                if (combat(a.type)) nFriend++;
            if (nFriend < 3) { rc.setIndicatorString("mass " + nFriend); return; }
        }
        moveToward(rc, obj);
        rc.setIndicatorString("objective " + obj);
    }

    // --------------------------------------------------------------- movement
    static Direction lastDir = null;   // per-robot momentum, so we trace obstacle
                                       // edges instead of oscillating against them
    static Dijkstra pather = null;     // vendored within-vision Dijkstra (see Dijkstra20)
    static MapLocation pathGoal = null;

    /** ITERATION 21: step onto a lower-rubble tile still in action range of
     * target, if one is free -- free speed for next turn's cooldown, doesn't
     * cost this turn's attack (movement and action are separate resources). */
    static void repositionForRubble(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        int cur = rc.senseRubble(rc.getLocation());
        int actionR2 = rc.getType().actionRadiusSquared;
        for (Direction d : DIRS) {
            if (!rc.canMove(d)) continue;
            MapLocation nxt = rc.getLocation().add(d);
            if (nxt.distanceSquaredTo(target) > actionR2) continue;
            if (rc.senseRubble(nxt) >= cur) continue;
            rc.move(d);
            return;
        }
    }

    /**
     * ITERATION 8: the 8-direction greedy scorer (Iterations 4-7) still strung
     * the army out on obstacle maps -- soldiers reached the enemy one at a time
     * and were destroyed piecemeal. Route movement through a real within-vision
     * Dijkstra (vendored from sample_camelcase, MIT -- see Dijkstra20.java): it
     * returns the first step of the true rubble-weighted shortest path to the
     * goal, so the army moves as one body. The old scorer stays as the fallback
     * for when Dijkstra's step is blocked by a unit.
     */
    static void moveToward(RobotController rc, MapLocation goal) throws GameActionException {
        if (!rc.isMovementReady() || goal == null) return;
        MapLocation me = rc.getLocation();
        if (me.equals(goal)) return;

        if (pather == null) pather = new Dijkstra20(rc);
        Direction blocked = goal.equals(pathGoal) && lastDir != null ? lastDir.opposite() : null;
        pathGoal = goal;
        Direction dj = pather.getBestDirection(goal, blocked);
        if (dj != null && rc.canMove(dj)) { rc.move(dj); lastDir = dj; return; }

        Direction best = null;
        double bestScore = -1e18;
        for (Direction d : DIRS) {
            if (!rc.canMove(d)) continue;
            MapLocation nxt = me.add(d);
            double score = -Math.sqrt(nxt.distanceSquaredTo(goal));   // nearer is better
            score -= rc.senseRubble(nxt) * 0.02;                      // dislike rubble
            if (d == lastDir) score += 0.6;
            else if (lastDir != null && (d == lastDir.rotateLeft() || d == lastDir.rotateRight()))
                score += 0.3;
            if (score > bestScore) { bestScore = score; best = d; }
        }
        if (best != null) { rc.move(best); lastDir = best; }
    }
    static void moveExplore(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        for (int i = 0; i < 8; i++) {
            Direction d = DIRS[rng.nextInt(DIRS.length)];
            if (rc.canMove(d)) { rc.move(d); return; }
        }
    }

    // ---------------------------------------------------------- instrumentation
    static void report(RobotController rc) throws GameActionException {
        if (rc.getType() != RobotType.ARCHON) return;
        Team me = rc.getTeam();
        int ea = 0;
        for (int s = SA_ENEMY_ARCHON_0; s < SA_ENEMY_ARCHON_0 + 4; s++)
            if (rc.readSharedArray(s) != 0) ea++;
        rc.setIndicatorString(
            "r" + rc.getRoundNum() + " lead=" + rc.getTeamLeadAmount(me)
            + " gold=" + rc.getTeamGoldAmount(me) + " archons=" + rc.getArchonCount()
            + " hp=" + rc.getHealth() + " miners=" + rc.readSharedArray(SA_MINERS)
            + " soldiers=" + rc.readSharedArray(SA_SOLDIERS) + " attacks=" + rc.readSharedArray(SA_ATTACKS)
            + " enemyArchons=" + ea + " homeThreat=" + (rc.readSharedArray(SA_HOME_THREAT) != 0)
        );
    }
}
