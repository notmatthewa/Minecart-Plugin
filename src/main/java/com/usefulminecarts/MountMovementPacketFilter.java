package com.usefulminecarts;

import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.entities.MountMovement;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Intercepts MountMovement and ClientMovement packets using the lambda-based
 * PacketAdapters.registerInbound pattern (proven in JetpackJoyride/Ev0sJets mods).
 *
 * For MountMovement: MODIFIES the packet's position to our server physics position
 * before the vanilla handler processes it. This way the vanilla handler calls
 * setPosition(ourPhysicsPos) which marks dirty for entity replication, sending
 * corrections back to the client.
 *
 * For ClientMovement: reads movementStates (walking/crouching) for rider input detection.
 *
 * This replaces the old PlayerPacketFilter approach which never intercepted any packets.
 */
public class MountMovementPacketFilter {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Store latest physics-calculated position per cart (set by MinecartPhysicsSystem)
    private static final Map<Integer, double[]> PHYSICS_POSITIONS = new ConcurrentHashMap<>();

    // Store latest physics-calculated rotation per cart (set by MinecartPhysicsSystem)
    private static final Map<Integer, float[]> PHYSICS_ROTATIONS = new ConcurrentHashMap<>();

    // Rider input direction keyed by cart entity ID (1=forward, -1=backward, 0=none)
    private static final Map<Integer, Integer> RIDER_INPUT_DIRECTION = new ConcurrentHashMap<>();

    // Map PacketHandler -> cart entity ID (set when first MountMovement arrives)
    private static final Map<PacketHandler, Integer> HANDLER_CART = new ConcurrentHashMap<>();

    // Walking state per handler (from ClientMovement packets)
    private static final Map<PacketHandler, Boolean> HANDLER_WALKING = new ConcurrentHashMap<>();

    // Queue of cart entity IDs waiting to be associated with a PacketHandler
    // Populated when a player mounts, consumed when their first MountMovement arrives
    private static final Queue<Integer> PENDING_CARTS = new ConcurrentLinkedQueue<>();

    // Logging throttle
    private static int mountMovementCount = 0;
    private static int clientMovementCount = 0;

    /**
     * Register the packet interceptor using the lambda pattern.
     * This is the same pattern used by JetpackJoyride and Ev0sJets mods.
     */
    public void register() {
        PacketAdapters.registerInbound((PacketHandler handler, Packet packet) -> {
            // Skip packet processing if plugin is shutting down
            if (UsefulMinecartsPlugin.isShuttingDown()) {
                return;
            }
            try {
                if (packet instanceof MountMovement mm) {
                    handleMountMovement(handler, mm);
                } else if (packet instanceof ClientMovement cm) {
                    handleClientMovement(handler, cm);
                }
            } catch (Exception e) {
                LOGGER.atSevere().log("[MinecartPacketInterceptor] Error: %s", e.getMessage());
            }
        });
        LOGGER.atInfo().log("[MinecartPacketInterceptor] Registered inbound packet interceptor (lambda pattern)");
    }

    /**
     * Unregister on shutdown. With the lambda pattern, we may not be able to
     * deregister individually, but clearing state is important.
     */
    public void unregister() {
        HANDLER_CART.clear();
        HANDLER_WALKING.clear();
        PENDING_CARTS.clear();
        RIDER_INPUT_DIRECTION.clear();
        PHYSICS_POSITIONS.clear();
        PHYSICS_ROTATIONS.clear();
        LOGGER.atInfo().log("[MinecartPacketInterceptor] Cleared all state on shutdown");
    }

