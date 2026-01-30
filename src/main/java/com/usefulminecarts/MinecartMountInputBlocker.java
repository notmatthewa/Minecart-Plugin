package com.usefulminecarts;

import com.hypixel.hytale.builtin.mounts.MountSystems;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts player input BEFORE the vanilla HandleMountInput system runs.
 *
 * HandleMountInput processes PlayerInput.getMovementUpdateQueue() and applies
 * AbsoluteMovement/RelativeMovement entries to the mount entity's position.
 * For minecart riders, this overwrites our server-authoritative physics position.
 *
 * This system:
 * 1. Scans the queue to extract rider intent (forward/backward)
 * 2. Clears the queue so HandleMountInput has nothing to process
 *
 * The extracted input is stored for MinecartPhysicsSystem to consume.
 */
public class MinecartMountInputBlocker extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Rider input: cart entity ID -> direction (1 = forward, -1 = backward, 0 = none)
    private static final Map<Integer, Integer> RIDER_INPUT = new ConcurrentHashMap<>();

    // Track carts that just had a player mount - ignore position-based input for a few ticks
    // This prevents false backward detection when mounting from a different position
    private static final Map<Integer, Integer> MOUNT_GRACE_PERIOD = new ConcurrentHashMap<>();
    private static final int GRACE_PERIOD_TICKS = 15; // ~0.5 seconds at 30fps

    // Track the CLIENT's last known position (from AbsoluteMovement in input queue)
    // This is the actual client position, not the server position we set
    private static final Map<Integer, double[]> CLIENT_POSITIONS = new ConcurrentHashMap<>();

    // Track the CLIENT's last known rotation (body yaw from SetBody, head pitch from SetHead)
    // Format: [bodyYaw, headYaw, headPitch]
    private static final Map<Integer, float[]> CLIENT_ROTATIONS = new ConcurrentHashMap<>();

    // Run BEFORE HandleMountInput so we clear the queue before it processes movement
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
        new SystemDependency<>(Order.BEFORE, MountSystems.HandleMountInput.class)
    );

    @Nonnull
    private final Query<EntityStore> query;

    private static int tickCount = 0;

    public MinecartMountInputBlocker() {
        this.query = Query.and(
            CustomMinecartRiderComponent.getComponentType(),
            PlayerInput.getComponentType()
        );
        LOGGER.atInfo().log("[MinecartMountInputBlocker] Initialized - extracts input then clears queue before HandleMountInput");
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
        tickCount++;
        boolean shouldLog = (tickCount % 60 == 0);

        // Skip processing if plugin is shutting down to avoid race conditions
        if (UsefulMinecartsPlugin.isShuttingDown()) {
            return;
        }

        Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);

        // Safety check: verify entity reference is still valid (prevents crashes during shutdown)
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        // Get our custom rider component for the cart entity ID
        CustomMinecartRiderComponent riderComp = commandBuffer.getComponent(
            playerRef, CustomMinecartRiderComponent.getComponentType()
        );
        if (riderComp == null) return;
        int cartEntityId = riderComp.getMinecartEntityId();

        // Check and update grace period (ignore position-based input right after mounting)
        Integer graceTicksLeft = MOUNT_GRACE_PERIOD.get(cartEntityId);
        boolean inGracePeriod = (graceTicksLeft != null && graceTicksLeft > 0);
        if (inGracePeriod) {
            MOUNT_GRACE_PERIOD.put(cartEntityId, graceTicksLeft - 1);
            if (graceTicksLeft <= 1) {
                MOUNT_GRACE_PERIOD.remove(cartEntityId);
            }
        }

        PlayerInput playerInput = (PlayerInput) archetypeChunk.getComponent(
            index, PlayerInput.getComponentType()
        );
        if (playerInput == null) return;

        List<PlayerInput.InputUpdate> queue = playerInput.getMovementUpdateQueue();
        int queueSize = queue.size();

        // Extract input direction from queue entries before clearing
        // We ONLY look at position delta now - MovementStates is unreliable when using MountNPC
        double lastAbsX = Double.NaN, lastAbsY = Double.NaN, lastAbsZ = Double.NaN;
        float lastBodyYaw = Float.NaN;
        float lastHeadYaw = Float.NaN;
        float lastHeadPitch = Float.NaN;

        for (PlayerInput.InputUpdate entry : queue) {
            if (entry instanceof PlayerInput.AbsoluteMovement am) {
                // AbsoluteMovement = client's desired position for the mount
                lastAbsX = am.getX();
                lastAbsY = am.getY();
                lastAbsZ = am.getZ();
            } else if (entry instanceof PlayerInput.SetBody sb) {
                // SetBody = client's body orientation for the mount
                lastBodyYaw = sb.direction().yaw;
            } else if (entry instanceof PlayerInput.SetHead sh) {
                // SetHead = client's head/camera orientation (where player is looking)
                lastHeadYaw = sh.direction().yaw;
                lastHeadPitch = sh.direction().pitch;
            }
            // NOTE: We ignore SetMovementStates and SetRiderMovementStates because
            // they report walking=true even when no keys are pressed (due to MountNPC)
        }

        // Detect direction from position delta
        // Compare client's desired position vs cart position to detect W/S input
        // The client sends where they WANT the mount to be, we detect direction from that
        int direction = 0;

        if (!inGracePeriod && !Double.isNaN(lastAbsX)) {
            // Get current cart position from our physics tracker
            Vector3d cartPos = CustomMinecartRidingSystem.cartPositions.get(cartEntityId);
            if (cartPos != null) {
                // Get previous client position to detect CHANGE in desired position
                double[] prevClientPos = CLIENT_POSITIONS.get(cartEntityId);

                if (prevClientPos != null) {
                    // Calculate delta from PREVIOUS client position to CURRENT client position
                    // This represents actual player input, not just position offset
                    double dx = lastAbsX - prevClientPos[0];
                    double dz = lastAbsZ - prevClientPos[2];
                    double distSq = dx * dx + dz * dz;

                    // Higher threshold - only detect deliberate movement input
                    if (distSq > 0.04) { // ~0.2 blocks movement required
                        // Use PLAYER'S head yaw (where they're looking) for direction detection
                        float playerYaw = !Float.isNaN(lastHeadYaw) ? lastHeadYaw :
                                         (!Float.isNaN(lastBodyYaw) ? lastBodyYaw : 0f);

                        // Player's look direction
                        double lookX = -Math.sin(playerYaw);
                        double lookZ = Math.cos(playerYaw);

                        // Normalize movement delta
                        double dist = Math.sqrt(distSq);
                        double moveX = dx / dist;
                        double moveZ = dz / dist;

                        // Dot product: positive = moving in look direction (W), negative = opposite (S)
                        double dot = moveX * lookX + moveZ * lookZ;

                        if (dot > 0.5) {
                            direction = 1;  // W key - forward relative to player look
                        } else if (dot < -0.5) {
                            direction = -1; // S key - backward relative to player look
                        }
                        // If |dot| < 0.5, player is mostly strafing (A/D) - ignore for cart movement

                        if (shouldLog) {
                            LOGGER.atInfo().log("[InputDebug] cart %d: dx=%.3f dz=%.3f dist=%.3f playerYaw=%.2f dot=%.3f dir=%d",
                                cartEntityId, dx, dz, dist, playerYaw, dot, direction);
                        }
                    }
                }
            }
        }

        // Only store non-zero input (don't overwrite with 0 every tick)
        if (direction != 0) {
            RIDER_INPUT.put(cartEntityId, direction);
        }

        // Store the client's position for drift detection
        // This is the ACTUAL client position, not the server position
        if (!Double.isNaN(lastAbsX)) {
            CLIENT_POSITIONS.put(cartEntityId, new double[]{lastAbsX, lastAbsY, lastAbsZ});
        }

        // Store the client's rotation for use in teleports (preserves camera direction)
        if (!Float.isNaN(lastHeadYaw) || !Float.isNaN(lastHeadPitch) || !Float.isNaN(lastBodyYaw)) {
            float[] existing = CLIENT_ROTATIONS.get(cartEntityId);
            float bodyYaw = !Float.isNaN(lastBodyYaw) ? lastBodyYaw : (existing != null ? existing[0] : 0f);
            float headYaw = !Float.isNaN(lastHeadYaw) ? lastHeadYaw : (existing != null ? existing[1] : 0f);
            float headPitch = !Float.isNaN(lastHeadPitch) ? lastHeadPitch : (existing != null ? existing[2] : 0f);
            CLIENT_ROTATIONS.put(cartEntityId, new float[]{bodyYaw, headYaw, headPitch});
        }

        MovementStateReader movementState = new MovementStateReader(queue);
        if (movementState.isWalking() || movementState.isGliding()) {
            LOGGER.atInfo().log("[MinecartMountInputBlocker] cart %d: player is WALKING", cartEntityId);
        }
        if (movementState.isCrouching()) {
            LOGGER.atInfo().log("[MinecartMountInputBlocker] cart %d: player is CROUCHING", cartEntityId);
        }

        // Clear the entire queue - HandleMountInput will have nothing to process
        queue.clear();

        if (shouldLog && (queueSize > 0 || direction != 0)) {
            LOGGER.atInfo().log("[ClientMovementDebug] INPUT_BLOCKER: cart %d, cleared %d entries, direction=%d, clientPos=(%.2f, %.2f, %.2f)",
                cartEntityId, queueSize, direction,
                Double.isNaN(lastAbsX) ? 0.0 : lastAbsX,
                Double.isNaN(lastAbsY) ? 0.0 : lastAbsY,
                Double.isNaN(lastAbsZ) ? 0.0 : lastAbsZ);
        }
    }

    /**
     * Get and consume the rider input direction for a cart.
     * @return 1 for forward, -1 for backward, 0 for no input
     */
    public static int consumeRiderInput(int cartEntityId) {
        Integer dir = RIDER_INPUT.remove(cartEntityId);
        return dir != null ? dir : 0;
    }

    /**
     * Peek at the rider input direction without consuming.
     */
    public static int peekRiderInput(int cartEntityId) {
        Integer dir = RIDER_INPUT.get(cartEntityId);
        return dir != null ? dir : 0;
    }

    /**
     * Clear input for a cart (on dismount/destroy).
     */
    public static void clearInput(int cartEntityId) {
        RIDER_INPUT.remove(cartEntityId);
        MOUNT_GRACE_PERIOD.remove(cartEntityId);
        CLIENT_POSITIONS.remove(cartEntityId);
        CLIENT_ROTATIONS.remove(cartEntityId);
    }

    /**
     * Clear all tracking data (called on plugin shutdown).
     * This prevents the system from accessing stale entity references during server shutdown.
     */
    public static void clearAll() {
        RIDER_INPUT.clear();
        MOUNT_GRACE_PERIOD.clear();
        CLIENT_POSITIONS.clear();
        CLIENT_ROTATIONS.clear();
    }

    /**
     * Get the client's last known position for a cart's rider.
     * This is from AbsoluteMovement in the input queue - the actual client position.
     * @return [x, y, z] or null if no position data available
     */
    public static double[] getClientPosition(int cartEntityId) {
        return CLIENT_POSITIONS.get(cartEntityId);
    }

    /**
     * Get the client's last known rotation for a cart's rider.
     * This is from SetBody and SetHead in the input queue - the actual client rotation.
     * @return [bodyYaw, headYaw, headPitch] or null if no rotation data available
     */
    public static float[] getClientRotation(int cartEntityId) {
        return CLIENT_ROTATIONS.get(cartEntityId);
    }

    /**
     * Called when a player mounts a cart. Starts grace period to ignore
     * position-based input detection (prevents false backward from mount position).
     */
    public static void onMount(int cartEntityId) {
        MOUNT_GRACE_PERIOD.put(cartEntityId, GRACE_PERIOD_TICKS);
        RIDER_INPUT.remove(cartEntityId); // Clear any stale input
        LOGGER.atInfo().log("[MinecartMountInputBlocker] Started grace period for cart %d (%d ticks)", cartEntityId, GRACE_PERIOD_TICKS);
    }
}
