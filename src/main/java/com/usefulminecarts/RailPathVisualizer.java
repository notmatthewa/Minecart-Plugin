package com.usefulminecarts;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.RailConfig;
import com.hypixel.hytale.protocol.RailPoint;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System that visualizes rail paths for players who have debug mode enabled.
 *
 * When enabled, spawns particles along rail paths showing:
 * - Active paths (green) - the path a cart would take
 *
 * Use /mc pathvis to toggle visualization for a player.
 */
public class RailPathVisualizer extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Players who have path visualization enabled
    private static final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();

    // Visualization range (blocks)
    private static final int VIS_RANGE = 16;

    // Update interval (ticks) - spawn particles periodically
    private static final int UPDATE_INTERVAL = 20;  // ~1.5 times per second at 30fps

    // Particle system to use for path markers
    private static final String PATH_PARTICLE_SYSTEM = "RailPath_Marker";

    // Track last particle spawn time per block position (milliseconds)
    private static final long PARTICLE_COOLDOWN_MS = 500;
    private static final Map<String, Long> lastParticleSpawn = new ConcurrentHashMap<>();

    private int tickCount = 0;

    @Nonnull
    private final Query<EntityStore> query;

    public RailPathVisualizer() {
        this.query = Query.and(Player.getComponentType(), TransformComponent.getComponentType());
        LOGGER.atInfo().log("[RailPathVisualizer] Initialized with ParticleUtil");
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        tickCount++;
        if (tickCount % UPDATE_INTERVAL != 0) return;

        Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);
        if (playerRef == null || !playerRef.isValid()) return;

        PlayerRef playerRefComp = store.getComponent(playerRef, PlayerRef.getComponentType());
        if (playerRefComp == null) return;

        UUID playerUuid = playerRefComp.getUuid();
        if (!enabledPlayers.contains(playerUuid)) return;

        // Get player position
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d pos = transform.getPosition();
        int centerX = (int) Math.floor(pos.x);
        int centerY = (int) Math.floor(pos.y);
        int centerZ = (int) Math.floor(pos.z);

        // Get world
        World world = store.getExternalData().getWorld();
        if (world == null) return;

        long now = System.currentTimeMillis();
        int particleCount = 0;

        // Scan for rails in range and spawn particles along paths
        for (int dx = -VIS_RANGE; dx <= VIS_RANGE; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -VIS_RANGE; dz <= VIS_RANGE; dz++) {
                    int bx = centerX + dx;
                    int by = centerY + dy;
                    int bz = centerZ + dz;

                    BlockType blockType = world.getBlockType(bx, by, bz);
                    if (blockType == null) continue;

                    String blockId = blockType.getId();
                    if (blockId == null || !blockId.contains("Rail")) continue;

                    // Check cooldown for this block
                    String blockKey = bx + "," + by + "," + bz;
                    Long lastSpawn = lastParticleSpawn.get(blockKey);
                    if (lastSpawn != null && now - lastSpawn < PARTICLE_COOLDOWN_MS) {
                        continue;
                    }

                    // This is a rail block
                    int rotationIndex = world.getBlockRotationIndex(bx, by, bz);

                    // Get rail config directly from block type (this is the authoritative source)
                    RailConfig railConfig = blockType.getRailConfig(rotationIndex);
                    if (railConfig == null || railConfig.points == null || railConfig.points.length < 2) {
                        // Try other rotations if current doesn't have config
                        for (int rot = 0; rot < 4; rot++) {
                            railConfig = blockType.getRailConfig(rot);
                            if (railConfig != null && railConfig.points != null && railConfig.points.length >= 2) {
                                break;
                            }
                        }
                    }

                    if (railConfig == null || railConfig.points == null || railConfig.points.length < 2) {
                        continue;
                    }

                    // Check if this is a switch (2x2 footprint) and find origin block
                    boolean isSwitch = blockId.contains("Rail_Switch") || blockId.contains("_Switch");
                    int originX = bx;
                    int originZ = bz;

                    if (isSwitch) {
                        // Find origin of 2x2 footprint (same logic as MinecartPhysicsSystem)
                        String currentBlockType = blockType.toString();

                        BlockType checkXMinus = world.getBlockType(bx - 1, by, bz);
                        if (checkXMinus != null) {
                            String xMinusType = checkXMinus.toString();
                            if (xMinusType != null && xMinusType.equals(currentBlockType)) {
                                originX = bx - 1;
                            }
                        }

                        BlockType checkZMinus = world.getBlockType(originX, by, bz - 1);
                        if (checkZMinus != null) {
                            String zMinusType = checkZMinus.toString();
                            if (zMinusType != null && zMinusType.equals(currentBlockType)) {
                                originZ = bz - 1;
                            }
                        }

                        // Only visualize from origin block to avoid duplicate particles
                        if (bx != originX || bz != originZ) {
                            continue;
                        }
                    }

                    // Rotation correction for 2x2 footprint switches
                    double switchRotCorrX = 0, switchRotCorrZ = 0;
                    if (isSwitch && rotationIndex > 0) {
                        switch (rotationIndex % 4) {
                            case 1: switchRotCorrZ = 1.0; break;
                            case 2: switchRotCorrX = 1.0; switchRotCorrZ = 1.0; break;
                            case 3: switchRotCorrX = 1.0; break;
                        }
                    }

                    // Spawn particles along actual rail points
                    RailPoint[] points = railConfig.points;
                    for (int i = 0; i < points.length; i++) {
                        // Only spawn every other point for performance
                        if (i % 2 == 0) {
                            RailPoint rp = points[i];
                            // For switches, points are in 2x2 space; for others, 0-1 space
                            double worldX = originX + rp.point.x + switchRotCorrX;
                            double worldY = by + rp.point.y;
                            double worldZ = originZ + rp.point.z + switchRotCorrZ;

                            Vector3d point = new Vector3d(worldX, worldY, worldZ);
                            try {
                                ParticleUtil.spawnParticleEffect(PATH_PARTICLE_SYSTEM, point, store);
                                particleCount++;
                            } catch (Exception e) {
                                // Particle system might not exist yet, silently ignore
                            }
                        }
                    }

                    // Mark this block as having spawned particles
                    lastParticleSpawn.put(blockKey, now);
                }
            }
        }

        // Log periodically
        if (tickCount % 180 == 0 && particleCount > 0) {
            LOGGER.atInfo().log("[PathVis] Player %s: spawned %d particles",
                playerUuid.toString().substring(0, 8), particleCount);
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Toggle path visualization for a player.
     * @return true if now enabled, false if now disabled
     */
    public static boolean toggleVisualization(UUID playerUuid) {
        if (enabledPlayers.contains(playerUuid)) {
            enabledPlayers.remove(playerUuid);
            LOGGER.atInfo().log("[RailPathVisualizer] Disabled for player %s", playerUuid);
            return false;
        } else {
            enabledPlayers.add(playerUuid);
            LOGGER.atInfo().log("[RailPathVisualizer] Enabled for player %s", playerUuid);
            return true;
        }
    }

    /**
     * Check if visualization is enabled for a player.
     */
    public static boolean isEnabled(UUID playerUuid) {
        return enabledPlayers.contains(playerUuid);
    }

    /**
     * Disable visualization for all players.
     */
    public static void disableAll() {
        enabledPlayers.clear();
        lastParticleSpawn.clear();
    }

    /**
     * Get path info for a specific block (for /mc pathinfo command).
     */
    public static String getPathInfoForBlock(World world, int blockX, int blockY, int blockZ) {
        BlockType blockType = world.getBlockType(blockX, blockY, blockZ);
        if (blockType == null) {
            return "No block at position";
        }

        String blockId = blockType.getId();
        if (blockId == null || !blockId.contains("Rail")) {
            return "Not a rail block: " + blockId;
        }

        int rotationIndex = world.getBlockRotationIndex(blockX, blockY, blockZ);
        String railType = RailPathRegistry.getRailType(blockId);
        boolean isSwitch = blockId.contains("Rail_Switch") || blockId.contains("_Switch");

        StringBuilder info = new StringBuilder();
        info.append("=== Rail Path Info ===\n");
        info.append(String.format("Block: %s\n", blockId));
        info.append(String.format("Type: %s\n", railType));
        info.append(String.format("Rotation: %d\n", rotationIndex));
        info.append(String.format("Is Switch: %b\n", isSwitch));

        // Get actual rail config from block type
        RailConfig railConfig = blockType.getRailConfig(rotationIndex);
        if (railConfig == null || railConfig.points == null || railConfig.points.length < 2) {
            for (int rot = 0; rot < 4; rot++) {
                railConfig = blockType.getRailConfig(rot);
                if (railConfig != null && railConfig.points != null && railConfig.points.length >= 2) {
                    info.append(String.format("(Found config at rotation %d)\n", rot));
                    break;
                }
            }
        }

        if (railConfig == null || railConfig.points == null || railConfig.points.length < 2) {
            info.append("\nNo rail config found!\n");
            return info.toString();
        }

        info.append(String.format("\nRail Points: %d\n", railConfig.points.length));

        RailPoint[] points = railConfig.points;
        RailPoint first = points[0];
        RailPoint last = points[points.length - 1];
        info.append(String.format("  Entry: (%.2f, %.2f, %.2f)\n", first.point.x, first.point.y, first.point.z));
        info.append(String.format("  Exit:  (%.2f, %.2f, %.2f)\n", last.point.x, last.point.y, last.point.z));

        // Convert to world coords for ASCII visualization
        Vector3d[] worldPoints = new Vector3d[points.length];
        for (int i = 0; i < points.length; i++) {
            worldPoints[i] = new Vector3d(
                blockX + points[i].point.x,
                blockY + points[i].point.y,
                blockZ + points[i].point.z
            );
        }

        info.append("\nPath (top-down):\n");
        info.append(getAsciiPath(worldPoints, blockX, blockZ));

        return info.toString();
    }

    /**
     * Generate an ASCII visualization of a path in the XZ plane.
     */
    private static String getAsciiPath(Vector3d[] points, int blockX, int blockZ) {
        if (points == null || points.length == 0) return "";

        // Create a 5x5 grid representing the block
        char[][] grid = new char[5][5];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                grid[i][j] = '.';
            }
        }

        // Plot each point on the grid
        for (int i = 0; i < points.length; i++) {
            Vector3d p = points[i];
            // Convert world coords to grid coords (0-4 range within the block)
            int gx = (int) Math.round((p.x - blockX) * 4);
            int gz = (int) Math.round((p.z - blockZ) * 4);

            // Clamp to grid bounds
            gx = Math.max(0, Math.min(4, gx));
            gz = Math.max(0, Math.min(4, gz));

            // Mark path (first=S, last=E, middle=*)
            if (i == 0) {
                grid[gz][gx] = 'S';  // Start
            } else if (i == points.length - 1) {
                grid[gz][gx] = 'E';  // End
            } else if (grid[gz][gx] == '.') {
                grid[gz][gx] = '*';  // Path point
            }
        }

        // Build output with N/S/E/W labels
        StringBuilder sb = new StringBuilder();
        sb.append("         N\n");
        sb.append("       +-----+\n");
        for (int z = 0; z < 5; z++) {
            if (z == 2) {
                sb.append("     W |");
            } else {
                sb.append("       |");
            }
            for (int x = 0; x < 5; x++) {
                sb.append(grid[z][x]);
            }
            if (z == 2) {
                sb.append("| E\n");
            } else {
                sb.append("|\n");
            }
        }
        sb.append("       +-----+\n");
        sb.append("         S\n");

        return sb.toString();
    }
}
