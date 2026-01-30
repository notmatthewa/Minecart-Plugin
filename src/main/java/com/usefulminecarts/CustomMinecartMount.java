package com.usefulminecarts;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.codec.ProtocolCodecs;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.io.PacketHandler;

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

    private final Vector3f attachmentOffset = new Vector3f(0, 1.5f, 0.3f);

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
            Ref<EntityStore> targetEntity = context.getTargetEntity();
            if (targetEntity == null) {
                context.getState().state = InteractionState.Failed;
                return;
            }

            Ref<EntityStore> playerEntity = context.getEntity();
            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();

            // Check if player is already riding
            CustomMinecartRiderComponent existingRider = null;
            try {
                existingRider = commandBuffer.getComponent(playerEntity, CustomMinecartRiderComponent.getComponentType());
            } catch (Exception e) {
                // Ignore
            }

            if (existingRider != null) {
                // Already riding - dismount
                try {
                    int cartId = existingRider.getMinecartEntityId();

                    PacketHandler handler = MountMovementPacketFilter.getHandlerForCart(cartId);
                    if (handler != null) {
                        CustomMinecartRidingSystem.restoreMovementSettings(cartId, commandBuffer, playerEntity, handler);
                    }

                    commandBuffer.removeComponent(playerEntity, CustomMinecartRiderComponent.getComponentType());
                    MinecartRiderTracker.removeRider(cartId);
                    CustomMinecartRidingSystem.removeCart(cartId);
                    MinecartMountInputBlocker.clearInput(cartId);
                    MountMovementPacketFilter.onDismount(cartId);
                } catch (Exception e) {
                    // Ignore
                }
                context.getState().state = InteractionState.Failed;
                return;
            }

            // Check if cart already has a rider
            NetworkId cartNetworkId = commandBuffer.getComponent(targetEntity, NetworkId.getComponentType());
            if (cartNetworkId == null) {
                context.getState().state = InteractionState.Failed;
                return;
            }
            int cartEntityId = cartNetworkId.getId();

            if (MinecartRiderTracker.hasRider(cartEntityId)) {
                context.getState().state = InteractionState.Failed;
                return;
            }

            // Get minecart position
            TransformComponent minecartTransform = commandBuffer.getComponent(targetEntity, TransformComponent.getComponentType());
            if (minecartTransform == null) {
                context.getState().state = InteractionState.Failed;
                return;
            }
            Vector3d minecartPos = minecartTransform.getPosition();

            // Add rider component
            try {
                CustomMinecartRiderComponent riderComp = new CustomMinecartRiderComponent(cartEntityId, attachmentOffset);
                commandBuffer.addComponent(playerEntity, CustomMinecartRiderComponent.getComponentType(), riderComp);
            } catch (Exception e) {
                context.getState().state = InteractionState.Failed;
                return;
            }

            // Set up mount tracking
            MountMovementPacketFilter.onMount(cartEntityId);
            MinecartMountInputBlocker.onMount(cartEntityId);
            MinecartRiderTracker.setRider(cartEntityId, playerEntity);
            CustomMinecartRidingSystem.updateCartPosition(cartEntityId, minecartPos.x, minecartPos.y, minecartPos.z);
            CustomMinecartRidingSystem.markJustMounted(cartEntityId);

            // Teleport player to cart
            try {
                TransformComponent playerTransform = commandBuffer.getComponent(playerEntity, TransformComponent.getComponentType());
                if (playerTransform != null) {
                    playerTransform.setPosition(new Vector3d(
                            minecartPos.x + attachmentOffset.x,
                            minecartPos.y + attachmentOffset.y,
                            minecartPos.z + attachmentOffset.z
                    ));
                }
            } catch (Exception e) {
                // Ignore
            }

        } catch (Exception e) {
            context.getState().state = InteractionState.Failed;
        }
    }
}