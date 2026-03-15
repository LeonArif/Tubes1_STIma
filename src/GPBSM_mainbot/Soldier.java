package botv11;

import battlecode.common.*;

class Soldier extends Utils {

    //  SOLDIER — greedy state machine: REFILL > COMBAT > BUILD > EXPLORE
    static void runSoldier(RobotController rc) throws GameActionException {
        receiveTowerHints(rc);

        MapInfo[]   tiles  = rc.senseNearbyMapInfos();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo[] allies  = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation[] ruins = rc.senseNearbyRuins(-1);

        // Learn pattern colors from marks placed by allied soldiers
        updatePatternFromMarks(rc, ruins);

        // Greedy state selection (priority order)
        uState = determineSoldierState(rc, enemies, ruins);

        switch (uState) {
            case REFILL  -> soldierRefill(rc, allies);
            case COMBAT  -> soldierCombat(rc, enemies, allies);
            case BUILD   -> soldierBuild(rc, ruins, enemies);
            case EXPLORE -> soldierExplore(rc, tiles, ruins, enemies);
            default -> {}
        }

        // Every turn: try to complete towers + paint nearby tiles correctly (strategy early)
        soldierInvariant(rc, tiles, ruins, enemies);
    }

    static UState determineSoldierState(RobotController rc, RobotInfo[] enemies,
            MapLocation[] ruins) throws GameActionException {
        // Persist BUILD if have enough paint to finish
        if (uState == UState.BUILD) {
            MapLocation r = getClosestCompletableRuin(rc, ruins);
            if (r != null && paintLeftForRuin * 5 + 1 < rc.getPaint()) return UState.BUILD;
        }
        // Soldier refills earlier so to prevent die
        int soldierRefillThreshold = Math.max(20, (int)(rc.getType().paintCapacity * 0.35));
        if (rc.getPaint() <= soldierRefillThreshold) {
            if (returnLoc == null) returnLoc = rc.getLocation();
            return UState.REFILL;
        }
        MapLocation ruin = getClosestCompletableRuin(rc, ruins);

        // Early game: soldier secure ruins/towers before committing to tower combat (fokus tower).
        if (ruin != null
                && rc.getRoundNum() < SOLDIER_TOWER_FIRST_ROUND
                && rc.getPaint() > rc.getType().paintCapacity * 0.35) {
            return UState.BUILD;
        }

        // COMBAT: non-defense enemy tower visible and robot healthy
        RobotInfo et = nearestEnemyNonDefenseTower(rc, enemies);
        if (et != null && rc.getHealth() > 32
                && (rc.getRoundNum() >= SOLDIER_TOWER_FIRST_ROUND || ruin == null)) {
            return UState.COMBAT;
        }
        // BUILD: ruin available and chips sufficient
        if (ruin != null && (rc.getChips() >= 700 || rc.getNumberTowers() < 5)) return UState.BUILD;
        return UState.EXPLORE;
    }

    static void soldierRefill(RobotController rc, RobotInfo[] allies) throws GameActionException {
        int refillTarget = (int)(rc.getType().paintCapacity * 0.75);
        if (rc.getPaint() >= refillTarget) {
            uState = UState.EXPLORE; returnLoc = null; return;
        }

        // Prefer robust refill sources first, then any non-empty tower as fallback.
        RobotInfo preferred = null;
        RobotInfo fallback = null;
        int preferredDist = Integer.MAX_VALUE;
        int fallbackDist = Integer.MAX_VALUE;
        for (RobotInfo a : allies) {
            if (!a.type.isTowerType()) continue;
            if (a.paintAmount > 0) {
                int d = rc.getLocation().distanceSquaredTo(a.location);
                if (d < fallbackDist) { fallbackDist = d; fallback = a; }
            }
            boolean ok = a.type.getBaseType() == UnitType.LEVEL_ONE_MONEY_TOWER
                    ? a.paintAmount > 0 : a.paintAmount > 50;
            if (!ok) continue;
            int d = rc.getLocation().distanceSquaredTo(a.location);
            if (d < preferredDist) { preferredDist = d; preferred = a; }
        }
        RobotInfo tower = preferred != null ? preferred : fallback;

        if (tower == null) {
            // No refill source in sight: use communication hint before random exploration. (manfaatin bc)
            MapLocation hint = bestTowerHint(rc, true);
            if (hint != null) bugNav(rc, hint, null);
            else {
                if (exploreTarget == null || rc.getLocation().distanceSquaredTo(exploreTarget) <= 8)
                    newExploreTarget(rc);
                bugNav(rc, exploreTarget, null);
            }
            return;
        }

        if (rc.getLocation().distanceSquaredTo(tower.location) > 2)
            bugNav(rc, tower.location, null);

        int need = Math.max(0, refillTarget - rc.getPaint());
        int amt = Math.min(need, tower.paintAmount);
        if (amt > 0 && rc.canTransferPaint(tower.location, -amt)) {
            rc.transferPaint(tower.location, -amt);
            return;
        }

        // Tower is dry or blocked: step away and search another source next turn.
        if (rc.isMovementReady()) {
            if (!tryMoveOutOfRange(rc, tower.location, 2, null)) {
                if (exploreTarget == null || rc.getLocation().distanceSquaredTo(exploreTarget) <= 8)
                    newExploreTarget(rc);
                bugNav(rc, exploreTarget, null);
            }
        }
    }

