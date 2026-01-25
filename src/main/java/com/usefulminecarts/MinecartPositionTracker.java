package com.usefulminecarts;

import com.hypixel.hytale.builtin.mounts.minecart.MinecartComponent;
import com.hypixel.hytale.builtin.mounts.MountSystems;
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
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks minecart positions BEFORE HandleMountInput runs.
 * This captures the correct position before the mount bug teleports the cart.
 */
public class MinecartPositionTracker extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Shared position storage - keyed by NetworkId
    static final Map<Integer, Vector3d> trackedPositions = new ConcurrentHashMap<>();

    // Run BEFORE HandleMountInput with highest priority
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
        new SystemDependency<>(Order.BEFORE, MountSystems.HandleMountInput.class, OrderPriority.CLOSEST)
    );

    @Nonnull
    private final Query<EntityStore> query;

    public MinecartPositionTracker() {
        this.query = Query.and(MinecartComponent.getComponentType());
        LOGGER.atInfo().log("[MinecartPositionTracker] Initialized - tracking positions BEFORE mount input");
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
        Ref<EntityStore> minecartRef = archetypeChunk.getReferenceTo(index);

        // Get NetworkId for stable identification
        NetworkId networkId = store.getComponent(minecartRef, NetworkId.getComponentType());
        if (networkId == null) {
            return;
        }
        int entityId = networkId.getId();

        // Get current position
        TransformComponent transform = store.getComponent(minecartRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }

        // Always store current position (before HandleMountInput can mess with it)
        Vector3d pos = transform.getPosition();
        trackedPositions.put(entityId, new Vector3d(pos.x, pos.y, pos.z));
    }
}
