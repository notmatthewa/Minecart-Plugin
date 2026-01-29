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
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
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
    private static float riderGravityCounterVel = 0.1f;

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
        boolean shouldLog = (tickCounter % 60 == 0);

        try {
            // Skip processing if plugin is shutting down
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
                LOGGER.atWarning().log("[CustomMinecartRiding] Cart %d not found, dismounting rider", cartEntityId);
                PacketHandler dismountHandler = MountMovementPacketFilter.getHandlerForCart(cartEntityId);
                if (dismountHandler != null) {
                    restoreMovementSettings(cartEntityId, store, playerRef, dismountHandler);
                }
                commandBuffer.removeComponent(playerRef, CustomMinecartRiderComponent.getComponentType());
                MinecartRiderTracker.removeRider(cartEntityId);
                MountMovementPacketFilter.onDismount(cartEntityId);
                return;
            }

            // Calculate target position (cart + offset)
            double targetX = cartPos.x + offset.x;
            double targetY = cartPos.y + offset.y - 0.5f;
            double targetZ = cartPos.z + offset.z - 0.25f;

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
            if (isFlying && shouldLog) {
                LOGGER.atInfo().log("[RiderDebug] cart=%d | FLYING DETECTED! clientY=%.2f targetY=%.2f diff=%.2f",
                    cartEntityId, clientY, targetY, clientY - targetY);
            }

            // Calculate distance from target
            double distFromTarget = Math.sqrt(
                (clientX - targetX) * (clientX - targetX) +
                (clientY - targetY) * (clientY - targetY) +
                (clientZ - targetZ) * (clientZ - targetZ)
            );

            // Calculate cart velocity from position delta
            Vector3d prevPos = prevCartPositions.get(cartEntityId);
            float cartVelX = 0, cartVelY = 0, cartVelZ = 0;
            double cartDeltaX = 0, cartDeltaY = 0, cartDeltaZ = 0;

            if (prevPos != null && !justMounted) {
                cartDeltaX = cartPos.x - prevPos.x;
                cartDeltaY = cartPos.y - prevPos.y;
                cartDeltaZ = cartPos.z - prevPos.z;
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

            // Log detailed state
            if (shouldLog) {
                LOGGER.atInfo().log("[RiderDebug] cart=%d | cartPos=(%.2f,%.2f,%.2f) | cartDelta=(%.3f,%.3f,%.3f) | offset=(%.2f,%.2f,%.2f)",
                    cartEntityId, cartPos.x, cartPos.y, cartPos.z,
                    cartDeltaX, cartDeltaY, cartDeltaZ,
                    offset.x, offset.y, offset.z);
                LOGGER.atInfo().log("[RiderDebug] cart=%d | targetPos=(%.2f,%.2f,%.2f) | serverPos=(%.2f,%.2f,%.2f) | clientPos=(%.2f,%.2f,%.2f) | distFromTarget=%.3f | hasClientPos=%b",
                    cartEntityId, targetX, targetY, targetZ,
                    serverX, serverY, serverZ,
                    clientX, clientY, clientZ, distFromTarget, hasClientPos);
            }

            // 1. Set server-side position directly
            playerTransform.setPosition(new Vector3d(targetX, targetY, targetZ));

            // 1b. Use Velocity component to counteract gravity
            // This applies server-side velocity that should counteract falling
            try {
                Velocity velocityComp = commandBuffer.getComponent(playerRef, Velocity.getComponentType());
                if (velocityComp != null) {
                    // Set velocity to match cart movement + upward to counter gravity
                    // Increase until player stops falling
                    double antiGravityY = 0.5; // Upward force to counteract gravity per tick

                    if (isFlying) {
                        // Strong downward pull if trying to fly away
                        velocityComp.set(cartVelX, -20.0, cartVelZ);
                    } else {
                        // Normal riding - set velocity to cart velocity + anti-gravity
                        velocityComp.set(cartVelX, cartVelY + antiGravityY, cartVelZ);
                    }
                }
            } catch (Exception e) {
                // Ignore velocity errors
            }

            // 2. Set player to mounting/sitting state
            try {
                MovementStatesComponent moveComp = commandBuffer.getComponent(
                    playerRef, MovementStatesComponent.getComponentType()
                );
                if (moveComp != null) {
                    MovementStates states = moveComp.getMovementStates();
                    if (states != null) {
                        states.sitting = true;
                        states.onGround = true;
                        states.falling = false;
                        states.jumping = false;
                        states.walking = false;
                        states.running = false;
                        states.sprinting = false;
                        moveComp.setMovementStates(states);
                    }
                }
            } catch (Exception e) {
                // Ignore - sitting state is cosmetic
            }

            // 2b. Try to play sitting animation directly
            try {
                ActiveAnimationComponent animComp = commandBuffer.getComponent(
                    playerRef, ActiveAnimationComponent.getComponentType()
                );
                if (animComp != null) {
                    animComp.setPlayingAnimation(AnimationSlot.Movement, "sitting");
                    animComp.setPlayingAnimation(AnimationSlot.Status, "sitting");
                }
            } catch (Exception e) {
                // Ignore - animation is cosmetic
            }

            // 3. Handle position corrections using Teleport component
            float[] clientRot = MinecartMountInputBlocker.getClientRotation(cartEntityId);
            Vector3f bodyRotation = new Vector3f(0, 0, 0);
            Vector3f headRotation = new Vector3f(0, 0, 0);
            if (clientRot != null) {
                bodyRotation = new Vector3f(0, clientRot[0], 0);
                headRotation = new Vector3f(clientRot[2], clientRot[1], 0);
            }

            boolean shouldTeleport = justMounted || (tickCounter % 10 == 0);

            if (shouldTeleport) {
                try {
                    Teleport teleport = Teleport.createForPlayer(
                        new Vector3d(targetX, targetY, targetZ),
                        bodyRotation
                    ).withoutVelocityReset();

                    if (clientRot != null) {
                        teleport.setHeadRotation(headRotation);
                    }

                    commandBuffer.addComponent(playerRef, Teleport.getComponentType(), teleport);

                    if (justMounted) {
                        LOGGER.atInfo().log("[RiderDebug] cart=%d | TELEPORT on mount to (%.2f,%.2f,%.2f) clientRot=%s",
                            cartEntityId, targetX, targetY, targetZ,
                            clientRot != null ? String.format("(%.1f,%.1f,%.1f)", clientRot[0], clientRot[1], clientRot[2]) : "null");
                        justMountedCarts.remove(cartEntityId);
                    } else if (shouldLog) {
                        LOGGER.atInfo().log("[RiderDebug] cart=%d | TELEPORT correction, dist=%.3f, clientRot=%s",
                            cartEntityId, distFromTarget,
                            clientRot != null ? String.format("(%.1f,%.1f,%.1f)", clientRot[0], clientRot[1], clientRot[2]) : "null");
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().log("[RiderDebug] Failed to teleport: %s", e.getMessage());
                }
            }

            // 4. Send ChangeVelocity for smooth movement + apply movement lock
            PacketHandler handler = MountMovementPacketFilter.getHandlerForCart(cartEntityId);
            if (handler != null) {
                // Apply movement lock on first tick with handler
                if (!movementLockApplied.getOrDefault(cartEntityId, false)) {
                    applyMovementLock(cartEntityId, store, playerRef, handler, commandBuffer);
                }

                // Send cart velocity to keep player moving with cart
                try {
                    ChangeVelocity changeVel = new ChangeVelocity(
                        cartVelX, cartVelY, cartVelZ,
                        ChangeVelocityType.Set,
                        null
                    );
                    handler.writeNoCache(changeVel);

                    if (shouldLog) {
                        LOGGER.atInfo().log("[RiderDebug] cart=%d | vel=(%.2f,%.2f,%.2f) | dist=%.3f | flying=%b",
                            cartEntityId, cartVelX, cartVelY, cartVelZ, distFromTarget, isFlying);
                    }
                } catch (Exception e) {
                    if (shouldLog) {
                        LOGGER.atWarning().log("[RiderDebug] Failed to send ChangeVelocity: %s", e.getMessage());
                    }
                }
            } else {
                if (shouldLog || justMounted) {
                    LOGGER.atInfo().log("[RiderDebug] cart=%d | No PacketHandler yet (justMounted=%b)", cartEntityId, justMounted);
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

            // Log AFTER state
            Vector3d afterPos = playerTransform.getPosition();
            if (shouldLog) {
                LOGGER.atInfo().log("[RiderDebug] cart=%d | AFTER setPosition: playerPos=(%.2f,%.2f,%.2f)",
                    cartEntityId, afterPos.x, afterPos.y, afterPos.z);
            }

        } catch (Exception e) {
            LOGGER.atSevere().log("[CustomMinecartRiding] Error in tick: %s", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Apply movement lock - sets low mass for low gravity, low movement settings, and makes player intangible.
     */
    public static void applyMovementLock(int cartEntityId, Store<EntityStore> store,
                                         Ref<EntityStore> playerRef, PacketHandler handler,
                                         CommandBuffer<EntityStore> commandBuffer) {
        if (movementLockApplied.getOrDefault(cartEntityId, false)) {
            return;
        }

        try {
            // 0. Add Intangible component to disable collisions
            try {
                commandBuffer.addComponent(playerRef, Intangible.getComponentType(), Intangible.INSTANCE);
                LOGGER.atInfo().log("[MovementLock] Added Intangible component for cart %d rider (no collisions)", cartEntityId);
            } catch (Exception e) {
                LOGGER.atWarning().log("[MovementLock] Failed to add Intangible: %s", e.getMessage());
            }

            // 1. Apply PhysicsValues - set very low mass to minimize gravity
            PhysicsValues physicsValues = store.getComponent(playerRef, PhysicsValues.getComponentType());
            if (physicsValues != null) {
                if (!originalPhysicsValues.containsKey(cartEntityId)) {
                    originalPhysicsValues.put(cartEntityId, new PhysicsValuesSnapshot(
                        physicsValues.getMass(),
                        physicsValues.getDragCoefficient(),
                        physicsValues.isInvertedGravity()
                    ));
                    LOGGER.atInfo().log("[MovementLock] Saved original PhysicsValues for cart %d: mass=%.4f",
                        cartEntityId, physicsValues.getMass());
                }

                try {
                    java.lang.reflect.Field massField = PhysicsValues.class.getDeclaredField("mass");
                    massField.setAccessible(true);
                    massField.setDouble(physicsValues, 0.001);

                    java.lang.reflect.Field dragField = PhysicsValues.class.getDeclaredField("dragCoefficient");
                    dragField.setAccessible(true);
                    dragField.setDouble(physicsValues, 10.0);

                    LOGGER.atInfo().log("[MovementLock] Set PhysicsValues: mass=0.001, drag=10.0");
                } catch (Exception e) {
                    LOGGER.atWarning().log("[MovementLock] Failed to set PhysicsValues: %s", e.getMessage());
                }
            }

            // 2. Apply MovementSettings - disable movement abilities
            MovementManager movementManager = store.getComponent(playerRef, MovementManager.getComponentType());
            if (movementManager != null) {
                MovementSettings settings = movementManager.getSettings();
                if (settings != null) {
                    if (!originalMovementSettings.containsKey(cartEntityId)) {
                        originalMovementSettings.put(cartEntityId, new MovementSettingsSnapshot(settings));
                        LOGGER.atInfo().log("[MovementLock] Saved original MovementSettings for cart %d", cartEntityId);
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
                    LOGGER.atInfo().log("[MovementLock] Applied MovementSettings with low values");
                }
            }

            movementLockApplied.put(cartEntityId, true);
            LOGGER.atInfo().log("[MovementLock] SUCCESS! Applied movement lock for cart %d rider", cartEntityId);

        } catch (Exception e) {
            LOGGER.atWarning().log("[MovementLock] EXCEPTION applying lock for cart %d: %s", cartEntityId, e.getMessage());
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
        LOGGER.atInfo().log("[CustomMinecartRiding] Marked cart %d as just mounted (will retry snap for %d ticks)", entityId, MOUNT_SNAP_RETRIES);
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
            // 0. Remove Intangible component (restore collisions)
            if (commandBuffer != null && playerRef != null) {
                try {
                    commandBuffer.removeComponent(playerRef, Intangible.getComponentType());
                    LOGGER.atInfo().log("[MovementLock] Removed Intangible component for cart %d rider (collisions restored)", cartEntityId);
                } catch (Exception e) {
                    LOGGER.atWarning().log("[MovementLock] Failed to remove Intangible: %s", e.getMessage());
                }
            }

            // 1. Restore PhysicsValues
            PhysicsValuesSnapshot physicsSnapshot = originalPhysicsValues.remove(cartEntityId);
            if (physicsSnapshot != null) {
                PhysicsValues physicsValues = physicsSupplier.get();
                if (physicsValues != null) {
                    physicsSnapshot.applyTo(physicsValues);
                    LOGGER.atInfo().log("[MovementLock] Restored PhysicsValues for cart %d", cartEntityId);
                }
            }

            // 2. Restore MovementStates
            MovementStatesComponent statesComp = statesSupplier.get();
            if (statesComp != null) {
                MovementStates states = statesComp.getMovementStates();
                if (states != null) {
                    states.sitting = false;
                    states.mounting = false;
                    states.flying = false;
                    states.onGround = true;
                    LOGGER.atInfo().log("[MovementLock] Restored MovementStates for cart %d", cartEntityId);
                }
            }

            // 3. Restore MovementSettings
            MovementSettingsSnapshot snapshot = originalMovementSettings.remove(cartEntityId);
            if (snapshot != null) {
                MovementManager movementManager = managerSupplier.get();
                if (movementManager != null) {
                    MovementSettings settings = movementManager.getSettings();
                    if (settings != null) {
                        snapshot.applyTo(settings);
                        movementManager.update(handler);
                        LOGGER.atInfo().log("[MovementLock] Restored MovementSettings for cart %d", cartEntityId);
                    }
                }
            }

            LOGGER.atInfo().log("[MovementLock] All settings restored for cart %d rider", cartEntityId);

        } catch (Exception e) {
            LOGGER.atWarning().log("[MovementLock] Failed to restore settings for cart %d: %s", cartEntityId, e.getMessage());
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
                LOGGER.atWarning().log("[PhysicsValuesSnapshot] Failed to restore: %s", e.getMessage());
            }
        }
    }
}
