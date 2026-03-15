package GreedyExpansion;

import battlecode.common.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.hibernate.proxy.map.MapLazyInitializer;



/**
 * RobotPlayer is the class that describes your main robot strategy.
 * The run() method inside this class is like your main function: this is what we'll call once your robot
 * is created!
 */
public class RobotPlayer {
    /**
     * We will use this variable to count the number of turns this robot has been alive.
     * You can use static variables like this to save any information you want. Keep in mind that even though
     * these variables are static, in Battlecode they aren't actually shared between your robots.
     */
    static int turnCount = 0;

    /**
     * A random number generator.
     * We will use this RNG to make some random moves. The Random class is provided by the java.util.Random
     * import at the top of this file. Here, we *seed* the RNG with a constant number (6147); this makes sure
     * we get the same sequence of numbers every time this code is run. This is very useful for debugging!
     */
    static final Random rng = new Random(6147);

    /** Array containing all the possible movement directions. */
    static final Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    static MapLocation exploreTarget = null;
    static MapLocation homeTower = null;

    static final UnitType[] towerTypes = {UnitType.LEVEL_ONE_MONEY_TOWER, UnitType.LEVEL_ONE_PAINT_TOWER};
    static final int minimalMoney = 1000;
    static final double ratioThreshold = 1.25;
    static Direction botDirection = null;
    
    /**
     * run() is the method that is called when a robot is instantiated in the Battlecode world.
     * It is like the main function for your robot. If this method returns, the robot dies!
     *
     * @param rc  The RobotController object. You use it to perform actions from this robot, and to get
     *            information on its current status. Essentially your portal to interacting with the world.
     **/
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        // Hello world! Standard output is very useful for debugging.
        // Everything you say here will be directly viewable in your terminal when you run a match!
        System.out.println("I'm alive");

        // You can also use indicators to save debug notes in replays.
        rc.setIndicatorString("Hello world!");

        while (true) {
            // This code runs during the entire lifespan of the robot, which is why it is in an infinite
            // loop. If we ever leave this loop and return from run(), the robot dies! At the end of the
            // loop, we call Clock.yield(), signifying that we've done everything we want to do.

            turnCount += 1;  // We have now been alive for one more turn!
            // Try/catch blocks stop unhandled exceptions, which cause your robot to explode.
            try {
                // The same run() function is called for every robot on your team, even if they are
                // different types. Here, we separate the control depending on the UnitType, so we can
                // use different strategies on different robots. If you wish, you are free to rewrite
                // this into a different control structure!
                switch (rc.getType()){
                    case SOLDIER: runSoldierEfficient(rc); break; 
                    case MOPPER: runMopperEfficient(rc); break;
                    case SPLASHER: runSplasherEfficient(rc); break; // Consider upgrading examplefuncsplayer to use splashers!
                    default: runTowerEfficient(rc); break;
                    }
                }
             catch (GameActionException e) {
                // Oh no! It looks like we did something illegal in the Battlecode world. You should
                // handle GameActionExceptions judiciously, in case unexpected events occur in the game
                // world. Remember, uncaught exceptions cause your robot to explode!
                System.out.println("GameActionException");
                e.printStackTrace();

            } catch (Exception e) {
                // Oh no! It looks like our code tried to do something bad. This isn't a
                // GameActionException, so it's more likely to be a bug in our code.
                System.out.println("Exception");
                e.printStackTrace();

            } finally {
                // Signify we've done everything we want to do, thereby ending our turn.
                // This will make our code wait until the next turn, and then perform this loop again.
                Clock.yield();
            }
            // End of loop: go back to the top. Clock.yield() has ended, so it's time for another turn!
        }

