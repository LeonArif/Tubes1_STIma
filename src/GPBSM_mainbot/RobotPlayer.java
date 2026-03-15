package botv11;

import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {

	// Constraints
	static final Direction[] MOVE_DIRS = {
		Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
		Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
	};
	static final UnitType[] TOWER_TYPES = {
		UnitType.LEVEL_ONE_PAINT_TOWER,
		UnitType.LEVEL_ONE_MONEY_TOWER,
		UnitType.LEVEL_ONE_DEFENSE_TOWER
	};

	// Tower patterns: pattern[dx+2][dy+2] -> 1=primary, 2=secondary
	// Outer index = column offset (-2..+2), inner index = row offset (-2..+2)
	static final int[][] PAINT_PATTERN   = {{2,1,1,1,2},{1,2,1,2,1},{1,1,2,1,1},{1,2,1,2,1},{2,1,1,1,2}};
	static final int[][] MONEY_PATTERN   = {{1,2,2,2,1},{2,2,1,2,2},{2,1,1,1,2},{2,2,1,2,2},{1,2,2,2,1}};
	static final int[][] DEFENSE_PATTERN = {{1,1,2,1,1},{1,2,2,2,1},{2,2,2,2,2},{1,2,2,2,1},{1,1,2,1,1}};

	// Map State
	static int mapW, mapH;
	static int[][] tileColors;   // 0=unknown, 1=primary, 2=secondary
	static boolean mapInited = false;

	// BugNav State (navigation)
	static Direction[] bugStack = new Direction[100];
	static int bugIdx = 0;
	static MapLocation bugLastTarget = null;
	static MapLocation bugLastLoc = null;
	static boolean bugRight = false;

	// Unit State
	enum UState { REFILL, COMBAT, BUILD, EXPLORE, MOP }
	static UState uState = UState.EXPLORE;
	static MapLocation exploreTarget = null;
	static MapLocation returnLoc = null;
	static int paintLeftForRuin = 0;

    // Tower State
    static int spawnCount = 0;
	static int emergencyMopperTurn = -1000;
	static final int MOPPER_COOLDOWN = 25;
	static final int OPENING_ROUND = 400;
	static final int SOLDIER_TOWER_FIRST_ROUND = 900;
	static final int SPAWN_RESERVE_PAINT_TOWER = 120;
	static final int SPAWN_RESERVE_OTHER_TOWER = 80;
	static final int MSG_PREFIX_TOWER_LOC = 6;
	static final int TOWER_HINT_TTL = 80;

	static MapLocation hintedPaintTower = null;
	static int hintedPaintTowerRound = -1000;
	static MapLocation hintedAnyTower = null;
	static int hintedAnyTowerRound = -1000;

	// Anti-stuck trackers for support units
	static int mopperStuckTurns = 0;
	static int splasherStuckTurns = 0;
	static MapLocation splasherExploreTarget = null;

    // RNG
    static Random rng = new Random();

	//  Main Entry
	@SuppressWarnings("unused")
	public static void run(RobotController rc) throws GameActionException {
		while (true) {
			try {
				initMap(rc);
				UnitType t = rc.getType();
				if      (t.isTowerType())          Tower.runTower(rc);
				else if (t == UnitType.SOLDIER)    Soldier.runSoldier(rc);
				else if (t == UnitType.MOPPER)     Mopper.runMopper(rc);
				else if (t == UnitType.SPLASHER)   Splasher.runSplasher(rc);
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				Clock.yield();
			}
		}
	}

	static void initMap(RobotController rc) {
		if (!mapInited) {
			mapW = rc.getMapWidth();
			mapH = rc.getMapHeight();
			tileColors = new int[mapW][mapH];
			mapInited = true;
		}
	}
}
