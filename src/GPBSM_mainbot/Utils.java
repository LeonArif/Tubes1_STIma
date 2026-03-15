package botv11;

import battlecode.common.*;

class Utils extends RobotPlayer {

    //  SHARED REFILL (soldier / mopper)
    static void refillState(RobotController rc, RobotInfo[] allies) throws GameActionException {
        int refillTarget = (int)(rc.getType().paintCapacity * 0.75);
        if (rc.getPaint() >= refillTarget) {
            uState = UState.EXPLORE; returnLoc = null; return;
        }
        RobotInfo target = null; int minD = Integer.MAX_VALUE;
        for (RobotInfo a : allies) {
            if (!a.type.isTowerType()) continue;
            UnitType base = a.type.getBaseType();
            int minPaint = (base == UnitType.LEVEL_ONE_MONEY_TOWER) ? 0 : 50;
            if (a.paintAmount < minPaint) continue;
            int d = rc.getLocation().distanceSquaredTo(a.location);
            if (d < minD) { minD = d; target = a; }
        }
        if (target == null) {
            MapLocation hint = bestTowerHint(rc, false);
            if (hint != null) bugNav(rc, hint, null);
            else {
                if (exploreTarget == null || rc.getLocation().distanceSquaredTo(exploreTarget) <= 8)
                    newExploreTarget(rc);
                bugNav(rc, exploreTarget, null);
            }
            return;
        }
        if (rc.getLocation().distanceSquaredTo(target.location) > 2)
            bugNav(rc, target.location, null);
        int need = Math.max(0, refillTarget - rc.getPaint());
        int amt = Math.min(need, target.paintAmount);
        if (amt > 0 && rc.canTransferPaint(target.location, -amt))
            rc.transferPaint(target.location, -amt);
    }

    //  BUGNAV — greedy wall-following navigation
    //  Forward 3 dirs are scored; if all blocked, start wall-follow.
    static void bugNav(RobotController rc, MapLocation target, RobotInfo[] enemies)
            throws GameActionException {
        if (!rc.isMovementReady()) return;

        // Reset stack when target changes or stack overflows
        if (bugLastTarget == null || bugLastTarget.distanceSquaredTo(target) > 8 || bugIdx >= 90) {
            bugStack = new Direction[100];
            bugIdx = 0;
            bugLastTarget = target;
            bugLastLoc = rc.getLocation();
        }
        if (bugLastTarget.distanceSquaredTo(target) <= 8) bugLastTarget = target;
        MapLocation myLoc = rc.getLocation();

        // Pop stack entries that are now passable (obstacle moved/cleared)
        while (bugIdx > 0 && canMoveAndSafe(rc, bugStack[bugIdx - 1], enemies)) bugIdx--;

        if (bugIdx == 0) {
            // Direct path: try dirTo target and ±1 rotations
            Direction dirTo = myLoc.directionTo(target);
            Direction bestDir = null; int bestScore = -9999;
            for (Direction d : new Direction[]{dirTo, dirTo.rotateLeft(), dirTo.rotateRight()}) {
                if (!rc.canMove(d)) continue;
                MapLocation nl = myLoc.add(d);
                if (nl.equals(bugLastLoc)) continue;
                if (!canMoveAndSafe(rc, d, enemies)) continue;
                int score = tileScore(rc, nl, enemies) - (d == dirTo ? 0 : 1);
                // Jiggle on cardinal directions to avoid oscillation (biar ga stuck)
                if (dirTo.ordinal() % 2 == 0 && myLoc.distanceSquaredTo(target) > 50) {
                    if (rc.getRoundNum() % 4 < 2 && d == dirTo.rotateLeft()) score++;
                    else if (rc.getRoundNum() % 4 >= 2 && d == dirTo.rotateRight()) score++;
                }
                if (score > bestScore) { bestScore = score; bestDir = d; }
            }
            if (bestDir != null && bestScore > -20) {
                bugLastLoc = myLoc; rc.move(bestDir); return;
            }
            // If all 3 dirs blocked by robots, wiggle oppositely
            boolean allRobots = true;
            for (Direction d : new Direction[]{dirTo, dirTo.rotateLeft(), dirTo.rotateRight()}) {
                MapLocation nl = myLoc.add(d);
                if (!rc.onTheMap(nl) || !rc.canSenseRobotAtLocation(nl)) { allRobots = false; break; }
            }
            if (allRobots) { fuzzyMove(rc, target, enemies); return; }
            // Begin wall-following
            if (bugIdx < 99) bugStack[bugIdx++] = bugRight ? dirTo.rotateLeft() : dirTo.rotateRight();
        }

        // Wall-follow: rotate around the obstacle
        Direction wallDir = bugStack[bugIdx - 1];
        if (bugRight) wallDir = wallDir.rotateRight();
        else          wallDir = wallDir.rotateLeft();
        for (int i = 0; i < 8; i++) {
            if (!rc.canMove(wallDir) || !canMoveAndSafe(rc, wallDir, enemies)) {
                if (!rc.onTheMap(myLoc.add(wallDir))) {
                    bugStack = new Direction[100]; bugIdx = 0; bugRight = !bugRight; break;
                }
                if (bugIdx < 99) bugStack[bugIdx++] = wallDir;
            } else {
                bugLastLoc = myLoc; rc.move(wallDir); return;
            }
            wallDir = bugRight ? wallDir.rotateRight() : wallDir.rotateLeft();
        }
    }