    /**
     * Handle MountMovement packet: extract rider input, then modify position
     * to our physics position so the vanilla handler processes OUR position.
     */
    private static void handleMountMovement(PacketHandler handler, MountMovement mm) {
        mountMovementCount++;
        boolean shouldLog = (mountMovementCount % 60 == 1);

        // Find which cart this handler is associated with
        Integer cartId = HANDLER_CART.get(handler);
        if (cartId == null) {
            // Try to associate with a pending cart
            cartId = PENDING_CARTS.poll();
            if (cartId != null) {
                HANDLER_CART.put(handler, cartId);
                LOGGER.atInfo().log("[MinecartPacketInterceptor] Associated handler with cart %d", cartId);
            } else {
                // No pending cart - try to find any cart with a rider that has no handler
                for (var entry : PHYSICS_POSITIONS.entrySet()) {
                    int cid = entry.getKey();
                    if (MinecartRiderTracker.hasRider(cid) && !HANDLER_CART.containsValue(cid)) {
                        cartId = cid;
                        HANDLER_CART.put(handler, cartId);
                        LOGGER.atInfo().log("[MinecartPacketInterceptor] Auto-associated handler with cart %d", cartId);
                        break;
                    }
                }
            }
        }

        if (cartId == null) {
            if (shouldLog) {
                LOGGER.atInfo().log("[MinecartPacketInterceptor] MountMovement #%d but no cart association found", mountMovementCount);
            }
            return;
        }

        double[] physPos = PHYSICS_POSITIONS.get(cartId);
        float[] physRot = PHYSICS_ROTATIONS.get(cartId);

        // 1. Extract movement direction BEFORE modifying the packet
        int direction = 0;

        // Check movementStates for walking (W key)
        if (mm.movementStates != null) {
            if (mm.movementStates.walking || mm.movementStates.running || mm.movementStates.sprinting) {
                direction = 1; // forward
            }
        }

        // Check position delta for direction (S key detection)
        if (mm.absolutePosition != null && physPos != null) {
            double dx = mm.absolutePosition.x - physPos[0];
            double dz = mm.absolutePosition.z - physPos[2];
            double distSq = dx * dx + dz * dz;

            if (distSq > 0.0001) {
                float yaw = (mm.bodyOrientation != null) ? mm.bodyOrientation.yaw : 0f;
                double facingX = -Math.sin(yaw);
                double facingZ = Math.cos(yaw);
                double dot = dx * facingX + dz * facingZ;
                if (dot < -0.001) {
                    direction = -1; // backward
                } else if (dot > 0.001 && direction == 0) {
                    direction = 1; // forward from position delta
                }
            }
        }

        if (direction != 0) {
            RIDER_INPUT_DIRECTION.put(cartId, direction);
        } else {
            // No input detected - clear any stuck input
            RIDER_INPUT_DIRECTION.remove(cartId);
        }

        // 2. Modify packet position to our physics position
        // The vanilla GamePacketHandler will process THIS position (not the client's prediction)
        // and call setPosition(), which marks the transform dirty for entity replication
        if (physPos != null && mm.absolutePosition != null) {
            if (shouldLog) {
                LOGGER.atInfo().log("[MinecartPacketInterceptor] REWRITING MountMovement #%d cart %d: client=(%.2f,%.2f,%.2f) -> physics=(%.2f,%.2f,%.2f), dir=%d",
                    mountMovementCount, cartId,
                    mm.absolutePosition.x, mm.absolutePosition.y, mm.absolutePosition.z,
                    physPos[0], physPos[1], physPos[2], direction);
            }
            mm.absolutePosition.x = physPos[0];
            mm.absolutePosition.y = physPos[1];
            mm.absolutePosition.z = physPos[2];
        }
        if (physRot != null && mm.bodyOrientation != null) {
            mm.bodyOrientation.yaw = physRot[0];
            mm.bodyOrientation.pitch = physRot[1];
        }
    }

    /**
     * Handle ClientMovement packet: read walking state for rider input.
     * Also associates handler with pending carts (since we don't have MountedComponent,
     * we won't receive MountMovement packets, so we must associate from ClientMovement).
     */
    private static void handleClientMovement(PacketHandler handler, ClientMovement cm) {
        clientMovementCount++;
        boolean shouldLog = (clientMovementCount % 300 == 1);

        // Try to associate handler with a pending cart if not already associated
        Integer cartId = HANDLER_CART.get(handler);
        if (cartId == null) {
            // Try to get a pending cart
            cartId = PENDING_CARTS.poll();
            if (cartId != null) {
                HANDLER_CART.put(handler, cartId);
                LOGGER.atInfo().log("[MinecartPacketInterceptor] Associated handler with cart %d (from ClientMovement)", cartId);
            } else {
                // No pending cart - try to find any cart with a rider that has no handler
                for (var entry : PHYSICS_POSITIONS.entrySet()) {
                    int cid = entry.getKey();
                    if (MinecartRiderTracker.hasRider(cid) && !HANDLER_CART.containsValue(cid)) {
                        cartId = cid;
                        HANDLER_CART.put(handler, cartId);
                        LOGGER.atInfo().log("[MinecartPacketInterceptor] Auto-associated handler with cart %d (from ClientMovement)", cartId);
                        break;
                    }
                }
            }
        }

        if (cm.movementStates != null) {
            boolean walking = cm.movementStates.walking || cm.movementStates.running || cm.movementStates.sprinting;
            HANDLER_WALKING.put(handler, walking);

            // If this handler is associated with a cart, update rider input
            if (cartId != null) {
                if (walking) {
                    // ClientMovement walking = W key for forward
                    RIDER_INPUT_DIRECTION.put(cartId, 1);
                    if (shouldLog) {
                        LOGGER.atInfo().log("[MinecartPacketInterceptor] ClientMovement walking=true for cart %d", cartId);
                    }
                } else {
                    // No walking - clear the input so cart stops accelerating
                    RIDER_INPUT_DIRECTION.remove(cartId);
                }
            }
        }
    }

