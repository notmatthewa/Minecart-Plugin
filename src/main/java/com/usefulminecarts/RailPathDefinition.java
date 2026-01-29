package com.usefulminecarts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Defines the paths a minecart can take through a rail block.
 *
 * Each rail type has one or more paths defined by:
 * - Entry edge (NORTH, SOUTH, EAST, WEST)
 * - Exit edge
 * - List of waypoints forming a smooth curve from entry to exit
 *
 * For switches and junctions, multiple paths from the same entry are possible,
 * with one being the "default" and others being alternates (activated by block state).
 *
 * Coordinates are in block-local space (0-1 range), where:
 * - (0, 0) is the WEST-NORTH corner
 * - (1, 1) is the EAST-SOUTH corner
 * - Y is height above block base
 */
public class RailPathDefinition {

    // Edge constants matching MinecartPhysicsSystem
    public static final int EDGE_WEST = 0;   // -X direction
    public static final int EDGE_EAST = 1;   // +X direction
    public static final int EDGE_SOUTH = 2;  // +Z direction
    public static final int EDGE_NORTH = 3;  // -Z direction

    /**
     * A single path through a rail, from entry to exit.
     */
    public static class RailPath {
        public final int entryEdge;
        public final int exitEdge;
        public final List<PathPoint> points;
        public final boolean isDefault;  // True if this is the default path (for switches)
        public final String stateCondition;  // Block state that activates this path (e.g., "left", "right")

        public RailPath(int entryEdge, int exitEdge, List<PathPoint> points, boolean isDefault, String stateCondition) {
            this.entryEdge = entryEdge;
            this.exitEdge = exitEdge;
            this.points = points;
            this.isDefault = isDefault;
            this.stateCondition = stateCondition;
        }

        /**
         * Get the path points transformed for a specific block rotation.
         * @param rotationIndex 0=none, 1=90°CW, 2=180°, 3=270°CW
         * @return Rotated path points
         */
        public List<PathPoint> getRotatedPoints(int rotationIndex) {
            List<PathPoint> rotated = new ArrayList<>(points.size());
            for (PathPoint p : points) {
                rotated.add(p.rotate(rotationIndex));
            }
            return rotated;
        }

        /**
         * Get the entry edge after rotation.
         */
        public int getRotatedEntryEdge(int rotationIndex) {
            return (entryEdge + rotationIndex) % 4;
        }

        /**
         * Get the exit edge after rotation.
         */
        public int getRotatedExitEdge(int rotationIndex) {
            return (exitEdge + rotationIndex) % 4;
        }
    }

    /**
     * A single point along a rail path.
     * Coordinates are in block-local space (0-1).
     */
    public static class PathPoint {
        public final double x;  // 0 = west edge, 1 = east edge
        public final double y;  // Height above block base
        public final double z;  // 0 = north edge, 1 = south edge

        public PathPoint(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /**
         * Rotate this point around block center (0.5, 0.5) by rotation index.
         */
        public PathPoint rotate(int rotationIndex) {
            double cx = 0.5, cz = 0.5;
            double dx = x - cx;
            double dz = z - cz;

            double rotX, rotZ;
            switch (rotationIndex % 4) {
                case 0: rotX = dx; rotZ = dz; break;
                case 1: rotX = dz; rotZ = -dx; break;  // 90° CW
                case 2: rotX = -dx; rotZ = -dz; break; // 180°
                case 3: rotX = -dz; rotZ = dx; break;  // 270° CW
                default: rotX = dx; rotZ = dz; break;
            }

            return new PathPoint(cx + rotX, y, cz + rotZ);
        }

        /**
         * Convert to world coordinates given block position.
         */
        public double[] toWorld(int blockX, int blockY, int blockZ) {
            return new double[] {
                blockX + x,
                blockY + y,
                blockZ + z
            };
        }
    }

    // Rail type identifier (e.g., "straight", "corner", "t_junction", "switch")
    private final String railType;

    // All paths for this rail type, indexed by entry edge
    // Map<entryEdge, List<RailPath>> - multiple paths possible from same entry (for switches)
    private final Map<Integer, List<RailPath>> pathsByEntry = new HashMap<>();