    static void soldierCombat(RobotController rc, RobotInfo[] enemies, RobotInfo[] allies)
            throws GameActionException {
        RobotInfo target = nearestEnemyNonDefenseTower(rc, enemies);
        if (target == null) return;
        int dist  = rc.getLocation().distanceSquaredTo(target.location);
        int range = rc.getType().actionRadiusSquared;

        int nearbySoldiers = 0;
        for (RobotInfo a : allies) if (a.type == UnitType.SOLDIER) nearbySoldiers++;

        if (rc.isMovementReady()) {
            if (dist <= range) {
                if (rc.canAttack(target.location)) rc.attack(target.location);
                if (!tryMoveOutOfRange(rc, target.location, target.type.actionRadiusSquared, enemies)) {
                    fuzzyMove(rc, rc.getLocation().add(rc.getLocation().directionTo(target.location).opposite()), enemies);
                }
            } else {
                if (dist > 18) {
                    fuzzyMove(rc, target.location, enemies);
                }
                boolean shouldStepIn = rc.isActionReady()
                        && (rc.getRoundNum() % 2 == 0
                                || nearbySoldiers <= 1
                                || rc.getPaint() < rc.getType().paintCapacity * 0.5);
                if (shouldStepIn && rc.isMovementReady()) {
                    if (!tryMoveIntoRange(rc, target.location, range, enemies)) {
                        fuzzyMove(rc, target.location, enemies);
                    }
                }
            }
        }

        if (rc.canAttack(target.location)) rc.attack(target.location);
    }

    static void soldierBuild(RobotController rc, MapLocation[] ruins, RobotInfo[] enemies)
            throws GameActionException {
        MapLocation ruin = getClosestCompletableRuin(rc, ruins);
        if (ruin == null) { uState = UState.EXPLORE; return; }

        // Determine and store the correct 5x5 pattern in tileColors
        UnitType towerType = determineTowerType(rc, ruin);
        markTowerPattern(ruin, towerType);

        // Signal tower type to allies via a mark
        Direction markDir = towerTypeToMarkDir(towerType);
        MapLocation markLoc = ruin.add(markDir);
        if (rc.canMark(markLoc)) rc.mark(markLoc, true);

        for (UnitType t : TOWER_TYPES) {
            if (rc.canCompleteTowerPattern(t, ruin)) {
                rc.completeTowerPattern(t, ruin);
                uState = UState.EXPLORE;
                return;
            }
        }

        // Paint tiles in fill order — greedy: nearest unpainted ruin tile first
        paintLeftForRuin = 0;
        MapLocation paintTarget = null;
        int minDist = Integer.MAX_VALUE;
        for (MapLocation loc : ruinFillOrder(ruin)) {
            if (!rc.onTheMap(loc)) continue;
            if (!rc.canSenseLocation(loc)) { paintLeftForRuin++; continue; }
            MapInfo info = rc.senseMapInfo(loc);
            if (info.hasRuin()) continue;
            int cInt = safeGetColor(loc);
            if (cInt == 0) { paintLeftForRuin++; continue; }
            boolean wantSec = (cInt == 2);
            PaintType want = wantSec ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;
            if (info.getPaint() != want) {
                int d = rc.getLocation().distanceSquaredTo(loc);
                if (d < minDist) { minDist = d; paintTarget = loc; }
                paintLeftForRuin++;
            }
        }

        if (paintTarget != null) {
            boolean wantSec = (safeGetColor(paintTarget) == 2);
            if (rc.canAttack(paintTarget)) {
                rc.attack(paintTarget, wantSec);
            } else {
                if (rc.isMovementReady()) {
                    if (rc.getLocation().distanceSquaredTo(paintTarget) > 8)
                        bugNav(rc, paintTarget, enemies);
                    else
                        fuzzyMove(rc, paintTarget, enemies);
                }
                if (rc.canAttack(paintTarget)) rc.attack(paintTarget, wantSec);
            }
        } else {
            // All visible tiles painted — move closer
            if (rc.isMovementReady()) {
                if (rc.getLocation().distanceSquaredTo(ruin) > 8) bugNav(rc, ruin, enemies);
                else fuzzyMove(rc, ruin, enemies);
            }
        }
    }

