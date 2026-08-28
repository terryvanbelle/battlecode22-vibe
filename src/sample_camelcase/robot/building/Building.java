package sample_camelcase.robot.building;

import battlecode.common.GameActionException;
import battlecode.common.RobotController;
import battlecode.common.RobotType;
import sample_camelcase.dijkstra.Dijkstra;
import sample_camelcase.robot.Robot;

public abstract class Building extends Robot {
    public Building(RobotController rc, RobotType type, Dijkstra dijkstra) {
        super(rc, type, dijkstra);
    }

    @Override
    public void run() throws GameActionException {
        super.run();
    }

    protected boolean tryTransform() throws GameActionException {
        if (rc.canTransform()) {
            rc.transform();
            return true;
        }

        return false;
    }
}
