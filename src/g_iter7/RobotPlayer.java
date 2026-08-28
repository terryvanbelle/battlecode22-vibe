package g_iter7;

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
    static int minerCap() { return richHome ? 22 : Math.min(16, 8 + 2 * curArchons); }
    static int curArchons = 2;

    static boolean counted = false, publishedStart = false;
    static int W, H;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        W = rc.getMapWidth();
        H = rc.getMapHeight();
        while (true) {
            try {
                registerOnce(rc);
                RobotInfo[] foes = rc.senseNearbyRobots(rc.getType().visionRadiusSquared,
                                                        rc.getTeam().opponent());
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

    static void registerOnce(RobotController rc) throws GameActionException {
        if (counted) return;
        counted = true;
        if (rc.getType() == RobotType.MINER)   bump(rc, SA_MINERS);
        if (rc.getType() == RobotType.SOLDIER) bump(rc, SA_SOLDIERS);
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

        // ITERATION 7: strong-bot doctrine -- first 6 Miners fast, then a Miner
        // only while Soldiers have kept pace (soldiers + 2 >= miners), up to the
        // (lead-aware) cap, so army and economy climb together.
        curArchons = rc.getArchonCount();
        if (rc.getRoundNum() <= 2 && !richHome) {
            int homeLead = 0;
            for (MapLocation l : rc.senseNearbyLocationsWithLead(rc.getType().visionRadiusSquared, 1))
                homeLead += rc.senseLead(l);
            if (homeLead > 600) richHome = true;   // dense lead next to spawn (maptestsmall: thousands)
        }
        int miners = rc.readSharedArray(SA_MINERS);
        int soldiers = rc.readSharedArray(SA_SOLDIERS);
        boolean needMiners = miners < 6
                || (miners < minerCap() && soldiers + 2 >= miners);
        RobotType want = needMiners ? RobotType.MINER : RobotType.SOLDIER;
        Direction best = null; int bestR = Integer.MAX_VALUE;
        for (Direction d : DIRS) {
            if (!rc.canBuildRobot(want, d)) continue;
            int r = rc.senseRubble(rc.getLocation().add(d));
            if (r < bestR) { bestR = r; best = d; }
        }
        if (best != null) { rc.buildRobot(want, best); bump(rc, SA_BUILDS); }
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
        if (threat != null) { moveToward(rc, me.add(threat.location.directionTo(me))); return; }

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
    static void runSoldier(RobotController rc, RobotInfo[] foes) throws GameActionException {
        MapLocation me = rc.getLocation();

        // ---- Iteration 1 combat logic, verbatim ----
        RobotInfo target = null;
        for (RobotInfo f : foes) {
            if (target == null) { target = f; continue; }
            boolean fA = f.type == RobotType.ARCHON, tA = target.type == RobotType.ARCHON;
            if (fA != tA) { if (fA) target = f; }
            else if (f.health < target.health) target = f;
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
        // ---- only change: one shared objective instead of mirror-of-self ----
        MapLocation obj = armyObjective(rc);
        moveToward(rc, obj);
        rc.setIndicatorString("objective " + obj);
    }

    // --------------------------------------------------------------- movement
    static Direction lastDir = null;   // per-robot momentum, so we trace obstacle
                                       // edges instead of oscillating against them

    /**
     * ITERATION 4: greedy "goal direction +/- 2 rotations" could not get around
     * the big rubble-wall blocks on obstacle-dense maps (pillars, valley), so
     * the army splintered against them. Score all 8 movable directions by
     * progress toward the goal, penalise rubble, and reward continuing in (or
     * near) the last heading -- this slides the army along walls as one body.
     */
    static void moveToward(RobotController rc, MapLocation goal) throws GameActionException {
        if (!rc.isMovementReady() || goal == null) return;
        MapLocation me = rc.getLocation();
        if (me.equals(goal)) return;
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