    // Which edges this rail connects to (for quick lookup)
    private final boolean[] connectedEdges = new boolean[4];

    public RailPathDefinition(String railType) {
        this.railType = railType;
    }

    /**
     * Add a path to this rail definition.
     */
    public RailPathDefinition addPath(RailPath path) {
        pathsByEntry.computeIfAbsent(path.entryEdge, k -> new ArrayList<>()).add(path);
        connectedEdges[path.entryEdge] = true;
        connectedEdges[path.exitEdge] = true;
        return this;
    }

    /**
     * Get the default path from an entry edge (considering rotation).
     * @param entryEdge The edge the cart is entering from
     * @param rotationIndex Block rotation (0-3)
     * @return The default path, or null if no path from that edge
     */
    public RailPath getDefaultPath(int entryEdge, int rotationIndex) {
        // Reverse-rotate the entry edge to find the base path
        int baseEntry = (entryEdge - rotationIndex + 4) % 4;
        List<RailPath> paths = pathsByEntry.get(baseEntry);
        if (paths == null || paths.isEmpty()) return null;

        for (RailPath path : paths) {
            if (path.isDefault) return path;
        }
        return paths.get(0);  // Return first if no default marked
    }

    /**
     * Get a specific path by state condition (for switches).
     * @param entryEdge The edge the cart is entering from
     * @param rotationIndex Block rotation (0-3)
     * @param stateCondition The block state (e.g., "left", "straight")
     * @return The matching path, or default if not found
     */
    public RailPath getPathByState(int entryEdge, int rotationIndex, String stateCondition) {
        int baseEntry = (entryEdge - rotationIndex + 4) % 4;
        List<RailPath> paths = pathsByEntry.get(baseEntry);
        if (paths == null || paths.isEmpty()) return null;

        for (RailPath path : paths) {
            if (stateCondition != null && stateCondition.equals(path.stateCondition)) {
                return path;
            }
        }
        return getDefaultPath(entryEdge, rotationIndex);
    }

    /**
     * Get all paths from an entry edge (for rendering all possibilities).
     */
    public List<RailPath> getAllPaths(int entryEdge, int rotationIndex) {
        int baseEntry = (entryEdge - rotationIndex + 4) % 4;
        return pathsByEntry.getOrDefault(baseEntry, List.of());
    }

    /**
     * Check if this rail connects to an edge (considering rotation).
     */
    public boolean connectsToEdge(int edge, int rotationIndex) {
        int baseEdge = (edge - rotationIndex + 4) % 4;
        return connectedEdges[baseEdge];
    }

    public String getRailType() {
        return railType;
    }

    // ==================== STATIC BUILDERS ====================

    /**
     * Create a straight rail path definition.
     * Default orientation: NORTH-SOUTH (Z-axis)
     */
    public static RailPathDefinition createStraight() {
        RailPathDefinition def = new RailPathDefinition("straight");

        // Path from NORTH to SOUTH
        List<PathPoint> northToSouth = List.of(
            new PathPoint(0.5, 0.1, 0.0),   // Entry at north edge
            new PathPoint(0.5, 0.1, 0.25),
            new PathPoint(0.5, 0.1, 0.5),   // Center
            new PathPoint(0.5, 0.1, 0.75),
            new PathPoint(0.5, 0.1, 1.0)    // Exit at south edge
        );
        def.addPath(new RailPath(EDGE_NORTH, EDGE_SOUTH, northToSouth, true, null));

        // Path from SOUTH to NORTH (reverse)
        List<PathPoint> southToNorth = List.of(
            new PathPoint(0.5, 0.1, 1.0),
            new PathPoint(0.5, 0.1, 0.75),
            new PathPoint(0.5, 0.1, 0.5),
            new PathPoint(0.5, 0.1, 0.25),
            new PathPoint(0.5, 0.1, 0.0)
        );
        def.addPath(new RailPath(EDGE_SOUTH, EDGE_NORTH, southToNorth, true, null));

        return def;
    }

