package botv11;

import battlecode.common.*;

class Mopper extends Utils {

    //  MOPPER — greedy paint drain + area clean (support & defend)
    static void runMopper(RobotController rc) throws GameActionException {
        receiveTowerHints(rc);

        MapLocation mopperStart = rc.getLocation();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo[] allies  = rc.senseNearbyRobots(-1, rc.getTeam());
        MapInfo[]   tiles   = rc.senseNearbyMapInfos();
        MapLocation[] ruins = rc.senseNearbyRuins(-1);

        // Invariant: complete nearby towers
        for (MapLocation ruin : ruins)
            for (UnitType t : TOWER_TYPES)
                if (rc.canCompleteTowerPattern(t, ruin)) rc.completeTowerPattern(t, ruin);

        // Invariant: give paint to nearby low-paint allies
        mopperRefillAllies(rc, allies);

        uState = determineMopperState(rc, tiles, enemies, ruins);

        switch (uState) {
            case REFILL  -> refillState(rc, allies);
            case COMBAT  -> mopperCombat(rc, enemies);
            case MOP     -> moppingState(rc, tiles, ruins, enemies);
            case EXPLORE -> mopperExplore(rc, allies, enemies);
            default -> {}
        }

        // Anti-idle: if mopper keeps getting stuck, force a safe random step.
        if (rc.getLocation().equals(mopperStart)) mopperStuckTurns++;
        else mopperStuckTurns = 0;
        if (mopperStuckTurns >= 2 && rc.isMovementReady()) {
            tryRandomSafeMove(rc, enemies);
        }
    }

    static UState determineMopperState(RobotController rc, MapInfo[] tiles,
            RobotInfo[] enemies, MapLocation[] ruins) {
        if (shouldRefill(rc)) {
            if (returnLoc == null) returnLoc = rc.getLocation();
            return UState.REFILL;
        }
        RobotInfo targetEnemy = getMopperTarget(rc, enemies);
        if (targetEnemy != null) return UState.COMBAT;
        MapLocation mop = bestMopTarget(rc, tiles, ruins);
        if (mop != null) return UState.MOP;
        return UState.EXPLORE;
    }

    // Greedy target: enemy soldier with lowest paint (most vulnerable)
    static RobotInfo getMopperTarget(RobotController rc, RobotInfo[] enemies) {
        RobotInfo best = null; boolean foundSoldier = false;
        int minPaint = Integer.MAX_VALUE; int minDist = Integer.MAX_VALUE;
        for (RobotInfo e : enemies) {
            if (e.type.isTowerType() || e.paintAmount == 0) continue;
            int dist = rc.getLocation().distanceSquaredTo(e.location);
            boolean isSol = (e.type == UnitType.SOLDIER);
            if (!foundSoldier && isSol){ 
                    foundSoldier=true; best=e; minPaint=e.paintAmount; minDist=dist; 
                }
            else if (foundSoldier && isSol && (e.paintAmount < minPaint || (e.paintAmount==minPaint && dist<minDist))){
                best=e; minPaint=e.paintAmount; minDist=dist; 
            }
            else if (!foundSoldier && (e.paintAmount < minPaint || dist < minDist))
                {
                    best=e; minPaint=e.paintAmount; minDist=dist;
                }
        }
        return best;
    }

    static void mopperCombat(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        RobotInfo target = getMopperTarget(rc, enemies);
        if (target == null) return;
        // Try to find the best position to swing at multiple enemies
        // Greedy: move to location maximising swing hits, then swing (biar kena musuh banyak)
        Direction[] cardinals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        int bestHits = 0; MapLocation bestSwingLoc = null; Direction bestSwingDir = null;
        // Check current location and all adjacent moves
        for (int mi = -1; mi < MOVE_DIRS.length; mi++) {
            MapLocation testLoc;
            if (mi < 0) { testLoc = rc.getLocation(); }
            else {
                if (!rc.canMove(MOVE_DIRS[mi])) continue;
                testLoc = rc.getLocation().add(MOVE_DIRS[mi]);
                if (isTooCloseToEnemyTower(testLoc, enemies)) continue;
            }
            for (Direction cd : cardinals) {
                int hits = countSwingHits(testLoc, cd, enemies);
                if (hits > bestHits) { bestHits = hits; bestSwingLoc = testLoc; bestSwingDir = cd; }
            }
        }
        // Move to swing location if beneficial
        if (bestHits >= 2 && bestSwingLoc != null && !bestSwingLoc.equals(rc.getLocation())) {
            Direction md = rc.getLocation().directionTo(bestSwingLoc);
            if (rc.isMovementReady() && rc.canMove(md)) rc.move(md);
        }
        // Swing
        if (rc.isActionReady() && bestSwingDir != null && bestHits >= 1
                && rc.canMopSwing(bestSwingDir)) {
            rc.mopSwing(bestSwingDir);
        }
        // Fallback: direct attack
        if (rc.isActionReady()) {
            if (!tryMoveInRange(rc, target.location, 2, enemies))
                fuzzyMove(rc, target.location, enemies);
            if (rc.canAttack(target.location)) rc.attack(target.location);
            // Swing at target if adjacent
            if (rc.isActionReady()) {
                int xOff = target.location.x - rc.getLocation().x;
                int yOff = target.location.y - rc.getLocation().y;
                Direction swDir = null;
                if (xOff >= 1 && xOff <= 2 && yOff >= -1 && yOff <= 1) swDir = Direction.EAST;
                else if (xOff <= -1 && xOff >= -2 && yOff >= -1 && yOff <= 1) swDir = Direction.WEST;
                else if (yOff >= 1 && yOff <= 2 && xOff >= -1 && xOff <= 1) swDir = Direction.NORTH;
                else if (yOff <= -1 && yOff >= -2 && xOff >= -1 && xOff <= 1) swDir = Direction.SOUTH;
                if (swDir != null && rc.canMopSwing(swDir)) rc.mopSwing(swDir);
            }
        } else {
            // No action yet — get into range on best tile
            tryMoveInRange(rc, target.location, 8, enemies);
        }
    }

