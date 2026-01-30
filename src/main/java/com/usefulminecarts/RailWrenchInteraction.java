package com.usefulminecarts;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rail Wrench interaction for rotating rails and changing their connection type.
 *
 * Primary (Left Click): Rotate the rail 90 degrees clockwise
 * Secondary (Right Click): Cycle through connection types (flat -> slope -> corner)
 */
public class RailWrenchInteraction extends SimpleBlockInteraction {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<RailWrenchInteraction> CODEC =
        BuilderCodec.builder(RailWrenchInteraction.class, RailWrenchInteraction::new, SimpleBlockInteraction.CODEC).build();

    // Block update flag (same as TNT plugin uses)
    private static final int BLOCK_UPDATE = 256;

    public RailWrenchInteraction() {
        super();
    }

    @Override
    protected void interactWithBlock(
            @Nonnull World world,
            @Nonnull CommandBuffer buffer,
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext context,
            @Nonnull ItemStack heldItem,
            @Nonnull Vector3i targetPos,
            @Nonnull CooldownHandler cooldown
    ) {
        BlockType blockType = world.getBlockType(targetPos.x, targetPos.y, targetPos.z);
        if (blockType == null) {
            return;
        }

        String blockId = blockType.getId();

        // Only work on rails
        if (!blockId.contains("Rail")) {
            LOGGER.atInfo().log("[RailWrench] Block '%s' is not a rail", blockId);
            return;
        }

        // Get chunk for the block position
        int chunkX = targetPos.x >> 4;
        int chunkZ = targetPos.z >> 4;
        long chunkKey = ((long)chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        BlockAccessor chunk = world.getChunkIfLoaded(chunkKey);
        if (chunk == null) {
            LOGGER.atWarning().log("[RailWrench] Chunk not loaded at (%d, %d, %d)", targetPos.x, targetPos.y, targetPos.z);
            return;
        }

        if (interactionType == InteractionType.Primary) {
            // Left click - rotate the rail
            rotateRail(world, chunk, targetPos, blockType, blockId);
        } else if (interactionType == InteractionType.Secondary) {
            // Right click - cycle connection type
            cycleRailType(world, chunk, targetPos, blockType, blockId);
        }
    }

    /**
     * Rotate the rail 90 degrees clockwise.
     * Uses BlockSection directly to access and modify rotation data.
     */
    private void rotateRail(World world, BlockAccessor chunk, Vector3i pos, BlockType blockType, String blockId) {
        try {
            LOGGER.atInfo().log("[RailWrench] Rotating rail at (%d, %d, %d), blockId=%s",
                pos.x, pos.y, pos.z, blockId);

            if (!(chunk instanceof WorldChunk worldChunk)) {
                LOGGER.atWarning().log("[RailWrench] Chunk is not WorldChunk, rotation not supported");
                return;
            }


            world.execute(() -> {
                try {
                    int blockTypeId = world.getBlock(pos.x, pos.y, pos.z);
                    int rotation = world.getBlockRotationIndex(pos.x, pos.y, pos.z);

                    int newRotation = ((rotation % 4) + 1) % 4;

                    boolean success = worldChunk.setBlock(
                        pos.x,
                        pos.y,
                        pos.z,
                        blockTypeId,
                        blockType,
                        newRotation,
                        0,
                        157
                    );

                    int newRot = world.getBlockRotationIndex(pos.x, pos.y, pos.z);
                    LOGGER.atInfo().log("Rotated block from %d to %d, actual=%d, success=%b", rotation, newRotation, newRot, success);

                } catch (Exception e) {
                    LOGGER.atSevere().log("oopsies", e.getMessage());
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            LOGGER.atSevere().log("[RailWrench] Error rotating rail: %s", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Extract the full block ID from BlockType.toString()
     * Format: "BlockType{id=*Rail_State_Definitions_Slope, ...}"
     */
    private String extractFullBlockId(BlockType blockType) {
        if (blockType == null) return null;
        String blockTypeName = blockType.toString();
        if (blockTypeName == null) return null;

        int idStart = blockTypeName.indexOf("id=");
        if (idStart < 0) return null;

        idStart += 3; // Skip "id="
        int idEnd = blockTypeName.indexOf(",", idStart);
        if (idEnd < 0) idEnd = blockTypeName.indexOf("}", idStart);
        if (idEnd <= idStart) return null;

        return blockTypeName.substring(idStart, idEnd).trim();
    }

    /**
     * Cycle through rail connection types (flat -> slope -> corner).
     */
    private void cycleRailType(World world, BlockAccessor chunk, Vector3i pos, BlockType blockType, String blockId) {
        try {
            // Get the full block ID to detect current state
            String fullBlockId = extractFullBlockId(blockType);
            LOGGER.atInfo().log("[RailWrench] Current block: base='%s', full='%s'", blockId, fullBlockId);

            // Determine current state from full block ID
            String currentState = detectCurrentState(fullBlockId);
            LOGGER.atInfo().log("[RailWrench] Detected current state: %s", currentState);

            // Get the base block ID without state suffix
            String baseBlockId = getBaseBlockId(blockId);
            LOGGER.atInfo().log("[RailWrench] Base block ID: %s", baseBlockId);

            // Determine state cycle based on rail type
            String[] stateCycle;

            if (baseBlockId.contains("Accel")) {
                // Accelerator rails: flat <-> slope
                stateCycle = new String[] { null, "Slope" }; // null = default/flat
            } else if (baseBlockId.contains("Switch")) {
                // Switch rails only support rotation
                LOGGER.atInfo().log("[RailWrench] Switch rails only support rotation (left click)");
                return;
            } else {
                // Regular rails - cycle through states
                stateCycle = new String[] { null, "Slope", "Corner_Right", "Corner_Left", "T", "Cross" };
            }

            // Find current position in cycle and get next state
            int currentIndex = -1;
            for (int i = 0; i < stateCycle.length; i++) {
                if ((stateCycle[i] == null && currentState == null) ||
                    (stateCycle[i] != null && stateCycle[i].equalsIgnoreCase(currentState))) {
                    currentIndex = i;
                    break;
                }
            }

            // Move to next state in cycle
            int nextIndex = (currentIndex + 1) % stateCycle.length;
            String nextState = stateCycle[nextIndex];

            LOGGER.atInfo().log("[RailWrench] Cycling from state %d (%s) to state %d (%s)",
                currentIndex, currentState, nextIndex, nextState);

            // Construct the new block ID
            // State variants use format: *{BaseBlockId}_State_Definitions_{State}
            // Base/flat uses just: {BaseBlockId}
            String newBlockId;
            if (nextState != null) {
                // Try using getBlockKeyForState first (keeps asterisk prefix for state definitions)
                String stateKey = blockType.getBlockKeyForState(nextState);
                if (stateKey != null) {
                    newBlockId = stateKey; // Keep asterisk if present
                    LOGGER.atInfo().log("[RailWrench] Using state key from BlockType: %s", newBlockId);
                } else {
                    // Fallback: construct the ID manually
                    newBlockId = "*" + baseBlockId + "_State_Definitions_" + nextState;
                    LOGGER.atInfo().log("[RailWrench] Constructed state key: %s", newBlockId);
                }
            } else {
                // Reset to default/flat state - use base block ID
                newBlockId = baseBlockId;
            }

            // Set the new block using World.setBlock() (same API as TNT plugin)
            LOGGER.atInfo().log("[RailWrench] Setting block at (%d, %d, %d) to '%s'",
                pos.x, pos.y, pos.z, newBlockId);

            world.setBlock(pos.x, pos.y, pos.z, newBlockId, BLOCK_UPDATE);

            LOGGER.atInfo().log("[RailWrench] Block set successfully!");

        } catch (Exception e) {
            LOGGER.atSevere().log("[RailWrench] Error cycling rail type: %s", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Get the base block ID without any state suffix.
     * Handles both simple IDs and state definition IDs:
     * - "Rail" -> "Rail"
     * - "*Rail_State_Definitions_Slope" -> "Rail"
     * - "*UsefulMinecarts_Rail_Accel_State_Definitions_Slope" -> "UsefulMinecarts_Rail_Accel"
     */
    private String getBaseBlockId(String blockId) {
        if (blockId == null) return null;

        String id = blockId;

        // Remove * prefix if present
        if (id.startsWith("*")) {
            id = id.substring(1);
        }

        // Check for _State_Definitions_ pattern (state variant)
        int stateDefIndex = id.indexOf("_State_Definitions_");
        if (stateDefIndex > 0) {
            // Return everything before _State_Definitions_
            return id.substring(0, stateDefIndex);
        }

        // No state definition pattern - remove known state suffixes as fallback
        String[] stateSuffixes = {"_Slope", "_Corner_Right", "_Corner_Left", "_T", "_Cross"};
        for (String suffix : stateSuffixes) {
            if (id.endsWith(suffix)) {
                return id.substring(0, id.length() - suffix.length());
            }
        }
        return id;
    }

    /**
     * Detect the current state from the full block ID.
     * Examples: "*Rail_State_Definitions_Slope" -> "Slope", "*Rail" -> null
     */
    private String detectCurrentState(String fullBlockId) {
        if (fullBlockId == null) return null;

        // Check for known states in the ID (order matters - check longer matches first)
        if (fullBlockId.contains("_Corner_Right")) return "Corner_Right";
        if (fullBlockId.contains("_Corner_Left")) return "Corner_Left";
        if (fullBlockId.contains("_Cross")) return "Cross";
        if (fullBlockId.contains("_Slope")) return "Slope";
        if (fullBlockId.contains("_T")) return "T";

        // No state suffix = default/flat
        return null;
    }

    @Override
    protected void simulateInteractWithBlock(
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext context,
            @Nullable ItemStack itemStack,
            @Nonnull World world,
            @Nonnull Vector3i pos
    ) {
        // No simulation needed
    }
}
