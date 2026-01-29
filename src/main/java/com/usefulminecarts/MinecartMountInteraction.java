package com.usefulminecarts;

import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.codec.ProtocolCodecs;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Custom mount interaction for minecarts that prevents the teleport bug.
 *
 * The standard Mount interaction causes minecarts to teleport to the player.
 * This custom interaction teleports the player TO the minecart instead.
 */
public class MinecartMountInteraction extends SimpleInstantInteraction {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Codec with AttachmentOffset field
    public static final BuilderCodec<MinecartMountInteraction> CODEC = BuilderCodec.builder(
        MinecartMountInteraction.class,
        MinecartMountInteraction::new,
        SimpleInstantInteraction.CODEC
    )
    .append(
        new KeyedCodec<>("AttachmentOffset", ProtocolCodecs.VECTOR3F),
        (interaction, offset) -> interaction.attachmentOffset.assign(offset.x, offset.y, offset.z),
        interaction -> new com.hypixel.hytale.protocol.Vector3f(
            interaction.attachmentOffset.x,
            interaction.attachmentOffset.y,
            interaction.attachmentOffset.z
        )
    ).add()
    .build();

    private final Vector3f attachmentOffset = new Vector3f(0, 1, 0.3f);

    public MinecartMountInteraction() {
        super();
    }

    @Override
    protected void firstRun(
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        // Get the target entity (minecart)
        Ref<EntityStore> targetEntity = context.getTargetEntity();
        if (targetEntity == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        // Get the player entity
        Ref<EntityStore> playerEntity = context.getEntity();
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();

        // Check if player is already mounted
        MountedComponent existingMount = commandBuffer.getComponent(playerEntity, MountedComponent.getComponentType());
        if (existingMount != null) {
            // Already mounted - dismount instead
            commandBuffer.removeComponent(playerEntity, MountedComponent.getComponentType());
            context.getState().state = InteractionState.Failed;
            return;
        }

        // Check if target already has passengers
        MountedByComponent mountedBy = commandBuffer.getComponent(targetEntity, MountedByComponent.getComponentType());
        if (mountedBy != null && !mountedBy.getPassengers().isEmpty()) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        // === KEY FIX: Get minecart position BEFORE mounting ===
        TransformComponent minecartTransform = commandBuffer.getComponent(targetEntity, TransformComponent.getComponentType());
        Vector3d minecartPos = null;
        if (minecartTransform != null) {
            Vector3d pos = minecartTransform.getPosition();
            minecartPos = new Vector3d(pos.x, pos.y, pos.z);
            LOGGER.atInfo().log("[MinecartMount] Saving minecart position: (%.2f, %.2f, %.2f)", pos.x, pos.y, pos.z);
        }

        // Add MountedComponent to player with Minecart controller for proper visual mounting
        // Note: We track our own rider state separately to handle physics
        commandBuffer.addComponent(
            playerEntity,
            MountedComponent.getComponentType(),
            new MountedComponent(targetEntity, attachmentOffset, MountController.Minecart)
        );

        // Also add to our custom rider tracker for physics processing
        NetworkId cartNetworkId = commandBuffer.getComponent(targetEntity, NetworkId.getComponentType());
        if (cartNetworkId != null) {
            MinecartRiderTracker.setRider(cartNetworkId.getId(), playerEntity);
            LOGGER.atInfo().log("[MinecartMount] Added rider tracking for cart %d", cartNetworkId.getId());
        }

        // === KEY FIX: Teleport player TO the minecart ===
        if (minecartPos != null) {
            TransformComponent playerTransform = commandBuffer.getComponent(playerEntity, TransformComponent.getComponentType());
            if (playerTransform != null) {
                Vector3d newPlayerPos = new Vector3d(
                    minecartPos.x + attachmentOffset.x,
                    minecartPos.y + attachmentOffset.y,
                    minecartPos.z + attachmentOffset.z
                );
                playerTransform.getPosition().assign(newPlayerPos);
                LOGGER.atInfo().log("[MinecartMount] Teleported player to minecart: (%.2f, %.2f, %.2f)",
                    newPlayerPos.x, newPlayerPos.y, newPlayerPos.z);
            }
        }

        LOGGER.atInfo().log("[MinecartMount] Mount complete!");
    }
}
