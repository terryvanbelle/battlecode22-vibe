package bot;

import battlecode.common.*;
import java.util.Random;

/**
 * The current implementation of our Battlecode 2022 bot.
 *
 * This file evolves over time as the training loop in TRAINING_ALGORITHM.md runs.
 * Each accepted iteration is snapshotted into src/gauntlet/ and added to the
 * opponent set; git history is the "undo" mechanism for rejected changes.
 *
 * ITERATION 0
 *   Archons: collectively build exactly one Miner, then idle.
 *   Miner:   move in a random direction each turn.
 *   Everything else: idle.
 *   Instrumentation: each Archon writes a per-round summary indicator string;
 *   the Miner writes its location. Indicator strings are saved into the replay
 *   and read back with `tools/bc22_replay.py --indicators`.
 */
public strictfp class RobotPlayer {

    static final Random rng = new Random(6147);

    static final Direction[] DIRS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    /** Shared-array slot: 1 once some Archon has built the team's single Miner. */
    static final int SA_MINER_BUILT = 0;

    static int turnCount = 0;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            turnCount++;
            try {
                switch (rc.getType()) {
                    case ARCHON: runArchon(rc); break;
                    case MINER:  runMiner(rc);  break;
                    default:     break;
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

    static void runArchon(RobotController rc) throws GameActionException {
        // Build the team's single Miner at the first opportunity.
        if (rc.readSharedArray(SA_MINER_BUILT) == 0) {
            for (Direction dir : DIRS) {
                if (rc.canBuildRobot(RobotType.MINER, dir)) {
                    rc.buildRobot(RobotType.MINER, dir);
                    rc.writeSharedArray(SA_MINER_BUILT, 1);
                    break;
                }
            }
        }
        report(rc);
    }

    static void runMiner(RobotController rc) throws GameActionException {
        Direction dir = DIRS[rng.nextInt(DIRS.length)];
        if (rc.canMove(dir)) {
            rc.move(dir);
        }
        rc.setIndicatorString("miner @ " + rc.getLocation() + " r" + rc.getRoundNum());
    }

    /** Per-round instrumentation written by every Archon into the replay. */
    static void report(RobotController rc) throws GameActionException {
        Team me = rc.getTeam();
        rc.setIndicatorString(
            "r" + rc.getRoundNum()
            + " lead=" + rc.getTeamLeadAmount(me)
            + " gold=" + rc.getTeamGoldAmount(me)
            + " myArchons=" + rc.getArchonCount()
            + " archonHP=" + rc.getHealth()
            + " minerBuilt=" + rc.readSharedArray(SA_MINER_BUILT)
        );
    }
}
