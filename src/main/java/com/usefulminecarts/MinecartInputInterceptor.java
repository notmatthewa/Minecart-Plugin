package com.usefulminecarts;

import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.builtin.mounts.MountSystems;
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
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts player input for minecart riders BEFORE HandleMountInput processes it.
 *
 * Strategy:
 * 1. Extract MovementStates from the input queue (for W/S input)
 * 2. Apply MovementStates directly to the cart's MovementStatesComponent
 * 3. CLEAR the entire queue (so HandleMountInput can't move the cart)
 *
 * This prevents vanilla HandleMountInput from applying any position/rotation changes
 * while still allowing our MinecartPhysicsSystem to read W/S input.
 */
public class MinecartInputInterceptor extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    private final Query<EntityStore> query;

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies;

    private int tickCounter = 0;

    public MinecartInputInterceptor() {
        // Query for entities with PlayerInput - we filter if they're minecart riders
        this.query = Query.and(
            PlayerInput.getComponentType()
        );
        // Run BEFORE HandleMountInput with FURTHEST priority to run as early as possible
        this.dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, MountSystems.HandleMountInput.class, OrderPriority.FURTHEST)
        );
        LOGGER.atInfo().log("[MinecartInputInterceptor] Initialized - extracts W/S input, blocks position control");
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return this.dependencies;
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
        boolean shouldLog = (tickCounter % 60 == 0); // Log every second

        Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);

        // Check if player is riding a minecart (via MountedComponent)
        MountedComponent mounted = store.getComponent(playerRef, MountedComponent.getComponentType());
        if (mounted == null) return;

        // Only intercept for Minecart controller (BlockMount doesn't work with entity mounts)
        MountController controller = mounted.getControllerType();
        if (controller != MountController.Minecart) return;

        // Verify the mount entity is actually a minecart
        Ref<EntityStore> mountRef = mounted.getMountedToEntity();
        if (mountRef == null || !mountRef.isValid()) return;

        MinecartComponent minecart = store.getComponent(mountRef, MinecartComponent.getComponentType());
        if (minecart == null) return;

        // Get the movement queue
        PlayerInput playerInput = (PlayerInput) archetypeChunk.getComponent(
            index, PlayerInput.getComponentType()
        );
        if (playerInput == null) return;

        List<PlayerInput.InputUpdate> queue = playerInput.getMovementUpdateQueue();
        if (queue.isEmpty()) return;

        int originalSize = queue.size();
        MovementStates extractedStates = null;

        // Extract MovementStates from the queue BEFORE clearing
        // We look for SetMovementStates which applies to the cart (not SetRiderMovementStates which applies to player)
        for (PlayerInput.InputUpdate update : queue) {
            if (update instanceof PlayerInput.SetMovementStates) {
                PlayerInput.SetMovementStates setStates = (PlayerInput.SetMovementStates) update;
                extractedStates = setStates.movementStates();
                if (shouldLog) {
                    LOGGER.atInfo().log("[MinecartInputInterceptor] Extracted SetMovementStates: walking=%b, running=%b, crouching=%b",
                        extractedStates.walking, extractedStates.running, extractedStates.crouching);
                }
            }
            // Also check SetRiderMovementStates as fallback
            if (update instanceof PlayerInput.SetRiderMovementStates && extractedStates == null) {
                PlayerInput.SetRiderMovementStates setStates = (PlayerInput.SetRiderMovementStates) update;
                extractedStates = setStates.movementStates();
                if (shouldLog) {
                    LOGGER.atInfo().log("[MinecartInputInterceptor] Extracted SetRiderMovementStates: walking=%b, running=%b, crouching=%b",
                        extractedStates.walking, extractedStates.running, extractedStates.crouching);
                }
            }
        }

        // Apply extracted MovementStates directly to the cart's MovementStatesComponent
        // This way our MinecartPhysicsSystem can read W/S input from the cart
        if (extractedStates != null) {
            MovementStatesComponent cartMoveComp = commandBuffer.getComponent(
                mountRef, MovementStatesComponent.getComponentType()
            );
            if (cartMoveComp != null) {
                cartMoveComp.setMovementStates(extractedStates);
                if (shouldLog) {
                    LOGGER.atInfo().log("[MinecartInputInterceptor] Applied MovementStates to cart");
                }
            }
        }

        // CLEAR the entire queue so HandleMountInput doesn't process anything
        // This prevents vanilla from moving the cart based on client input
        queue.clear();

        if (shouldLog) {
            LOGGER.atInfo().log("[MinecartInputInterceptor] Cleared %d input updates, extracted states: %s",
                originalSize, extractedStates != null);
        }
    }
}
