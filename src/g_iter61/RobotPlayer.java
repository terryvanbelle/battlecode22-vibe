package g_iter61;

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

    // ITERATION 126 (v1, restored as v5 after v4's "away from center" build-
    // placement variant tested clearly worse at full-Gauntlet scale --
    // widened the A/B gap to 55.8 points, up from baseline's 27.8, the
    // opposite of the goal): several sites tie-broke rubble/movement
    // decisions via fixed DIRS compass order -- confirmed root cause of a
    // real per-map tempo asymmetry (mirror-matching the bot against its own
    // snapshot: identical code favors side A on maptestsmall, side B on
    // squer, purely from spawn geometry, since a fixed absolute-direction
    // preference interacts with each map's specific, non-standardized spawn
    // corners). This restores the original "toward map center" tiebreak
    // for every site, which is the version that measurably narrowed the A/B
    // gap in verification (8-peer: 21.25pt baseline gap -> 13.8pt). Per
    // explicit user direction: this fix is judged important enough to keep
    // even with a net win-rate cost against the current peer roster (itself
    // partly an artifact of every peer sharing this project's own history
    // of the same bias, and partly small-sample noise against the
    // benchmark bots) -- do not revert this again without a specific,
    // verified reason.
    static MapLocation mapCenter() { return new MapLocation(W / 2, H / 2); }

    static Direction bestBuildDirection(RobotController rc, RobotType type, MapLocation from) throws GameActionException {
        MapLocation center = mapCenter();
        Direction best = null; int bestR = Integer.MAX_VALUE; int bestDist = Integer.MAX_VALUE;
        for (Direction d : DIRS) {
            if (!rc.canBuildRobot(type, d)) continue;
            MapLocation loc = from.add(d);
            int r = rc.senseRubble(loc);
            int dist = loc.distanceSquaredTo(center);
            if (r < bestR || (r == bestR && dist < bestDist)) {
                best = d; bestR = r; bestDist = dist;
            }
        }
        return best;
    }

    static Direction bestMovableDirection(RobotController rc, MapLocation from) throws GameActionException {
        MapLocation center = mapCenter();
        Direction best = null; int bestR = Integer.MAX_VALUE; int bestDist = Integer.MAX_VALUE;
        for (Direction d : DIRS) {
            if (!rc.canMove(d)) continue;
            MapLocation loc = from.add(d);
            int r = rc.senseRubble(loc);
            int dist = loc.distanceSquaredTo(center);
            if (r < bestR || (r == bestR && dist < bestDist)) {
                best = d; bestR = r; bestDist = dist;
            }
        }
        return best;
    }

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
    static final int SA_LAST_SOLDIER_BUILDER = 31, SA_LAST_SOLDIER_RND = 32;  // ITERATION 61:
                                                // packed loc + round of the Archon that most
                                                // recently built a Soldier (fairness cooldown)
    static final int SA_SOLDIER_HUNGRY = 33, SA_SOLDIER_HUNGRY_RND = 34;  // packed loc + round
                                                // of an Archon that wanted a Soldier but
                                                // couldn't afford one this round
    static final int SA_RAID_BUCKET = 35, SA_RAID_COUNT = 36;  // ITERATION 73: round/50 bucket +
                                                // raid-cry count within that bucket -- a self-
                                                // calibrating throttle for Soldiers responding
                                                // to SA_ECON_THREAT (see runSoldier)
    static final int SA_LAB_BUILT = 37;        // ITERATION 93: 0 until any Builder completes a
                                                // Laboratory, then 1 forever -- lets an Archon
                                                // tell "still incomplete" apart from "already
                                                // done", see needBuilder in runArchon
    static final int SA_SAGE_SEEN = 38, SA_SAGE_SEEN_RND = 39;  // ITERATION 96: packed loc +
                                                // round of the most recent enemy Sage sighting
                                                // by ANY unit -- lets a Builder react to a Sage
                                                // it cannot itself see (SAGE.actionRadiusSquared
                                                // exceeds BUILDER.visionRadiusSquared, the gap
                                                // this session's whole sample_afinals thread
                                                // converged on)

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
    static int myBuildersSpawned = 0; // ITERATION 30: Builders this Archon has built (cap 1)
    static MapLocation myLeadTarget = null;  // ITERATION 35: this Miner's committed beacon
    static int relocateSteps = 0;     // ITERATION 34: movement steps taken this relocation
    static boolean hasRelocated = false;  // cap: at most one relocation per Archon per game
    static int wtRelocateSteps = 0;   // ITERATION 78: same, for Watchtower
    static boolean wtHasRelocated = false;

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
                // ITERATION 96: Sage's actionRadiusSquared (25) exceeds a
                // Soldier/Miner/Builder's own visionRadiusSquared (20) -- a
                // real gap this session confirmed lets a Sage kill a Builder
                // that structurally cannot see it coming (the mechanism the
                // whole sample_afinals gold-economy thread converged on).
                // Any unit that DOES see a Sage (Archon/Watchtower have a
                // bigger visionRadiusSquared=34, so they'll often spot one
                // before a nearby Builder ever could) reports its location,
                // so runBuilder can react to a threat outside its own vision.
                for (RobotInfo e : foes) if (e.type == RobotType.SAGE) {
                    rc.writeSharedArray(SA_SAGE_SEEN, pack(e.location));
                    rc.writeSharedArray(SA_SAGE_SEEN_RND, rc.getRoundNum());
                    break;
                }
                if (rc.getType() != RobotType.MINER) {
                    reportEnemyArchons(rc, foes);
                    checkHomeThreat(rc, foes);
                }
                switch (rc.getType()) {
                    case ARCHON:     runArchon(rc, foes);     break;
                    case MINER:      runMiner(rc, foes);      break;
                    case SOLDIER:    runSoldier(rc, foes);    break;
                    case BUILDER:    runBuilder(rc, foes);    break;
                    case WATCHTOWER: runWatchtower(rc, foes); break;
                    case SAGE:       runSoldier(rc, foes);     break;
                    case LABORATORY: runLaboratory(rc);        break;
                    default:         break;
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
    // round's total and resets.
    // ITERATION 24: this used to also clear SA_FOCUS every round ("re-pick a
    // focus-fire target each round"). A g_iter9/valley loss showed our
    // formation visibly widening mid-fight (solSpread 8.3->13.2) while the
    // opponent's tightened (8.9->4.0) -- comparable attack volume (1.19x
    // theirs) but a much worse kill ratio (they lost 4, we lost 10). Iteration
    // 19's reinforcing soldiers march toward wherever SA_FOCUS points *right
    // now*; resetting it to 0 every round (before the first engaged Soldier
    // re-picks) means the rally point can jump or blink out from one round to
    // the next, scattering the approach instead of a stable convergence. The
    // existing dead-target check in runSoldier already clears SA_FOCUS when
    // the tracked enemy is confirmed gone -- that's sufficient; drop the
    // blanket per-round reset here.
    static void census(RobotController rc) throws GameActionException {
        int r = rc.getRoundNum();
        if (rc.readSharedArray(SA_STAMP) != r) {
            rc.writeSharedArray(SA_STAMP, r);
            rc.writeSharedArray(SA_MINERS,   rc.readSharedArray(SA_MIN_ACC));
            rc.writeSharedArray(SA_SOLDIERS, rc.readSharedArray(SA_SOL_ACC));
            rc.writeSharedArray(SA_MIN_ACC, 0);
            rc.writeSharedArray(SA_SOL_ACC, 0);
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

        // ITERATION 34: Archon relocation -- a real camelcase mechanic never
        // attempted this session, aimed squarely at the reinforcement-
        // distance problem documented in Iterations 29 and 33 (a
        // sample_afinals/highway loss spent 64% of Soldier-turns just
        // marching toward a *stable* live-fight point across 200 rounds --
        // genuine cross-map distance, not a targeting bug). New units always
        // spawn from a static Archon; on a long game the front moves away
        // and every fresh Soldier faces a longer trip than the last. Confirmed
        // via javap on the actual battlecode22.jar (not just inference from
        // camelcase's code) that RobotMode.PORTABLE has canAct=false -- a
        // relocating Archon genuinely cannot build/repair/attack while
        // portable or mid-transform, so this is real, bounded downtime, not
        // free: gate it hard (once per Archon per game, only late, only when
        // provably safe, capped step budget) and rely on the Gauntlet to
        // catch anything this reasoning missed.
        if (rc.getMode() == RobotMode.PORTABLE) {
            boolean nearbyThreat = false;
            for (RobotInfo e : foes) if (combat(e.type)) { nearbyThreat = true; break; }
            // A threat showing up mid-relocation (rare, given the entry gate)
            // ends the trip early -- transform back to defend rather than
            // keep walking away from safety.
            if ((nearbyThreat || relocateSteps >= 6) && rc.canTransform()) {
                rc.transform();
                hasRelocated = true;
                rc.setIndicatorString("relocated, transforming back");
            } else if (rc.isMovementReady()) {
                moveToward(rc, armyObjective(rc));
                relocateSteps++;
                rc.setIndicatorString("relocating " + relocateSteps);
            }
            return;   // canAct is false in PORTABLE mode -- nothing else to do this turn
        }
        // (Attempt 1 required foes.length==0 -- zero enemy units anywhere in
        // the Archon's entire vision radius -- which never held true in a
        // sample_afinals/highway test where the Archon was already damaged
        // (195/600, 465/600 HP) by r500 from stray Sage fire: relocation
        // never got a chance to trigger. A single distant unit passing
        // through vision isn't a real threat to a relocation move; check for
        // combat units genuinely near the Archon instead of an empty vision.)
        boolean localThreat = false;
        for (RobotInfo e : foes) if (combat(e.type) && e.location.isWithinDistanceSquared(rc.getLocation(), 40)) { localThreat = true; break; }
        if (!hasRelocated && rc.getRoundNum() > 500 && th == 0 && !localThreat && rc.canTransform()) {
            MapLocation obj = armyObjective(rc);
            if (!rc.getLocation().isWithinDistanceSquared(obj, 400)) {  // >20 tiles from the action
                rc.transform();
                relocateSteps = 0;
                rc.setIndicatorString("begin relocate toward " + obj);
                return;
            }
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
        // ITERATION 101: traced a fresh sample_camelcase/maptestsmall loss
        // (richHome, 1-Archon) via a direct Miner-count growth-curve
        // comparison: our Miners actually LED camelcase's early (18 vs 11
        // by r31), but ours hard-plateaus at exactly `maxLeadMiners` (18 on
        // richHome) while camelcase's climb past us around r61-66 and keep
        // compounding unopposed the rest of the game. Root cause: this
        // `floor` -- the ONLY replenishment mechanism once the opening
        // quota (`myMinersSpawned < quota`) is satisfied -- climbs far too
        // slowly to matter on a richHome map: `min(6+round/100,25)` is only
        // 7 at r100, vs the map's own already-established 18-Miner opening
        // target. Once natural attrition drags Miners below 18, nothing
        // brings the count back up to what this map was already proven to
        // support -- it settles at the much lower ongoing floor instead.
        // richHome's 18-Miner quota (Iteration 7/16) was already judged
        // correct for maps like this; the floor just never carries that
        // judgment forward past the opening. Non-richHome maps' floor
        // formula is untouched -- it's been directly tuned by Iterations
        // 19/20/23 through real peer-Gauntlet iteration and this cycle's
        // trace gives no reason to revisit it there.
        if (richHome) floor = Math.max(floor, maxLeadMiners);
        // ITERATION 110 (HIGH-RISK STRUCTURAL): `sample_camelcase` reaches
        // 72 Miners in a single game (confirmed via --metrics this
        // session) while our own richHome ceiling caps at 25 and, per the
        // formula above, only reaches that after ~1900 rounds -- Iteration
        // 101 fixed richHome maps *reaching* their own 18-Miner target, but
        // never raised the ceiling past it. Iteration 23's own history
        // already shows *un-gated* ceiling increases cause a real peer
        // regression (opportunity cost against Soldier production on maps
        // that don't need the extra economy) -- same lesson Iteration 109
        // v1 just re-learned for Builders. Apply the same fix shape that
        // worked there: raise the ceiling and ramp rate substantially, but
        // ONLY for richHome maps (already proven, via Iteration 7/16 and
        // 109, to be exactly the maps with enough spare economy to absorb
        // it) -- non-richHome maps keep the exact, already peer-Gauntlet-
        // tuned formula untouched.
        if (richHome) floor = Math.max(floor, Math.min(6 + rc.getRoundNum() / 30, 50));
        // ITERATION 99: traced a sample_afinals/sandwich loss where this
        // Archon's build turns went almost entirely to replacing dying
        // Miners for the ~200 rounds it spent under direct, sustained Sage
        // siege (--metrics/--indicators: lead dipping by ~47 in lockstep
        // with miners incrementing, soldiers flat at 0 the whole window,
        // while enemy Sages grew 11->19 and archonHP bled from 600 to 216).
        // The floor is completely threat-blind -- unlike `quota` (which
        // `contact` already shrinks during an opening rush), it keeps
        // demanding up to 25 Miners regardless of an active siege, and
        // competes every single build turn with Soldier production, the
        // one thing that could actually contest the siege. `localThreat`
        // (a combat foe within 40 distSq of this Archon, already computed
        // above for the relocation gate) is a direct, cheap signal that
        // topping up the Miner floor right now is a losing trade -- let
        // Soldier win priority instead while under real, immediate threat.
        boolean needMiners = myMinersSpawned < quota || (miners < floor && !localThreat);
        // ITERATION 30: a fresh sample_camelcase/highway loss showed a clean,
        // low-combat economy race -- neither Archon takes real damage until
        // r570+, army sizes diverge from ~r300 purely on production (theirs
        // 24->132, ours 21->0). Two direct attempts at raising the Miner
        // floor to compete on economy alone were already rejected this
        // session for broad peer regressions (Iteration 26). Try a different,
        // additive lever instead: one Builder per Archon, once the opening
        // Miner quota is met and there's no active rush, to put up a
        // Watchtower at home -- static defense that doesn't compete with the
        // existing (already-tuned) Miner/Soldier balance the way a bigger
        // Miner floor does. Gated like camelcase's own Builder spawn: only
        // once team lead > 300.
        // (Attempt 1 reused `contact` to mean "no active rush" -- but that
        // means "enemy sighted recently", permanently true for the back half
        // of any long game with sustained visual contact, the same trap
        // Iteration 20's attempt 1 hit. Attempt 2 gated on `!needMiners`
        // instead -- but the floor keeps climbing with round number, so
        // Miners hover perpetually at/near it and `needMiners` is nearly
        // always true too, for the same reason: neither condition ever
        // actually clears in a real contested game, confirmed by --metrics
        // showing 0 Builders/Watchtowers the entire replay both times. Since
        // the Builder is self-limiting (max 1 ever, via myBuildersSpawned),
        // give it priority instead of waiting for a floor condition that
        // rarely clears -- one guaranteed build cycle once there's a baseline
        // economy and round, not conditioned on the Miner quota's state.)
        // ITERATION 31: a second Builder/Watchtower, gated to a later round
        // (400) than the first (100) so the extra investment only lands in
        // games that are actually running long -- exactly the class of game
        // (clean economy race, camelcase/highway-style) this mechanic
        // targets, without doubling the up-front cost in every game the way
        // Iteration 26's floor-boost attempts did.
        // ITERATION 109 (HIGH-RISK STRUCTURAL): every prior tuning attempt
        // in this area (26/30/31/54/55/56/58/64/90/93) treated the Builder/
        // Watchtower investment as a small, ~1-2-unit, mostly one-time
        // thing. But `sample_camelcase` -- the exact benchmark this whole
        // production-throughput-ceiling thread has been chasing all
        // session -- reaches 143 Watchtowers in a single game (confirmed
        // via --metrics this session). Each of our Builders only ever
        // builds one Watchtower in its lifetime (`builtWatchtower` is a
        // one-shot per-instance flag), so matching anything near that
        // scale requires far more Builder *production volume*, not better
        // use of the 1-2 we currently ever spawn. This is a deliberate,
        // bold swing per TRAINING_ALGORITHM.md's "High-risk structural
        // exploration" -- named capability gap (camelcase treats Builder-
        // driven static defense as an ongoing production channel, we
        // treat it as a rare one-off), not a narrow single-game fix. A
        // flat 4x bump was tried first (Iteration 109 v1, REJECTED): a
        // full Gauntlet found a severe, 100% one-directional, 20-game
        // regression concentrated entirely on `highway` across 12
        // different opponents -- that map is lead-scarce (already
        // established this session as a long, chaotic economy race where
        // Soldiers absorb income as fast as it arrives), so the extra
        // Builder investment starved Soldier production hardest exactly
        // where lead was already tightest. v2: gate the aggressive cap on
        // `richHome` (already computed above, the same lead-dense/
        // lead-scarce signal Iteration 7/16 already use for the Miner
        // opening quota) -- the bold investment only applies where the
        // economy can actually support it; lead-scarce maps like `highway`
        // keep the original, already-proven-safe cap.
        int builderCap = richHome
                ? (rc.getRoundNum() > 400 ? 8 : 4)
                : (rc.getRoundNum() > 400 ? 2 : 1);
        // ITERATION 90: this `lead > 300` bar has stood unchanged since
        // Iteration 30 -- every later attempt in this area (54/55/56/58/64)
        // tuned Builder *count* or a 3rd-Builder threshold, never this one.
        // Direct measurement this cycle (bot vs sample_afinals, 3 separate
        // maps, --metrics): team lead never exceeded 92 the entire game in
        // any of the 3 -- Soldiers absorb income as fast as it arrives in a
        // real contested game, so a 300 surplus (worth 4 un-built Soldiers
        // sitting idle) essentially never accumulates. `needBuilder` was
        // silently dead code against exactly the kind of opponent (a strong,
        // continuously-pressuring one) where the whole Watchtower/Laboratory/
        // Sage economy downstream of it (Iteration 64: confirmed correctly
        // functional once it fires) would matter most. Builder only costs 40
        // lead; lower the bar to something a real game can actually clear.
        // ITERATION 93: `myBuildersSpawned` counts builders ever *spawned*,
        // not currently alive -- it never decrements on death. Traced
        // directly this session (bot vs sample_afinals, multiple maps): the
        // one Builder built before round 400 dies to enemy pressure well
        // before finishing the Watchtower+Laboratory sequence (confirmed via
        // Iteration 92's own investigation, a Builder killed mid-route after
        // successfully fleeing once). Once that happens, `myBuildersSpawned`
        // stays at 1 forever, `builderCap` stays at 1 until round 400 --
        // the entire investment is silently abandoned for potentially
        // hundreds of rounds even though nothing was ever built and the
        // economy may still be perfectly healthy. Add a second, independent
        // trigger: if the team's Laboratory genuinely isn't built yet
        // (`SA_LAB_BUILT`, written by the Builder itself on completion) and
        // we've already started this investment (spawned >=1 Builder, so
        // this isn't the very first attempt racing the normal gate above),
        // keep retrying regardless of round or the scaling-focused cap --
        // up to a small bound (4) so a genuinely impossible map (e.g. no
        // room to ever place a Laboratory) doesn't spam Builders forever.
        boolean needReplacementBuilder = rc.readSharedArray(SA_LAB_BUILT) == 0
                && myBuildersSpawned >= 1 && myBuildersSpawned < 4
                && miners >= 8 && rc.getTeamLeadAmount(rc.getTeam()) > 120;
        boolean needBuilder = (myBuildersSpawned < builderCap && rc.getRoundNum() > 100
                && miners >= 8 && rc.getTeamLeadAmount(rc.getTeam()) > 120)
                || needReplacementBuilder;
        // ITERATION 64: restoring Iteration 58's proven-non-regressive gold
        // economy (Laboratory placed away from the Archon's build ring via a
        // Builder, transmute every round, spend gold on a Sage) -- 58.7% peer
        // in its own Gauntlet, roughly neutral, but it barely ever engaged in
        // the standard 10-map peer pool (needs a long, calm, high-lead-surplus
        // game to produce any gold at all). Testing it now specifically
        // against `sample_afinals`, the one opponent whose own doctrine is
        // built entirely around this mechanic, per Iteration 58's own "Next"
        // note. Sage costs 0 lead / 20 gold -- a fully parallel production
        // lane, so build one whenever affordable without touching the
        // existing Miner/Soldier tradeoff at all.
        // ITERATION 100: that "fully parallel... without touching the
        // existing tradeoff" premise was wrong -- this Archon has exactly
        // one build action per round, so a Sage ALWAYS displaces whatever
        // Miner/Soldier would otherwise have been built that round, same as
        // any other `want` branch. Traced directly on a `sample_camelcase/
        // maptestsmall` loss (a 1-Archon map): `--metrics` showed
        // `A_soldiers` collapsing 37->0 over ~200 rounds while `A_sages`
        // climbed 0->3 in that exact window, `A_labs` reaching 4, and team
        // lead sitting on an unspent 1000-2700 surplus the whole time --
        // the single build action was going to Sage (unconditional top
        // priority) and Builder (Lab upkeep) instead of the Soldier
        // reinforcement the ongoing siege badly needed. Same fix shape as
        // Iteration 99: don't let a lower-urgency investment (here, Sage --
        // a real, valuable unit, but not more urgent than defending against
        // an active attack) win over Soldier while under direct local
        // threat. Gold doesn't decay, so suppressing this costs nothing --
        // the Sage still gets built once the threat clears.
        // (v1 gated on `localThreat`, Iteration 99's own signal -- but that
        // only fires for a foe within the Archon's own vision, and on this
        // exact motivating map the front line sat well away from the
        // Archon; re-checked directly, v1 changed nothing, Sages still
        // climbed 0->3 in the same window. `contact` -- the existing
        // team-wide "recent enemy sighting" signal -- doesn't work either:
        // it's unconditionally false on richHome maps, and maptestsmall
        // (this exact motivating map) is richHome by definition. Read
        // SA_ENEMY_SEEN directly instead, without the richHome gate that
        // `contact` applies for an unrelated reason (the Miner-quota
        // decision, left untouched).)
        // ITERATION 115 (HIGH-RISK STRUCTURAL): traced a fresh
        // `bot vs g_iter21/maptestsmall` game and found gold climbing
        // steadily to 287 by r961 -- comfortably past the 20-gold Sage
        // cost, repeatedly -- yet **zero Sages built the entire game**.
        // Root cause: `recentEnemyContact` requires no enemy sighting for
        // 60 rounds, but `A_attacks` grew continuously the whole game
        // (0->10504) -- real, sustained wars essentially never have a
        // 60-round lull, so this gate never actually opens once combat
        // starts (the same "contact never clears in a real game" trap
        // Iteration 114 already found tonight for `needBuilder`, now
        // found blocking something different). This premise -- gold
        // being scarce/rare, so Sage almost never fires anyway -- was
        // true when Iteration 100 first added this gate, but Iterations
        // 109/110 have since made richHome economies genuinely large
        // (more Miners, more Labs), so gold now *is* real and abundant,
        // and this gate is now actively suppressing something valuable
        // rather than a rare edge case. Unlike Builder/Miner investment
        // (which cost ongoing lead, directly competing with Soldiers),
        // Sage costs 0 lead -- the only cost is the single build-action
        // turn itself, and at the observed accumulation rate that's
        // roughly once every ~55 rounds, not the frequent, sustained
        // drain Iteration 100 was originally protecting against.
        // Removing the gate entirely -- a bold swing per
        // TRAINING_ALGORITHM.md's high-risk track, not a narrow tweak,
        // since a shorter contact window would likely have the same
        // "never actually opens" problem at any reasonable window size.
        boolean wantSage = rc.getTeamGoldAmount(rc.getTeam()) >= RobotType.SAGE.buildCostGold;
        RobotType want = wantSage ? RobotType.SAGE
                : needBuilder ? RobotType.BUILDER
                : needMiners ? RobotType.MINER : RobotType.SOLDIER;
        // ITERATION 61: restoring Iteration 53's curArchons-gated,
        // sibling-hunger-aware fairness-yield (best-verified code on this
        // thread: 178/300=59.3%, 9 opponents improved, g_iter22-26 regress
        // -- confirmed via diagnosis to be ordinary timing-sensitivity in
        // already-close chessboard/intersection/pillars games, not a
        // fixable bug). Trying a shorter yield window (6 rounds, down from
        // 8) this time *with* the hunger gate active throughout -- the
        // earlier 5-round attempt (Iteration 52-era) that lost the
        // sandwich win was tested without the hunger gate, a different
        // interaction.
        boolean iAmHungry = want == RobotType.SOLDIER
                && rc.getTeamLeadAmount(rc.getTeam()) < RobotType.SOLDIER.buildCostLead;
        if (iAmHungry) {
            rc.writeSharedArray(SA_SOLDIER_HUNGRY, pack(rc.getLocation()));
            rc.writeSharedArray(SA_SOLDIER_HUNGRY_RND, rc.getRoundNum());
        }
        boolean yieldSoldier = false;
        if (want == RobotType.SOLDIER && curArchons > 1) {
            int lastBuilder = rc.readSharedArray(SA_LAST_SOLDIER_BUILDER);
            int lastRnd = rc.readSharedArray(SA_LAST_SOLDIER_RND);
            int hungryLoc = rc.readSharedArray(SA_SOLDIER_HUNGRY);
            int hungryRnd = rc.readSharedArray(SA_SOLDIER_HUNGRY_RND);
            boolean siblingHungry = hungryLoc != 0 && hungryLoc != pack(rc.getLocation())
                    && rc.getRoundNum() - hungryRnd < 8;
            if (lastBuilder == pack(rc.getLocation()) && rc.getRoundNum() - lastRnd < 6
                    && siblingHungry)
                yieldSoldier = true;
        }
        Direction best = yieldSoldier ? null : bestBuildDirection(rc, want, rc.getLocation());

        // ITERATION 14: our Archon never used its action to REPAIR -- so
        // Iteration 12's retreat-to-heal sent wounded Soldiers home to an Archon
        // that just ignored them. An Archon and a build share one action; spend
        // it healing a hurt friendly droid in range (most-wounded first, combat
        // units before Miners) whenever there is one, else build. A healed
        // veteran beats a fresh recruit and denies the enemy a kill.
        // ITERATION 91: that "whenever there is one" had no lower bound --
        // any unit missing more than 6 HP (a scratch: 12% of a Soldier's 50
        // max) qualified, with heal taking absolute, unconditional priority
        // over build, every single round, forever. Traced via a fresh
        // benchmark check (bot vs sample_camelcase/maptestsmall, a loss):
        // team lead climbed monotonically 5013->5095->...->5603 over a
        // 15-round window with the Archon's own indicator showing nothing
        // but "repairs SOLDIER"/"repairs MINER" every round -- team lead hit
        // 6815 unspent by r180 while our Soldier count crashed 36->0 and
        // the opponent's grew unopposed the whole time. A first attempt
        // (require missing >50% max HP, not just 6) only partially helped
        // (re-verified: lead still piled up to 5337, just slower) -- with
        // sustained combat pressure nearby, *some* unit is almost always
        // below whatever fixed bar is picked, so heal keeps winning every
        // round regardless of how large the fixed threshold is. The real
        // problem is unconditional priority, not the specific number: heal
        // should only pre-empt a build when there's no real backlog to
        // spend on (lead is scarce anyway, so healing is free) or the wound
        // is genuinely critical (worth interrupting production for). When
        // lead is abundant, let minor/moderate wounds wait -- they'll still
        // get healed once the backlog clears, or the unit will retreat
        // further per Iteration 18's own threshold (HP<=10, 20% max).
        // ITERATION 91 (v2): the first cut used `> 2x Soldier cost` (150) as
        // "abundant" -- an 8-peer reproduction sample looked clean (1 flip),
        // but a full 22-peer Gauntlet exposed a real, concentrated
        // regression: 6 flips, all on `highway`'s B side, against a wide
        // opponent range (g29, g32-36). Traced one (`g_iter32/highway/
        // botB`): our own lead only briefly ticked up to 151-180 around
        // r508 -- an entirely ordinary economic fluctuation, not the
        // thousands-deep hoarding crisis this fix targets -- but on
        // `highway` (a long, low-combat economy-race map per Iteration
        // 30's own note) even a brief, minor behavior change early can
        // cascade unpredictably over 1000+ remaining rounds. 150 was
        // catching completely healthy economies, not just the pathological
        // case. Raise the bar to something clearly past ordinary
        // fluctuation and closer to the actual crisis scale observed
        // (thousands, in the original `sample_camelcase` diagnosis).
        boolean leadAbundant = rc.getTeamLeadAmount(rc.getTeam()) > 600;
        RobotInfo heal = null;
        for (RobotInfo a : rc.senseNearbyRobots(RobotType.ARCHON.actionRadiusSquared, rc.getTeam())) {
            if (a.type == RobotType.ARCHON || a.type.isBuilding()) continue;
            int maxHp = a.type.getMaxHealth(a.level);
            if (a.health >= maxHp - 6) continue;
            if (leadAbundant && a.health * 5 > maxHp) continue;  // not critical (>20% HP) -- let the build queue go first
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
            else if (want == RobotType.BUILDER) myBuildersSpawned++;
            else if (want == RobotType.SOLDIER && curArchons > 1) {
                rc.writeSharedArray(SA_LAST_SOLDIER_BUILDER, pack(rc.getLocation()));
                rc.writeSharedArray(SA_LAST_SOLDIER_RND, rc.getRoundNum());
            }
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
            int prevLoc = rc.readSharedArray(SA_ECON_THREAT);
            int prevRnd = rc.readSharedArray(SA_ECON_RND);
            rc.writeSharedArray(SA_ECON_THREAT, pack(me));
            rc.writeSharedArray(SA_ECON_RND, rc.getRoundNum());
            // ITERATION 73: count this as a new raid event (for the
            // self-calibrating rescue throttle in runSoldier) only if it's
            // not just the same ongoing siege re-reporting every round --
            // a different Miner/location or a stale previous alert (>20
            // rounds old) means a genuinely new raid, not a continuation.
            boolean freshRaid = prevLoc != pack(me) || rc.getRoundNum() - prevRnd > 20;
            if (freshRaid) {
                int bucket = rc.getRoundNum() / 50;
                if (rc.readSharedArray(SA_RAID_BUCKET) != bucket) {
                    rc.writeSharedArray(SA_RAID_BUCKET, bucket);
                    rc.writeSharedArray(SA_RAID_COUNT, 1);
                } else {
                    rc.writeSharedArray(SA_RAID_COUNT, rc.readSharedArray(SA_RAID_COUNT) + 1);
                }
            }
            moveToward(rc, me.add(threat.location.directionTo(me)));
            return;
        }
        // ITERATION 96: same Sage-vision-gap blind spot as Builders (Sage's
        // actionRadiusSquared exceeds a Miner's own visionRadiusSquared) --
        // Miners wander even more widely than Builders (Iteration 86's
        // exploration momentum), so if anything they're more exposed to it.
        // Fall back to a recent, nearby SA_SAGE_SEEN report the same way
        // runBuilder does. Deliberately simpler than the direct-threat
        // branch above (no raid-cry/throttle bookkeeping) -- this is a
        // precautionary dodge of a sighting reported by another unit, not a
        // confirmed close-range raid on this specific Miner.
        int sageInfo = rc.readSharedArray(SA_SAGE_SEEN);
        if (sageInfo != 0 && rc.getRoundNum() - rc.readSharedArray(SA_SAGE_SEEN_RND) < 8) {
            MapLocation sageLoc = unpack(sageInfo);
            if (me.isWithinDistanceSquared(sageLoc, 60)) {
                moveToward(rc, me.add(sageLoc.directionTo(me)));
                return;
            }
        }

        // ITERATION 126: `senseNearbyLocationsWithLead` returns tiles in the
        // engine's own internal scan order (likely a fixed absolute-
        // coordinate order, e.g. row-major) -- ties in lead amount used to
        // go to whichever tile happened to come first in that order, an
        // absolute-position bias same in spirit as the DIRS-order fixes
        // elsewhere in this pass. Tie-break by distance to `me` instead
        // (closer is also just objectively better to go for regardless of
        // symmetry).
        MapLocation goal = null; int bestLead = 0; int bestDist = Integer.MAX_VALUE;
        for (MapLocation l : rc.senseNearbyLocationsWithLead(rc.getType().visionRadiusSquared, 2)) {
            int lead = rc.senseLead(l);
            int dist = l.distanceSquaredTo(me);
            if (lead > bestLead || (lead == bestLead && dist < bestDist)) {
                bestLead = lead; goal = l; bestDist = dist;
            }
        }
        // ITERATION 7: on lead-sparse maps (jellyfish/intersection/valley/pillars)
        // the deposits are 8-15 tiles from spawn -- out of a Miner's vision -- so
        // idle Miners random-walked in the home corner and the economy never
        // started. Broadcast rich tiles we find; idle Miners head for the
        // nearest known deposit instead of wandering.
        if (bestLead >= 10 && goal != null) publishLead(rc, goal);
        if (goal != null && !goal.equals(me)) { moveToward(rc, goal); report(rc); return; }

        // ITERATION 35: nearestLead() used to be called fresh every round with
        // no memory -- when two published beacons sit at similar distance
        // (common on maze maps like intersection, where lead clusters repeat
        // at regular intervals), a Miner could flip which one is "nearest"
        // from one round to the next as it moves or as other Miners deplete
        // one of the two, and moveToward's own anti-reversal guard (keyed on
        // the goal *staying* the same) never engages because the goal itself
        // is what's changing. Traced via --all-actions on a g_iter21/
        // intersection loss: individual Miners visibly ping-ponging between
        // two tiles for 10+ rounds, mining nothing (552 total lead-mining
        // actions for us vs 1241 for the same map's g_iter21 side, mirrored
        // on both team sides -- a real, reproducible regression, not map
        // luck). Commit to one beacon and stick with it until reached or
        // depleted, instead of re-picking "nearest" fresh every round.
        if (myLeadTarget != null
                && ((rc.canSenseLocation(myLeadTarget) && rc.senseLead(myLeadTarget) < 6)
                    || me.equals(myLeadTarget)))
            myLeadTarget = null;
        if (myLeadTarget == null) myLeadTarget = nearestLead(rc, me);
        if (myLeadTarget != null) moveToward(rc, myLeadTarget);
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

    // --------------------------------------------------------------- BUILDER
    // ITERATION 30: minimal Builder AI -- head home, put up one Watchtower,
    // repair it (and anything else under construction) while it's a
    // PROTOTYPE, then hang around home. Deliberately simple; camelcase's own
    // Builder does more (relocation-aware placement, wandering after) but a
    // single static Watchtower per Archon is the safe first version to test.
    static boolean builtWatchtower = false;
    static int extraWatchtowers = 0;
    // ITERATION 64: Laboratory placement, restored from Iteration 58's
    // proven-non-regressive fix. Iteration 57's minimal version had the
    // Archon build the Laboratory directly onto its own 8-tile build ring --
    // a second permanent structure there (alongside the Watchtower) triggered
    // the same tile-occupancy self-blocking the sandwich thread (Iterations
    // 42-44) characterized, but self-inflicted: team lead ballooned to 9750
    // unspent while Soldier production fully collapsed. Fix: after the
    // Watchtower, walk 7 more tiles straight out (away from home) before
    // building the Laboratory, keeping it clear of the Archon's ring entirely.
    static boolean builtLab = false;
    static final int MAX_LABS = 3;   // ITERATION 118/122: team-wide cap, see SA_LAB_BUILT below
    static int builderAwaySteps = 0;
    static void runBuilder(RobotController rc, RobotInfo[] foes) throws GameActionException {
        MapLocation me = rc.getLocation();

        // ITERATION 92: `foes` was passed into this function but never once
        // used -- a Builder had zero self-preservation, unlike Miners
        // (flee combat) or Soldiers (retreat when critical). Confirmed
        // directly this session (Iteration 89's investigation): a Builder
        // walking home to build a Watchtower against sample_afinals was
        // killed by a Sage mid-walk, wasting the entire Watchtower/
        // Laboratory investment -- nothing else ever finishes that job.
        // Flee like a Miner does whenever a combat unit is in range,
        // ahead of every other priority here (a dead Builder can't repair,
        // build, or mutate anything).
        RobotInfo threat = null; int td = Integer.MAX_VALUE;
        for (RobotInfo e : foes) if (combat(e.type)) {
            int d = me.distanceSquaredTo(e.location);
            if (d < td) { td = d; threat = e; }
        }
        if (threat != null) {
            moveToward(rc, me.add(threat.location.directionTo(me)));
            rc.setIndicatorString("builder flee " + threat.location);
            return;
        }
        // ITERATION 96: the flee check above only sees threats within the
        // Builder's OWN vision (20) -- but a Sage's action range (25)
        // exceeds that, so a Sage can already be close enough to kill us
        // before we'd ever see it directly (confirmed repeatedly this
        // session: every Builder lost to sample_afinals died this way).
        // Fall back to a recent, nearby SA_SAGE_SEEN report from any unit
        // with better vision (Archon/Watchtower see out to 34) -- a short
        // freshness window (8 rounds) and a generous-but-bounded radius
        // keep this from reacting to a stale or long-gone sighting.
        int sageInfo = rc.readSharedArray(SA_SAGE_SEEN);
        if (sageInfo != 0 && rc.getRoundNum() - rc.readSharedArray(SA_SAGE_SEEN_RND) < 8) {
            MapLocation sageLoc = unpack(sageInfo);
            if (me.isWithinDistanceSquared(sageLoc, 60)) {
                moveToward(rc, me.add(sageLoc.directionTo(me)));
                rc.setIndicatorString("builder flee (reported sage) " + sageLoc);
                return;
            }
        }

        MapLocation home = nearestHomeArchon(rc, me);
        if (home == null) { moveExplore(rc); return; }

        for (RobotInfo r : rc.senseNearbyRobots(rc.getType().actionRadiusSquared, rc.getTeam())) {
            if (r.mode == RobotMode.PROTOTYPE && rc.canRepair(r.location)) {
                rc.repair(r.location);
                rc.setIndicatorString("repair " + r.type + "@" + r.location);
                return;
            }
        }

        if (!builtWatchtower) {
            if (me.isWithinDistanceSquared(home, 8)) {
                Direction best = bestBuildDirection(rc, RobotType.WATCHTOWER, me);
                if (best != null) {
                    rc.buildRobot(RobotType.WATCHTOWER, best);
                    builtWatchtower = true;
                    rc.setIndicatorString("built watchtower");
                    return;
                }
                // ITERATION 94: already as close to home as `moveToward` can
                // usefully get us, but every adjacent tile is occupied (the
                // same build-ring occupancy-blocking mechanism the sandwich
                // thread characterized, here hitting a Builder instead of an
                // Archon). Falling through to `moveToward(rc, home)` below is
                // a structural no-op once we're this close -- confirmed
                // directly on a `chessboard` game (a tight, maze-walled map):
                // one Builder froze at a single tile for 800+ consecutive
                // rounds, permanently wasting the entire Watchtower
                // investment. Reposition to ANY valid adjacent tile instead
                // of retrying the same failed approach -- a different tile
                // may have different (open) neighbors to build into.
                Direction unstick = bestMovableDirection(rc, me);
                if (unstick != null) {
                    rc.move(unstick);
                    rc.setIndicatorString("repositioning for watchtower slot");
                    return;
                }
            }
            moveToward(rc, home);
            rc.setIndicatorString("to home for watchtower");
            return;
        }
        // ITERATION 112: `needReplacementBuilder` (see runArchon) only
        // gates whether a *new* Builder gets spawned, checked once at
        // spawn time -- it never re-checks once a Builder is already
        // en route. Iteration 111 (REJECTED) raised the retry cap and
        // directly exposed this: 8 separate Builders each independently
        // completed their own Laboratory before the team-wide flag could
        // stop any of them, wasting massive lead. Re-check right before
        // this Builder would actually place one -- if a teammate already
        // finished (SA_LAB_BUILT != 0), abort a redundant build instead
        // of wasting the lead, regardless of what the retry cap is set
        // to. This fix stands on its own even without revisiting
        // Iteration 111's cap.
        if (!builtLab && rc.readSharedArray(SA_LAB_BUILT) >= MAX_LABS) {
            builtLab = true;
            rc.setIndicatorString("lab cap reached elsewhere, aborting");
        }
        // ITERATION 118: sample_afinals alone runs 4 Laboratories (72 Sages
        // built off them) against our permanent cap of 1 -- the same
        // per-robot-lifetime-cap pattern Iteration 117 found for Watchtowers,
        // here directly gating the "afinals A_gold stays flat at 0" thread
        // (Iteration 115's diagnostic note). Unlike the first Lab (a
        // committed investment, always worth pursuing), each additional Lab
        // beyond the first is discretionary surplus spending -- only worth
        // the 7-tile detour if the team is already lead-rich (checked once,
        // before committing to the walk, not worth camping and waiting for
        // lead that may never come on lead-scarce maps).
        // ITERATION 122: Iteration 121's diagnostic directly measured team
        // lead reaching 62785 unspent in one long game -- comfortably past
        // even a much higher bar than the 2nd Lab's 1500, and confirmed
        // (Iteration 121) that Laboratory throughput, not Sage priority, is
        // the real gold-income bottleneck behind the still-unmoved
        // `sample_afinals` matchup. Extend the same escalating-threshold
        // pattern to a 3rd Lab, gated higher (3000) to stay conservative as
        // the investment stacks.
        int labCount = rc.readSharedArray(SA_LAB_BUILT);
        int labSurplusBar = labCount == 1 ? 1500 : 3000;
        if (!builtLab && labCount >= 1 && rc.getTeamLeadAmount(rc.getTeam()) < labSurplusBar) {
            builtLab = true;
            rc.setIndicatorString("skipping extra lab, not lead-rich enough");
        }
        if (!builtLab) {
            if (builderAwaySteps < 7) {
                Direction away = home.directionTo(me);
                // ITERATION 126: was a fixed Direction.NORTH fallback when
                // builder and home coincide -- use direction away from map
                // center instead (relative to the map's own geometry).
                if (away == Direction.CENTER) away = mapCenter().directionTo(me);
                if (away == Direction.CENTER) away = Direction.NORTH;   // home is the map center itself: no bias possible
                if (rc.isMovementReady() && rc.canMove(away)) rc.move(away);
                builderAwaySteps++;
                rc.setIndicatorString("to lab site " + builderAwaySteps + "/7");
                return;
            }
            Direction best = bestBuildDirection(rc, RobotType.LABORATORY, me);
            if (best != null) {
                rc.buildRobot(RobotType.LABORATORY, best);
                builtLab = true;
                rc.writeSharedArray(SA_LAB_BUILT, rc.readSharedArray(SA_LAB_BUILT) + 1);
                rc.setIndicatorString("built laboratory");
                return;
            }
            // no open tile here yet (still finishing a move) -- step out once
            // more next round rather than getting stuck retrying forever.
            // ITERATION 94: the fixed "away from home" direction can itself
            // be permanently blocked (e.g. a wall tile on a maze-like map
            // such as chessboard) -- same freeze risk as the Watchtower case
            // above. Fall back to any valid move if the preferred direction
            // is unavailable, rather than doing nothing this round.
            Direction away = home.directionTo(me);
            if (away != Direction.CENTER && rc.isMovementReady() && rc.canMove(away)) {
                rc.move(away);
            } else if (rc.isMovementReady()) {
                Direction d = bestMovableDirection(rc, me);
                if (d != null) rc.move(d);
            }
            rc.setIndicatorString("finding lab site");
            return;
        }
        // ITERATION 64: the repair-priority loop at the top of this function
        // only fires when canRepair() is true THIS round -- on a round where
        // the Builder's own repair action is merely on cooldown (not because
        // the Lab is finished), that check silently doesn't fire and control
        // falls through here, which used to walk the Builder straight back to
        // home. That permanently strands an unfinished (still-PROTOTYPE) Lab:
        // once the Builder leaves its actionRadius, nothing ever comes back to
        // finish repairing it. Confirmed directly: a Lab got 4 repairs then
        // sat as a lifeless prototype (0 gold, 0 transmutes) for the
        // remaining ~190 rounds of a game while sample_afinals's own 4 Labs
        // transmuted every round. Don't walk home while a nearby structure is
        // still mid-construction, even on an off-cooldown round.
        boolean nearbyPrototype = false;
        for (RobotInfo r : rc.senseNearbyRobots(rc.getType().actionRadiusSquared, rc.getTeam()))
            if (r.mode == RobotMode.PROTOTYPE) { nearbyPrototype = true; break; }
        // ITERATION 82: confirmed via javap that BUILDER.canMutate(ARCHON/
        // LABORATORY/WATCHTOWER) is real (an earlier attempt at Archon
        // self-mutation was wrong -- it's the Builder, same as repair(),
        // that performs structure upgrades). Archon level 2 costs 300 lead
        // only for 600->1080 max HP (+80%) and 2->4 healing/turn (doubled)
        // -- a huge, permanent, one-time durability investment on the one
        // unit type whose death is literally the win condition, for
        // roughly the cost of 4 Soldiers. Once done building (this
        // Builder's own job), spend any further idle time here trying to
        // upgrade the home Archon instead of just standing around.
        if (rc.canMutate(home)) {
            rc.mutate(home);
            rc.setIndicatorString("mutate archon");
            return;
        }
        // ITERATION 83: same BUILDER.canMutate() mechanic, applied to the
        // Watchtower this Builder already built. Level 2 costs 150 lead
        // (half the Archon's) for 150->270 HP (+80%) and 4->8 damage
        // (doubled) -- an even cheaper, higher-leverage upgrade than the
        // Archon one, on a unit whose whole job is dealing and soaking
        // damage. The Watchtower sits adjacent to home (built within 8
        // tiles of it), so the Builder parking near home for the Archon
        // check above is naturally in range of it too.
        // ITERATION 85: the Laboratory (built 7 tiles out) doesn't get the
        // same free ride -- but the Builder already lingers right next to
        // it for several rounds after building, repairing it out of
        // PROTOTYPE mode (the loop at the top of this function), before
        // ever starting the multi-round walk back home. That's a real,
        // already-existing window where the Builder is in range with
        // nothing else to do -- catch it in the same scan instead of
        // adding separate travel logic. Level 2 costs 150 lead for
        // 100->180 HP (+80%, no damage since the Lab doesn't attack).
        for (RobotInfo r : rc.senseNearbyRobots(rc.getType().actionRadiusSquared, rc.getTeam())) {
            if ((r.type == RobotType.WATCHTOWER || r.type == RobotType.LABORATORY)
                    && rc.canMutate(r.location)) {
                rc.mutate(r.location);
                rc.setIndicatorString("mutate " + r.type);
                return;
            }
        }
        // the pre-existing "within 8" idle threshold is looser than
        // actionRadiusSquared (5) -- close enough to stop walking but too
        // far to ever call mutate(), a dead zone. Close all the way to
        // actual action range instead, so mutate above gets a real chance.
        // ITERATION 117: once fully idle (Watchtower+Lab built, both structures
        // and the Archon maxed at level 2), this Builder has nothing left to do
        // for the rest of the game. Confirmed directly against sample_camelcase
        // (maptestsmall): our team lead balloons to 6748+ unspent by r239 while
        // camelcase alone reaches 63 Watchtowers to our 3, and our Soldier count
        // collapses to 0 under the resulting mass mismatch. `builtWatchtower` is
        // per-robot (Iteration 30), so each Builder only ever builds ONE
        // Watchtower in its lifetime -- a second, lead-surplus-gated Watchtower
        // per idle Builder puts this dead time to use instead of leaving it
        // permanently wasted. Bounded (not unlimited) so this scales with
        // however many Builders richHome (Iteration 109) already allows,
        // rather than spamming without limit.
        // ITERATION 123: extended to a 2nd extra Watchtower per Builder
        // (escalating threshold, same pattern as the Lab thread's 117->118->
        // 122 progression) -- camelcase's own 63-Watchtower count in that
        // same motivating game is still far beyond even this, but each
        // increment on this thread has independently verified clean so far.
        // ITERATION 125: 3rd extra. Also newly motivated by this session's
        // per-map tempo-asymmetry diagnostic -- a stationary Watchtower
        // fights from wherever it's built regardless of which side wins the
        // maneuver war, unlike Soldiers whose reinforcement value depends on
        // reaching a moving front in time. More static defense is one of the
        // few levers that plausibly helps the *disadvantaged* side of a
        // tempo mismatch specifically, without touching the still-unresolved
        // combat-time decision itself.
        int extraWtBar = extraWatchtowers == 0 ? 1000 : extraWatchtowers == 1 ? 2500 : 5000;
        if (extraWatchtowers < 3 && rc.getTeamLeadAmount(rc.getTeam()) > extraWtBar
                && me.isWithinDistanceSquared(home, 8)) {
            Direction best = bestBuildDirection(rc, RobotType.WATCHTOWER, me);
            if (best != null) {
                rc.buildRobot(RobotType.WATCHTOWER, best);
                extraWatchtowers++;
                rc.setIndicatorString("built extra watchtower");
                return;
            }
        }
        if (!nearbyPrototype && !me.isWithinDistanceSquared(home, rc.getType().actionRadiusSquared))
            moveToward(rc, home);
        rc.setIndicatorString("idle near home");
    }

    // ----------------------------------------------------------- LABORATORY
    static void runLaboratory(RobotController rc) throws GameActionException {
        if (rc.canTransmute()) {
            rc.transmute();
            rc.setIndicatorString("transmute");
        }
    }

    // ------------------------------------------------------------ WATCHTOWER
    static void runWatchtower(RobotController rc, RobotInfo[] foes) throws GameActionException {
        // ITERATION 78: relocation, mirroring the Archon's own proven pattern
        // (Iteration 34) -- confirmed via a diagnostic probe (canTransform()
        // logged true 128/144 rounds for a live Watchtower in TURRET mode)
        // that Watchtower supports the same PORTABLE/TURRET transform as
        // Archon, and the code never called it: a Watchtower is permanently
        // stuck wherever the Builder first placed it, providing zero value
        // once the front moves elsewhere. Same downtime tradeoff as Archon
        // relocation (canAct=false in PORTABLE mode) -- gate it the same
        // way: once per Watchtower per game, only late, only when provably
        // safe nearby, capped step budget.
        if (rc.getMode() == RobotMode.PORTABLE) {
            boolean nearbyThreat = false;
            for (RobotInfo e : foes) if (combat(e.type)) { nearbyThreat = true; break; }
            if ((nearbyThreat || wtRelocateSteps >= 6) && rc.canTransform()) {
                rc.transform();
                wtHasRelocated = true;
                rc.setIndicatorString("wt relocated, transforming back");
            } else if (rc.isMovementReady()) {
                moveToward(rc, armyObjective(rc));
                wtRelocateSteps++;
                rc.setIndicatorString("wt relocating " + wtRelocateSteps);
            }
            return;   // canAct is false in PORTABLE mode
        }
        MapLocation wtMe = rc.getLocation();
        RobotInfo target = null;
        for (RobotInfo f : foes) if (betterTarget(f, target, wtMe)) target = f;
        // ITERATION 119: join the shared SA_FOCUS mechanism (Iteration 12)
        // instead of always picking independently. With Iteration 117 now
        // producing up to 2 Watchtowers per Archon, uncoordinated Watchtower
        // fire was splitting damage across separate targets instead of
        // piling onto the kill Soldiers are already committed to, extending
        // fights and giving the enemy more total return-fire opportunity.
        // Mirrors runSoldier's own focus-fire logic.
        int fv = rc.readSharedArray(SA_FOCUS);
        MapLocation fl = fv == 0 ? null : unpack(fv);
        RobotInfo fbot = (fl != null && rc.canSenseLocation(fl)) ? rc.senseRobotAtLocation(fl) : null;
        boolean fEnemy = fbot != null && fbot.team == rc.getTeam().opponent();
        if (fl != null && !fEnemy && rc.canSenseLocation(fl)) { rc.writeSharedArray(SA_FOCUS, 0); fl = null; }
        if (target != null && (fl == null || !fEnemy || betterTarget(target, fbot, wtMe))) {
            rc.writeSharedArray(SA_FOCUS, pack(target.location));
            fl = target.location; fEnemy = true;
        }
        MapLocation attackAt = (fEnemy && rc.canAttack(fl)) ? fl
                : (target != null && rc.canAttack(target.location)) ? target.location : null;
        if (attackAt != null) {
            rc.attack(attackAt); bump(rc, SA_ATTACKS);
            rc.setIndicatorString("watchtower attack " + attackAt);
            return;
        }
        boolean localThreat = false;
        for (RobotInfo e : foes) if (combat(e.type) && e.location.isWithinDistanceSquared(rc.getLocation(), 40)) { localThreat = true; break; }
        if (!wtHasRelocated && rc.getRoundNum() > 500 && !localThreat && rc.canTransform()) {
            MapLocation obj = armyObjective(rc);
            if (!rc.getLocation().isWithinDistanceSquared(obj, 400)) {  // >20 tiles from the action
                rc.transform();
                wtRelocateSteps = 0;
                rc.setIndicatorString("wt begin relocate toward " + obj);
            }
        }
    }

    /** better focus-fire target: enemy Soldier first, then lower HP.
     * ITERATION 28: this was "Archon first" since Iteration 1. --events on
     * the g_iter9/highway stall window (r815-830) showed why that's a trap
     * once a siege meets a defended Archon: of our 5 remaining Soldiers, only
     * 1 was even in range of the Archon -- the other 4 were entangled with
     * the opponent's growing garrison, each individually still honoring
     * "Archon first" by ignoring the Soldier in front of them whenever any
     * Archon was anywhere in sight, rather than clearing the defenders that
     * were actually blocking the siege. camelcase's own (verified, working)
     * attack-priority list ranks Soldier above Archon for exactly this
     * reason -- clear what's actually fighting you before chasing the
     * building behind it. */
    static boolean betterTarget(RobotInfo a, RobotInfo b, MapLocation from) {
        if (b == null) return true;
        if (a == null) return false;
        int pa = targetPriority(a.type), pb = targetPriority(b.type);
        if (pa != pb) return pa > pb;
        if (a.health != b.health) return a.health < b.health;
        // ITERATION 126: an exact priority+health tie used to silently keep
        // whichever candidate the engine's own `senseNearbyRobots` scan
        // happened to return first -- likely a fixed absolute-position scan
        // order, the same category of bias as the DIRS-order fixes
        // elsewhere in this pass. Break real ties by proximity to `from`
        // instead (also a sensible tactical preference on its own).
        return from.distanceSquaredTo(a.location) < from.distanceSquaredTo(b.location);
    }
    // ITERATION 32: a sample_afinals/highway loss showed their real build --
    // 0 Soldiers, but 111 Sages (100 HP, 45 dmg -- ~a one-shot on our 50-HP
    // Soldiers) by r840, fueled entirely by Laboratories converting lead to
    // gold (6 Labs by game end). Their Sages alone wiped our 25-Soldier army
    // and then our Archon (1200->15 HP) with zero Soldiers on their side.
    // Laboratory was priority 0 (same as Miner/Builder) -- worthless in our
    // targeting even though it's the actual source of the whole threat.
    // camelcase's own attack-priority list (verified working code) ranks
    // Laboratory second only to Soldier, above even Sage/Watchtower/Archon --
    // match that: raid the gold pipeline, not just the Sages it produces.
    static int targetPriority(RobotType t) {
        if (combat(t)) return 3;
        if (t == RobotType.LABORATORY) return 2;
        if (t == RobotType.ARCHON) return 1;
        return 0;   // Miner, Builder
    }

    static void runSoldier(RobotController rc, RobotInfo[] foes) throws GameActionException {
        MapLocation me = rc.getLocation();

        // local best target (Iteration 28: enemy Soldier first, then Archon, else weakest)
        RobotInfo target = null;
        for (RobotInfo f : foes) if (betterTarget(f, target, me)) target = f;

        // ITERATION 12 -- FOCUS FIRE: the army concentrates damage on one enemy
        // via SA_FOCUS so it dies fast and stops firing back. Each Soldier
        // promotes its own pick if it beats the current focus (or the focus is
        // dead/gone); everyone shoots the shared focus when it is in range.
        int fv = rc.readSharedArray(SA_FOCUS);
        MapLocation fl = fv == 0 ? null : unpack(fv);
        RobotInfo fbot = (fl != null && rc.canSenseLocation(fl)) ? rc.senseRobotAtLocation(fl) : null;
        boolean fEnemy = fbot != null && fbot.team == rc.getTeam().opponent();
        if (fl != null && !fEnemy && rc.canSenseLocation(fl)) { rc.writeSharedArray(SA_FOCUS, 0); fl = null; }
        if (target != null && (fl == null || !fEnemy || betterTarget(target, fbot, me))) {
            rc.writeSharedArray(SA_FOCUS, pack(target.location));
            fl = target.location; fEnemy = true;
        }
        if (fEnemy && rc.canAttack(fl)) {
            // ITERATION 89 (this session, re-attempted -- originally REJECTED
            // as peer-neutral because Sages never got built at all back then,
            // zero diffs across 420 games, mechanism never once fired; now
            // that Iteration 115 makes Sage production real in richHome peer
            // games, re-implementing it fresh since it has something to
            // apply to for the first time this project has ever had). Per
            // the 2022 postmortem PDF (a team that finished 7th at finals):
            // "since sages have such a big vision and attack radius, you
            // could attack people without them ever seeing you... by
            // dancing outside of the soldier vision radius, they could
            // assassinate the soldier from the shadows." Confirmed via
            // `javap`: `SAGE.actionRadiusSquared` (25, ~5 tiles) exceeds
            // `SOLDIER/MINER/BUILDER.visionRadiusSquared` (20, ~4.47 tiles)
            // -- a real ~1-tile band where a Sage can hit those types
            // without being seen back. If we're a Sage, can already attack
            // the target, but are currently *within the target's own
            // vision* (exposed to return fire), retreat one step directly
            // away -- but only if the retreat destination stays within our
            // own action range, so the attack this round is never given up,
            // just the exposure for next round.
            if (rc.getType() == RobotType.SAGE && fbot != null
                    && me.isWithinDistanceSquared(fl, fbot.type.visionRadiusSquared)
                    && rc.isMovementReady()) {
                Direction away = fl.directionTo(me);
                MapLocation retreatTo = me.add(away);
                if (away != Direction.CENTER && rc.canMove(away)
                        && retreatTo.isWithinDistanceSquared(fl, rc.getType().actionRadiusSquared)) {
                    rc.move(away);
                    if (rc.canAttack(fl)) { rc.attack(fl); bump(rc, SA_ATTACKS); }
                    rc.setIndicatorString("sage standoff " + fl);
                    return;
                }
            }
            // ITERATION 21: --metrics on a fresh sample_camelcase/maptestsmall
            // loss showed a real per-soldier attack-rate gap even at comparable
            // troop counts (r100-200: ours ~0.24 attacks/soldier/round vs
            // camelcase's ~0.35, ~1.47x). camelcase's Robot.tryAttack
            // repositions to a lower-rubble adjacent tile (still in action
            // range) before firing whenever able -- move and attack are
            // separate per-turn resources, so this costs nothing and speeds up
            // next turn's cooldown recovery. We never did this.
            repositionForRubble(rc, fl);
            // ITERATION 81: Sage-specific overkill avoidance. A regular
            // Soldier's cheap, frequent attack (actCD 10, dmg 3) makes
            // overkill on the shared SA_FOCUS target basically free -- an
            // earlier attempt at general overkill avoidance was rejected
            // this session for risking SA_FOCUS's fast-kill guarantee. A
            // Sage is the opposite: actCD 200 means this shot won't repeat
            // for ~20 rounds, so 45 damage spent finishing off a target
            // that's already nearly dead (and would die anyway to ordinary
            // Soldier fire this round) is a much bigger relative waste than
            // it is for a Soldier. Only for Sage: if the shared focus is
            // already critically wounded, prefer a healthier enemy combat
            // unit also in range instead -- doesn't touch SA_FOCUS itself,
            // so other Soldiers still coordinate on the original target.
            MapLocation attackAt = fl;
            if (rc.getType() == RobotType.SAGE && fbot != null && fbot.health <= 15) {
                RobotInfo better = null;
                for (RobotInfo f : foes) {
                    if (!combat(f.type) || f.location.equals(fl) || !rc.canAttack(f.location)) continue;
                    if (f.health > 15 && (better == null || f.health > better.health)) better = f;
                }
                if (better != null) attackAt = better.location;
            }
            if (rc.canAttack(attackAt)) { rc.attack(attackAt); bump(rc, SA_ATTACKS); }
            rc.setIndicatorString("focus " + attackAt);
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
        // ITERATION 120: this HP<=10 bar was calibrated against camelcase's
        // own Soldier retreat behavior (Iteration 18) -- but `runSoldier`
        // dispatches SAGE through the same code path, and a Sage has 100 max
        // HP, not a Soldier's much lower pool. An absolute threshold tuned
        // for one unit type is proportionally almost meaningless for the
        // other: <=10 means a Sage fights down to 90% HP lost before ever
        // retreating. Given a Sage's rarity (20 gold, slow to produce),
        // slowness (moveCD 25, the slowest combat unit), and infrequent
        // attack (actCD 200, ~once per 20 rounds) -- unlike a cheap, fast,
        // frequently-replaced Soldier -- losing one to a fight it could have
        // survived by retreating earlier is a much bigger relative loss.
        // Retreat at a proportionally higher bar instead (30 of 100 max HP).
        int retreatHp = rc.getType() == RobotType.SAGE ? 30 : 10;
        if (rc.getHealth() <= retreatHp && home != null && !nearHome) {
            if (target != null && rc.canAttack(target.location)) { rc.attack(target.location); bump(rc, SA_ATTACKS); }
            moveToward(rc, home);
            if (target != null && rc.canAttack(target.location)) { rc.attack(target.location); bump(rc, SA_ATTACKS); }
            rc.setIndicatorString("heal");
            return;
        }

        if (target != null && rc.canAttack(target.location)) {
            // ITERATION 27: Iteration 21's rubble-repositioning only reached
            // the focus-fire attack branch above -- this direct-target
            // fallback (used before SA_FOCUS is set, e.g. the first contact
            // of a fight) never got it, so the same free cooldown-speed
            // benefit was missing on exactly the opening moments of a
            // skirmish. Apply it here too.
            repositionForRubble(rc, target.location);
            if (rc.canAttack(target.location)) { rc.attack(target.location); bump(rc, SA_ATTACKS); }
            rc.setIndicatorString("attack " + target.type);
            return;
        }
        if (target != null) {
            moveToward(rc, target.location);
            if (rc.canAttack(target.location)) { rc.attack(target.location); bump(rc, SA_ATTACKS); }
            rc.setIndicatorString("advance " + target.location);
            return;
        }
        // ITERATION 37: a g_iter14/valley loss showed 2 of our 3 Archons
        // killed (r384, r467) by flanking raids while our whole visible army
        // sat at the map's center the entire time (solCx/solCy ~15,20 the
        // whole window) fighting an even, ongoing skirmish there. Traced to
        // this function's own priority order: SA_HOME_THREAT is only ever
        // read inside armyObjective() below, which the live-focus check right
        // after this comment always short-circuits past whenever SA_FOCUS is
        // set -- and once major combat starts, SA_FOCUS is essentially always
        // set. A real home-threat flag, even correctly raised by
        // checkHomeThreat(), was structurally unreachable by any Soldier for
        // the entire game once the central fight began. Give a real home
        // threat priority over reinforcing a distant fight -- it already had
        // priority over the *speculative* armyObjective guess below; this
        // just extends that same priority past the live-focus check too.
        // ITERATION 102 (v1, REJECTED): a fresh g_iter17/intersection loss
        // showed this unconditional priority meant *every* Soldier without
        // a direct target converged on home defense, including ones near
        // an active live fight elsewhere, abandoning it entirely (5 of 6
        // Soldiers idle "defend home" while our one remaining engaged
        // Soldier got 3-on-1 focus-fired). v1 tried "only defend home if
        // it's actually closer than the live fight" -- fixed the motivating
        // case, but a full Gauntlet found a real, one-directional
        // regression concentrated on `squer` (9 opponents flipped
        // win->loss): total army size there is only 2-11 Soldiers (vs
        // 40-90 on richer maps), so the same per-Soldier "closer wins"
        // comparison could redirect the *entire* available force away
        // from home defense, leaving the Archon essentially undefended.
        // ITERATION 103 (v2, REJECTED): tried a local floor -- only redirect
        // once the threatened Archon itself already had >=2 friendly
        // combat units nearby (mirroring checkHomeThreat's own trigger
        // bar). Reproduction sample (8 peers) looked promising (2 squer
        // flips instead of 9), but a full Gauntlet showed the local
        // snapshot was misleading: a transient cluster of freshly-spawned
        // Soldiers near the Archon could satisfy ">=2 nearby" for a round
        // even on squer, right before dispersing -- the squer sweep was
        // still present almost at full size (9 of 9 opponents again, just
        // a different subset). A *local* count doesn't reliably capture
        // "is this map's total army big enough to spare anyone."
        // ITERATION 103 (v3): use the team-wide Soldier census (SA_SOLDIERS,
        // already maintained every round by census()) directly instead --
        // a global, non-transient measure of exactly the quantity that
        // actually distinguishes squer from every other map in this
        // thread's traces. 10 is comfortably above squer's observed
        // peak (11 total, both sides combined -- roughly 5-6 per side)
        // and comfortably below every richer map's typical count (40-90).
        int th2 = rc.readSharedArray(SA_HOME_THREAT);
        int lf0 = th2 != 0 ? rc.readSharedArray(SA_FOCUS) : 0;
        boolean armyBigEnough = rc.readSharedArray(SA_SOLDIERS) >= 10;
        boolean homeCloser = lf0 == 0
                || me.distanceSquaredTo(unpack(th2)) <= me.distanceSquaredTo(unpack(lf0));
        if (th2 != 0 && (!armyBigEnough || homeCloser)) {
            moveToward(rc, unpack(th2));
            if (target != null && rc.canAttack(target.location)) { rc.attack(target.location); bump(rc, SA_ATTACKS); }
            rc.setIndicatorString("defend home " + unpack(th2));
            return;
        }
        // ITERATION 69-72 (4 attempts, all REJECTED): raided-Miner cry for
        // help (SA_ECON_THREAT) was buried inside armyObjective(), the same
        // structural trap Iteration 37 fixed for SA_HOME_THREAT --
        // unreachable once any Soldier knew about a live fight elsewhere.
        // Fixing that reachability bug is genuinely correct (verified: Miner
        // deaths dropped 8->5 on the original target case), but four
        // attempts at gating the response with a fixed distance threshold
        // all failed the same way -- opponents in the peer pool raid at very
        // different frequencies (g_iter19-21 noticeably more than
        // g_iter22-30), so any single fixed cutoff helps the low-raid group
        // and over-commits against the high-raid group, or vice versa.
        // ITERATION 73 (attempt 1, superseded): self-calibrating throttle --
        // track how many *distinct* raid events have fired in the current
        // round/50 bucket (SA_RAID_COUNT, incremented in runMiner only on a
        // genuinely new raid, not every round of an ongoing siege). Against
        // a low-raid opponent the count stays small all game; against a
        // high-raid one (like g_iter21) it exceeds the cap and further
        // raids get ignored until the next bucket. Verified directly:
        // g_iter21 jumped to 5/6 = 83% on a quick check (best result this
        // whole thread), but the *original* squer target case regressed
        // back to the unfixed baseline (8 Miner deaths, same as before any
        // fix) despite the throttle not blocking it (raid count stayed low
        // there) -- traced the real cause: all 3 of our available Soldiers
        // converge on the *same* single rescue signal every round, since
        // nothing limits how many responders one raid gets. With only ~3
        // Soldiers total on this map, that means the entire army becomes
        // the rescue squad each time, leaving nothing to fight with --
        // a different problem than raid *frequency*. Attempt 2: keep the
        // frequency throttle (it demonstrably fixed g_iter21) and add a
        // modest distance cap specifically to limit how many Soldiers are
        // even in range to volunteer for the same rescue, rather than a
        // long-march cutoff like the earlier 4 rejected attempts used.
        int econThreat = rc.readSharedArray(SA_ECON_THREAT);
        if (econThreat != 0 && rc.getRoundNum() - rc.readSharedArray(SA_ECON_RND) < 40
                && rc.readSharedArray(SA_RAID_COUNT) <= 3
                && me.distanceSquaredTo(unpack(econThreat)) < 40) {
            moveToward(rc, unpack(econThreat));
            if (target != null && rc.canAttack(target.location)) { rc.attack(target.location); bump(rc, SA_ATTACKS); }
            rc.setIndicatorString("rescue miner " + unpack(econThreat));
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
        MapLocation me = rc.getLocation();
        int cur = rc.senseRubble(me);
        int actionR2 = rc.getType().actionRadiusSquared;
        Direction best = null; int bestRubble = cur; int bestDist = Integer.MAX_VALUE;
        for (Direction d : DIRS) {
            if (!rc.canMove(d)) continue;
            MapLocation nxt = me.add(d);
            if (nxt.distanceSquaredTo(target) > actionR2) continue;
            int r = rc.senseRubble(nxt);
            if (r >= cur) continue;
            int dist = nxt.distanceSquaredTo(target);
            if (r < bestRubble || (r == bestRubble && dist < bestDist)) {
                best = d; bestRubble = r; bestDist = dist;
            }
        }
        if (best != null) rc.move(best);
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
        if (dj != null) {
            if (rc.canMove(dj)) { rc.move(dj); lastDir = dj; return; }
            // ITERATION 29: a sample_camelcase/sandwich replay showed our army
            // centroid barely advancing for 90 rounds (29->34 of a 60-wide
            // map) despite zero enemy resistance -- pure corridor congestion,
            // many Soldiers funneling through a narrow path. When the
            // intended step is blocked (almost always by a friendly unit,
            // not terrain, mid-march), try side-stepping around it first --
            // falling straight to the full greedy re-scan below can pull a
            // unit onto a different route entirely, scattering the column
            // instead of keeping it filing through single-file.
            for (Direction alt : new Direction[]{dj.rotateLeft(), dj.rotateRight()}) {
                if (rc.canMove(alt)) { rc.move(alt); lastDir = alt; return; }
            }
        }

        MapLocation center = mapCenter();
        Direction best = null;
        double bestScore = -1e18;
        int bestCenterDist = Integer.MAX_VALUE;
        for (Direction d : DIRS) {
            if (!rc.canMove(d)) continue;
            MapLocation nxt = me.add(d);
            double score = -Math.sqrt(nxt.distanceSquaredTo(goal));   // nearer is better
            score -= rc.senseRubble(nxt) * 0.02;                      // dislike rubble
            if (d == lastDir) score += 0.6;
            else if (lastDir != null && (d == lastDir.rotateLeft() || d == lastDir.rotateRight()))
                score += 0.3;
            int centerDist = nxt.distanceSquaredTo(center);
            if (score > bestScore || (score == bestScore && centerDist < bestCenterDist)) {
                bestScore = score; best = d; bestCenterDist = centerDist;
            }
        }
        if (best != null) { rc.move(best); lastDir = best; }
    }
    static Direction exploreDir = null;
    static MapLocation exploreAnchor = null;
    static int exploreAnchorRound = -1;
    static int stuckUntilRound = -1;
    static void moveExplore(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        MapLocation me = rc.getLocation();
        int rnd = rc.getRoundNum();
        // ITERATION 86 (v3 of the Iteration 80 thread; v1/v2 both rejected --
        // see log): v1 (persistent direction) helped open terrain (valley)
        // but trapped Miners oscillating in small obstacle-dense pockets
        // (pillars, confirmed via --moves: one Miner made 111 moves for 4.5
        // tiles net progress). v2's stuck-detector (15-round window,
        // distance^2<9) fixed pillars but regressed valley below its own
        // baseline via an undiagnosed false-trigger. v3: much stricter and
        // shorter -- only treat a Miner as genuinely trapped (not just
        // making slow/imperfect progress) if it's covered under 2 tiles of
        // straight-line distance in a 10-round window, instead of nearly 3
        // in 15. Tighter bar should leave valley's borderline-but-real
        // progress alone while still catching pillars-style dead stops.
        if (exploreAnchor == null || rnd - exploreAnchorRound >= 10) {
            if (exploreAnchor != null && me.distanceSquaredTo(exploreAnchor) <= 4)
                stuckUntilRound = rnd + 10;
            exploreAnchor = me;
            exploreAnchorRound = rnd;
        }
        if (rnd < stuckUntilRound) {
            for (int i = 0; i < 8; i++) {
                Direction d = DIRS[rng.nextInt(DIRS.length)];
                if (rc.canMove(d)) { rc.move(d); return; }
            }
            return;
        }
        if (exploreDir == null || !rc.canMove(exploreDir) || rng.nextInt(15) == 0) {
            Direction picked = null;
            for (int i = 0; i < 8; i++) {
                Direction d = DIRS[rng.nextInt(DIRS.length)];
                if (rc.canMove(d)) { picked = d; break; }
            }
            exploreDir = picked;
        }
        if (exploreDir != null) rc.move(exploreDir);
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
