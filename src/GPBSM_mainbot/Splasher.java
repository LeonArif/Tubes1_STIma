package botv11;

import battlecode.common.*;

class Splasher extends Utils {

    //  SPLASHER — greedy max-coverage area attack (gapake FSM)
    static void runSplasher(RobotController rc) throws GameActionException {
        receiveTowerHints(rc);

        MapLocation splasherStart = rc.getLocation();
        MapInfo[]   tiles   = rc.senseNearbyMapInfos();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo[] allies  = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        MapLocation myLoc   = rc.getLocation();

        // Opportunistic tower completion
        for (MapLocation ruin : ruins)
            for (UnitType t : TOWER_TYPES)
                if (rc.canCompleteTowerPattern(t, ruin)) rc.completeTowerPattern(t, ruin);

        if (rc.getPaint() < 75) { refillState(rc, allies); return; }

        // GREEDY ATTACK: build 13x13 adjacency scoring table
        // Objective f_splash(loc) = sum of enemy tiles within 1 of loc (+ 5 per enemy tower adj)
        MapLocation bestTile = null;
        if (rc.isActionReady()) {
            int[][] adj = new int[13][13];
            int maxPossible = 0;
            for (MapInfo info : tiles) {
                if (Clock.getBytecodesLeft() < 2000) break;
                if (!info.getPaint().isEnemy()) continue;
                int w = 1;
                maxPossible += w;

                int x = info.getMapLocation().x - myLoc.x + 6;
                int y = info.getMapLocation().y - myLoc.y + 6;
                if (x >= 0 && x < 13 && y >= 0 && y < 13) {
                    adj[x][y] += w;
                    if (x+1 < 13)  adj[x+1][y] += w;
                    if (x-1 >= 0)  adj[x-1][y] += w;
                    if (y+1 < 13)  adj[x][y+1] += w;
                    if (y-1 >= 0)  adj[x][y-1] += w;
                    if (x+1 < 13 && y+1 < 13)  adj[x+1][y+1] += w;
                    if (x-1 >= 0  && y-1 >= 0)  adj[x-1][y-1] += w;
                    if (x+1 < 13 && y-1 >= 0)   adj[x+1][y-1] += w;
                    if (x-1 >= 0  && y+1 < 13)  adj[x-1][y+1] += w;
                }
            }
            for (RobotInfo e : enemies) {
                if (!e.type.isTowerType()) continue;
                int x = e.location.x - myLoc.x + 6;
                int y = e.location.y - myLoc.y + 6;
                if (x >= 0 && x < 13 && y >= 0 && y < 13) {
                    for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                        int nx = x+dx, ny = y+dy;
                        if (nx >= 0 && nx < 13 && ny >= 0 && ny < 13) adj[nx][ny] += 5;
                    }
                }
            }
            int max = 0;
            for (MapLocation loc : rc.getAllLocationsWithinRadiusSquared(myLoc,
                    rc.getType().actionRadiusSquared)) {
                int x = loc.x - myLoc.x + 6;
                int y = loc.y - myLoc.y + 6;
                if (x >= 0 && x < 13 && y >= 0 && y < 13 && adj[x][y] > max) {
                    max = adj[x][y]; bestTile = loc;
                }
            }
            if (bestTile != null && (max >= 4
                    || (max == maxPossible && maxPossible > 0))) {
                if (myLoc.distanceSquaredTo(bestTile) > 4 && rc.isMovementReady()) {
                    if (!tryMoveInRange(rc, bestTile, 4, enemies)) fuzzyMove(rc, bestTile, enemies);
                }
                if (rc.canAttack(bestTile)) rc.attack(bestTile);
            } else if (bestTile != null && max > 0 && rc.isMovementReady()) {
                // Keep advancing toward splash opportunities instead of waiting.
                tryMoveInRange(rc, bestTile, 4, enemies);
            }
        }

        // Evade enemy tower if in range
        for (RobotInfo e : enemies) {
            if (e.type.isTowerType()
                    && myLoc.distanceSquaredTo(e.location) <= e.type.actionRadiusSquared
                    && rc.isMovementReady()) {
                fuzzyMove(rc, rc.getLocation().add(rc.getLocation().directionTo(e.location).opposite()), enemies);
                return;
            }
        }

        // EXPLORE: head toward enemy paint centroid, or random exploration
        if (rc.isMovementReady()) {
            MapLocation enemyPaintLoc = enemyPaintCentroid(rc, tiles);
            if (enemyPaintLoc != null && !rc.senseMapInfo(myLoc).getPaint().isEnemy()) {
                for (Direction dir : fuzzyDirs(myLoc.directionTo(enemyPaintLoc))) {
                    MapLocation nl = myLoc.add(dir);
                    if (rc.onTheMap(nl) && !isTooCloseToEnemyTower(nl, enemies) && rc.canMove(dir)) {
                        rc.move(dir); break;
                    }
                }
            } else {
                if (splasherStuckTurns >= 2) splasherExploreTarget = null;
                if (splasherExploreTarget == null
                        || myLoc.distanceSquaredTo(splasherExploreTarget) <= 8) {
                    Direction rd = MOVE_DIRS[rng.nextInt(8)];
                    MapLocation cur = myLoc;
                    int steps = Math.max(mapW, mapH) / 2;
                    for (int i = 0; i < steps; i++) {
                        MapLocation next = cur.add(rd);
                        if (next.x < 0 || next.y < 0 || next.x >= mapW || next.y >= mapH) break;
                        cur = next;
                    }
                    splasherExploreTarget = cur;
                }
                bugNav(rc, splasherExploreTarget, enemies);
            }
        }

        // Anti-idle: force movement if repeatedly blocked.
        if (rc.getLocation().equals(splasherStart)) splasherStuckTurns++;
        else splasherStuckTurns = 0;
        if (splasherStuckTurns >= 2 && rc.isMovementReady()) {
            tryRandomSafeMove(rc, enemies);
        }
    }
}