    static void soldierExplore(RobotController rc, MapInfo[] tiles,
            MapLocation[] ruins, RobotInfo[] enemies)
            throws GameActionException {
        if (returnLoc != null) {
            if (rc.getLocation().distanceSquaredTo(returnLoc) <= 16) returnLoc = null;
            else { bugNav(rc, returnLoc, enemies); return; }
        }

        // Early tempo: move to nearest completable ruin quickly for faster tower conversion.
        if (rc.getRoundNum() < SOLDIER_TOWER_FIRST_ROUND) {
            MapLocation ruin = getClosestCompletableRuin(rc, ruins);
            if (ruin != null) {
                if (rc.getLocation().distanceSquaredTo(ruin) > 8) bugNav(rc, ruin, enemies);
                else fuzzyMove(rc, ruin, enemies);
                return;
            }
        }

        // Move toward closest visible empty tile (greedy: maximize new paint coverage)
        MapLocation closestEmpty = null; int closestDist = Integer.MAX_VALUE;
        for (MapInfo info : tiles) {
            if (info.getPaint() == PaintType.EMPTY && !info.hasRuin() && !info.isWall()) {
                int d = rc.getLocation().distanceSquaredTo(info.getMapLocation());
                if (d < closestDist) { closestDist = d; closestEmpty = info.getMapLocation(); }
            }
        }
        if (closestEmpty != null && closestDist > 4) {
            bugNav(rc, closestEmpty, enemies);
        } else {
            if (exploreTarget == null || rc.getLocation().distanceSquaredTo(exploreTarget) <= 8)
                newExploreTarget(rc);
            bugNav(rc, exploreTarget, enemies);
        }
    }

    static void soldierInvariant(RobotController rc, MapInfo[] tiles,
            MapLocation[] ruins, RobotInfo[] enemies) throws GameActionException {
        // Complete any ready tower patterns
        for (MapLocation ruin : ruins) {
            for (UnitType t : TOWER_TYPES) {
                if (rc.canCompleteTowerPattern(t, ruin)) {
                    rc.completeTowerPattern(t, ruin);
                    uState = UState.EXPLORE;
                }
            }
        }

        // Early game: quickly claim ruin-adjacent paint before generic painting.
        if (rc.isActionReady()
                && rc.getRoundNum() < SOLDIER_TOWER_FIRST_ROUND
                && tryBookNearbyRuin(rc, ruins)) {
            return;
        }

        // Paint nearby tiles using tileColors (correct pattern colors)
        if (rc.isActionReady()) {
            // Priority 1: nearest ruin's unpainted tiles
            MapLocation ruin = getClosestCompletableRuin(rc, ruins);
            if (ruin != null) {
                for (MapLocation loc : ruinFillOrder(ruin)) {
                    if (checkAndPaint(rc, loc)) break;
                }
            }
            // Priority 2: tiles in vision spiral
            if (rc.isActionReady()) {
                for (MapLocation loc : r3spiral(rc.getLocation())) {
                    if (checkAndPaint(rc, loc)) break;
                }
            }
        }
    }
}
