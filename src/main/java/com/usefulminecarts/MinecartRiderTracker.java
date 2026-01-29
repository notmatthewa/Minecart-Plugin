package com.usefulminecarts;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which players are riding which minecarts.
 *
 * This is separate from the vanilla MountedByComponent because we need to
 * know the rider for our physics calculations, even when vanilla's mount
 * system is handling the visual attachment.
 */
public class MinecartRiderTracker {

    // Map of minecart entity ID -> rider entity ref
    private static final Map<Integer, Ref<EntityStore>> ridersByCart = new ConcurrentHashMap<>();

    /**
     * Set the rider for a minecart.
     */
    public static void setRider(int cartEntityId, Ref<EntityStore> riderRef) {
        ridersByCart.put(cartEntityId, riderRef);
    }

    /**
     * Get the rider for a minecart, or null if no rider.
     */
    public static Ref<EntityStore> getRider(int cartEntityId) {
        return ridersByCart.get(cartEntityId);
    }

    /**
     * Remove the rider tracking for a minecart.
     */
    public static void removeRider(int cartEntityId) {
        ridersByCart.remove(cartEntityId);
    }

    /**
     * Check if a minecart has a rider tracked.
     */
    public static boolean hasRider(int cartEntityId) {
        Ref<EntityStore> rider = ridersByCart.get(cartEntityId);
        return rider != null && rider.isValid();
    }

    /**
     * Clear all rider tracking (for cleanup on server shutdown).
     */
    public static void clear() {
        ridersByCart.clear();
    }

    /**
     * Get all cart entity IDs that have riders tracked.
     * Used for cleanup on shutdown.
     */
    public static java.util.Set<Integer> getAllCartIds() {
        return new java.util.HashSet<>(ridersByCart.keySet());
    }

    /**
     * Get the underlying map for iteration during cleanup.
     * Returns a snapshot copy to avoid concurrent modification.
     */
    public static Map<Integer, Ref<EntityStore>> getAllRiders() {
        return new ConcurrentHashMap<>(ridersByCart);
    }
}
