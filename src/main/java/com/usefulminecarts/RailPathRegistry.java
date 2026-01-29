package com.usefulminecarts;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for rail path definitions.
 *
 * Maps block IDs to their path definitions, allowing the physics system
 * and rendering system to query paths for any rail type.
 *
 * Usage:
 *   RailPathDefinition def = RailPathRegistry.getDefinition(blockId);
 *   RailPath path = def.getDefaultPath(entryEdge, rotationIndex);
 *   List<PathPoint> points = path.getRotatedPoints(rotationIndex);
 */
public class RailPathRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Map of block ID patterns to path definitions
    // Uses pattern matching (contains) for flexibility
    private static final Map<String, RailPathDefinition> definitions = new HashMap<>();

    // Pre-built standard definitions
    private static final RailPathDefinition STRAIGHT = RailPathDefinition.createStraight();
    private static final RailPathDefinition CORNER = RailPathDefinition.createCorner();
    private static final RailPathDefinition T_JUNCTION = RailPathDefinition.createTJunction();
    private static final RailPathDefinition SWITCH = RailPathDefinition.createSwitch();
    private static final RailPathDefinition SLOPE = RailPathDefinition.createSlope();
    private static final RailPathDefinition ACCELERATOR = RailPathDefinition.createAccelerator();

    // Initialize with standard mappings
    static {
        // Register patterns for block ID matching
        // Order matters - more specific patterns should be checked first
        registerPattern("_Corner", CORNER);
        registerPattern("_T", T_JUNCTION);
        registerPattern("_Switch", SWITCH);
        registerPattern("Slope", SLOPE);
        registerPattern("Accel", ACCELERATOR);
        // Default straight rail (no special suffix)
        registerPattern("Rail", STRAIGHT);

        LOGGER.atInfo().log("[RailPathRegistry] Initialized with %d rail type definitions", definitions.size());
    }

    /**
     * Register a pattern-to-definition mapping.
     */
    public static void registerPattern(String pattern, RailPathDefinition definition) {
        definitions.put(pattern, definition);
    }

    /**
     * Get the path definition for a block ID.
     * @param blockId The block type ID (e.g., "UsefulMinecarts_Rail_Corner")
     * @return The matching definition, or STRAIGHT as fallback
     */
    public static RailPathDefinition getDefinition(String blockId) {
        if (blockId == null) return STRAIGHT;

        // Check patterns in order of specificity
        if (blockId.contains("_Corner")) return CORNER;
        if (blockId.contains("_T")) return T_JUNCTION;
        if (blockId.contains("_Switch") || blockId.contains("Switch")) return SWITCH;
        if (blockId.contains("Slope")) return SLOPE;
        if (blockId.contains("Accel")) return ACCELERATOR;

        // Default to straight rail
        return STRAIGHT;
    }

    /**
     * Determine the rail type string from block ID.
     */
    public static String getRailType(String blockId) {
        if (blockId == null) return "straight";
        if (blockId.contains("_Corner")) return "corner";
        if (blockId.contains("_T")) return "t_junction";
        if (blockId.contains("_Switch") || blockId.contains("Switch")) return "switch";
        if (blockId.contains("Slope")) return "slope";
        if (blockId.contains("Accel")) return "accelerator";
        return "straight";
    }

    /**
     * Get the current state of a switch/junction block.
     * @param world The world
     * @param blockX Block X coordinate
     * @param blockY Block Y coordinate
     * @param blockZ Block Z coordinate
     * @param blockId The block ID
     * @return State string ("straight", "left", etc.) or null for non-switches
     */
    public static String getBlockState(World world, int blockX, int blockY, int blockZ, String blockId) {
        if (blockId == null) return null;

        // Check if it's a switch by block ID
        if (blockId.contains("_Switch") || blockId.contains("Switch")) {
            // Determine state from block ID suffix
            if (blockId.contains("_Left") || blockId.contains("State_Definitions_Left")) {
                return "left";
            }
            return "straight";  // Default state
        }

        return null;  // Not a stateful block
    }

    /**
     * Get world-space path points for a rail at a specific position.
     *
     * @param world The world
     * @param blockX Block X coordinate
     * @param blockY Block Y coordinate
     * @param blockZ Block Z coordinate
     * @param blockId The block type ID
     * @param rotationIndex Block rotation (0-3)
     * @param entryEdge Edge the cart enters from (or -1 for all paths)
     * @return List of world-space points forming the path(s)
     */
    public static List<Vector3d[]> getWorldPaths(World world, int blockX, int blockY, int blockZ,
                                                  String blockId, int rotationIndex, int entryEdge) {
        RailPathDefinition def = getDefinition(blockId);
        String state = getBlockState(world, blockX, blockY, blockZ, blockId);

        List<Vector3d[]> worldPaths = new ArrayList<>();

        if (entryEdge >= 0) {
            // Get specific path from entry edge
            RailPathDefinition.RailPath path = (state != null)
                ? def.getPathByState(entryEdge, rotationIndex, state)
                : def.getDefaultPath(entryEdge, rotationIndex);

            if (path != null) {
                worldPaths.add(pathToWorld(path, rotationIndex, blockX, blockY, blockZ));
            }
        } else {
            // Get all paths (for rendering)
            for (int edge = 0; edge < 4; edge++) {
                List<RailPathDefinition.RailPath> paths = def.getAllPaths(edge, rotationIndex);
                for (RailPathDefinition.RailPath path : paths) {
                    // For switches, only show the active path
                    if (state != null && path.stateCondition != null && !path.stateCondition.equals(state)) {
                        continue;
                    }
                    worldPaths.add(pathToWorld(path, rotationIndex, blockX, blockY, blockZ));
                }
            }
        }

        return worldPaths;
    }

    /**
     * Convert a path to world coordinates.
     */
    private static Vector3d[] pathToWorld(RailPathDefinition.RailPath path, int rotationIndex,
                                          int blockX, int blockY, int blockZ) {
        List<RailPathDefinition.PathPoint> points = path.getRotatedPoints(rotationIndex);
        Vector3d[] worldPoints = new Vector3d[points.size()];

        for (int i = 0; i < points.size(); i++) {
            RailPathDefinition.PathPoint p = points.get(i);
            worldPoints[i] = new Vector3d(
                blockX + p.x,
                blockY + p.y,
                blockZ + p.z
            );
        }

        return worldPoints;
    }

    /**
     * Find the closest point on any path to a given position.
     * Returns [snapX, snapY, snapZ, dirX, dirY, dirZ, distanceSq]
     */
    public static double[] snapToPath(double entityX, double entityY, double entityZ,
                                       String blockId, int rotationIndex,
                                       int blockX, int blockY, int blockZ,
                                       double incomingDirX, double incomingDirZ,
                                       String blockState) {
        RailPathDefinition def = getDefinition(blockId);

        // Determine entry edge from incoming direction
        int entryEdge = getEntryEdgeFromDirection(incomingDirX, incomingDirZ);

        // Get the appropriate path
        RailPathDefinition.RailPath path = (blockState != null)
            ? def.getPathByState(entryEdge, rotationIndex, blockState)
            : def.getDefaultPath(entryEdge, rotationIndex);

        if (path == null) {
            // Try opposite direction if no path found
            entryEdge = (entryEdge + 2) % 4;
            path = (blockState != null)
                ? def.getPathByState(entryEdge, rotationIndex, blockState)
                : def.getDefaultPath(entryEdge, rotationIndex);
        }

        if (path == null) return null;

        List<RailPathDefinition.PathPoint> points = path.getRotatedPoints(rotationIndex);

        // Find closest segment
        double bestDistSq = Double.MAX_VALUE;
        double snapX = entityX, snapY = entityY, snapZ = entityZ;
        double dirX = 0, dirY = 0, dirZ = 1;

        for (int i = 0; i < points.size() - 1; i++) {
            RailPathDefinition.PathPoint p1 = points.get(i);
            RailPathDefinition.PathPoint p2 = points.get(i + 1);

            double px1 = blockX + p1.x;
            double py1 = blockY + p1.y;
            double pz1 = blockZ + p1.z;
            double px2 = blockX + p2.x;
            double py2 = blockY + p2.y;
            double pz2 = blockZ + p2.z;

            double sX = px2 - px1;
            double sY = py2 - py1;
            double sZ = pz2 - pz1;
            double sLenSq = sX * sX + sY * sY + sZ * sZ;

            if (sLenSq < 0.0001) continue;

            double t = ((entityX - px1) * sX + (entityY - py1) * sY + (entityZ - pz1) * sZ) / sLenSq;
            t = Math.max(0, Math.min(1, t));

            double cX = px1 + t * sX;
            double cY = py1 + t * sY;
            double cZ = pz1 + t * sZ;

            double dx = cX - entityX;
            double dy = cY - entityY;
            double dz = cZ - entityZ;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                snapX = cX;
                snapY = cY;
                snapZ = cZ;

                double segLen = Math.sqrt(sLenSq);
                dirX = sX / segLen;
                dirY = sY / segLen;
                dirZ = sZ / segLen;
            }
        }

        return new double[] { snapX, snapY, snapZ, dirX, dirY, dirZ, bestDistSq };
    }

    /**
     * Determine entry edge from movement direction.
     */
    private static int getEntryEdgeFromDirection(double dirX, double dirZ) {
        // Entry edge is OPPOSITE to movement direction
        // (if moving +X, we entered from WEST)
        if (Math.abs(dirX) > Math.abs(dirZ)) {
            return dirX > 0 ? RailPathDefinition.EDGE_WEST : RailPathDefinition.EDGE_EAST;
        } else {
            return dirZ > 0 ? RailPathDefinition.EDGE_NORTH : RailPathDefinition.EDGE_SOUTH;
        }
    }

    // ==================== DEBUG / VISUALIZATION ====================

    /**
     * Get all path points for a rail block (for debug rendering).
     * Returns paths with their active/inactive state for color coding.
     */
    public static List<DebugPath> getDebugPaths(World world, int blockX, int blockY, int blockZ,
                                                 String blockId, int rotationIndex) {
        RailPathDefinition def = getDefinition(blockId);
        String state = getBlockState(world, blockX, blockY, blockZ, blockId);

        List<DebugPath> debugPaths = new ArrayList<>();

        // Collect unique paths (avoid duplicates from reverse directions)
        Map<String, RailPathDefinition.RailPath> uniquePaths = new HashMap<>();

        for (int edge = 0; edge < 4; edge++) {
            List<RailPathDefinition.RailPath> paths = def.getAllPaths(edge, rotationIndex);
            for (RailPathDefinition.RailPath path : paths) {
                // Use entry-exit as key to avoid duplicates
                String key = Math.min(path.entryEdge, path.exitEdge) + "-" + Math.max(path.entryEdge, path.exitEdge)
                           + (path.stateCondition != null ? "-" + path.stateCondition : "");
                uniquePaths.putIfAbsent(key, path);
            }
        }

        for (RailPathDefinition.RailPath path : uniquePaths.values()) {
            boolean isActive = (path.stateCondition == null)
                            || (state != null && state.equals(path.stateCondition))
                            || (state == null && path.isDefault);

            List<RailPathDefinition.PathPoint> points = path.getRotatedPoints(rotationIndex);
            Vector3d[] worldPoints = new Vector3d[points.size()];
            for (int i = 0; i < points.size(); i++) {
                RailPathDefinition.PathPoint p = points.get(i);
                worldPoints[i] = new Vector3d(blockX + p.x, blockY + p.y, blockZ + p.z);
            }

            debugPaths.add(new DebugPath(worldPoints, isActive, path.stateCondition));
        }

        return debugPaths;
    }

    /**
     * Debug path info for rendering.
     */
    public static class DebugPath {
        public final Vector3d[] points;
        public final boolean isActive;
        public final String stateCondition;

        public DebugPath(Vector3d[] points, boolean isActive, String stateCondition) {
            this.points = points;
            this.isActive = isActive;
            this.stateCondition = stateCondition;
        }
    }
}
