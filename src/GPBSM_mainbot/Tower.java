package botv11;

import battlecode.common.*;

class Tower extends Utils {

    static void runTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies  = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        UnitType base = rc.getType().getBaseType();
        boolean isPaint = (base == UnitType.LEVEL_ONE_PAINT_TOWER);
        int paintReserve = isPaint ? SPAWN_RESERVE_PAINT_TOWER : SPAWN_RESERVE_OTHER_TOWER;

        // Lightweight communication: advertise tower location for refill/navigation.
        if (rc.canBroadcastMessage() && (rc.getRoundNum() < 10 || rc.getRoundNum() % 5 == 0)) {
            rc.broadcastMessage(encodeTowerHint(rc.getLocation(), base));
        }

        // UPGRADE: greedy, upgrade whenever chips sufficient + enough allies
        int upgradeMin = isPaint ? 2500 : 3600;
        if (rc.getChips() > upgradeMin && rc.canUpgradeTower(rc.getLocation())
                && (allies.length >= 3 || rc.getChips() > upgradeMin + 1000)) {
            rc.upgradeTower(rc.getLocation());
        }

        // SPAWN: greedy unit composition
        int chips = rc.getChips();
        boolean primarySpawner = isPaint || rc.getNumberTowers() <= 2;

        if (primarySpawner) {
            // Emergency mopper: when enemy soldiers attack (keperluan defend aja)
            int enemySoldiers = 0, friendlyMoppers = 0;
            for (RobotInfo e : enemies) if (e.type == UnitType.SOLDIER) enemySoldiers++;
            for (RobotInfo a : allies)  if (a.type == UnitType.MOPPER)  friendlyMoppers++;
            if (enemySoldiers >= 1 && friendlyMoppers < enemySoldiers
                    && rc.getRoundNum() - emergencyMopperTurn > MOPPER_COOLDOWN) {
                Direction ed = rc.getLocation().directionTo(enemies[0].location);
                if (trySummonInDir(rc, UnitType.MOPPER, ed))
                    emergencyMopperTurn = rc.getRoundNum();
            }

            // Main rhythm
            MapLocation enemyCorner = new MapLocation(mapW - rc.getLocation().x - 1,
                                                      mapH - rc.getLocation().y - 1);
            Direction enemyDirection = rc.getLocation().directionTo(enemyCorner);
            boolean firstTower = rc.getRoundNum() < 8;
            if (chips >= 200) {
                if (rc.getRoundNum() < OPENING_ROUND && rc.getNumberTowers() <= 5) {
                    boolean canOpen = allies.length < 3
                            && ((spawnCount < 2 && (firstTower || rc.getNumberTowers() > 5))
                                    || chips > 2100);
                    if (canOpen) {
                        if (spawnCount % 3 < 2) {
                            boolean spawned = false;
                            if (spawnCount == 0) {
                                Direction dir = enemyDirection;
                                MapLocation edgeLoc = extendLocToEdge(rc, rc.getLocation(), enemyDirection.opposite());
                                MapLocation diagonalLoc = new MapLocation(mapW - rc.getLocation().x,
                                                                          mapH - rc.getLocation().y);
                                if (rc.getLocation().distanceSquaredTo(diagonalLoc)
                                        <= rc.getLocation().distanceSquaredTo(edgeLoc)) {
                                    dir = dir.opposite();
                                }
                                if (canSpawnWithReserve(rc, UnitType.SOLDIER, paintReserve))
                                    spawned = trySummonInDir(rc, UnitType.SOLDIER, dir);
                            }
                            if (!spawned && canSpawnWithReserve(rc, UnitType.SOLDIER, paintReserve))
                                spawned = trySummon(rc, UnitType.SOLDIER);
                            if (spawned) spawnCount++;
                        } else {
                            if (canSpawnWithReserve(rc, UnitType.SPLASHER, paintReserve)
                                    && trySummonInDir(rc, UnitType.SPLASHER, enemyDirection)) spawnCount++;
                        }
                    }
                } else if (chips > 1100) {
                    // Standard cycle: soldier -> mopper -> splasher
                    if      (spawnCount % 3 == 0) { if (canSpawnWithReserve(rc, UnitType.SOLDIER, paintReserve) && trySummon(rc, UnitType.SOLDIER))  spawnCount++; }
                    else if (spawnCount % 3 == 1) { if (canSpawnWithReserve(rc, UnitType.MOPPER, paintReserve) && trySummon(rc, UnitType.MOPPER))   spawnCount++; }
                    else                          { if (canSpawnWithReserve(rc, UnitType.SPLASHER, paintReserve) && trySummonInDir(rc, UnitType.SPLASHER,
                                                        enemyDirection)) spawnCount++; }
                }
            }
        } else if (rc.getNumberTowers() >= 4 && chips > 1100) {
            // Secondary tower: only soldiers
            if (canSpawnWithReserve(rc, UnitType.SOLDIER, paintReserve)) {
                trySummon(rc, UnitType.SOLDIER);
            }
        }

        // ATTACK: lowest-HP enemy, prioritise soldiers
        // Selection: lowest health = highest priority kill
        RobotInfo bestTarget = null;
        int lowestHP = Integer.MAX_VALUE;
        boolean foundSoldier = false;
        for (RobotInfo e : enemies) {
            if (!rc.canAttack(e.location)) continue;
            boolean isSol = (e.type == UnitType.SOLDIER);
            if (!foundSoldier && isSol)                                     { foundSoldier=true; bestTarget=e; lowestHP=e.health; }
            else if (foundSoldier && isSol && e.health < lowestHP)          { bestTarget=e; lowestHP=e.health; }
            else if (!foundSoldier && e.health < lowestHP)                  { bestTarget=e; lowestHP=e.health; }
        }
        if (bestTarget != null) rc.attack(bestTarget.location);

        // Also try standard attack just in case
        if (rc.isActionReady()) {
            for (RobotInfo e : enemies) {
                if (rc.canAttack(e.location)) { rc.attack(e.location); break; }
            }
        }
    }
}
