package com.usefulminecarts;

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
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.protocol.packets.entities.EntityUpdates;
import com.hypixel.hytale.protocol.packets.player.ClientTeleport;
import com.hypixel.hytale.protocol.ModelTransform;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.EntityUpdate;
import com.hypixel.hytale.protocol.ComponentUpdate;
import com.hypixel.hytale.protocol.ComponentUpdateType;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System that handles positioning players who are riding minecarts.
 *
 * Since we don't use MountedComponent (which would give the client control),
 * we use a combination of:
 * 1. setPosition() to set the server-side position
 * 2. ChangeVelocity packets to affect client-side prediction (like jetpack mods)
 * 3. PhysicsValues with low mass to minimize gravity
 * 4. MovementSettings with low values to prevent player movement
 */
public class CustomMinecartRidingSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Track cart entity ID -> cart position for quick lookup
    public static final Map<Integer, Vector3d> cartPositions = new ConcurrentHashMap<>();

    // Track previous cart positions for velocity calculation
    private static final Map<Integer, Vector3d> prevCartPositions = new ConcurrentHashMap<>();

    // Track carts that just had a player mount - value is ticks remaining to try snap
    private static final Map<Integer, Integer> justMountedCarts = new ConcurrentHashMap<>();
    private static final int MOUNT_SNAP_RETRIES = 10;

    // Store original settings per cart (to restore on dismount)
    private static final Map<Integer, MovementSettingsSnapshot> originalMovementSettings = new ConcurrentHashMap<>();
    private static final Map<Integer, PhysicsValuesSnapshot> originalPhysicsValues = new ConcurrentHashMap<>();

    // Track if we've already applied movement lock for a cart
    private static final Map<Integer, Boolean> movementLockApplied = new ConcurrentHashMap<>();

    // For periodic logging
    private static int tickCounter = 0;

    // Configurable upward velocity to counteract gravity while riding
    // Needs to be balanced - too low = falling, too high = rising
    private static float riderGravityCounterVel = 0.15f;

    public static float getRiderGravityCounterVel() {
        return riderGravityCounterVel;
    }

    public static void setRiderGravityCounterVel(float vel) {
        riderGravityCounterVel = vel;
        LOGGER.atInfo().log("[CustomMinecartRiding] Rider gravity counter velocity set to %.3f", vel);
    }

    // Run AFTER MinecartPhysicsSystem so cart position is finalized
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, MinecartPhysicsSystem.class, OrderPriority.FURTHEST)
    );

    @Nonnull
    private final Query<EntityStore> query;

    public CustomMinecartRidingSystem() {
        this.query = Query.and(
                CustomMinecartRiderComponent.getComponentType(),
                TransformComponent.getComponentType(),
                PlayerInput.getComponentType()
        );
        LOGGER.atInfo().log("[CustomMinecartRiding] Initialized - will position riders using setPosition + ChangeVelocity");
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

        try {
            if (UsefulMinecartsPlugin.isShuttingDown()) {
                return;
            }

            Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);
            if (playerRef == null || !playerRef.isValid()) {
                return;
            }

            // Clear player movement input to prevent walking while riding
            PlayerInput playerInput = (PlayerInput) archetypeChunk.getComponent(
                    index, PlayerInput.getComponentType()
            );
            if (playerInput != null) {
                playerInput.getMovementUpdateQueue().clear();
            }

            // Get our custom rider component
            CustomMinecartRiderComponent riderComp = commandBuffer.getComponent(
                    playerRef, CustomMinecartRiderComponent.getComponentType()
            );
            if (riderComp == null) {
                return;
            }

            int cartEntityId = riderComp.getMinecartEntityId();
            Vector3f offset = riderComp.getAttachmentOffset();

            // Check if this is within the first few ticks after mounting
            Integer mountTicksLeft = justMountedCarts.get(cartEntityId);
            boolean justMounted = (mountTicksLeft != null && mountTicksLeft > 0);
            if (justMounted) {
                justMountedCarts.put(cartEntityId, mountTicksLeft - 1);
                if (mountTicksLeft <= 1) {
                    justMountedCarts.remove(cartEntityId);
                }
            }

            // Get the cart's current position from our tracker
            Vector3d cartPos = cartPositions.get(cartEntityId);
            if (cartPos == null) {
                LOGGER.atWarning().log("[CustomMinecartRiding] Cart %d position not found, dismounting rider", cartEntityId);
                forceDismountRider(cartEntityId, playerRef, store, commandBuffer, "cart position not tracked");
                return;
            }

            // Check if player is too far from cart (e.g., teleported away)
            TransformComponent playerTransformCheck = commandBuffer.getComponent(playerRef, TransformComponent.getComponentType());
            if (playerTransformCheck != null) {
                Vector3d playerPos = playerTransformCheck.getPosition();
                double distSq = (playerPos.x - cartPos.x) * (playerPos.x - cartPos.x)
                        + (playerPos.y - cartPos.y) * (playerPos.y - cartPos.y)
                        + (playerPos.z - cartPos.z) * (playerPos.z - cartPos.z);
                // If player is more than 10 blocks away, they probably got teleported
                if (distSq > 100.0) {  // 10^2 = 100
                    LOGGER.atWarning().log("[CustomMinecartRiding] Player too far from cart %d (dist=%.1f), dismounting",
                            cartEntityId, Math.sqrt(distSq));
                    forceDismountRider(cartEntityId, playerRef, store, commandBuffer, "player too far from cart");
                    return;
                }
            }

            // Calculate target position (cart + offset)
            double targetX = cartPos.x + offset.x;
            double targetY = cartPos.y + offset.y;
            double targetZ = cartPos.z + offset.z;

            // Get player transform
            TransformComponent playerTransform = commandBuffer.getComponent(
                    playerRef, TransformComponent.getComponentType()
            );
            if (playerTransform == null) {
                return;
            }

            // Get SERVER position
            Vector3d serverPos = playerTransform.getPosition();
            double serverX = serverPos.x;
            double serverY = serverPos.y;
            double serverZ = serverPos.z;

            // Get CLIENT position
            double[] clientPos = MinecartMountInputBlocker.getClientPosition(cartEntityId);
            double clientX, clientY, clientZ;
            boolean hasClientPos = (clientPos != null);
            if (hasClientPos) {
                clientX = clientPos[0];
                clientY = clientPos[1];
                clientZ = clientPos[2];
            } else {
                clientX = serverX;
                clientY = serverY;
                clientZ = serverZ;
            }

            // Detect flying
            boolean isFlying = (clientY - targetY) > 2.0;

            // Calculate distance from target
            double distFromTarget = Math.sqrt(
                    (clientX - targetX) * (clientX - targetX) +
                            (clientY - targetY) * (clientY - targetY) +
                            (clientZ - targetZ) * (clientZ - targetZ)
            );

            // Calculate cart velocity from position delta
            Vector3d prevPos = prevCartPositions.get(cartEntityId);
            float cartVelX = 0, cartVelY = 0, cartVelZ = 0;

            if (prevPos != null && !justMounted) {
                double cartDeltaX = cartPos.x - prevPos.x;
                double cartDeltaY = cartPos.y - prevPos.y;
                double cartDeltaZ = cartPos.z - prevPos.z;
                float tickRate = 30f;
                float velocityScale = 0.9f;
                cartVelX = (float)cartDeltaX * tickRate * velocityScale;
                cartVelY = (float)cartDeltaY * tickRate * velocityScale;
                cartVelZ = (float)cartDeltaZ * tickRate * velocityScale;
            }

            // If flying, add strong downward velocity to pull player back
            if (isFlying) {
                cartVelY = -20.0f;
            } else if (Math.abs(cartVelY) < 0.1f) {
                cartVelY = riderGravityCounterVel;
            }

            // Calculate how far player has drifted from target
            double driftX = serverX - targetX;
            double driftY = serverY - targetY;
            double driftZ = serverZ - targetZ;
            double driftDist = Math.sqrt(driftX * driftX + driftY * driftY + driftZ * driftZ);

            // Always set server-side position to keep player on cart
            // This is server-authoritative and doesn't cause client camera jerk
            playerTransform.setPosition(new Vector3d(targetX, targetY, targetZ));

            // Set velocity to counter gravity and match cart movement
            try {
                Velocity velocityComp = commandBuffer.getComponent(playerRef, Velocity.getComponentType());
                if (velocityComp != null) {
                    if (isFlying) {
                        velocityComp.set(cartVelX, -20.0, cartVelZ);
                    } else {
                        // Upward velocity to counter gravity + drift correction
                        double upwardForce = riderGravityCounterVel - driftY * 2.0;
                        velocityComp.set(cartVelX, cartVelY + upwardForce, cartVelZ);
                    }
                }
            } catch (Exception e) {
                // Ignore velocity errors
            }

            // Set player to sitting state
            try {
                MovementStatesComponent moveComp = commandBuffer.getComponent(
                        playerRef, MovementStatesComponent.getComponentType()
                );
                if (moveComp != null) {
                    MovementStates states = moveComp.getMovementStates();
                    if (states != null) {
                        states.mounting = true;
                        states.sitting = true;
                        states.onGround = true;
                        states.falling = false;
                        states.jumping = false;
                        states.walking = false;
                        states.running = false;
                        states.sprinting = false;
                        states.crouching = false;
                        moveComp.setMovementStates(states);
                    }
                }
            } catch (Exception e) {
                // Ignore
            }

            // Play sitting animation
            if (justMounted) {
                String[] animNamesToTry = {"Sit", "MountIdle", "SitGround", "Sit2"};
                for (String animName : animNamesToTry) {
                    try {
                        AnimationUtils.playAnimation(playerRef, AnimationSlot.Action, null, animName, true, store);
                        break;
                    } catch (Exception e) {
                        // Try next
                    }
                }
            }

            // 3. Sync position to client via ClientTeleport packet
            // Only teleport on mount or when drifted significantly
            boolean shouldTeleport = justMounted || driftDist > 0.1;

            // Apply movement lock + send position update
            PacketHandler handler = MountMovementPacketFilter.getHandlerForCart(cartEntityId);

            if (shouldTeleport && handler != null) {
                try {
                    // Send ClientTeleport packet directly with position only (null orientations)
                    // This should update position without affecting camera rotation
                    ModelTransform transform = new ModelTransform(
                            new Position(targetX, targetY, targetZ),
                            null,  // null bodyOrientation = don't change
                            null   // null lookOrientation = don't change camera
                    );

                    ClientTeleport teleportPacket = new ClientTeleport(
                            (byte) 0,    // teleportId
                            transform,
                            false        // don't reset velocity
                    );

                    handler.writeNoCache(teleportPacket);

                    if (justMounted) {
                        justMountedCarts.remove(cartEntityId);
                    }
                } catch (Exception e) {
                    // Fallback to Teleport component if packet fails
                    try {
                        Teleport teleport = Teleport.createForPlayer(
                                new Vector3d(targetX, targetY, targetZ),
                                new Vector3f(0, 0, 0)
                        ).withoutVelocityReset();
                        commandBuffer.addComponent(playerRef, Teleport.getComponentType(), teleport);
                    } catch (Exception e2) {
                        // Ignore
                    }
                }
            }
            if (handler != null) {
                if (!movementLockApplied.getOrDefault(cartEntityId, false)) {
                    applyMovementLock(cartEntityId, store, playerRef, handler, commandBuffer);
                }

                // Send velocity to client to help with prediction
                // Include drift correction to pull client back toward target
                try {
                    float velX = cartVelX - (float)(driftX * 2.0);
                    float velZ = cartVelZ - (float)(driftZ * 2.0);
                    float velY = cartVelY - (float)(driftY * 2.0) + riderGravityCounterVel;

                    if (isFlying) {
                        velY = -20.0f;
                    }

                    ChangeVelocity changeVel = new ChangeVelocity(
                            velX, velY, velZ,
                            ChangeVelocityType.Set,
                            null
                    );
                    handler.writeNoCache(changeVel);
                } catch (Exception e) {
                    // Ignore velocity errors
                }
            }

            // Store current position as previous for next tick
            Vector3d prev = prevCartPositions.get(cartEntityId);
            if (prev == null) {
                prevCartPositions.put(cartEntityId, new Vector3d(cartPos.x, cartPos.y, cartPos.z));
            } else {
                prev.x = cartPos.x;
                prev.y = cartPos.y;
                prev.z = cartPos.z;
            }

        } catch (Exception e) {
            LOGGER.atSevere().log("[CustomMinecartRiding] Error in tick: %s", e.getMessage());
        }
    }

    /**
     * Apply movement lock - sets low mass, low movement settings, and makes player intangible.
     */
    public static void applyMovementLock(int cartEntityId, Store<EntityStore> store,
                                         Ref<EntityStore> playerRef, PacketHandler handler,
                                         CommandBuffer<EntityStore> commandBuffer) {
        if (movementLockApplied.getOrDefault(cartEntityId, false)) {
            return;
        }
        movementLockApplied.put(cartEntityId, true);

        try {
            // Add Intangible component (server-side)
            Intangible existingIntangible = store.getComponent(playerRef, Intangible.getComponentType());
            if (existingIntangible == null) {
                commandBuffer.addComponent(playerRef, Intangible.getComponentType(), Intangible.INSTANCE);
            }

            // Send Intangible update to client to disable client-side collisions
            try {
                NetworkId playerNetworkId = store.getComponent(playerRef, NetworkId.getComponentType());
                if (playerNetworkId != null && handler != null) {
                    // Create ComponentUpdate with Intangible type
                    ComponentUpdate intangibleUpdate = new ComponentUpdate();
                    intangibleUpdate.type = ComponentUpdateType.Intangible;

                    // Also try HitboxCollision with index -1 to disable collision
                    ComponentUpdate hitboxUpdate = new ComponentUpdate();
                    hitboxUpdate.type = ComponentUpdateType.HitboxCollision;
                    hitboxUpdate.hitboxCollisionConfigIndex = -1;  // Try -1 to disable

                    // Create EntityUpdate for the player with both updates
                    EntityUpdate entityUpdate = new EntityUpdate(
                            playerNetworkId.getId(),
                            null,  // no removed components
                            new ComponentUpdate[] { intangibleUpdate, hitboxUpdate }
                    );

                    // Send EntityUpdates packet to client
                    EntityUpdates packet = new EntityUpdates(
                            null,  // no removed entities
                            new EntityUpdate[] { entityUpdate }
                    );
                    handler.writeNoCache(packet);
                    LOGGER.atInfo().log("[CustomMinecartRiding] Sent Intangible+HitboxCollision update to client for player %d", playerNetworkId.getId());
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("[CustomMinecartRiding] Failed to send collision packet: %s", e.getMessage());
            }

            // Apply PhysicsValues - low mass to minimize gravity
            PhysicsValues physicsValues = store.getComponent(playerRef, PhysicsValues.getComponentType());
            if (physicsValues != null) {
                if (!originalPhysicsValues.containsKey(cartEntityId)) {
                    originalPhysicsValues.put(cartEntityId, new PhysicsValuesSnapshot(
                            physicsValues.getMass(),
                            physicsValues.getDragCoefficient(),
                            physicsValues.isInvertedGravity()
                    ));
                }

                try {
                    java.lang.reflect.Field massField = PhysicsValues.class.getDeclaredField("mass");
                    massField.setAccessible(true);
                    massField.setDouble(physicsValues, 0.001);

                    java.lang.reflect.Field dragField = PhysicsValues.class.getDeclaredField("dragCoefficient");
                    dragField.setAccessible(true);
                    dragField.setDouble(physicsValues, 10.0);
                } catch (Exception e) {
                    // Ignore
                }
            }

            // Apply MovementSettings - disable movement abilities
            MovementManager movementManager = store.getComponent(playerRef, MovementManager.getComponentType());
            if (movementManager != null) {
                MovementSettings settings = movementManager.getSettings();
                if (settings != null) {
                    if (!originalMovementSettings.containsKey(cartEntityId)) {
                        originalMovementSettings.put(cartEntityId, new MovementSettingsSnapshot(settings));
                    }

                    settings.acceleration = 0.01f;
                    settings.jumpForce = 0.01f;
                    settings.baseSpeed = 0.01f;
                    settings.forwardWalkSpeedMultiplier = 0.01f;
                    settings.forwardRunSpeedMultiplier = 0.01f;
                    settings.forwardSprintSpeedMultiplier = 0.01f;
                    settings.backwardWalkSpeedMultiplier = 0.01f;
                    settings.backwardRunSpeedMultiplier = 0.01f;
                    settings.strafeWalkSpeedMultiplier = 0.01f;
                    settings.strafeRunSpeedMultiplier = 0.01f;
                    settings.forwardCrouchSpeedMultiplier = 0.01f;
                    settings.backwardCrouchSpeedMultiplier = 0.01f;
                    settings.strafeCrouchSpeedMultiplier = 0.01f;
                    settings.velocityResistance = 1.0f;

                    movementManager.update(handler);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Called by MinecartPhysicsSystem after updating cart position.
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
     * Called when a player mounts a cart.
     */
    public static void markJustMounted(int entityId) {
        justMountedCarts.put(entityId, MOUNT_SNAP_RETRIES);
        prevCartPositions.remove(entityId);
    }

    /**
     * Remove cart from tracking.
     */
    public static void removeCart(int entityId) {
        cartPositions.remove(entityId);
        prevCartPositions.remove(entityId);
        justMountedCarts.remove(entityId);
        originalMovementSettings.remove(entityId);
        originalPhysicsValues.remove(entityId);
        movementLockApplied.remove(entityId);
    }

    /**
     * Clear all tracking data (called on plugin shutdown).
     */
    public static void clearAllTracking() {
        cartPositions.clear();
        prevCartPositions.clear();
        justMountedCarts.clear();
        originalMovementSettings.clear();
        originalPhysicsValues.clear();
        movementLockApplied.clear();
    }

    /**
     * Force dismount a rider - used when cart is destroyed or player teleported.
     */
    public static void forceDismountRider(int cartEntityId, Ref<EntityStore> playerRef,
                                          Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer,
                                          String reason) {
        try {
            if (playerRef != null && playerRef.isValid()) {
                commandBuffer.removeComponent(playerRef, CustomMinecartRiderComponent.getComponentType());

                // Restore movement states
                MovementStatesComponent statesComp = commandBuffer.getComponent(playerRef, MovementStatesComponent.getComponentType());
                if (statesComp != null) {
                    MovementStates states = statesComp.getMovementStates();
                    if (states != null) {
                        states.sitting = false;
                        states.mounting = false;
                        states.flying = false;
                        states.onGround = true;
                        states.falling = false;
                        statesComp.setMovementStates(states);
                    }
                }

                // Restore movement settings
                PacketHandler handler = MountMovementPacketFilter.getHandlerForCart(cartEntityId);
                if (handler != null) {
                    restoreMovementSettings(cartEntityId, commandBuffer, playerRef, handler);
                } else {
                    PhysicsValuesSnapshot physicsSnapshot = originalPhysicsValues.get(cartEntityId);
                    if (physicsSnapshot != null) {
                        PhysicsValues physicsValues = commandBuffer.getComponent(playerRef, PhysicsValues.getComponentType());
                        if (physicsValues != null) {
                            physicsSnapshot.applyTo(physicsValues);
                        }
                    }

                    Intangible existingIntangible = commandBuffer.getComponent(playerRef, Intangible.getComponentType());
                    if (existingIntangible != null) {
                        commandBuffer.removeComponent(playerRef, Intangible.getComponentType());
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }

        // Clean up tracking data
        MinecartRiderTracker.removeRider(cartEntityId);
        removeCart(cartEntityId);
        MinecartMountInputBlocker.clearInput(cartEntityId);
        MountMovementPacketFilter.onDismount(cartEntityId);
    }

    /**
     * Check if a cart entity ID is still valid (cart exists in the world).
     * Call this periodically to detect destroyed carts.
     */
    public static boolean isCartEntityValid(int cartEntityId) {
        // If we have no position tracked, cart is invalid
        return cartPositions.containsKey(cartEntityId);
    }

    /**
     * Restore original movement settings on dismount (using Store).
     * Note: Cannot remove Intangible with Store, only with CommandBuffer.
     */
    public static void restoreMovementSettings(int cartEntityId, Store<EntityStore> store,
                                               Ref<EntityStore> playerRef, PacketHandler handler) {
        restoreAllSettingsInternal(cartEntityId,
                () -> store.getComponent(playerRef, MovementManager.getComponentType()),
                () -> store.getComponent(playerRef, PhysicsValues.getComponentType()),
                () -> store.getComponent(playerRef, MovementStatesComponent.getComponentType()),
                handler, null, null);
    }

    /**
     * Restore original movement settings on dismount (using CommandBuffer).
     */
    public static void restoreMovementSettings(int cartEntityId, CommandBuffer<EntityStore> commandBuffer,
                                               Ref<EntityStore> playerRef, PacketHandler handler) {
        restoreAllSettingsInternal(cartEntityId,
                () -> commandBuffer.getComponent(playerRef, MovementManager.getComponentType()),
                () -> commandBuffer.getComponent(playerRef, PhysicsValues.getComponentType()),
                () -> commandBuffer.getComponent(playerRef, MovementStatesComponent.getComponentType()),
                handler, commandBuffer, playerRef);
    }

    /**
     * Internal implementation that restores all settings.
     */
    private static void restoreAllSettingsInternal(int cartEntityId,
                                                   java.util.function.Supplier<MovementManager> managerSupplier,
                                                   java.util.function.Supplier<PhysicsValues> physicsSupplier,
                                                   java.util.function.Supplier<MovementStatesComponent> statesSupplier,
                                                   PacketHandler handler,
                                                   CommandBuffer<EntityStore> commandBuffer,
                                                   Ref<EntityStore> playerRef) {
        movementLockApplied.remove(cartEntityId);

        try {
            // Remove Intangible component
            if (commandBuffer != null && playerRef != null) {
                Intangible existingIntangible = commandBuffer.getComponent(playerRef, Intangible.getComponentType());
                if (existingIntangible != null) {
                    commandBuffer.removeComponent(playerRef, Intangible.getComponentType());
                }
            }

            // Restore PhysicsValues
            PhysicsValuesSnapshot physicsSnapshot = originalPhysicsValues.remove(cartEntityId);
            if (physicsSnapshot != null) {
                PhysicsValues physicsValues = physicsSupplier.get();
                if (physicsValues != null) {
                    physicsSnapshot.applyTo(physicsValues);
                }
            }

            // Restore MovementStates
            MovementStatesComponent statesComp = statesSupplier.get();
            if (statesComp != null) {
                MovementStates states = statesComp.getMovementStates();
                if (states != null) {
                    states.sitting = false;
                    states.mounting = false;
                    states.flying = false;
                    states.onGround = true;
                }
            }

            // Restore MovementSettings
            MovementSettingsSnapshot snapshot = originalMovementSettings.remove(cartEntityId);
            if (snapshot != null) {
                MovementManager movementManager = managerSupplier.get();
                if (movementManager != null) {
                    MovementSettings settings = movementManager.getSettings();
                    if (settings != null) {
                        snapshot.applyTo(settings);
                        movementManager.update(handler);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Snapshot of MovementSettings to save/restore on mount/dismount.
     */
    private static class MovementSettingsSnapshot {
        final float acceleration;
        final float jumpForce;
        final float baseSpeed;
        final float forwardWalkSpeedMultiplier;
        final float forwardRunSpeedMultiplier;
        final float forwardSprintSpeedMultiplier;
        final float backwardWalkSpeedMultiplier;
        final float backwardRunSpeedMultiplier;
        final float strafeWalkSpeedMultiplier;
        final float strafeRunSpeedMultiplier;
        final float forwardCrouchSpeedMultiplier;
        final float backwardCrouchSpeedMultiplier;
        final float strafeCrouchSpeedMultiplier;
        final float velocityResistance;

        MovementSettingsSnapshot(MovementSettings s) {
            this.acceleration = s.acceleration;
            this.jumpForce = s.jumpForce;
            this.baseSpeed = s.baseSpeed;
            this.forwardWalkSpeedMultiplier = s.forwardWalkSpeedMultiplier;
            this.forwardRunSpeedMultiplier = s.forwardRunSpeedMultiplier;
            this.forwardSprintSpeedMultiplier = s.forwardSprintSpeedMultiplier;
            this.backwardWalkSpeedMultiplier = s.backwardWalkSpeedMultiplier;
            this.backwardRunSpeedMultiplier = s.backwardRunSpeedMultiplier;
            this.strafeWalkSpeedMultiplier = s.strafeWalkSpeedMultiplier;
            this.strafeRunSpeedMultiplier = s.strafeRunSpeedMultiplier;
            this.forwardCrouchSpeedMultiplier = s.forwardCrouchSpeedMultiplier;
            this.backwardCrouchSpeedMultiplier = s.backwardCrouchSpeedMultiplier;
            this.strafeCrouchSpeedMultiplier = s.strafeCrouchSpeedMultiplier;
            this.velocityResistance = s.velocityResistance;
        }

        void applyTo(MovementSettings s) {
            s.acceleration = this.acceleration;
            s.jumpForce = this.jumpForce;
            s.baseSpeed = this.baseSpeed;
            s.forwardWalkSpeedMultiplier = this.forwardWalkSpeedMultiplier;
            s.forwardRunSpeedMultiplier = this.forwardRunSpeedMultiplier;
            s.forwardSprintSpeedMultiplier = this.forwardSprintSpeedMultiplier;
            s.backwardWalkSpeedMultiplier = this.backwardWalkSpeedMultiplier;
            s.backwardRunSpeedMultiplier = this.backwardRunSpeedMultiplier;
            s.strafeWalkSpeedMultiplier = this.strafeWalkSpeedMultiplier;
            s.strafeRunSpeedMultiplier = this.strafeRunSpeedMultiplier;
            s.forwardCrouchSpeedMultiplier = this.forwardCrouchSpeedMultiplier;
            s.backwardCrouchSpeedMultiplier = this.backwardCrouchSpeedMultiplier;
            s.strafeCrouchSpeedMultiplier = this.strafeCrouchSpeedMultiplier;
            s.velocityResistance = this.velocityResistance;
        }
    }

    /**
     * Snapshot of PhysicsValues to save/restore on mount/dismount.
     */
    private static class PhysicsValuesSnapshot {
        final double mass;
        final double dragCoefficient;
        final boolean invertedGravity;

        PhysicsValuesSnapshot(double mass, double dragCoefficient, boolean invertedGravity) {
            this.mass = mass;
            this.dragCoefficient = dragCoefficient;
            this.invertedGravity = invertedGravity;
        }

        void applyTo(PhysicsValues pv) {
            try {
                java.lang.reflect.Field massField = PhysicsValues.class.getDeclaredField("mass");
                massField.setAccessible(true);
                massField.setDouble(pv, this.mass);

                java.lang.reflect.Field dragField = PhysicsValues.class.getDeclaredField("dragCoefficient");
                dragField.setAccessible(true);
                dragField.setDouble(pv, this.dragCoefficient);

                java.lang.reflect.Field gravField = PhysicsValues.class.getDeclaredField("invertedGravity");
                gravField.setAccessible(true);
                gravField.setBoolean(pv, this.invertedGravity);
            } catch (Exception e) {
                // Ignore
            }
        }
    }
}