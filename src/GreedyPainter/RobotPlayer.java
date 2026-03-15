package GreedyPainter;

import battlecode.common.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

public class RobotPlayer {

    private enum MessageType {
        SAVE_CHIPS
    }

    static int turnCount = 0;
    static final Random rng = new Random(6147);
    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };
    static final Direction[] cardinalDirs = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    static final double PAINT_LOW_THRESHOLD = 0.20;
    static final int SPLASHER_MIN_EFFICIENCY = 3;

    static MapLocation mopperBase      = null;
    static boolean mopperReturning     = false;
    static MapLocation assignedRuin    = null;
    static UnitType assignedTowerType  = null;
    static MapLocation splashedRuin    = null;
    static int spawnCounter            = 0;

    static boolean shouldSave = false;
    static int saveTurns      = 0;

    static ArrayList<MapLocation> knownTowers = new ArrayList<>();
    static boolean isMessenger = false;

    static MapLocation lastPos  = null;
    static int stuckTurns       = 0;

    static boolean isTracing         = false;
    static MapLocation prevDest      = null;
    static HashSet<MapLocation> bugLine = null;
    static int obstacleStartDist     = 0;
    static Direction tracingDir      = null;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        if (rc.getType() == UnitType.MOPPER && rc.getID() % 2 == 0) {
            isMessenger = true;
        }
        while (true) {
            turnCount += 1;
            try {
                switch (rc.getType()) {
                    case SOLDIER:  runSoldier(rc);  break;
                    case MOPPER:   runMopper(rc);   break;
                    case SPLASHER: runSplasher(rc); break;
                    default:       runTower(rc);    break;
                }
            } catch (GameActionException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    static boolean moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return false;
        Direction dir = rc.getLocation().directionTo(target);
        if (dir == Direction.CENTER) return false;
        for (int i = 0; i < 8; i++) {
            if (rc.canMove(dir)) { rc.move(dir); return true; }
            dir = dir.rotateLeft();
        }
        return false;
    }

    static boolean navigateTo(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return false;
        if (!target.equals(prevDest)) {
            prevDest  = target;
            bugLine   = createLine(rc.getLocation(), target);
            isTracing = false;
        }
        if (!isTracing) {
            Direction dir = rc.getLocation().directionTo(target);
            if (rc.canMove(dir)) { rc.move(dir); return true; }
            isTracing         = true;
            obstacleStartDist = rc.getLocation().distanceSquaredTo(target);
            tracingDir        = dir;
            return false;
        } else {
            if (bugLine != null && bugLine.contains(rc.getLocation())
                    && rc.getLocation().distanceSquaredTo(target) < obstacleStartDist) {
                isTracing = false;
            }
            for (int i = 0; i < 9; i++) {
                if (rc.canMove(tracingDir)) {
                    rc.move(tracingDir);
                    tracingDir = tracingDir.rotateRight().rotateRight();
                    return true;
                } else {
                    tracingDir = tracingDir.rotateLeft();
                }
            }
            return false;
        }
    }

    static HashSet<MapLocation> createLine(MapLocation a, MapLocation b) {
        HashSet<MapLocation> locs = new HashSet<>();
        int x = a.x, y = a.y;
        int dx = b.x - a.x, dy = b.y - a.y;
        int sx = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int sy = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        dx = Math.abs(dx); dy = Math.abs(dy);
        int d = Math.max(dx, dy), r = d / 2;
        if (dx > dy) {
            for (int i = 0; i < d; i++) {
                locs.add(new MapLocation(x, y)); x += sx; r += dy;
                if (r >= dx) { locs.add(new MapLocation(x, y)); y += sy; r -= dx; }
            }
        } else {
            for (int i = 0; i < d; i++) {
                locs.add(new MapLocation(x, y)); y += sy; r += dx;
                if (r >= dy) { locs.add(new MapLocation(x, y)); x += sx; r -= dy; }
            }
        }
        locs.add(new MapLocation(x, y));
        return locs;
    }

    static Direction exploreDir = null;

    static boolean moveAny(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return false;

        if (exploreDir == null) {
            exploreDir = directions[rng.nextInt(directions.length)];
        }

        if (rc.canMove(exploreDir)) {
            rc.move(exploreDir);
            return true;
        }

        Direction[] cand = null;
        if (rng.nextBoolean()) {
            cand = new Direction[]{exploreDir.rotateLeft(), exploreDir.rotateRight(), exploreDir.rotateLeft().rotateLeft(), exploreDir.rotateRight().rotateRight()};
        } else {
            cand = new Direction[]{exploreDir.rotateRight(), exploreDir.rotateLeft(), exploreDir.rotateRight().rotateRight(), exploreDir.rotateLeft().rotateLeft()};
        }

        for (Direction d : cand) {
            if (rc.canMove(d)) {
                exploreDir = d; 
                rc.move(d);
                return true;
            }
        }

        int start = rng.nextInt(directions.length);
        for (int i = 0; i < directions.length; i++) {
            Direction dir = directions[(start + i) % directions.length];
            if (rc.canMove(dir)) { 
                exploreDir = dir;
                rc.move(dir); 
                return true; 
            }
        }
        return false;
    }

    static MapLocation findNearestAllyTower(RobotController rc, boolean paintOnly) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            UnitType t = ally.getType();
            if (!t.isTowerType()) continue;
            if (paintOnly && t != UnitType.LEVEL_ONE_PAINT_TOWER
                    && t != UnitType.LEVEL_TWO_PAINT_TOWER
                    && t != UnitType.LEVEL_THREE_PAINT_TOWER) continue;
            int dist = myLoc.distanceSquaredTo(ally.getLocation());
            if (dist < bestDist) { bestDist = dist; best = ally.getLocation(); }
        }
        return best;
    }

    static boolean tryWithdrawPaint(RobotController rc, boolean paintOnly) throws GameActionException {
        if (!rc.isActionReady()) return false;
        for (RobotInfo ally : rc.senseNearbyRobots(2, rc.getTeam())) {
            UnitType t = ally.getType();
            if (!t.isTowerType()) continue;
            if (paintOnly && t != UnitType.LEVEL_ONE_PAINT_TOWER
                    && t != UnitType.LEVEL_TWO_PAINT_TOWER
                    && t != UnitType.LEVEL_THREE_PAINT_TOWER) continue;
            int paintNeeded = rc.getType().paintCapacity - rc.getPaint();
            if (paintNeeded > 0 && rc.canTransferPaint(ally.getLocation(), -paintNeeded)) {
                rc.transferPaint(ally.getLocation(), -paintNeeded);
                return true;
            }
        }
        return false;
    }

    public static void runTower(RobotController rc) throws GameActionException {
        rc.setIndicatorString("save=" + saveTurns);

        if (rc.canUpgradeTower(rc.getLocation())) rc.upgradeTower(rc.getLocation());

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            MapLocation myLoc = rc.getLocation();
            RobotInfo closest = null;
            int minDist = Integer.MAX_VALUE;
            for (RobotInfo e : enemies) {
                int dist = myLoc.distanceSquaredTo(e.getLocation());
                if (dist < minDist) { minDist = dist; closest = e; }
            }
            if (closest != null && rc.canAttack(closest.getLocation())) rc.attack(closest.getLocation());
        }

        for (Message m : rc.readMessages(-1)) {
            if (!shouldSave && m.getBytes() == MessageType.SAVE_CHIPS.ordinal()) {
                if (rc.canBroadcastMessage()) rc.broadcastMessage(MessageType.SAVE_CHIPS.ordinal());
                saveTurns  = 100;
                shouldSave = true;
            }
        }

        if (saveTurns > 0) {
            saveTurns--;
            return;
        }
        shouldSave = false;

        if (!rc.isActionReady()) return;

        UnitType[] spawnOrder = {
            UnitType.SPLASHER, UnitType.SPLASHER, UnitType.MOPPER,
            UnitType.MOPPER,  UnitType.SOLDIER,  UnitType.SOLDIER,
        };
        
        UnitType desired = spawnOrder[spawnCounter % spawnOrder.length];
        if (rc.getPaint() >= desired.paintCost && rc.getMoney() >= desired.moneyCost) {
            for (Direction dir : directions) {
                MapLocation loc = rc.getLocation().add(dir);
                if (rc.canBuildRobot(desired, loc)) {
                    rc.buildRobot(desired, loc);
                    spawnCounter++;
                    return;
                }
            }
        }
    }

    public static void runSoldier(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        if (lastPos != null && lastPos.equals(myLoc)) stuckTurns++;
        else stuckTurns = 0;
        lastPos = myLoc;

        MapInfo currentTile = rc.senseMapInfo(myLoc);
        if (rc.isActionReady() && !currentTile.getPaint().isAlly() && rc.canAttack(myLoc)) {
            rc.attack(myLoc);
        }

        if (rc.getPaint() == 0){
            rc.disintegrate();
            return;
        }
        if (rc.getPaint() < rc.getType().paintCapacity * PAINT_LOW_THRESHOLD) {
            if (tryWithdrawPaint(rc, true)) return;

            MapLocation pt = findNearestAllyTower(rc, true);
            if (pt != null) {
                int crowd = rc.senseNearbyRobots(pt, 2, rc.getTeam()).length;
                if (crowd < 2) {
                    navigateTo(rc, pt);
                    tryWithdrawPaint(rc, true);
                    return; 
                } else {
                    moveAny(rc); 
                }
            }
        }

        if (assignedRuin != null && rc.canSenseLocation(assignedRuin)) {
            RobotInfo ri = rc.senseRobotAtLocation(assignedRuin);
            if ((ri != null && ri.getType().isTowerType()) || !rc.senseMapInfo(assignedRuin).hasRuin()) {
                assignedRuin = null; assignedTowerType = null;
            } 
            else if (myLoc.distanceSquaredTo(assignedRuin) > 8) {
                int count = 0;
                for (RobotInfo ally : rc.senseNearbyRobots(assignedRuin, 5, rc.getTeam())) {
                    if (ally.getType() == UnitType.SOLDIER) count++;
                }
                if (count >= 1) {
                    assignedRuin = null;
                    assignedTowerType = null;
                }
            }
        }

        if (assignedRuin == null) {
            int bestDist = Integer.MAX_VALUE;
            for (MapInfo tile : rc.senseNearbyMapInfos()) {
                if (!tile.hasRuin()) continue;
                MapLocation ruinLoc = tile.getMapLocation();
                RobotInfo ri = rc.senseRobotAtLocation(ruinLoc);
                if (ri != null && ri.getType().isTowerType()) continue;
                int soldiersNear = 0;
                for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
                    if (ally.getType() == UnitType.SOLDIER
                            && ally.getLocation().distanceSquaredTo(ruinLoc) <= 8)
                        soldiersNear++;
                }
                if (soldiersNear >= 1) continue;
                int dist = myLoc.distanceSquaredTo(ruinLoc);
                if (dist < bestDist) { bestDist = dist; assignedRuin = ruinLoc; }
            }
            if (assignedRuin != null) {
                int paintTowers = 0, moneyTowers = 0;
                for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
                    UnitType t = ally.getType();
                    if (t == UnitType.LEVEL_ONE_PAINT_TOWER || t == UnitType.LEVEL_TWO_PAINT_TOWER || t == UnitType.LEVEL_THREE_PAINT_TOWER) paintTowers++;
                    else if (t == UnitType.LEVEL_ONE_MONEY_TOWER || t == UnitType.LEVEL_TWO_MONEY_TOWER || t == UnitType.LEVEL_THREE_MONEY_TOWER) moneyTowers++;
                }
                assignedTowerType = (paintTowers <= moneyTowers) ? UnitType.LEVEL_ONE_PAINT_TOWER : UnitType.LEVEL_ONE_MONEY_TOWER;
            }
        }

        if (assignedRuin != null) {
            MapLocation ruinLoc = assignedRuin;
            UnitType towerType = assignedTowerType != null ? assignedTowerType : UnitType.LEVEL_ONE_MONEY_TOWER;
            UnitType[] allTypes = {towerType, UnitType.LEVEL_ONE_MONEY_TOWER, UnitType.LEVEL_ONE_DEFENSE_TOWER, UnitType.LEVEL_ONE_PAINT_TOWER};

            for (UnitType tt : allTypes) {
                if (rc.canCompleteTowerPattern(tt, ruinLoc)) {
                    rc.completeTowerPattern(tt, ruinLoc);
                    assignedRuin = null; assignedTowerType = null;
                    return;
                }
            }

            if (myLoc.distanceSquaredTo(ruinLoc) > 2) { navigateTo(rc, ruinLoc); myLoc = rc.getLocation(); }

            boolean marksExist = false;
            for (MapInfo checkTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (checkTile.getMark() != PaintType.EMPTY) { marksExist = true; break; }
            }
            if (!marksExist) {
                if (rc.canMarkTowerPattern(towerType, ruinLoc)) rc.markTowerPattern(towerType, ruinLoc);
                else if (rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruinLoc)) rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruinLoc);
            }

            if (rc.isActionReady()) {
                MapInfo bestInRange = null, nearestOOR = null;
                int bestDist = Integer.MAX_VALUE, nearestDistOOR = Integer.MAX_VALUE;
                for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                    if (patternTile.getMark() == PaintType.EMPTY) continue;
                    if (patternTile.getMark() == patternTile.getPaint()) continue;
                    MapLocation tileLoc = patternTile.getMapLocation();
                    int dist = myLoc.distanceSquaredTo(tileLoc);
                    if (rc.canAttack(tileLoc)) {
                        if (dist < bestDist) { bestDist = dist; bestInRange = patternTile; }
                    } else {
                        if (dist < nearestDistOOR) { nearestDistOOR = dist; nearestOOR = patternTile; }
                    }
                }
                if (bestInRange != null) {
                    rc.attack(bestInRange.getMapLocation(), bestInRange.getMark() == PaintType.ALLY_SECONDARY);
                } else if (nearestOOR != null) {
                    navigateTo(rc, nearestOOR.getMapLocation());
                    myLoc = rc.getLocation();
                    if (rc.isActionReady() && rc.canAttack(nearestOOR.getMapLocation()))
                        rc.attack(nearestOOR.getMapLocation(), nearestOOR.getMark() == PaintType.ALLY_SECONDARY);
                }
            }

            for (UnitType tt : allTypes) {
                if (rc.canCompleteTowerPattern(tt, ruinLoc)) {
                    rc.completeTowerPattern(tt, ruinLoc);
                    assignedRuin = null; assignedTowerType = null;
                    return;
                }
            }

            if (rc.getMoney() < towerType.moneyCost) {
                if (rc.isActionReady()) {
                    for (MapInfo tile : rc.senseNearbyMapInfos(myLoc, 9)) {
                        if (!tile.isPassable() || tile.hasRuin() || tile.getMark() != PaintType.EMPTY) continue;
                        if (!tile.getPaint().isAlly() && rc.canAttack(tile.getMapLocation())) {
                            rc.attack(tile.getMapLocation());
                            break;
                        }
                    }
                }
                if (rc.isMovementReady()) {
                    Direction candDir = directions[rng.nextInt(directions.length)];
                    MapLocation nextLoc = myLoc.add(candDir);
                    if (rc.canMove(candDir) && nextLoc.distanceSquaredTo(ruinLoc) <= 8) {
                        rc.move(candDir);
                    }
                }
            }

            return;
        }


        if (rc.getPaint() < rc.getType().paintCapacity * PAINT_LOW_THRESHOLD) {
            if (tryWithdrawPaint(rc, false)) return;
            MapLocation towerLoc = findNearestAllyTower(rc, false);
            if (towerLoc != null) { navigateTo(rc, towerLoc); tryWithdrawPaint(rc, false); return; }
        }

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo enemyTower = null;
        int closestDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : enemies) {
            if (!enemy.getType().isTowerType()) continue;
            int dist = myLoc.distanceSquaredTo(enemy.getLocation());
            if (dist < closestDist) { closestDist = dist; enemyTower = enemy; }
        }
        if (enemyTower != null) {
            MapLocation eLoc = enemyTower.getLocation();
            if (rc.canAttack(eLoc)) rc.attack(eLoc);
            moveToward(rc, eLoc);
            if (rc.canAttack(eLoc)) rc.attack(eLoc);
            return;
        }

        MapInfo bestTarget = null;
        int bestScore = Integer.MAX_VALUE;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.getPaint().isAlly() && tile.isPassable() && !tile.hasRuin()) {
                int dist = myLoc.distanceSquaredTo(tile.getMapLocation());
                int score = tile.getPaint().isEnemy() ? dist - 100 : dist;
                if (score < bestScore) { bestScore = score; bestTarget = tile; }
            }
        }
        if (bestTarget != null) {
            MapLocation target = bestTarget.getMapLocation();
            if (rc.canAttack(target)) rc.attack(target);
            moveToward(rc, target);
            return;
        }

        rc.setIndicatorString("Exploring stuck=" + stuckTurns);
        if (stuckTurns > 3) {
            stuckTurns = 0;
            moveAny(rc);
        } else {
            MapLocation center = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
            if (myLoc.distanceSquaredTo(center) > 25) navigateTo(rc, center);
            else moveAny(rc);
        }
    }


    public static void runSplasher(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        if (lastPos != null && lastPos.equals(myLoc)) stuckTurns++;
        else stuckTurns = 0;
        lastPos = myLoc;

        if (rc.getPaint() < 50) {
            if (tryWithdrawPaint(rc, true)) return;

            MapLocation pt = findNearestAllyTower(rc, true);
            if (pt != null) {
                int crowd = rc.senseNearbyRobots(pt, 2, rc.getTeam()).length;
                if (crowd < 2) {
                    navigateTo(rc, pt);
                    tryWithdrawPaint(rc, true);
                    return; 
                } else {
                    moveAny(rc); 
                }
            }
        }

        MapLocation ruinLoc = null;
        int bestRuinDist = Integer.MAX_VALUE;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.hasRuin()) continue;
            MapLocation rl = tile.getMapLocation();
            RobotInfo ri = rc.senseRobotAtLocation(rl);
            if (ri != null && ri.getType().isTowerType()) continue;
            int dist = myLoc.distanceSquaredTo(rl);
            if (dist < bestRuinDist) { bestRuinDist = dist; ruinLoc = rl; }
        }
        if (splashedRuin != null && rc.canSenseLocation(splashedRuin)) {
            RobotInfo ri = rc.senseRobotAtLocation(splashedRuin);
            if (ri != null && ri.getType().isTowerType()) splashedRuin = null;
        }
        if (ruinLoc != null && !ruinLoc.equals(splashedRuin) && rc.getPaint() >= 50) {
            if (myLoc.distanceSquaredTo(ruinLoc) > 4) { navigateTo(rc, ruinLoc); myLoc = rc.getLocation(); }
            if (rc.isActionReady()) {
                MapLocation bestTarget = null;
                int bestScore = 0;
                for (MapLocation loc : rc.getAllLocationsWithinRadiusSquared(myLoc, 4)) {
                    if (!rc.canAttack(loc)) continue;
                    int score = evaluateSplash(rc, loc);
                    if (loc.distanceSquaredTo(ruinLoc) <= 8) score += 3;
                    if (score > bestScore) { bestScore = score; bestTarget = loc; }
                }
                if (bestTarget != null && bestScore >= SPLASHER_MIN_EFFICIENCY) {
                    rc.attack(bestTarget);
                    splashedRuin = ruinLoc;
                    moveTowardMostUnpainted(rc);
                    return;
                }
                splashedRuin = ruinLoc;
            }
        }

        if (rc.isActionReady() && rc.getPaint() >= 50) {
            MapLocation bestTarget = null;
            int bestScore = 1;
            for (MapLocation loc : rc.getAllLocationsWithinRadiusSquared(myLoc, 4)) {
                if (!rc.canAttack(loc)) continue;
                int score = evaluateSplash(rc, loc);
                if (score > bestScore) { bestScore = score; bestTarget = loc; }
            }
            if (bestTarget != null && bestScore >= SPLASHER_MIN_EFFICIENCY) {
                rc.attack(bestTarget);
                moveTowardMostUnpainted(rc);
                return;
            }
        }

        if (stuckTurns > 3) { stuckTurns = 0; moveAny(rc); }
        else moveTowardMostUnpainted(rc);
    }

    static int evaluateSplash(RobotController rc, MapLocation center) throws GameActionException {
        int score = 0;
        for (MapInfo tile : rc.senseNearbyMapInfos(center, 4)) {
            if (!tile.isPassable() || tile.hasRuin()) continue;
            if (tile.getPaint() == PaintType.EMPTY) score += 2;
            else if (tile.getPaint().isEnemy() && center.distanceSquaredTo(tile.getMapLocation()) <= 2) score += 3;
        }
        return score;
    }

    static void moveTowardMostUnpainted(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        MapLocation myLoc = rc.getLocation();
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        Direction bestDir = null;
        int bestCount = 2;
        int start = rng.nextInt(directions.length);
        for (int i = 0; i < directions.length; i++) {
            Direction dir = directions[(start + i) % directions.length];
            if (!rc.canMove(dir)) continue;
            MapLocation nextLoc = myLoc.add(dir);
            int count = 0;
            for (MapInfo tile : nearbyTiles) {
                if (!tile.isPassable() || tile.hasRuin()) continue;
                if (!tile.getPaint().isAlly() && nextLoc.distanceSquaredTo(tile.getMapLocation()) <= 13) count++;
            }
            if (count > bestCount) { bestCount = count; bestDir = dir; }
        }
        if (bestDir != null) rc.move(bestDir);
        else moveAny(rc);
    }


    public static void runMopper(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        if (mopperBase == null) mopperBase = myLoc;

        if (lastPos != null && lastPos.equals(myLoc)) stuckTurns++;
        else stuckTurns = 0;
        lastPos = myLoc;

        if (rc.getHealth() <= 15) {
            MapLocation towerLoc = findNearestAllyTower(rc, false);
            navigateTo(rc, towerLoc != null ? towerLoc : mopperBase);
            return;
        }

        if (rc.getPaint() <= 5) mopperReturning = true;

        if (mopperReturning) {
            for (RobotInfo ally : rc.senseNearbyRobots(2, rc.getTeam())) {
                if (ally.getType().isTowerType() || ally.getType() == UnitType.MOPPER) continue;
                int needed = ally.getType().paintCapacity - ally.paintAmount;
                if (needed <= 0) continue;
                int give = Math.min(rc.getPaint() - 5, needed);
                if (give > 0 && rc.canTransferPaint(ally.getLocation(), give)) {
                    rc.transferPaint(ally.getLocation(), give);
                    if (rc.getPaint() > rc.getType().paintCapacity * 0.40) mopperReturning = false;
                    return;
                }
            }
            if (rc.getPaint() <= 20 && tryWithdrawPaint(rc, false)) { mopperReturning = false; return; }

            MapLocation returnTarget = null;
            int bestAllyScore = Integer.MAX_VALUE;
            for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
                if (ally.getType().isTowerType() || ally.getType() == UnitType.MOPPER) continue;
                int percentFull = (ally.paintAmount * 100) / Math.max(1, ally.getType().paintCapacity);
                if (percentFull >= 60) continue;
                int score = (ally.getType() == UnitType.SPLASHER ? 0 : 1) * 1000 + percentFull;
                if (score < bestAllyScore) { bestAllyScore = score; returnTarget = ally.getLocation(); }
            }
            if (returnTarget == null) returnTarget = findNearestAllyTower(rc, false);
            if (returnTarget == null) returnTarget = mopperBase;

            if (rc.isActionReady() && rc.senseMapInfo(myLoc).getPaint().isEnemy() && rc.canAttack(myLoc))
                rc.attack(myLoc);

            navigateTo(rc, returnTarget);
            if (myLoc.distanceSquaredTo(returnTarget) <= 2) mopperReturning = false;
            return;
        }

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        if (rc.isActionReady() && enemies.length > 0) {
            Direction swingDir = getBestSwingDirection(rc);
            if (swingDir != null && countSwingHits(rc, swingDir) >= 2) {
                rc.mopSwing(swingDir);
                mopperReturning = true;
                return;
            }
        }

        RobotInfo closestEnemy = null;
        int closestDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : enemies) {
            if (enemy.getType().isTowerType()) continue;
            int dist = myLoc.distanceSquaredTo(enemy.getLocation());
            if (dist < closestDist) { closestDist = dist; closestEnemy = enemy; }
        }
        if (closestEnemy != null) {
            MapLocation enemyLoc = closestEnemy.getLocation();
            if (rc.isActionReady()) {
                Direction swingDir = getBestSwingDirection(rc);
                if (swingDir != null) { rc.mopSwing(swingDir); mopperReturning = true; return; }
            }
            if (rc.isActionReady() && rc.canAttack(enemyLoc)) { rc.attack(enemyLoc); mopperReturning = true; return; }
            moveToward(rc, enemyLoc);
            myLoc = rc.getLocation();
            if (rc.isActionReady() && rc.canAttack(enemyLoc)) { rc.attack(enemyLoc); mopperReturning = true; }
            return;
        }

        if (rc.isActionReady()) {
            MapInfo bestEnemyTile = null;
            int minDist = Integer.MAX_VALUE;
            for (MapInfo tile : rc.senseNearbyMapInfos()) {
                if (!tile.getPaint().isEnemy()) continue;
                int dist = myLoc.distanceSquaredTo(tile.getMapLocation());
                if (dist < minDist) { minDist = dist; bestEnemyTile = tile; }
            }
            if (bestEnemyTile != null) {
                MapLocation target = bestEnemyTile.getMapLocation();
                if (rc.canAttack(target)) { rc.attack(target); mopperReturning = true; return; }
                moveToward(rc, target);
                return;
            }
        }

        if (isMessenger) {
            updateKnownTowers(rc);
            checkNearbyRuins(rc);
            rc.setIndicatorDot(rc.getLocation(), 255, 0, 0);
        }

        if (rc.getPaint() < rc.getType().paintCapacity * 0.35) { mopperReturning = true; return; }
        rc.setIndicatorString("Mopper exploring stuck=" + stuckTurns);
        if (stuckTurns > 3) {
            stuckTurns = 0;
            moveAny(rc);
        } else {
            MapLocation center = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
            if (myLoc.distanceSquaredTo(center) > 16) navigateTo(rc, center);
            else moveAny(rc);
        }
    }

    static void updateKnownTowers(RobotController rc) throws GameActionException {
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!ally.getType().isTowerType()) continue;
            MapLocation allyLoc = ally.getLocation();
            if (knownTowers.contains(allyLoc)) {
                if (shouldSave && rc.canSendMessage(allyLoc)) {
                    rc.sendMessage(allyLoc, MessageType.SAVE_CHIPS.ordinal());
                    shouldSave = false;
                }
                continue;
            }
            knownTowers.add(allyLoc);
        }
    }

    static void checkNearbyRuins(RobotController rc) throws GameActionException {
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            MapLocation tileLoc = tile.getMapLocation();
            if (!tile.hasRuin() || rc.senseRobotAtLocation(tileLoc) != null) continue;
            MapLocation markLoc = tileLoc.add(tileLoc.directionTo(rc.getLocation()));
            if (!rc.canSenseLocation(markLoc)) continue;
            MapInfo markInfo = rc.senseMapInfo(markLoc);
            if (!markInfo.getMark().isAlly()) continue;
            shouldSave = true;
            return;
        }
    }

    static int countSwingHits(RobotController rc, Direction dir) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int hits = 0;
        Direction perp1 = dir.rotateLeft().rotateLeft();
        Direction perp2 = dir.rotateRight().rotateRight();
        MapLocation step1 = myLoc.add(dir);
        MapLocation step2 = step1.add(dir);
        MapLocation[] targets = {
            step1, step1.add(perp1), step1.add(perp2),
            step2, step2.add(perp1), step2.add(perp2)
        };
        for (MapLocation t : targets) {
            if (rc.canSenseLocation(t)) {
                RobotInfo ri = rc.senseRobotAtLocation(t);
                if (ri != null && ri.getTeam() == rc.getTeam().opponent()) hits++;
            }
        }
        return hits;
    }

    static Direction getBestSwingDirection(RobotController rc) throws GameActionException {
        Direction bestDir = null;
        int maxHits = 0;
        for (Direction dir : cardinalDirs) {
            if (!rc.canMopSwing(dir)) continue;
            int hits = countSwingHits(rc, dir);
            if (hits > maxHits) { maxHits = hits; bestDir = dir; }
        }
        return bestDir;
    }
}