    static boolean canMoveAndSafe(RobotController rc, Direction d, RobotInfo[] enemies)
            throws GameActionException {
        if (!rc.canMove(d)) return false;
        return !isTooCloseToEnemyTower(rc.getLocation().add(d), enemies);
    }

    static boolean isTooCloseToEnemyTower(MapLocation loc, RobotInfo[] enemies) {
        if (enemies == null) return false;
        for (RobotInfo e : enemies)
            if (e.type.isTowerType() && loc.distanceSquaredTo(e.location) <= e.type.actionRadiusSquared)
                return true;
        return false;
    }

    static boolean isTooCloseToEnemyTowerExcept(MapLocation loc, RobotInfo[] enemies,
            MapLocation ignoreTowerLoc) {
        if (enemies == null) return false;
        for (RobotInfo e : enemies) {
            if (!e.type.isTowerType()) continue;
            if (ignoreTowerLoc != null && e.location.equals(ignoreTowerLoc)) continue;
            if (loc.distanceSquaredTo(e.location) <= e.type.actionRadiusSquared) return true;
        }
        return false;
    }

    // Tile score for movement decisions
    static int tileScore(RobotController rc, MapLocation loc, RobotInfo[] enemies)
            throws GameActionException {
        if (!rc.onTheMap(loc)) return -9999;
        if (isTooCloseToEnemyTower(loc, enemies)) return -20;
        if (!rc.canSenseLocation(loc)) return 0;

        MapInfo info = rc.senseMapInfo(loc);
        int score;
        if (info.getPaint().isAlly()) score = 1;
        else if (info.getPaint() == PaintType.EMPTY) score = -1;
        else score = -2;

        // Avoid enemy paint more when low on paint.
        if (rc.getPaint() < rc.getType().paintCapacity * 0.35) {
            if (info.getPaint().isEnemy()) score -= 2;
            else if (info.getPaint() == PaintType.EMPTY) score -= 1;
        }

        // Small anti-clumping term keeps greedy movement from bunching too tightly.
        int nearbyAllies = rc.senseNearbyRobots(loc, 2, rc.getTeam()).length;
        if (nearbyAllies > 2) score -= (nearbyAllies - 2);

        return score;
    }

    // Fuzzy move toward target trying up to 7 directions (local movement biar ga stuck)
    static void fuzzyMove(RobotController rc, MapLocation target, RobotInfo[] enemies)
            throws GameActionException {
        if (!rc.isMovementReady()) return;
        Direction dirTo = rc.getLocation().directionTo(target);
        Direction bestDir = null; int bestScore = Integer.MIN_VALUE;
        for (Direction d : fuzzyDirs(dirTo)) {
            if (!rc.canMove(d)) continue;
            MapLocation nl = rc.getLocation().add(d);
            if (isTooCloseToEnemyTower(nl, enemies)) continue;
            int score = tileScore(rc, nl, enemies) * 2 - d.ordinal();
            if (score > bestScore) { bestScore = score; bestDir = d; }
        }
        if (bestDir != null) rc.move(bestDir);
    }

    //  TOWER PATTERN SYSTEM
    static int[][] patternFor(UnitType type) {
        if (type == null) return PAINT_PATTERN;
        UnitType base = type.getBaseType();
        if (base == UnitType.LEVEL_ONE_MONEY_TOWER)   return MONEY_PATTERN;
        if (base == UnitType.LEVEL_ONE_DEFENSE_TOWER) return DEFENSE_PATTERN;
        return PAINT_PATTERN;
    }

