package g_iter2;

import battlecode.common.*;
import java.util.Random;

/**
 * The current implementation of our Battlecode 2022 bot.
 *
 * This file evolves over time as the training loop in TRAINING_ALGORITHM.md runs.
 * Each accepted iteration is snapshotted into src/<name>/ and added to the
 * opponent set; git history is the "undo" mechanism for rejected changes.
 *
 * ITERATION 2  (solution to the verified Iteration-1 hypothesis:
 *   "cannot close out won games -- 128 soldiers scatter to mirror-of-self points
 *    instead of hunting the enemy's last Archon; lost a 281-vs-18 game on the
 *    round-2000 gold tiebreak").
 *   - Archons publish their start location to the shared array.
 *   - Every unit reports sensed enemy Archons to the shared array and clears a
 *     slot when it stands next to a stored location and sees nothing there.
 *   - Soldiers with no target converge on: a known enemy Archon, else the
 *     nearest symmetric image of one of our Archon starts (rotation / h-flip /
 *     v-flip -- we don't know the map symmetry, so all candidates are hunted).
 *   - Miners also collect gold drops.
 */
public strictfp class RobotPlayer {

    static final Random rng = new Random(6147);

    static final Direction[] DIRS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    // shared-array slots
    static final int SA_MINERS   = 1;
    static final int SA_SOLDIERS = 2;
    static final int SA_ATTACKS  = 3;
    static final int SA_BUILDS   = 4;
    static final int SA_OUR_ARCHON_0 = 5;   // 5..8  : our Archon start locations (packed)
    static final int SA_ENEMY_ARCHON_0 = 20; // 20..23: known enemy Archon locations (packed)

    static final int TARGET_MINERS = 8;

    static boolean counted = false;
    static boolean publishedStart = false;
    static int turnCount = 0;
    static int W, H;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        W = rc.getMapWidth();
        H = rc.getMapHeight();
        while (true) {
            turnCount++;
            try {
                registerOnce(rc);
                reportEnemyArchons(rc);
                switch (rc.getType()) {
                    case ARCHON:  runArchon(rc);  break;
                    case MINER:   runMiner(rc);   break;
                    case SOLDIER: runSoldier(rc); break;
                    default:      break;
                }
            } catch (GameActionException e) {
                System.out.println(rc.getType() + " GameActionException");
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println(rc.getType() + " Exception");
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    // ------------------------------------------------------------- shared array
    static int pack(MapLocation l) { return 1 + l.x * 64 + l.y; }
    static MapLocation unpack(int v) { v--; return new MapLocation(v / 64, v % 64); }

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

    /** Report enemy Archons we can see; clear a stale slot we're standing next to. */
    static void reportEnemyArchons(RobotController rc) throws GameActionException {
        Team enemy = rc.getTeam().opponent();
        for (RobotInfo r : rc.senseNearbyRobots(-1, enemy)) {
            if (r.type != RobotType.ARCHON) continue;
            int p = pack(r.location);
            int free = -1;
            boolean present = false;
            for (int s = SA_ENEMY_ARCHON_0; s < SA_ENEMY_ARCHON_0 + 4; s++) {
                int v = rc.readSharedArray(s);
                if (v == p) { present = true; break; }
                if (v == 0 && free < 0) free = s;
            }
            if (!present && free >= 0) rc.writeSharedArray(free, p);
        }
        for (int s = SA_ENEMY_ARCHON_0; s < SA_ENEMY_ARCHON_0 + 4; s++) {
            int v = rc.readSharedArray(s);
            if (v == 0) continue;
            MapLocation loc = unpack(v);
            if (rc.getLocation().isWithinDistanceSquared(loc, 2) && rc.canSenseLocation(loc)) {
                RobotInfo here = rc.senseRobotAtLocation(loc);
                if (here == null || here.type != RobotType.ARCHON || here.team == rc.getTeam())
                    rc.writeSharedArray(s, 0);
            }
        }
    }

    static MapLocation knownEnemyArchon(RobotController rc) throws GameActionException {
        MapLocation me = rc.getLocation(), best = null;
        int bd = Integer.MAX_VALUE;
        for (int s = SA_ENEMY_ARCHON_0; s < SA_ENEMY_ARCHON_0 + 4; s++) {
            int v = rc.readSharedArray(s);
            if (v == 0) continue;
            MapLocation l = unpack(v);
            int d = me.distanceSquaredTo(l);
            if (d < bd) { bd = d; best = l; }
        }
        return best;
    }

    /** Nearest symmetric image of one of our Archon starts (all 3 symmetries). */
    static MapLocation nearestEnemyCandidate(RobotController rc) throws GameActionException {
        MapLocation me = rc.getLocation(), best = null;
        int bd = Integer.MAX_VALUE;
        for (int s = SA_OUR_ARCHON_0; s < SA_OUR_ARCHON_0 + 4; s++) {
            int v = rc.readSharedArray(s);
            if (v == 0) continue;
            MapLocation o = unpack(v);
            MapLocation[] cand = {
                new MapLocation(W - 1 - o.x, H - 1 - o.y),  // rotational
                new MapLocation(W - 1 - o.x, o.y),          // horizontal flip
                new MapLocation(o.x, H - 1 - o.y),           // vertical flip
            };
            for (MapLocation c : cand) {
                int d = me.distanceSquaredTo(c);
                if (d < bd) { bd = d; best = c; }
            }
        }
        if (best == null)
            best = new MapLocation(W - 1 - me.x, H - 1 - me.y);
        return best;
    }

    // ----------------------------------------------------------------- ARCHON
    static void runArchon(RobotController rc) throws GameActionException {
        publishStart(rc);

        int miners = rc.readSharedArray(SA_MINERS);
        RobotType want = (miners < TARGET_MINERS || rng.nextInt(5) == 0)
                       ? RobotType.MINER : RobotType.SOLDIER;

        Direction best = null;
        int bestRubble = Integer.MAX_VALUE;
        for (Direction d : DIRS) {
            if (!rc.canBuildRobot(want, d)) continue;
            int r = rc.senseRubble(rc.getLocation().add(d));
            if (r < bestRubble) { bestRubble = r; best = d; }
        }
        if (best != null) { rc.buildRobot(want, best); bump(rc, SA_BUILDS); }
        report(rc);
    }

    // ------------------------------------------------------------------ MINER
    static void runMiner(RobotController rc) throws GameActionException {
        MapLocation me = rc.getLocation();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation t = me.translate(dx, dy);
                while (rc.canMineGold(t)) rc.mineGold(t);
                while (rc.canMineLead(t) && rc.senseLead(t) > 1) rc.mineLead(t);
            }
        }

        MapLocation goal = null;
        int score = 0;
        for (MapLocation l : rc.senseNearbyLocationsWithGold(rc.getType().visionRadiusSquared)) {
            int s = 100 + rc.senseGold(l) * 50 - me.distanceSquaredTo(l);
            if (s > score) { score = s; goal = l; }
        }
        for (MapLocation l : rc.senseNearbyLocationsWithLead(rc.getType().visionRadiusSquared, 2)) {
            int s = rc.senseLead(l) - me.distanceSquaredTo(l);
            if (s > score) { score = s; goal = l; }
        }
        if (goal != null && !goal.equals(me)) moveToward(rc, goal);
        else moveExplore(rc);
        report(rc);
    }

    // ---------------------------------------------------------------- SOLDIER
    static void runSoldier(RobotController rc) throws GameActionException {
        MapLocation me = rc.getLocation();
        Team enemy = rc.getTeam().opponent();
        RobotInfo[] foes = rc.senseNearbyRobots(rc.getType().visionRadiusSquared, enemy);

        RobotInfo target = null;
        for (RobotInfo f : foes) {
            if (target == null) { target = f; continue; }
            boolean fA = f.type == RobotType.ARCHON, tA = target.type == RobotType.ARCHON;
            if (fA != tA) { if (fA) target = f; }
            else if (f.health < target.health) target = f;
        }

        if (target != null && rc.canAttack(target.location)) {
            rc.attack(target.location);
            bump(rc, SA_ATTACKS);
            rc.setIndicatorString("attack " + target.type + " #" + target.ID);
            // kite a little toward it if it's fleeing but out of range next
            return;
        }

        MapLocation goal;
        String why;
        MapLocation ea = knownEnemyArchon(rc);
        if (ea != null)            { goal = ea;               why = "hunt archon " + ea; }
        else if (target != null)   { goal = target.location;  why = "advance " + target.location; }
        else                       { goal = nearestEnemyCandidate(rc); why = "sweep " + goal; }

        moveToward(rc, goal);
        if (target != null && rc.canAttack(target.location)) { rc.attack(target.location); bump(rc, SA_ATTACKS); }
        rc.setIndicatorString(why);
    }

    // --------------------------------------------------------------- movement
    static void moveToward(RobotController rc, MapLocation goal) throws GameActionException {
        if (!rc.isMovementReady()) return;
        Direction want = rc.getLocation().directionTo(goal);
        if (want == Direction.CENTER) return;
        Direction[] tries = { want, want.rotateLeft(), want.rotateRight(),
                              want.rotateLeft().rotateLeft(), want.rotateRight().rotateRight() };
        for (Direction d : tries) if (rc.canMove(d)) { rc.move(d); return; }
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
            "r" + rc.getRoundNum()
            + " lead=" + rc.getTeamLeadAmount(me)
            + " gold=" + rc.getTeamGoldAmount(me)
            + " archons=" + rc.getArchonCount()
            + " hp=" + rc.getHealth()
            + " miners=" + rc.readSharedArray(SA_MINERS)
            + " soldiers=" + rc.readSharedArray(SA_SOLDIERS)
            + " attacks=" + rc.readSharedArray(SA_ATTACKS)
            + " enemyArchonsKnown=" + ea
        );
    }
}
