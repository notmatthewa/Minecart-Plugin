package com.usefulminecarts;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.RailConfig;
import com.hypixel.hytale.protocol.RailPoint;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debug system that logs rail information when a player runs /mc railinfo.
 * Logs details about the rail block the player is looking at.
 */
public class RailDebugSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Edge constants (same as MinecartPhysicsSystem)
    // Standard convention: NORTH = -Z, SOUTH = +Z, WEST = -X, EAST = +X
    private static final int EDGE_WEST = 0;   // -X direction
    private static final int EDGE_EAST = 1;   // +X direction
    private static final int EDGE_SOUTH = 2;  // +Z direction (towards positive Z)
    private static final int EDGE_NORTH = 3;  // -Z direction (towards negative Z)

    // Players who requested rail debug info
    private static final Set<UUID> pendingDebugRequests = ConcurrentHashMap.newKeySet();

    @Nonnull
    private final Query<EntityStore> query;

    public RailDebugSystem() {
        this.query = Query.and(Player.getComponentType());
    }

    /**
     * Request rail debug info for a player. Called from command handler.
     */
    public static void requestDebug(UUID playerUuid) {
        pendingDebugRequests.add(playerUuid);
        LOGGER.atInfo().log("[RailDebug] Debug requested for player %s", playerUuid);
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        final Holder<EntityStore> holder = EntityUtils.toHolder(index, archetypeChunk);
        final Player player = holder.getComponent(Player.getComponentType());
        final PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());

        if (player == null || playerRef == null) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();

        // Check if this player requested debug info
        if (!pendingDebugRequests.remove(playerUuid)) {
            return;
        }

        // Get target block
        Vector3i targetPos = TargetUtil.getTargetBlock(archetypeChunk.getReferenceTo(index), 10, commandBuffer);
        if (targetPos == null) {
            LOGGER.atInfo().log("[RailDebug] Player %s: No target block found (look at a block)", playerUuid);
            return;
        }

        World world = store.getExternalData().getWorld();
        if (world == null) {
            LOGGER.atInfo().log("[RailDebug] Player %s: World not available", playerUuid);
            return;
        }

        int blockX = targetPos.x;
        int blockY = targetPos.y;
        int blockZ = targetPos.z;

        // Get block info
        BlockType blockType = world.getBlockType(blockX, blockY, blockZ);
        if (blockType == null) {
            LOGGER.atInfo().log("[RailDebug] Player %s: No block at (%d, %d, %d)", playerUuid, blockX, blockY, blockZ);
            return;
        }

        String blockTypeName = blockType.toString();
        int rotationIndex = world.getBlockRotationIndex(blockX, blockY, blockZ);

        LOGGER.atInfo().log("========== RAIL DEBUG INFO ==========");
        LOGGER.atInfo().log("[RailDebug] Block Position: (%d, %d, %d)", blockX, blockY, blockZ);
