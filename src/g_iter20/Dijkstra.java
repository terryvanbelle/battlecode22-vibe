package g_iter20;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapLocation;

public interface Dijkstra {
    Direction getBestDirection(MapLocation target, Direction blockedDirection) throws GameActionException;
}