    /**
     * Create a corner rail path definition.
     * Default orientation: SOUTH to EAST (turns right when coming from south)
     */
    public static RailPathDefinition createCorner() {
        RailPathDefinition def = new RailPathDefinition("corner");

        // Smooth curve from SOUTH to EAST using quadratic bezier points
        List<PathPoint> southToEast = generateSmoothCorner(
            0.5, 0.1, 1.0,   // Entry: south edge center
            0.5, 0.1, 0.5,   // Control: block center
            1.0, 0.1, 0.5,   // Exit: east edge center
            8  // Number of points
        );
        def.addPath(new RailPath(EDGE_SOUTH, EDGE_EAST, southToEast, true, null));

        // Reverse: EAST to SOUTH
        List<PathPoint> eastToSouth = new ArrayList<>(southToEast);
        java.util.Collections.reverse(eastToSouth);
        def.addPath(new RailPath(EDGE_EAST, EDGE_SOUTH, eastToSouth, true, null));

        return def;
    }

    /**
     * Create a T-junction rail path definition.
     * Default orientation: Bar runs EAST-WEST, stem goes SOUTH
     */
    public static RailPathDefinition createTJunction() {
        RailPathDefinition def = new RailPathDefinition("t_junction");

        // Straight through paths (bar)
        List<PathPoint> westToEast = List.of(
            new PathPoint(0.0, 0.1, 0.5),
            new PathPoint(0.25, 0.1, 0.5),
            new PathPoint(0.5, 0.1, 0.5),
            new PathPoint(0.75, 0.1, 0.5),
            new PathPoint(1.0, 0.1, 0.5)
        );
        def.addPath(new RailPath(EDGE_WEST, EDGE_EAST, westToEast, true, null));

        List<PathPoint> eastToWest = new ArrayList<>(westToEast);
        java.util.Collections.reverse(eastToWest);
        def.addPath(new RailPath(EDGE_EAST, EDGE_WEST, eastToWest, true, null));

        // Stem paths (curves)
        List<PathPoint> southToWest = generateSmoothCorner(
            0.5, 0.1, 1.0,
            0.5, 0.1, 0.5,
            0.0, 0.1, 0.5,
            6
        );
        def.addPath(new RailPath(EDGE_SOUTH, EDGE_WEST, southToWest, true, null));

        List<PathPoint> southToEast = generateSmoothCorner(
            0.5, 0.1, 1.0,
            0.5, 0.1, 0.5,
            1.0, 0.1, 0.5,
            6
        );
        def.addPath(new RailPath(EDGE_SOUTH, EDGE_EAST, southToEast, false, "right"));

        // Reverse stem paths
        List<PathPoint> westToSouth = new ArrayList<>(southToWest);
        java.util.Collections.reverse(westToSouth);
        def.addPath(new RailPath(EDGE_WEST, EDGE_SOUTH, westToSouth, true, null));

        List<PathPoint> eastToSouth = new ArrayList<>(southToEast);
        java.util.Collections.reverse(eastToSouth);
        def.addPath(new RailPath(EDGE_EAST, EDGE_SOUTH, eastToSouth, false, "left"));

        return def;
    }

    /**
     * Create a switch rail path definition.
     * Default orientation: Entry from SOUTH, can go NORTH (straight) or EAST (turned)
     */
    public static RailPathDefinition createSwitch() {
        RailPathDefinition def = new RailPathDefinition("switch");

        // Straight path (default): SOUTH to NORTH
        List<PathPoint> southToNorth = List.of(
            new PathPoint(0.5, 0.1, 1.0),
            new PathPoint(0.5, 0.1, 0.75),
            new PathPoint(0.5, 0.1, 0.5),
            new PathPoint(0.5, 0.1, 0.25),
            new PathPoint(0.5, 0.1, 0.0)
        );
        def.addPath(new RailPath(EDGE_SOUTH, EDGE_NORTH, southToNorth, true, "straight"));

        // Turned path: SOUTH to EAST
        List<PathPoint> southToEast = generateSmoothCorner(
            0.5, 0.1, 1.0,
            0.5, 0.1, 0.5,
            1.0, 0.1, 0.5,
            8
        );
        def.addPath(new RailPath(EDGE_SOUTH, EDGE_EAST, southToEast, false, "left"));

        // Reverse paths
        List<PathPoint> northToSouth = new ArrayList<>(southToNorth);
        java.util.Collections.reverse(northToSouth);
        def.addPath(new RailPath(EDGE_NORTH, EDGE_SOUTH, northToSouth, true, "straight"));

        List<PathPoint> eastToSouth = new ArrayList<>(southToEast);
        java.util.Collections.reverse(eastToSouth);
        def.addPath(new RailPath(EDGE_EAST, EDGE_SOUTH, eastToSouth, false, "left"));

        return def;
    }