//        LOGGER.atInfo().log("[RailDebug] Block Type: %s", blockTypeName);
        LOGGER.atInfo().log("[RailDebug] Rotation Index: %d", rotationIndex);

        // Extract block ID for type detection
        String blockId = extractBlockId(blockTypeName);
        boolean isTJunction = blockId != null && blockId.endsWith("_T");
        LOGGER.atInfo().log("[RailDebug] Block ID: %s", blockId);
        LOGGER.atInfo().log("[RailDebug] Is T-Junction (by name): %b", isTJunction);

        // Get rail config
        RailConfig railConfig = blockType.getRailConfig(rotationIndex);
        if (railConfig == null || railConfig.points == null || railConfig.points.length < 2) {
            // Try other rotations
            for (int rot = 0; rot < 4; rot++) {
                railConfig = blockType.getRailConfig(rot);
                if (railConfig != null && railConfig.points != null && railConfig.points.length >= 2) {
                    LOGGER.atInfo().log("[RailDebug] Found rail config at rotation %d (not %d)", rot, rotationIndex);
                    break;
                }
            }
        }

        if (railConfig == null || railConfig.points == null || railConfig.points.length < 2) {
            LOGGER.atInfo().log("[RailDebug] NOT A RAIL BLOCK (no rail config found)");
            LOGGER.atInfo().log("==========================================");
            return;
        }

        RailPoint[] points = railConfig.points;
        LOGGER.atInfo().log("[RailDebug] Rail Points Count: %d", points.length);

        // Log each rail point
        for (int i = 0; i < points.length; i++) {
            RailPoint pt = points[i];
            LOGGER.atInfo().log("[RailDebug]   Point %d: (%.3f, %.3f, %.3f)",
                i, pt.point.x, pt.point.y, pt.point.z);
        }

        // Detect rail type from points
        float yDiff = Math.abs(points[points.length - 1].point.y - points[0].point.y);
        boolean isSlope = yDiff > 0.1f;
        LOGGER.atInfo().log("[RailDebug] Is Slope: %b (Y diff: %.3f)", isSlope, yDiff);

        // Check if corner (endpoints not aligned on either axis)
        boolean looksLikeCorner = Math.abs(points[0].point.x - points[points.length-1].point.x) > 0.1
                               && Math.abs(points[0].point.z - points[points.length-1].point.z) > 0.1;
        LOGGER.atInfo().log("[RailDebug] Looks Like Corner: %b", looksLikeCorner);

        // Check neighboring rails
        // Standard: NORTH = -Z, SOUTH = +Z
        boolean hasWest = hasRailAt(world, blockX - 1, blockY, blockZ)
                       || hasRailAt(world, blockX - 1, blockY - 1, blockZ)
                       || hasRailAt(world, blockX - 1, blockY + 1, blockZ);
        boolean hasEast = hasRailAt(world, blockX + 1, blockY, blockZ)
                       || hasRailAt(world, blockX + 1, blockY - 1, blockZ)
                       || hasRailAt(world, blockX + 1, blockY + 1, blockZ);
        boolean hasSouth = hasRailAt(world, blockX, blockY, blockZ + 1)
                        || hasRailAt(world, blockX, blockY - 1, blockZ + 1)
                        || hasRailAt(world, blockX, blockY + 1, blockZ + 1);
        boolean hasNorth = hasRailAt(world, blockX, blockY, blockZ - 1)
                        || hasRailAt(world, blockX, blockY - 1, blockZ - 1)
                        || hasRailAt(world, blockX, blockY + 1, blockZ - 1);

        LOGGER.atInfo().log("[RailDebug] Connected Edges:");
        LOGGER.atInfo().log("[RailDebug]   WEST (-X): %b", hasWest);
        LOGGER.atInfo().log("[RailDebug]   EAST (+X): %b", hasEast);
        LOGGER.atInfo().log("[RailDebug]   SOUTH (+Z): %b", hasSouth);
        LOGGER.atInfo().log("[RailDebug]   NORTH (-Z): %b", hasNorth);

        int edgeCount = (hasWest ? 1 : 0) + (hasEast ? 1 : 0) + (hasSouth ? 1 : 0) + (hasNorth ? 1 : 0);
        LOGGER.atInfo().log("[RailDebug] Edge Count: %d", edgeCount);

        // Determine junction type using BLOCK ID and GEOMETRY (not neighbor count!)
        // This matches what MinecartPhysicsSystem uses for actual rail navigation
        boolean isCornerByName = blockId != null && blockId.contains("_Corner");
        String junctionType = "STRAIGHT";
        if (isTJunction) {
            junctionType = "T-JUNCTION";
        } else if (isCornerByName || looksLikeCorner) {
            junctionType = "CORNER";
        } else if (isSlope) {
            junctionType = "SLOPE";
        }
        LOGGER.atInfo().log("[RailDebug] Junction Type (by block ID/geometry): %s", junctionType);
        LOGGER.atInfo().log("[RailDebug] Is Corner By Name: %b", isCornerByName);

        // Also show what physics system would compute for connected edges
        LOGGER.atInfo().log("[RailDebug] Physics System Connected Edges (based on block type):");
        if (isTJunction) {
            // T-junction edges based on rotation
            switch (rotationIndex) {
                case 0:
                    LOGGER.atInfo().log("[RailDebug]   T-Junction rot=0: SOUTH(+Z), EAST(+X), WEST(-X) - stem SOUTH");
                    break;
                case 1:
                    LOGGER.atInfo().log("[RailDebug]   T-Junction rot=1: EAST(+X), NORTH(-Z), SOUTH(+Z) - stem EAST");
                    break;
                case 2:
                    LOGGER.atInfo().log("[RailDebug]   T-Junction rot=2: NORTH(-Z), EAST(+X), WEST(-X) - stem NORTH");
                    break;
                case 3:
                    LOGGER.atInfo().log("[RailDebug]   T-Junction rot=3: WEST(-X), NORTH(-Z), SOUTH(+Z) - stem WEST");
                    break;
            }
        } else if (isCornerByName || looksLikeCorner) {
            // Corner edges based on first and last rail points
            RailPoint firstPt = points[0];
            RailPoint lastPt = points[points.length - 1];
            LOGGER.atInfo().log("[RailDebug]   Corner endpoints: (%.2f,%.2f) to (%.2f,%.2f)",
                firstPt.point.x, firstPt.point.z, lastPt.point.x, lastPt.point.z);
        } else if (!isSlope) {
            // Straight rail edges
            RailPoint firstPt = points[0];
            RailPoint lastPt = points[points.length - 1];
            LOGGER.atInfo().log("[RailDebug]   Straight endpoints: (%.2f,%.2f) to (%.2f,%.2f)",
                firstPt.point.x, firstPt.point.z, lastPt.point.x, lastPt.point.z);
        }

        // Log neighbor block info
        // Standard: NORTH = -Z, SOUTH = +Z
        LOGGER.atInfo().log("[RailDebug] Neighbor Blocks:");
        logNeighborRail(world, blockX - 1, blockY, blockZ, "WEST (-X)");
        logNeighborRail(world, blockX + 1, blockY, blockZ, "EAST (+X)");
        logNeighborRail(world, blockX, blockY, blockZ + 1, "SOUTH (+Z)");
        logNeighborRail(world, blockX, blockY, blockZ - 1, "NORTH (-Z)");

        LOGGER.atInfo().log("==========================================");
    }

    private String extractBlockId(String blockTypeName) {
        if (blockTypeName == null) return null;
        int idStart = blockTypeName.indexOf("id=");
        if (idStart >= 0) {
            idStart += 3;
            int idEnd = blockTypeName.indexOf(",", idStart);
            if (idEnd < 0) idEnd = blockTypeName.indexOf("}", idStart);
            if (idEnd > idStart) {
                return blockTypeName.substring(idStart, idEnd).trim();
            }
        }
        return null;
    }

    private boolean hasRailAt(World world, int x, int y, int z) {
        BlockType blockType = world.getBlockType(x, y, z);
        if (blockType == null) return false;
        RailConfig rail = blockType.getRailConfig(0);
        return rail != null && rail.points != null && rail.points.length >= 2;
    }

    private void logNeighborRail(World world, int x, int y, int z, String direction) {
        BlockType blockType = world.getBlockType(x, y, z);
        if (blockType == null) {
            LOGGER.atInfo().log("[RailDebug]   %s: (no block)", direction);
            return;
        }

        String blockId = extractBlockId(blockType.toString());
        int rotIdx = world.getBlockRotationIndex(x, y, z);
        RailConfig rail = blockType.getRailConfig(rotIdx);
        boolean isRail = rail != null && rail.points != null && rail.points.length >= 2;

        if (isRail) {
            float yDiff = Math.abs(rail.points[rail.points.length - 1].point.y - rail.points[0].point.y);
            boolean isSlope = yDiff > 0.1f;
            LOGGER.atInfo().log("[RailDebug]   %s: RAIL - %s, rot=%d, slope=%b", direction, blockId, rotIdx, isSlope);
        } else {
            LOGGER.atInfo().log("[RailDebug]   %s: %s (not a rail)", direction, blockId);
        }
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
