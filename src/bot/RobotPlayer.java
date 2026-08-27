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
 * ITERATION 1  (solution to the verified Iteration-0 hypothesis:
 *   "no economy + no military -> out-mined ~50:1 and Archons annihilated").
 *   - Archons build Miners until we have a small economy, then Soldiers.
 *   - Miners actually mine (gold first, then lead) and drift toward lead.
 *   - Soldiers attack the weakest enemy in range (Archons first), otherwise
 *     advance on the nearest sensed enemy, otherwise head for the mirror of
 *     their own position (enemy territory on symmetric maps).
 *   Instrumentation: per-round Archon summary + shared-array counters
 *   (miners, soldiers, cumulative team attacks) surfaced in indicator strings.
 */
public strictfp class RobotPlayer {

    static final Random rng = new Random(6147);

    static final Direction[] DIRS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    // shared-array slots
    static final int SA_MINERS   = 1;   // live-ish miner count (incremented on spawn)
    static final int SA_SOLDIERS = 2;   // soldier count
    static final int SA_ATTACKS  = 3;   // cumulative attacks performed by our team
    static final int SA_BUILDS   = 4;   // total robots ordered by Archons

    static final int TARGET_MINERS = 8; // build miners until we have this many

    static boolean counted = false;     // this robot has registered itself in the shared array
    static int turnCount = 0;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            turnCount++;
            try {
                registerOnce(rc);
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

    /** Count this robot once in the shared array so Archons can see the army size. */
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

    // ----------------------------------------------------------------- ARCHON
    static void runArchon(RobotController rc) throws GameActionException {
        int miners = rc.readSharedArray(SA_MINERS);
        RobotType want = (miners < TARGET_MINERS || rng.nextInt(5) == 0)
                       ? RobotType.MINER : RobotType.SOLDIER;

        // build toward the lowest-rubble adjacent tile we can build on
        Direction best = null;
        int bestRubble = Integer.MAX_VALUE;
        for (Direction d : DIRS) {
            if (!rc.canBuildRobot(want, d)) continue;
            MapLocation t = rc.getLocation().add(d);
            int r = rc.senseRubble(t);
            if (r < bestRubble) { bestRubble = r; best = d; }
        }
        if (best != null) {
            rc.buildRobot(want, best);
            bump(rc, SA_BUILDS);
        }
        report(rc);
    }

    // ------------------------------------------------------------------ MINER
    static void runMiner(RobotController rc) throws GameActionException {
        MapLocation me = rc.getLocation();

        // mine everything reachable this turn (gold is worth more)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation t = me.translate(dx, dy);
                while (rc.canMineGold(t)) rc.mineGold(t);
                // leave 1 lead so the tile keeps regenerating
                while (rc.canMineLead(t) && rc.senseLead(t) > 1) rc.mineLead(t);
            }
        }

        // drift toward the richest lead we can see, else explore
        MapLocation goal = null;
        int bestLead = 0;
        for (MapLocation l : rc.senseNearbyLocationsWithLead(rc.getType().visionRadiusSquared, 2)) {
            int lead = rc.senseLead(l);
            if (lead > bestLead) { bestLead = lead; goal = l; }
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

        // pick a target: prefer an enemy Archon, then the weakest enemy
        RobotInfo target = null;
        for (RobotInfo f : foes) {
            if (target == null) { target = f; continue; }
            boolean fArchon = f.type == RobotType.ARCHON, tArchon = target.type == RobotType.ARCHON;
            if (fArchon != tArchon) { if (fArchon) target = f; }
            else if (f.health < target.health) target = f;
        }

        if (target != null && rc.canAttack(target.location)) {
            rc.attack(target.location);
            bump(rc, SA_ATTACKS);
            rc.setIndicatorString("attacking " + target.type + " #" + target.ID);
        } else if (target != null) {
            moveToward(rc, target.location);
            if (rc.canAttack(target.location)) { rc.attack(target.location); bump(rc, SA_ATTACKS); }
            rc.setIndicatorString("advancing on enemy @ " + target.location);
        } else {
            // no enemy in sight: head for the mirror of our position
            MapLocation mirror = new MapLocation(rc.getMapWidth() - 1 - me.x,
                                                 rc.getMapHeight() - 1 - me.y);
            moveToward(rc, mirror);
            rc.setIndicatorString("marching to " + mirror);
        }
    }

    // --------------------------------------------------------------- movement
    static void moveToward(RobotController rc, MapLocation goal) throws GameActionException {
        if (!rc.isMovementReady()) return;
        Direction want = rc.getLocation().directionTo(goal);
        Direction[] tries = { want, want.rotateLeft(), want.rotateRight(),
                              want.rotateLeft().rotateLeft(), want.rotateRight().rotateRight() };
        for (Direction d : tries) {
            if (d != Direction.CENTER && rc.canMove(d)) { rc.move(d); return; }
        }
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
        rc.setIndicatorString(
            "r" + rc.getRoundNum()
            + " lead=" + rc.getTeamLeadAmount(me)
            + " gold=" + rc.getTeamGoldAmount(me)
            + " archons=" + rc.getArchonCount()
            + " hp=" + rc.getHealth()
            + " miners=" + rc.readSharedArray(SA_MINERS)
            + " soldiers=" + rc.readSharedArray(SA_SOLDIERS)
            + " attacks=" + rc.readSharedArray(SA_ATTACKS)
            + " builds=" + rc.readSharedArray(SA_BUILDS)
        );
    }
}
