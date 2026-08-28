package sample_camelcase.robot.droid;

import battlecode.common.GameActionException;
import battlecode.common.RobotController;
import battlecode.common.RobotType;
import sample_camelcase.dijkstra.Dijkstra;
import sample_camelcase.robot.Robot;

public abstract class Droid extends Robot {
    public Droid(RobotController rc, RobotType type, Dijkstra dijkstra) {
        super(rc, type, dijkstra);
    }

    @Override
    public void run() throws GameActionException {
        super.run();
    }
}