        // Your code should never reach here (unless it's intentional)! Self-destruction imminent...
    }

    private static int heuristicScore(MapInfo tile) throws GameActionException {
        PaintType paint = tile.getPaint();

        if (paint == PaintType.EMPTY) {
            return 10;
        }

        if (paint.isEnemy()) {
            return 5;
        }
        return 1;
    }

    /**
     * Run a single turn for towers.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
    public static void runTowerEfficient(RobotController rc) throws GameActionException{

        int roundNum = rc.getRoundNum();
        double needMooper;
        if (roundNum < 400) {
            needMooper = 0.08;
        } 
        else if (roundNum < 850) {
            needMooper = 0.21;
        } 
        else if (roundNum < 1400) {
            needMooper = 0.34;
        } 
        else {
            needMooper = 0.42;
        }

        boolean enemyPaintNearby = false;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (tile.getPaint().isEnemy()) {
                enemyPaintNearby = true;
                break;
            }
        }
        if (enemyPaintNearby == true) {
            needMooper += 0.25;
        }

        boolean getMooper;
        if (rng.nextDouble() < needMooper) {
            getMooper = true;
        } 
        else {
            getMooper = false;
        }

        int botProductionCost;
        if (getMooper == true) {
            botProductionCost = 300;
        }
        else {
            botProductionCost = 250;
        }

        if ( (rc.getMoney() - botProductionCost) < minimalMoney) {
            return;
        }

        for (Direction dir : directions) {
            MapLocation loc = rc.getLocation().add(dir);
            if (getMooper == true && rc.canBuildRobot(UnitType.MOPPER, loc)) {
                rc.buildRobot(UnitType.MOPPER, loc);
                return;
            }
            if (getMooper == false && rc.canBuildRobot(UnitType.SOLDIER, loc)) {
                rc.buildRobot(UnitType.SOLDIER, loc);
                return;
            }
        }
    }

    

    /**
     * Run a single turn for a Soldier.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */

    public static void runSoldierEfficient(RobotController rc) throws GameActionException{
        if (homeTower == null) {
            for (RobotInfo ally : rc.senseNearbyRobots(2, rc.getTeam())) {
                UnitType ut = ally.getType();
                if (ut != UnitType.SOLDIER && ut != UnitType.MOPPER && ut != UnitType.SPLASHER) {
                    homeTower = ally.getLocation();
                    break;
                }
            }
        }

        if (rc.getPaint() < 40 && homeTower != null) {
            rc.setIndicatorString("LOW PAINT → home");

            if (rc.getLocation().distanceSquaredTo(homeTower) <= 2) {
                if (rc.canTransferPaint(homeTower, -50)) {
                    rc.transferPaint(homeTower, -50);
                }

                return;
            }
            Direction dir = rc.getLocation().directionTo(homeTower);
            if (rc.canMove(dir)) {
                rc.move(dir);
            }
            else if (rc.canMove(dir.rotateLeft())) {
                rc.move(dir.rotateLeft());
            }
            else if (rc.canMove(dir.rotateRight())) {
                rc.move(dir.rotateRight());
            }
            return;
        }
        
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        for (MapInfo nearbyTile : nearbyTiles) {
            if (nearbyTile.hasRuin()) {
                RobotInfo robotExistance = rc.senseRobotAtLocation(nearbyTile.getMapLocation());
                
                if (robotExistance == null && handleRuinBuilding(rc, nearbyTile)) {
                    return;
                }
            }
        }

        MapLocation curLocation = rc.getLocation();
        int bestScore = -1;
        Direction[] directionCandidates = new Direction[8];
        int countCandidates = 0;

        for (Direction dir : directions) {
            if (!rc.canMove(dir)) {
                continue;
            }

            MapInfo nexTile = rc.senseMapInfo(curLocation.add(dir));
            int score = heuristicScore(nexTile);

            if (botDirection != null && dir == botDirection) {
                score +=1;
            }

            if (score > bestScore) {
                bestScore = score;
                directionCandidates[0] = dir;
                countCandidates = 1;
            } 
            else if (score == bestScore) {
                directionCandidates[countCandidates] = dir;
                countCandidates += 1;
            }

        }

        if (countCandidates > 0){
            boolean keepDirection = false;

            for (int i=0; i <countCandidates; i++) {
                if (directionCandidates[i] == botDirection) {
                    keepDirection = true;
                    break;
                }
            }
            
            if (botDirection == null || keepDirection == false) {
                botDirection = directionCandidates[rng.nextInt(countCandidates)];
            }

            if (botDirection != null && rc.canMove(botDirection)) {
                rc.move(botDirection);
            }

            MapInfo currTile = rc.senseMapInfo(rc.getLocation());
            if (!currTile.getPaint().isAlly() && currTile.getMark() == PaintType.EMPTY && rc.canAttack(rc.getLocation())) {
                rc.attack(rc.getLocation());
            }

            return;
        }

        if (exploreTarget == null || rc.getLocation().distanceSquaredTo(exploreTarget) < 4) {
            exploreTarget = new MapLocation(rng.nextInt(rc.getMapWidth()), rng.nextInt(rc.getMapHeight()));

        } 
        Direction backDirection = rc.getLocation().directionTo(exploreTarget);

        if (rc.canMove(backDirection)) {
            rc.move(backDirection);
        }
        else if (rc.canMove(backDirection.rotateLeft())) {
            rc.move(backDirection.rotateLeft());
        }
        else if (rc.canMove(backDirection.rotateRight())) {
            rc.move(backDirection.rotateRight());
        }
        else {
            exploreTarget = null;
        }

        
          
    }


    /**
     * Run a single turn for a Mopper.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
    public static void runMopperEfficient(RobotController rc) throws GameActionException{
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(-1, rc.getTeam());

        for (MapInfo nearbyTile : nearbyTiles) {
            if (nearbyTile.getPaint().isEnemy() && rc.canAttack(nearbyTile.getMapLocation())) {
                rc.attack(nearbyTile.getMapLocation());
                break;
            }
        }
        
        MapLocation enemyTarget = null;
        double bestScore = 0;

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.getPaint().isEnemy()) {
                continue;
            }

            if (!tile.isPassable()) {
                continue;
            }

            double score = 1.0 / (1.0 + rc.getLocation().distanceSquaredTo(tile.getMapLocation()) * 0.05);
            if (score > bestScore) {
                bestScore = score;
                enemyTarget = tile.getMapLocation();
            }
        }

        if (enemyTarget != null) {
            Direction dir = rc.getLocation().directionTo(enemyTarget);
            if (rc.canMove(dir)) {
                rc.move(dir);
            }
            else if (rc.canMove(dir.rotateLeft())) {
                rc.move(dir.rotateLeft());
            }
            else if (rc.canMove(dir.rotateRight())) {
                rc.move(dir.rotateRight());
            }

            rc.setIndicatorString("chasing enemy @ " + enemyTarget);
        } else {
            Direction dir = directions[rng.nextInt(directions.length)];
            if (rc.canMove(dir)) {
                rc.move(dir);
            }

            rc.setIndicatorString("wandering");
        }

        for (Direction dir : directions) {
            if (!rc.canMopSwing(dir)) {
                continue;
            }

            MapLocation fwd = rc.getLocation().add(dir);
            MapLocation left = rc.getLocation().add(dir.rotateLeft());
            MapLocation right = rc.getLocation().add(dir.rotateRight());

            boolean hasEnemy =  (rc.canSenseLocation(fwd)   && rc.senseMapInfo(fwd).getPaint().isEnemy()) ||
                                (rc.canSenseLocation(left)  && rc.senseMapInfo(left).getPaint().isEnemy()) ||
                                (rc.canSenseLocation(right) && rc.senseMapInfo(right).getPaint().isEnemy());

            if (hasEnemy) {
                rc.mopSwing(dir);
                break;
            }
        }

        RobotInfo needAllies = null;
        int worstSupplies = -1;

        for (RobotInfo nearbyAlly : nearbyAllies) {
            if (nearbyAlly.getType().isRobotType()) {
                int allyPaint = nearbyAlly.paintAmount;
                int allyMaxPaint = nearbyAlly.getType().paintCapacity;

                if (allyPaint < allyMaxPaint * 0.45 && allyPaint < worstSupplies) {
                    worstSupplies = allyPaint;
                    needAllies = nearbyAlly;
                }
            }
        }

        if (needAllies != null && rc.getPaint() > 20) {
            int transferPaint;
            if ( (rc.getPaint() - 10) < 50) {
                transferPaint = rc.getPaint() - 10;
            }
            else {
                transferPaint = 50;
            }

            if (rc.canTransferPaint(needAllies.location, transferPaint)) {
                rc.transferPaint(needAllies.location, transferPaint);
            }
        }
    }

    public static void runSplasherEfficient(RobotController rc) throws GameActionException{
        // Move and attack randomly.
        double bestScore = -1;
        MapLocation bestTarget = null;

        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        for (MapInfo nearbyTile : nearbyTiles) {
            double score = 0;
            MapLocation centerLoc = nearbyTile.getMapLocation();

            for (MapInfo nearby : nearbyTiles) {
                if (centerLoc.distanceSquaredTo(nearby.getMapLocation()) <= 8) {
                    score += heuristicScore(nearby);
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestTarget = centerLoc;
            }
        }

        if (bestTarget != null) {
            if ( rc.canAttack(bestTarget)) {
                rc.attack(bestTarget);
            } 
            else {
                Direction dir = rc.getLocation().directionTo(bestTarget);
                if (rc.canMove(dir)) {
                    rc.move(dir);
                }
            }
        } 
        else {
            Direction dir = directions[rng.nextInt(directions.length)];
            if (rc.canMove(dir)) {
                rc.move(dir);
            }
        }
    }

    private static boolean handleRuinBuilding(RobotController rc, MapInfo ruin) throws GameActionException {
        boolean didSomething = false;
        MapLocation targetLoc = ruin.getMapLocation();
        Direction dir = rc.getLocation().directionTo(targetLoc);

        if (rc.canMove(dir)) {
            rc.move(dir);
            didSomething = true;
        }

        for (MapInfo nearbyMapInfo: rc.senseNearbyMapInfos(targetLoc, 8)) {
            if (nearbyMapInfo.getPaint().isEnemy()) {
                return false;
            }
        }

        UnitType towerThing;
        int towerMoney = rc.getMoney();
        int towerPaint = rc.getPaint();

        if (towerMoney < minimalMoney) {
            towerThing =  UnitType.LEVEL_ONE_MONEY_TOWER;
        } 
        else if (towerPaint <= 0) {
            towerThing = UnitType.LEVEL_ONE_PAINT_TOWER;
        }
        else {
            double ratioMoneyPaint = ((double) towerMoney) / towerPaint;
            
            if (ratioMoneyPaint > ratioThreshold) {
                towerThing =  UnitType.LEVEL_ONE_MONEY_TOWER;
            }
            else if (ratioMoneyPaint < ratioThreshold) {
                towerThing = UnitType.LEVEL_ONE_PAINT_TOWER;
            }
            else {
                int x_center = rc.getMapWidth() / 2;
                int y_center = rc.getMapHeight() / 2;

                int dist = Math.abs(targetLoc.x - x_center) + Math.abs(targetLoc.y - y_center);
                int maxDist = (rc.getMapWidth() + rc.getMapHeight()) / 2;

                if (dist < maxDist * 0.35) {
                    towerThing = UnitType.LEVEL_ONE_PAINT_TOWER;
                } else {
                    towerThing = UnitType.LEVEL_ONE_MONEY_TOWER;
                }
            }
        }

        MapLocation markedLoc = ruin.getMapLocation().subtract(dir);
        if (rc.senseMapInfo(markedLoc).getMark() == PaintType.EMPTY && rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)) {
            rc.markTowerPattern(towerThing, targetLoc);
            didSomething = true;
        }

        for (MapInfo patternTile : rc.senseNearbyMapInfos(targetLoc, 8)) {
            if (patternTile.getMark() != patternTile.getPaint() && patternTile.getMark() != PaintType.EMPTY) {
                boolean useSecondaryColor;
                if (patternTile.getMark() == PaintType.ALLY_SECONDARY) {
                    useSecondaryColor = true;
                }
                else {
                    useSecondaryColor = false;
                }
                
                if (rc.canAttack(patternTile.getMapLocation())) {
                    rc.attack(patternTile.getMapLocation(), useSecondaryColor);
                    didSomething = true;
                }
            }
        }

        for (UnitType towerType: towerTypes) {
            if (rc.canCompleteTowerPattern(towerType, targetLoc)) {
                rc.completeTowerPattern(towerType, targetLoc);
                System.out.println("[S] TOWER BUILT @ " + targetLoc);
                didSomething = true;
                break;
            }
        }
        

        return didSomething;
    }
}
