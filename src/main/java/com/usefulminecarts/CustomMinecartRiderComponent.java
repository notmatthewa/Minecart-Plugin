package com.usefulminecarts;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Custom component added to PLAYERS when they ride a minecart using our custom system.
 *
 * This replaces MountedComponent and doesn't trigger vanilla mount movement handling.
 * Our CustomMinecartRidingSystem reads this component to:
 * - Position the player on the cart each tick
 * - Handle player input (W/S for momentum)
 * - Lock player movement
 */
public class CustomMinecartRiderComponent implements Component<EntityStore> {

    private static ComponentType<EntityStore, CustomMinecartRiderComponent> COMPONENT_TYPE;

    // Reference to the minecart entity being ridden
    private int minecartEntityId;

    // Attachment offset (where player sits relative to cart center)
    private Vector3f attachmentOffset = new Vector3f(0, 1.0f, 0.3f);

    // Codec for serialization (required for component registration)
    public static final BuilderCodec<CustomMinecartRiderComponent> CODEC = BuilderCodec.builder(
        CustomMinecartRiderComponent.class,
        CustomMinecartRiderComponent::new
    )
    .append(
        new KeyedCodec<>("MinecartEntityId", Codec.INTEGER),
        (comp, value) -> comp.minecartEntityId = value,
        comp -> comp.minecartEntityId
    ).add()
    .build();

    public CustomMinecartRiderComponent() {
    }

    public CustomMinecartRiderComponent(int minecartEntityId, Vector3f offset) {
        this.minecartEntityId = minecartEntityId;
        this.attachmentOffset = offset;
    }

    public int getMinecartEntityId() {
        return minecartEntityId;
    }

    public void setMinecartEntityId(int id) {
        this.minecartEntityId = id;
    }

    public Vector3f getAttachmentOffset() {
        return attachmentOffset;
    }

    public void setAttachmentOffset(Vector3f offset) {
        this.attachmentOffset = offset;
    }

    public static ComponentType<EntityStore, CustomMinecartRiderComponent> getComponentType() {
        return COMPONENT_TYPE;
    }

    public static void setComponentType(ComponentType<EntityStore, CustomMinecartRiderComponent> type) {
        COMPONENT_TYPE = type;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        CustomMinecartRiderComponent clone = new CustomMinecartRiderComponent();
        clone.minecartEntityId = this.minecartEntityId;
        clone.attachmentOffset = new Vector3f(attachmentOffset.x, attachmentOffset.y, attachmentOffset.z);
        return clone;
    }
}