    static void markTowerPattern(MapLocation ruin, UnitType type) {
        int[][] pattern = patternFor(type);
        for (int i = 0; i < 5; i++) {
            int gx = ruin.x + (i - 2);
            if (gx < 0 || gx >= mapW) continue;
            for (int j = 0; j < 5; j++) {
                int gy = ruin.y + (j - 2);
                if (gy < 0 || gy >= mapH) continue;
                tileColors[gx][gy] = pattern[i][j];
            }
        }
    }

    // Read ally marks on ruin-adjacent tiles to learn tower type (set by soldiers)
    static void updatePatternFromMarks(RobotController rc, MapLocation[] ruins)
            throws GameActionException {
        for (MapLocation ruin : ruins) {
            for (Direction d : MOVE_DIRS) {
                MapLocation ml = ruin.add(d);
                if (!rc.canSenseLocation(ml)) continue;
                if (rc.senseMapInfo(ml).getMark() == PaintType.ALLY_SECONDARY) {
                    UnitType t = markDirToTowerType(d);
                    if (t != null) { markTowerPattern(ruin, t); break; }
                }
            }
        }
    }

    // Paint a tile using the tileColors lookup for correct primary/secondary color
    static boolean checkAndPaint(RobotController rc, MapLocation loc) throws GameActionException {
        if (!rc.onTheMap(loc) || !rc.canSenseLocation(loc) || !rc.canAttack(loc)) return false;
        MapInfo info = rc.senseMapInfo(loc);
        if (info.hasRuin() || info.isWall()) return false;
        int cInt = safeGetColor(loc);
        if (cInt == 0) {
            if (info.getPaint() == PaintType.EMPTY) { rc.attack(loc, false); return true; }
            return false;
        }
        boolean wantSec = (cInt == 2);
        PaintType want = wantSec ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;
        if (info.getPaint() == PaintType.EMPTY || (!info.getPaint().isEnemy() && info.getPaint() != want)) {
            rc.attack(loc, wantSec); return true;
        }
        return false;
    }