    static int countSwingHits(MapLocation swingCenter, Direction swingDir, RobotInfo[] enemies) {
        int count = 0;
        for (RobotInfo e : enemies) {
            if (e.type.isTowerType() || e.paintAmount == 0) continue;
            int dx = e.location.x - swingCenter.x;
            int dy = e.location.y - swingCenter.y;
            boolean hit = switch (swingDir) {
                case NORTH -> dy >= 1 && dy <= 2 && dx >= -1 && dx <= 1;
                case SOUTH -> dy <= -1 && dy >= -2 && dx >= -1 && dx <= 1;
                case EAST  -> dx >= 1 && dx <= 2 && dy >= -1 && dy <= 1;
                case WEST  -> dx <= -1 && dx >= -2 && dy >= -1 && dy <= 1;
                default    -> false;
            };
            if (hit) count++;
        }
        return count;
    }

    static void moppingState(RobotController rc, MapInfo[] tiles, MapLocation[] ruins,
            RobotInfo[] enemies) throws GameActionException {
        MapLocation mop = bestMopTarget(rc, tiles, ruins);
        if (mop == null) return;
        if (rc.isActionReady()) {
            if (!tryMoveInRange(rc, mop, 2, enemies)) bugNav(rc, mop, enemies);
            if (rc.canAttack(mop)) rc.attack(mop);
        } else {
            // While action on cooldown, reposition closer
            tryMoveInRange(rc, mop, 8, enemies);
        }
    }

    // Greedy mop target: enemy paint adjacent to ruins scores +5
    static MapLocation bestMopTarget(RobotController rc, MapInfo[] tiles, MapLocation[] ruins) {
        MapLocation best = null; int bestScore = Integer.MIN_VALUE;
        for (MapInfo t : tiles) {
            if (!t.getPaint().isEnemy()) continue;
            MapLocation tl = t.getMapLocation();
            int score = 1;
            for (MapLocation r : ruins)
                if (tl.distanceSquaredTo(r) <= 8) { score += 5; break; }
            score -= rc.getLocation().distanceSquaredTo(tl) / 4;
            if (score > bestScore) { bestScore = score; best = tl; }
        }
        return best;
    }

    static void mopperExplore(RobotController rc, RobotInfo[] allies, RobotInfo[] enemies)
            throws GameActionException {
        if (returnLoc != null && rc.getLocation().distanceSquaredTo(returnLoc) > 4) {
            bugNav(rc, returnLoc, enemies); return;
        }
        returnLoc = null;
        // Follow nearest soldier to the frontline
        RobotInfo soldier = null; int minD = Integer.MAX_VALUE;
        for (RobotInfo a : allies) {
            if (a.type == UnitType.SOLDIER) {
                int d = rc.getLocation().distanceSquaredTo(a.location);
                if (d < minD) { minD = d; soldier = a; }
            }
        }
        if (soldier != null) bugNav(rc, soldier.location, enemies);
        else {
            if (exploreTarget == null || rc.getLocation().distanceSquaredTo(exploreTarget) <= 8)
                newExploreTarget(rc);
            bugNav(rc, exploreTarget, enemies);
        }
    }

    static void mopperRefillAllies(RobotController rc, RobotInfo[] allies) throws GameActionException {
        for (RobotInfo a : allies) {
            if (a.type.isTowerType() || a.type == UnitType.MOPPER) continue;
            int amt = Math.max(0, Math.min(rc.getPaint() - 30, a.type.paintCapacity - a.paintAmount));
            if (amt > 0 && rc.canTransferPaint(a.location, amt)) {
                rc.transferPaint(a.location, amt);
                break;
            }
        }
    }
}
