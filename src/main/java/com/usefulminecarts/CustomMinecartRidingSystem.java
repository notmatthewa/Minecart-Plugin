package com.usefulminecarts;

import com.hypixel.hytale.builtin.mounts.minecart.MinecartComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.OrderPriority;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System that handles positioning players who are riding minecarts using our custom system.
 *
 * This runs AFTER our MinecartPhysicsSystem to ensure the cart position is finalized
 * before we position the rider.
 *
 * Key responsibilities:
 * - Position the rider on top of the cart each tick
 * - Handle dismounting when player presses dismount key
 * - Lock player movement while riding
 */
public class CustomMinecartRidingSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Track cart entity ID -> cart position for quick lookup
    public static final Map<Integer, Vector3d> cartPositions = new ConcurrentHashMap<>();

    // For periodic logging
    private static int tickCounter = 0;

    // Run AFTER MinecartPhysicsSystem so cart position is finalized
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
        new SystemDependency<>(Order.AFTER, MinecartPhysicsSystem.class, OrderPriority.FURTHEST)
    );

    @Nonnull
    private final Query<EntityStore> query;

    public CustomMinecartRidingSystem() {
        // Query for players with our custom rider component and input
        this.query = Query.and(
            CustomMinecartRiderComponent.getComponentType(),
            TransformComponent.getComponentType(),
            PlayerInput.getComponentType()
        );
        LOGGER.atInfo().log("[CustomMinecartRiding] Initialized - will position riders on carts and lock movement");
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        tickCounter++;
        boolean shouldLog = (tickCounter % 60 == 0); // Log every ~1 second at 60fps

        try {
            Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);

            // Clear player movement input to prevent walking while riding
            // This stops the server from processing movement - client may still predict locally
            PlayerInput playerInput = (PlayerInput) archetypeChunk.getComponent(
                index, PlayerInput.getComponentType()
            );
            if (playerInput != null) {
                // Clear the movement queue to prevent player from walking
                playerInput.getMovementUpdateQueue().clear();
                if (shouldLog) {
                    LOGGER.atInfo().log("[CustomMinecartRiding] Cleared movement queue for rider");
                }
            }

            // Get our custom rider component
            CustomMinecartRiderComponent riderComp = commandBuffer.getComponent(
                playerRef, CustomMinecartRiderComponent.getComponentType()
            );
            if (riderComp == null) {
                if (shouldLog) {
                    LOGGER.atWarning().log("[CustomMinecartRiding] Tick called but riderComp is null for index %d", index);
                }
                return;
            }

            int cartEntityId = riderComp.getMinecartEntityId();
            Vector3f offset = riderComp.getAttachmentOffset();

            if (shouldLog) {
                LOGGER.atInfo().log("[CustomMinecartRiding] Processing rider for cart %d, cartPositions size: %d",
                    cartEntityId, cartPositions.size());
            }

            // Get the cart's current position from our tracker
            Vector3d cartPos = cartPositions.get(cartEntityId);
            if (cartPos == null) {
                // Cart might be destroyed or unloaded - dismount the player
                LOGGER.atWarning().log("[CustomMinecartRiding] Cart %d not found in cartPositions (size=%d), dismounting rider",
                    cartEntityId, cartPositions.size());
                commandBuffer.removeComponent(playerRef, CustomMinecartRiderComponent.getComponentType());
                MinecartRiderTracker.removeRider(cartEntityId);
                return;
            }

            // Update player position to match cart + offset
            TransformComponent playerTransform = commandBuffer.getComponent(
                playerRef, TransformComponent.getComponentType()
            );
            if (playerTransform != null) {
                double newX = cartPos.x + offset.x;
                double newY = cartPos.y + offset.y;
                double newZ = cartPos.z + offset.z;
                playerTransform.getPosition().x = newX;
                playerTransform.getPosition().y = newY;
                playerTransform.getPosition().z = newZ;

                if (shouldLog) {
                    LOGGER.atInfo().log("[CustomMinecartRiding] Positioned rider at (%.2f, %.2f, %.2f) for cart %d at (%.2f, %.2f, %.2f)",
                        newX, newY, newZ, cartEntityId, cartPos.x, cartPos.y, cartPos.z);
                }
            } else {
                LOGGER.atWarning().log("[CustomMinecartRiding] Player has no TransformComponent!");
            }
        } catch (Exception e) {
            LOGGER.atSevere().log("[CustomMinecartRiding] Error in tick: %s", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Called by MinecartPhysicsSystem after updating cart position.
     * Stores the position so we can use it to position riders.
     */
    public static void updateCartPosition(int entityId, double x, double y, double z) {
        Vector3d pos = cartPositions.get(entityId);
        if (pos == null) {
            pos = new Vector3d(x, y, z);
            cartPositions.put(entityId, pos);
        } else {
            pos.x = x;
            pos.y = y;
            pos.z = z;
        }
    }

    /**
     * Remove cart from tracking (called when cart is destroyed or unloaded).
     */
    public static void removeCart(int entityId) {
        cartPositions.remove(entityId);
    }
}