    static boolean tryBookNearbyRuin(RobotController rc, MapLocation[] ruins) throws GameActionException {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapLocation ruin : ruins) {
            if (!rc.canSenseLocation(ruin)) continue;
            if (rc.senseRobotAtLocation(ruin) != null) continue;
            for (MapLocation loc : ruinFillOrder(ruin)) {
                if (!rc.onTheMap(loc) || !rc.canSenseLocation(loc) || !rc.canAttack(loc)) continue;
                MapInfo info = rc.senseMapInfo(loc);
                if (info.hasRuin() || info.isWall()) continue;
                if (info.getPaint().isAlly()) continue;
                int d = rc.getLocation().distanceSquaredTo(loc);
                if (d < bestDist) { bestDist = d; best = loc; }
            }
        }
        if (best != null) {
            rc.attack(best, false);
            return true;
        }
        return false;
    }

    static boolean tryRandomSafeMove(RobotController rc, RobotInfo[] enemies)
            throws GameActionException {
        if (!rc.isMovementReady()) return false;
        Direction rd = MOVE_DIRS[rng.nextInt(8)];
        for (Direction d : fuzzyDirs(rd)) {
            if (!rc.canMove(d)) continue;
            MapLocation nl = rc.getLocation().add(d);
            if (isTooCloseToEnemyTower(nl, enemies)) continue;
            rc.move(d);
            return true;
        }
        return false;
    }

    static int safeGetColor(MapLocation loc) {
        if (loc.x < 0 || loc.x >= mapW || loc.y < 0 || loc.y >= mapH) return 0;
        return tileColors[loc.x][loc.y];
    }

    //  TOWER TYPE SELECTION — greedy ratio heuristic
    static UnitType determineTowerType(RobotController rc, MapLocation ruinLoc) {
        try {
            int paint = 1, money = 0;

            for (RobotInfo a : rc.senseNearbyRobots(-1, rc.getTeam())) {
                if (!a.type.isTowerType()) continue;
                UnitType base = a.type.getBaseType();

                if (base == UnitType.LEVEL_ONE_PAINT_TOWER) paint++;
                else if (base == UnitType.LEVEL_ONE_MONEY_TOWER) money++;
            }

            // Simple ratio heuristic
            double ratio = money < 6 ? 2.5 : 1.5;
            if (money < Math.max(1, paint) * ratio && rc.getChips() < 4000)
                return UnitType.LEVEL_ONE_MONEY_TOWER;

        } catch (GameActionException e) {
            e.printStackTrace();
        }

        return UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    static Direction towerTypeToMarkDir(UnitType type) {
        UnitType base = type.getBaseType();
        if (base == UnitType.LEVEL_ONE_MONEY_TOWER)   return Direction.NORTHWEST;
        if (base == UnitType.LEVEL_ONE_DEFENSE_TOWER) return Direction.NORTHEAST;
        return Direction.NORTH;
    }

    static UnitType markDirToTowerType(Direction dir) {
        return switch (dir) {
            case NORTH     -> UnitType.LEVEL_ONE_PAINT_TOWER;
            case NORTHWEST -> UnitType.LEVEL_ONE_MONEY_TOWER;
            case NORTHEAST -> UnitType.LEVEL_ONE_DEFENSE_TOWER;
            default        -> null;
        };
    }

    //  FILL ORDER & SPIRAL HELPERS
    // Visits outer corners first, then edges, then inner — fills ruin pattern efficiently
    static MapLocation[] ruinFillOrder(MapLocation r) {
        int x = r.x, y = r.y;
        return new MapLocation[]{
            new MapLocation(x-2,y+2), new MapLocation(x+2,y+2),
            new MapLocation(x+2,y-2), new MapLocation(x-2,y-2),
            new MapLocation(x-1,y+2), new MapLocation(x+1,y+2),
            new MapLocation(x-1,y-2), new MapLocation(x+1,y-2),
            new MapLocation(x-2,y+1), new MapLocation(x+2,y+1),
            new MapLocation(x+2,y-1), new MapLocation(x-2,y-1),
            new MapLocation(x,  y+2), new MapLocation(x+2,y  ),
            new MapLocation(x,  y-2), new MapLocation(x-2,y  ),
            new MapLocation(x-1,y+1), new MapLocation(x+1,y+1),
            new MapLocation(x-1,y-1), new MapLocation(x+1,y-1),
            new MapLocation(x,  y+1), new MapLocation(x+1,y  ),
            new MapLocation(x,  y-1), new MapLocation(x-1,y  )
        };
    }

    /** Spiral outward from centre for painting nearby tiles */
    static MapLocation[] r3spiral(MapLocation c) {
        int x = c.x, y = c.y;
        return new MapLocation[]{
            new MapLocation(x,y),
            new MapLocation(x-1,y+1), new MapLocation(x,y+1), new MapLocation(x+1,y+1),
            new MapLocation(x+1,y),   new MapLocation(x+1,y-1), new MapLocation(x,y-1),
            new MapLocation(x-1,y-1), new MapLocation(x-1,y),
            new MapLocation(x-2,y+2), new MapLocation(x-1,y+2), new MapLocation(x,y+2),
            new MapLocation(x+1,y+2), new MapLocation(x+2,y+2), new MapLocation(x+2,y+1),
            new MapLocation(x+2,y),   new MapLocation(x+2,y-1), new MapLocation(x+2,y-2),
            new MapLocation(x+1,y-2), new MapLocation(x,y-2),   new MapLocation(x-1,y-2),
            new MapLocation(x-2,y-2), new MapLocation(x-2,y-1), new MapLocation(x-2,y),
            new MapLocation(x-2,y+1)
        };
    }

    //  Utility
    static boolean shouldRefill(RobotController rc) {
        return rc.getPaint() < rc.getType().paintCapacity * 0.25;
    }

    static boolean canSpawnWithReserve(RobotController rc, UnitType unit, int reserve) {
        return rc.getPaint() >= unit.paintCost + reserve;
    }

    static MapLocation getClosestCompletableRuin(RobotController rc, MapLocation[] ruins)
            throws GameActionException {
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;
        for (MapLocation r : ruins) {
            if (!rc.canSenseLocation(r)) continue;
            if (rc.senseRobotAtLocation(r) != null) continue; // already has a tower

            int correct = 0;
            int need = 0;
            int enemyPaint = 0;
            for (MapLocation loc : ruinFillOrder(r)) {
                if (!rc.onTheMap(loc)) continue;
                if (!rc.canSenseLocation(loc)) { need++; continue; }
                MapInfo info = rc.senseMapInfo(loc);
                if (info.hasRuin() || info.isWall()) continue;

                int cInt = safeGetColor(loc);
                if (cInt == 0) continue;
                boolean wantSec = (cInt == 2);
                PaintType want = wantSec ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;

                if (info.getPaint() == want) correct++;
                else {
                    need++;
                    if (info.getPaint().isEnemy()) enemyPaint++;
                }
            }

            int d = rc.getLocation().distanceSquaredTo(r);
            int score = correct * 6 - need * 3 - enemyPaint * 2 - d;
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    static int encodeTowerHint(MapLocation loc, UnitType baseType) {
        int towerType = 0;
        if (baseType == UnitType.LEVEL_ONE_MONEY_TOWER) towerType = 1;
        else if (baseType == UnitType.LEVEL_ONE_DEFENSE_TOWER) towerType = 2;
        return (MSG_PREFIX_TOWER_LOC << 28)
                | ((loc.x & 0x3F) << 22)
                | ((loc.y & 0x3F) << 16)
                | ((towerType & 0x3) << 14);
    }

    static void receiveTowerHints(RobotController rc) {
        try {
            int round = rc.getRoundNum();
            if (round <= 0) return;

            int startRound = Math.max(0, round - 1);
            for (int r = startRound; r <= round; r++) {
                for (Message message : rc.readMessages(r)) {
                    int raw = message.getBytes();
                    int prefix = (raw >>> 28) & 0x7;
                    if (prefix != MSG_PREFIX_TOWER_LOC) continue;

                    int x = (raw >>> 22) & 0x3F;
                    int y = (raw >>> 16) & 0x3F;
                    int towerType = (raw >>> 14) & 0x3;
                    MapLocation loc = new MapLocation(x, y);

                    hintedAnyTower = loc;
                    hintedAnyTowerRound = round;
                    if (towerType == 0) {
                        hintedPaintTower = loc;
                        hintedPaintTowerRound = round;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    static MapLocation bestTowerHint(RobotController rc, boolean preferPaintTower) {
        int round = rc.getRoundNum();
        if (preferPaintTower && hintedPaintTower != null && round - hintedPaintTowerRound <= TOWER_HINT_TTL)
            return hintedPaintTower;
        if (hintedAnyTower != null && round - hintedAnyTowerRound <= TOWER_HINT_TTL)
            return hintedAnyTower;
        if (hintedPaintTower != null && round - hintedPaintTowerRound <= TOWER_HINT_TTL)
            return hintedPaintTower;
        return null;
    }

    static RobotInfo nearestEnemyNonDefenseTower(RobotController rc, RobotInfo[] enemies) {
        RobotInfo best = null; int minD = Integer.MAX_VALUE;
        for (RobotInfo e : enemies) {
            if (!e.type.isTowerType()) continue;
            if (e.type.getBaseType() == UnitType.LEVEL_ONE_DEFENSE_TOWER) continue;
            int d = rc.getLocation().distanceSquaredTo(e.location);
            if (d < minD) { minD = d; best = e; }
        }
        return best;
    }

    static MapLocation enemyPaintCentroid(RobotController rc, MapInfo[] tiles) {
        long sx = 0, sy = 0; int n = 0;
        for (MapInfo t : tiles)
            if (t.getPaint().isEnemy()) { sx += t.getMapLocation().x; sy += t.getMapLocation().y; n++; }
        return n >= 2 ? new MapLocation((int)(sx / n), (int)(sy / n)) : null;
    }

    static MapLocation extendLocToEdge(RobotController rc, MapLocation start, Direction dir) {
        MapLocation cur = start;
        while (rc.onTheMap(cur.add(dir))) {
            cur = cur.add(dir);
        }
        return cur;
    }

    static void newExploreTarget(RobotController rc) {
        Direction d = MOVE_DIRS[rng.nextInt(8)];
        MapLocation cur = rc.getLocation();
        int steps = Math.max(mapW, mapH) / 3;
        for (int i = 0; i < steps; i++) {
            MapLocation next = cur.add(d);
            if (next.x < 0 || next.y < 0 || next.x >= mapW || next.y >= mapH) break;
            cur = next;
        }
        exploreTarget = cur;
    }

    // Move within distance `range` of `target`, preferring safe tiles
    static boolean tryMoveInRange(RobotController rc, MapLocation target, int range,
            RobotInfo[] enemies) throws GameActionException {
        if (!rc.isMovementReady()) return rc.getLocation().distanceSquaredTo(target) <= range;
        if (rc.getLocation().distanceSquaredTo(target) <= range) return true;
        Direction dirTo = rc.getLocation().directionTo(target);
        Direction best = null; int bestScore = Integer.MIN_VALUE;
        for (Direction d : fuzzyDirs(dirTo)) {
            if (!rc.canMove(d)) continue;
            MapLocation nl = rc.getLocation().add(d);
            if (nl.distanceSquaredTo(target) > range) continue;
            if (isTooCloseToEnemyTower(nl, enemies)) continue;
            int score = tileScore(rc, nl, enemies);
            if (score > bestScore) { bestScore = score; best = d; }
        }
        if (best != null) { rc.move(best); return true; }
        return rc.getLocation().distanceSquaredTo(target) <= range;
    }

    static boolean tryMoveOutOfRange(RobotController rc, MapLocation avoidLoc, int dist,
            RobotInfo[] enemies) throws GameActionException {
        if (!rc.isMovementReady()) return rc.getLocation().distanceSquaredTo(avoidLoc) > dist;
        Direction best = null;
        int bestScore = Integer.MIN_VALUE;
        Direction away = rc.getLocation().directionTo(avoidLoc).opposite();
        for (Direction d : fuzzyDirs(away)) {
            if (!rc.canMove(d)) continue;
            MapLocation nl = rc.getLocation().add(d);
            if (nl.distanceSquaredTo(avoidLoc) <= dist) continue;
            if (isTooCloseToEnemyTowerExcept(nl, enemies, avoidLoc)) continue;
            int score = tileScore(rc, nl, enemies);
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        if (best != null) {
            rc.move(best);
            return true;
        }
        return rc.getLocation().distanceSquaredTo(avoidLoc) > dist;
    }

    static boolean tryMoveIntoRange(RobotController rc, MapLocation targetLoc, int dist,
            RobotInfo[] enemies) throws GameActionException {
        if (!rc.isMovementReady()) return rc.getLocation().distanceSquaredTo(targetLoc) <= dist;

        Direction best = Direction.CENTER;
        int bestScore = rc.getLocation().distanceSquaredTo(targetLoc) <= dist
                ? tileScore(rc, rc.getLocation(), enemies)
                : -9999;

        for (Direction d : fuzzyDirs(rc.getLocation().directionTo(targetLoc))) {
            if (!rc.canMove(d)) continue;
            MapLocation nl = rc.getLocation().add(d);
            if (nl.distanceSquaredTo(targetLoc) > dist) continue;
            if (isTooCloseToEnemyTowerExcept(nl, enemies, targetLoc)) continue;
            int score = tileScore(rc, nl, enemies);
            if (score > bestScore) {
                best = d;
                bestScore = score;
            }
        }

        if (best != Direction.CENTER && bestScore > -1000) {
            rc.move(best);
            return true;
        }
        return rc.getLocation().distanceSquaredTo(targetLoc) <= dist;
    }

    static boolean trySummon(RobotController rc, UnitType type) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation bestLoc = null; int bestScore = Integer.MIN_VALUE;
        for (MapLocation loc : rc.getAllLocationsWithinRadiusSquared(myLoc,
                GameConstants.BUILD_ROBOT_RADIUS_SQUARED)) {
            if (!rc.canBuildRobot(type, loc)) continue;
            int score = 0;
            if (rc.canSenseLocation(loc) && rc.senseMapInfo(loc).getPaint().isAlly()) score += 2;
            if (loc.x < 4 || loc.y < 4 || mapW - loc.x < 4 || mapH - loc.y < 4) score -= 3;
            if (score > bestScore) { bestScore = score; bestLoc = loc; }
        }
        if (bestLoc != null) { rc.buildRobot(type, bestLoc); return true; }
        return false;
    }

    static boolean trySummonInDir(RobotController rc, UnitType type, Direction dir)
            throws GameActionException {
        for (Direction d : fuzzyDirs(dir)) {
            MapLocation loc = rc.getLocation().add(d);
            if (rc.canBuildRobot(type, loc)) { rc.buildRobot(type, loc); return true; }
        }
        return trySummon(rc, type);
    }

    static Direction[] fuzzyDirs(Direction dir) {
        return new Direction[]{
            dir,
            dir.rotateLeft(),
            dir.rotateRight(),
            dir.rotateLeft().rotateLeft(),
            dir.rotateRight().rotateRight(),
            dir.rotateLeft().rotateLeft().rotateLeft(),
            dir.rotateRight().rotateRight().rotateRight(),
            dir.opposite()
        };
    }
}
