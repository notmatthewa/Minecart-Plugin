package com.usefulminecarts;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import javax.annotation.Nonnull;

/**
 * Interaction handler for toggling the track switch between straight and left states.
 * Right-click the switch block to toggle.
 */
public class SwitchToggleInteraction extends SimpleInstantInteraction {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Block IDs for the switch states
    private static final String SWITCH_STRAIGHT_ID = "UsefulMinecarts_Rail_Switch";
    private static final String SWITCH_LEFT_ID = "*UsefulMinecarts_Rail_Switch_State_Definitions_Left";

    public static final BuilderCodec<SwitchToggleInteraction> CODEC =
        BuilderCodec.builder(SwitchToggleInteraction.class, SwitchToggleInteraction::new, SimpleInstantInteraction.CODEC).build();

    @Override
    protected void firstRun(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldown
    ) {
        try {
            // Get the command buffer and store
            final CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
            if (buffer == null) {
                LOGGER.atWarning().log("[SwitchToggle] No command buffer");
                return;
            }

            final Store<EntityStore> store = buffer.getStore();
            final Ref<EntityStore> playerRef = context.getEntity();

            if (playerRef == null || !playerRef.isValid()) {
                LOGGER.atWarning().log("[SwitchToggle] No valid player reference");
                return;
            }

            // Get the target block position using TargetUtil (like RailDebugSystem does)
            Vector3i targetPos = TargetUtil.getTargetBlock(playerRef, 10, buffer);
            if (targetPos == null) {
                LOGGER.atWarning().log("[SwitchToggle] No target block position");
                return;
            }

            // Get the world
            World world = store.getExternalData().getWorld();
            if (world == null) {
                LOGGER.atWarning().log("[SwitchToggle] Could not get world");
                return;
            }

            int x = targetPos.x;
            int y = targetPos.y;
            int z = targetPos.z;

            // Get current block type
            var currentBlock = world.getBlockType(x, y, z);
            if (currentBlock == null) {
                LOGGER.atWarning().log("[SwitchToggle] No block at target position");
                return;
            }

            String blockTypeName = currentBlock.toString();
            LOGGER.atInfo().log("[SwitchToggle] Current block: %s at (%d, %d, %d)", blockTypeName, x, y, z);

            // Check if this is a switch block
            if (!blockTypeName.contains("Rail_Switch")) {
                LOGGER.atWarning().log("[SwitchToggle] Not a switch block: %s", blockTypeName);
                return;
            }

            // Get current rotation to preserve it
            int rotationIndex = world.getBlockRotationIndex(x, y, z);

            // Determine current state and toggle
            boolean isCurrentlyLeft = blockTypeName.contains("_Left") || blockTypeName.contains("State_Definitions_Left");

            // Determine target block ID
            String newBlockId;
            String targetState;
            if (isCurrentlyLeft) {
                // Switch to straight
                targetState = "Straight";
                newBlockId = SWITCH_STRAIGHT_ID;
                LOGGER.atInfo().log("[SwitchToggle] Switching from Left to Straight");
            } else {
                // Switch to left
                targetState = "Left";
                newBlockId = SWITCH_LEFT_ID;
                LOGGER.atInfo().log("[SwitchToggle] Switching from Straight to Left");
            }

            // Set the new block with the same rotation
            world.setBlock(x, y, z, newBlockId, rotationIndex);
            LOGGER.atInfo().log("[SwitchToggle] Successfully toggled switch at (%d, %d, %d) to %s",
                x, y, z, targetState);

        } catch (Exception e) {
            LOGGER.atSevere().log("[SwitchToggle] Error: %s", e.getMessage());
            e.printStackTrace();
        }
    }
}
