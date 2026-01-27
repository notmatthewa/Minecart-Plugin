package com.usefulminecarts;

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
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Custom mount interaction for minecarts that completely bypasses vanilla mounting.
 *
 * Instead of adding MountedComponent (which triggers vanilla client-authoritative movement),
 * we add our own CustomMinecartRiderComponent which our systems handle entirely.
 *
 * This gives us full server-side control over minecart movement.
 */
public class CustomMinecartMount extends SimpleInstantInteraction {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Codec with AttachmentOffset field
    public static final BuilderCodec<CustomMinecartMount> CODEC = BuilderCodec.builder(
        CustomMinecartMount.class,
        CustomMinecartMount::new,
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

    public CustomMinecartMount() {
        super();
    }

    @Override
    protected void firstRun(
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        try {
            // Get the target entity (minecart)
            Ref<EntityStore> targetEntity = context.getTargetEntity();
            if (targetEntity == null) {
                LOGGER.atWarning().log("[CustomMinecartMount] Target entity is null");
                context.getState().state = InteractionState.Failed;
                return;
            }
            LOGGER.atInfo().log("[CustomMinecartMount] Target entity valid: %s", targetEntity.isValid());

            // Get the player entity
            Ref<EntityStore> playerEntity = context.getEntity();
            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
            LOGGER.atInfo().log("[CustomMinecartMount] Player entity valid: %s", playerEntity != null && playerEntity.isValid());

            // Check if player is already riding (using our custom component or vanilla)
            CustomMinecartRiderComponent existingRider = null;
            MountedComponent existingMount = null;
            try {
                existingRider = commandBuffer.getComponent(playerEntity, CustomMinecartRiderComponent.getComponentType());
                existingMount = commandBuffer.getComponent(playerEntity, MountedComponent.getComponentType());
            } catch (Exception e) {
                LOGGER.atWarning().log("[CustomMinecartMount] Error checking existing components: %s", e.getMessage());
            }

            LOGGER.atInfo().log("[CustomMinecartMount] Existing rider: %s, existing mount: %s",
                existingRider != null, existingMount != null);

            if (existingRider != null || existingMount != null) {
                // Already riding - dismount
                try {
                    int cartId = -1;
                    if (existingRider != null) {
                        cartId = existingRider.getMinecartEntityId();
                        LOGGER.atInfo().log("[CustomMinecartMount] Dismounting from cart %d", cartId);
                        commandBuffer.removeComponent(playerEntity, CustomMinecartRiderComponent.getComponentType());
                        MinecartRiderTracker.removeRider(cartId);
                        CustomMinecartRidingSystem.removeCart(cartId);
                    }
                    if (existingMount != null) {
                        // Removing MountedComponent handles dismount via vanilla system
                        commandBuffer.removeComponent(playerEntity, MountedComponent.getComponentType());
                        LOGGER.atInfo().log("[CustomMinecartMount] Removed MountedComponent");
                    }
                } catch (Exception e) {
                    LOGGER.atSevere().log("[CustomMinecartMount] Error during dismount: %s", e.getMessage());
                }
                LOGGER.atInfo().log("[CustomMinecartMount] Player dismounted");
                context.getState().state = InteractionState.Failed;
                return;
            }

            // Check if cart already has a rider
            NetworkId cartNetworkId = commandBuffer.getComponent(targetEntity, NetworkId.getComponentType());
            if (cartNetworkId == null) {
                LOGGER.atWarning().log("[CustomMinecartMount] Cart has no NetworkId");
                context.getState().state = InteractionState.Failed;
                return;
            }
            int cartEntityId = cartNetworkId.getId();
            LOGGER.atInfo().log("[CustomMinecartMount] Cart network ID: %d", cartEntityId);

            // CRITICAL: Add MovementStatesComponent to the cart if it doesn't have one
            // This prevents NPE in GamePacketHandler when client sends mount movement packets
            // The NPC-style mount causes client to send movement to the cart directly,
            // and the packet handler expects MovementStatesComponent to exist
            try {
                MovementStatesComponent existingMoveComp = commandBuffer.getComponent(
                    targetEntity, MovementStatesComponent.getComponentType()
                );
                if (existingMoveComp == null) {
                    // Create a new MovementStatesComponent with default (stationary) states
                    MovementStatesComponent moveComp = new MovementStatesComponent();
                    commandBuffer.addComponent(targetEntity, MovementStatesComponent.getComponentType(), moveComp);
                    LOGGER.atInfo().log("[CustomMinecartMount] Added MovementStatesComponent to cart %d", cartEntityId);
                } else {
                    LOGGER.atInfo().log("[CustomMinecartMount] Cart %d already has MovementStatesComponent", cartEntityId);
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("[CustomMinecartMount] Could not add MovementStatesComponent to cart: %s", e.getMessage());
                // Continue anyway - the component might already exist or be auto-added
            }

            if (MinecartRiderTracker.hasRider(cartEntityId)) {
                LOGGER.atInfo().log("[CustomMinecartMount] Cart %d already has a rider", cartEntityId);
                context.getState().state = InteractionState.Failed;
                return;
            }

            // Get minecart position
            TransformComponent minecartTransform = commandBuffer.getComponent(targetEntity, TransformComponent.getComponentType());
            if (minecartTransform == null) {
                LOGGER.atWarning().log("[CustomMinecartMount] Cart has no TransformComponent");
                context.getState().state = InteractionState.Failed;
                return;
            }
            Vector3d minecartPos = minecartTransform.getPosition();
            LOGGER.atInfo().log("[CustomMinecartMount] Cart position: (%.2f, %.2f, %.2f)",
                minecartPos.x, minecartPos.y, minecartPos.z);

            // Add our custom rider component for physics tracking
            try {
                CustomMinecartRiderComponent riderComp = new CustomMinecartRiderComponent(
                    cartEntityId,
                    attachmentOffset
                );
                commandBuffer.addComponent(playerEntity, CustomMinecartRiderComponent.getComponentType(), riderComp);
                LOGGER.atInfo().log("[CustomMinecartMount] Added CustomMinecartRiderComponent to player");
            } catch (Exception e) {
                LOGGER.atSevere().log("[CustomMinecartMount] Failed to add rider component: %s", e.getMessage());
                e.printStackTrace();
                context.getState().state = InteractionState.Failed;
                return;
            }

            // Use MountedComponent with Minecart controller for visual attachment
            // The client will try to control the cart, but:
            // 1. MinecartInputInterceptor clears the movement queue (extracts W/S first)
            // 2. HandleMountInput gets empty queue, does nothing
            // 3. MinecartPhysicsSystem calculates server position
            // 4. MinecartRailSnapSystem restores server position after any client interference
            // 5. Entity tracking system replicates the corrected position to client
            try {
                MountedComponent mountComp = new MountedComponent(
                    targetEntity,
                    attachmentOffset,
                    MountController.Minecart
                );
                commandBuffer.addComponent(playerEntity, MountedComponent.getComponentType(), mountComp);
                LOGGER.atInfo().log("[CustomMinecartMount] Added MountedComponent with Minecart controller");
            } catch (Exception e) {
                LOGGER.atWarning().log("[CustomMinecartMount] Failed to add MountedComponent: %s", e.getMessage());
            }

            // Track the rider in our tracker
            try {
                MinecartRiderTracker.setRider(cartEntityId, playerEntity);
                LOGGER.atInfo().log("[CustomMinecartMount] Tracked rider in MinecartRiderTracker");
            } catch (Exception e) {
                LOGGER.atSevere().log("[CustomMinecartMount] Failed to track rider: %s", e.getMessage());
            }

            // Store cart position for the riding system
            try {
                CustomMinecartRidingSystem.updateCartPosition(cartEntityId, minecartPos.x, minecartPos.y, minecartPos.z);
                LOGGER.atInfo().log("[CustomMinecartMount] Updated cart position in riding system");
            } catch (Exception e) {
                LOGGER.atSevere().log("[CustomMinecartMount] Failed to update cart position: %s", e.getMessage());
            }

            // Store position for physics system
            try {
                MinecartPositionTracker.trackedPositions.put(cartEntityId, new Vector3d(minecartPos.x, minecartPos.y, minecartPos.z));
                LOGGER.atInfo().log("[CustomMinecartMount] Stored position in MinecartPositionTracker");
            } catch (Exception e) {
                LOGGER.atSevere().log("[CustomMinecartMount] Failed to store position: %s", e.getMessage());
            }

            // Teleport player to the cart position + offset
            try {
                TransformComponent playerTransform = commandBuffer.getComponent(playerEntity, TransformComponent.getComponentType());
                if (playerTransform != null) {
                    double newX = minecartPos.x + attachmentOffset.x;
                    double newY = minecartPos.y + attachmentOffset.y;
                    double newZ = minecartPos.z + attachmentOffset.z;
                    playerTransform.getPosition().x = newX;
                    playerTransform.getPosition().y = newY;
                    playerTransform.getPosition().z = newZ;
                    LOGGER.atInfo().log("[CustomMinecartMount] Teleported player to (%.2f, %.2f, %.2f)", newX, newY, newZ);
                } else {
                    LOGGER.atWarning().log("[CustomMinecartMount] Player has no TransformComponent!");
                }
            } catch (Exception e) {
                LOGGER.atSevere().log("[CustomMinecartMount] Failed to teleport player: %s", e.getMessage());
            }

            LOGGER.atInfo().log("[CustomMinecartMount] === Mount complete for cart %d ===", cartEntityId);

        } catch (Exception e) {
            LOGGER.atSevere().log("[CustomMinecartMount] Unexpected error during mount: %s", e.getMessage());
            e.printStackTrace();
            context.getState().state = InteractionState.Failed;
        }
    }
}
