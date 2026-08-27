package bot;

import battlecode.common.*;
import java.util.Random;

/**
 * The current implementation of our Battlecode 2022 bot.
 *
 * This file evolves over time as the training loop in TRAINING_ALGORITHM.md runs.
 * Each accepted iteration is snapshotted into src/<name>/ and added to the
 * opponent set; git history is the "undo" mechanism for rejected changes.
 *
 * ITERATION 3  (solution to the verified Iteration-2 hypothesis:
 *   "soldiers fragment -- each picks its own nearest objective -- and meet the
 *    enemy's cohesive blob in pieces; no home defense, so raiders wipe the
 *    miners then the Archon -- annihilated r485").
 *
 * Approach: keep Iteration 1's soldier COMBAT logic verbatim (it wins fights),
 * and change only what a soldier does with no enemy in sight -- from "mirror of
 * my own position" (128 different points) to ONE army-wide objective:
 *     a threatened friendly Archon  >  a known enemy Archon (fixed slot)  >
 *     the mirror of our first Archon's start (one shared point).
 * Plus: miners flee enemy combat units; any unit flags an Archon with enemies
 * near it and Archons clear the flag when safe.
 * (Iteration 2's extra hedging -- "regroup when locally outnumbered", per-soldier
 *  candidate sweeps -- is dropped; it made the bot hesitant and lost the mirror.)
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

    static final int TARGET_MINERS = 10;

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

        int miners = rc.readSharedArray(SA_MINERS);
        RobotType want = (miners < TARGET_MINERS || rng.nextInt(5) == 0)
                       ? RobotType.MINER : RobotType.SOLDIER;
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
        if (goal != null && !goal.equals(me)) moveToward(rc, goal);
        else moveExplore(rc);
        report(rc);
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
    static void moveToward(RobotController rc, MapLocation goal) throws GameActionException {
        if (!rc.isMovementReady() || goal == null) return;
        Direction want = rc.getLocation().directionTo(goal);
        if (want == Direction.CENTER) return;
        Direction[] t = { want, want.rotateLeft(), want.rotateRight(),
                          want.rotateLeft().rotateLeft(), want.rotateRight().rotateRight() };
        for (Direction d : t) if (rc.canMove(d)) { rc.move(d); return; }
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