    /**
     * Create a slope rail path definition.
     * Default orientation: Rises from SOUTH (low) to NORTH (high)
     */
    public static RailPathDefinition createSlope() {
        RailPathDefinition def = new RailPathDefinition("slope");

        // Going uphill: SOUTH to NORTH
        List<PathPoint> southToNorth = List.of(
            new PathPoint(0.5, 0.1, 1.0),   // Low end at south
            new PathPoint(0.5, 0.35, 0.75),
            new PathPoint(0.5, 0.6, 0.5),   // Middle
            new PathPoint(0.5, 0.85, 0.25),
            new PathPoint(0.5, 1.1, 0.0)    // High end at north
        );
        def.addPath(new RailPath(EDGE_SOUTH, EDGE_NORTH, southToNorth, true, null));

        // Going downhill: NORTH to SOUTH
        List<PathPoint> northToSouth = new ArrayList<>(southToNorth);
        java.util.Collections.reverse(northToSouth);
        def.addPath(new RailPath(EDGE_NORTH, EDGE_SOUTH, northToSouth, true, null));

        return def;
    }

    /**
     * Create an accelerator rail path definition.
     * Same as straight but marked differently for boost logic.
     */
    public static RailPathDefinition createAccelerator() {
        RailPathDefinition def = new RailPathDefinition("accelerator");

        List<PathPoint> northToSouth = List.of(
            new PathPoint(0.5, 0.1, 0.0),
            new PathPoint(0.5, 0.1, 0.25),
            new PathPoint(0.5, 0.1, 0.5),
            new PathPoint(0.5, 0.1, 0.75),
            new PathPoint(0.5, 0.1, 1.0)
        );
        def.addPath(new RailPath(EDGE_NORTH, EDGE_SOUTH, northToSouth, true, null));

        List<PathPoint> southToNorth = new ArrayList<>(northToSouth);
        java.util.Collections.reverse(southToNorth);
        def.addPath(new RailPath(EDGE_SOUTH, EDGE_NORTH, southToNorth, true, null));

        return def;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Generate smooth corner points using quadratic Bezier curve.
     */
    private static List<PathPoint> generateSmoothCorner(
            double startX, double startY, double startZ,
            double controlX, double controlY, double controlZ,
            double endX, double endY, double endZ,
            int numPoints) {

        List<PathPoint> points = new ArrayList<>(numPoints);
        for (int i = 0; i < numPoints; i++) {
            double t = (double) i / (numPoints - 1);
            double oneMinusT = 1.0 - t;

            // Quadratic Bezier: B(t) = (1-t)²P0 + 2(1-t)tP1 + t²P2
            double x = oneMinusT * oneMinusT * startX + 2 * oneMinusT * t * controlX + t * t * endX;
            double y = oneMinusT * oneMinusT * startY + 2 * oneMinusT * t * controlY + t * t * endY;
            double z = oneMinusT * oneMinusT * startZ + 2 * oneMinusT * t * controlZ + t * t * endZ;

            points.add(new PathPoint(x, y, z));
        }
        return points;
    }

    /**
     * Get edge name for debugging.
     */
    public static String getEdgeName(int edge) {
        switch (edge) {
            case EDGE_WEST: return "WEST";
            case EDGE_EAST: return "EAST";
            case EDGE_SOUTH: return "SOUTH";
            case EDGE_NORTH: return "NORTH";
            default: return "UNKNOWN";
        }
    }
}
