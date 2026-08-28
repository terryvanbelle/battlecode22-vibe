/*
 * PROVENANCE: vendored from https://github.com/jmerle/battlecode-2022
 *   (src/camel_case_v25_final). Team "camel_case" -- Battlecode 2022 Final
 *   Tournament (13th-16th). MIT License, Copyright (c) 2022 Jasper van Merle.
 *   Only modification: package rename camel_case_v25_final -> sample_camelcase
 *   (including sub-packages robot/, dijkstra/, util/).
 */
package sample_camelcase;

import battlecode.common.Clock;
import battlecode.common.RobotController;
import sample_camelcase.robot.Robot;
import sample_camelcase.robot.building.Archon;
import sample_camelcase.robot.building.Laboratory;
import sample_camelcase.robot.building.Watchtower;
import sample_camelcase.robot.droid.Builder;
import sample_camelcase.robot.droid.Miner;
import sample_camelcase.robot.droid.Sage;
import sample_camelcase.robot.droid.Soldier;

@SuppressWarnings("unused")
public class RobotPlayer {
    public static void run(RobotController rc) {
        Robot robot = createRobot(rc);

        if (robot == null) {
            return;
        }

        // noinspection InfiniteLoopStatement
        while (true) {
            performTurn(rc, robot);
            Clock.yield();
        }
    }

    private static void performTurn(RobotController rc, Robot robot) {
        int startRound = rc.getRoundNum();

        try {
            robot.run();
        } catch (Exception e) {
            System.out.println("Exception in robot #" + rc.getID() + " (" + rc.getType() + ")");
            e.printStackTrace();
        }

        int usedBytecodes = (rc.getRoundNum() - startRound) * rc.getType().bytecodeLimit + Clock.getBytecodeNum();
        int maxBytecodes = rc.getType().bytecodeLimit;
        double bytecodePercentage = (double) usedBytecodes / (double) maxBytecodes * 100.0;
        if (bytecodePercentage >= 95) {
            String format = "High bytecode usage!\n%s/%s (%s%%)\n";
            System.out.printf(format, usedBytecodes, maxBytecodes, (int) Math.round(bytecodePercentage));
        }
    }

    private static Robot createRobot(RobotController rc) {
        switch (rc.getType()) {
            case ARCHON:
                return new Archon(rc);
            case LABORATORY:
                return new Laboratory(rc);
            case WATCHTOWER:
                return new Watchtower(rc);
            case BUILDER:
                return new Builder(rc);
            case MINER:
                return new Miner(rc);
            case SAGE:
                return new Sage(rc);
            case SOLDIER:
                return new Soldier(rc);
            default:
                System.out.println("Unknown robot type '" + rc.getType() + "'");
                return null;
        }
    }
}