    // ========== PHYSICS STATE STORAGE ==========

    /**
     * Called by MinecartPhysicsSystem after calculating position and rotation.
     */
    public static void updatePhysicsState(int networkId, double x, double y, double z,
                                          float yaw, float pitch, float roll) {
        PHYSICS_POSITIONS.put(networkId, new double[]{x, y, z});
        PHYSICS_ROTATIONS.put(networkId, new float[]{yaw, pitch, roll});
    }

    /**
     * Clear stored physics state for a minecart.
     */
    public static void clearPhysicsState(int networkId) {
        PHYSICS_POSITIONS.remove(networkId);
        PHYSICS_ROTATIONS.remove(networkId);
    }

    // ========== MOUNT/DISMOUNT TRACKING ==========

    /**
     * Called when a player mounts a cart. Queues the cart ID for handler association.
     */
    public static void onMount(int cartEntityId) {
        PENDING_CARTS.add(cartEntityId);
        LOGGER.atInfo().log("[MinecartPacketInterceptor] Cart %d queued for handler association", cartEntityId);
    }

    /**
     * Called when a player dismounts. Clears handler association.
     */
    public static void onDismount(int cartEntityId) {
        HANDLER_CART.entrySet().removeIf(e -> e.getValue().equals(cartEntityId));
        RIDER_INPUT_DIRECTION.remove(cartEntityId);
        LOGGER.atInfo().log("[MinecartPacketInterceptor] Cart %d handler association cleared", cartEntityId);
    }

    /**
     * Get the PacketHandler for a cart's rider (for sending ChangeVelocity packets).
     * Returns null if no handler is associated with this cart.
     */
    public static PacketHandler getHandlerForCart(int cartEntityId) {
        for (var entry : HANDLER_CART.entrySet()) {
            if (entry.getValue().equals(cartEntityId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ========== INPUT API FOR PHYSICS SYSTEM ==========

    /**
     * Get and consume the rider input direction for a cart.
     * @return 1 for forward, -1 for backward, 0 for no input
     */
    public static int consumeRiderInput(int cartEntityId) {
        Integer dir = RIDER_INPUT_DIRECTION.remove(cartEntityId);
        return dir != null ? dir : 0;
    }

    /**
     * Get simple momentum direction without consuming.
     */
    public static int getMomentumDirection(int minecartNetworkId) {
        Integer dir = RIDER_INPUT_DIRECTION.get(minecartNetworkId);
        return dir != null ? dir : 0;
    }

    /**
     * Clear momentum for a minecart.
     */
    public static void clearMomentum(int minecartNetworkId) {
        RIDER_INPUT_DIRECTION.remove(minecartNetworkId);
    }

    // Legacy API compatibility
    public static MomentumData consumeMomentumInput(int minecartNetworkId) {
        Integer dir = RIDER_INPUT_DIRECTION.remove(minecartNetworkId);
        if (dir == null) return null;
        MomentumData data = new MomentumData();
        data.addMomentum(dir);
        return data;
    }

    public static class MomentumData {
        private int accumulatedMomentum = 0;
        private long firstInputTime = 0;
        private long lastInputTime = 0;

        public void addMomentum(int direction) {
            long now = System.currentTimeMillis();
            if (firstInputTime == 0) firstInputTime = now;
            lastInputTime = now;
            accumulatedMomentum += direction;
        }

        public int getDirection() {
            if (accumulatedMomentum > 0) return 1;
            if (accumulatedMomentum < 0) return -1;
            return 0;
        }

        public int getAccumulatedMomentum() { return accumulatedMomentum; }
        public long getHoldDuration() {
            return firstInputTime == 0 ? 0 : lastInputTime - firstInputTime;
        }
        public float getAccelerationMultiplier(long maxHoldTime, float maxMultiplier) {
            long holdTime = getHoldDuration();
            if (holdTime <= 0) return 1.0f;
            float t = Math.min(1.0f, (float) holdTime / maxHoldTime);
            return 1.0f + (maxMultiplier - 1.0f) * t;
        }
    }
}
