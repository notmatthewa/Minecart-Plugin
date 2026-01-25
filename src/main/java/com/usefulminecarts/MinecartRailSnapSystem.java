package com.usefulminecarts;

import com.hypixel.hytale.builtin.mounts.MountedByComponent;
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
import java.util.Set;

/**
 * Fixes the minecart teleport-to-player bug.
 *
 * Runs AFTER HandleMountInput and restores minecart positions that were
 * captured by MinecartPositionTracker (which runs BEFORE HandleMountInput).
 */
public class MinecartRailSnapSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Run AFTER HandleMountInput
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
        new SystemDependency<>(Order.AFTER, MountSystems.HandleMountInput.class, OrderPriority.CLOSEST)
    );

    @Nonnull
    private final Query<EntityStore> query;

    public MinecartRailSnapSystem() {
        // Query for mounted minecarts only
        this.query = Query.and(
            MinecartComponent.getComponentType(),
            MountedByComponent.getComponentType()
        );
        LOGGER.atInfo().log("[MinecartRailSnap] Initialized - will restore positions AFTER mount input");
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

        // Get NetworkId for lookup
        NetworkId networkId = store.getComponent(minecartRef, NetworkId.getComponentType());
        if (networkId == null) {
            return;
        }
        int entityId = networkId.getId();

        // Get the position that was captured BEFORE HandleMountInput
        Vector3d savedPos = MinecartPositionTracker.trackedPositions.get(entityId);
        if (savedPos == null) {
            return;
        }

        // Get current transform (after HandleMountInput may have modified it)
        TransformComponent transform = commandBuffer.getComponent(minecartRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }

        Vector3d currentPos = transform.getPosition();

        // Check if position changed significantly (more than 0.5 blocks = likely teleported)
        double dx = currentPos.x - savedPos.x;
        double dy = currentPos.y - savedPos.y;
        double dz = currentPos.z - savedPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq > 0.25) {  // 0.5 blocks squared
            // Position was changed - restore it
            LOGGER.atInfo().log("[MinecartRailSnap] Restoring position: (%.2f,%.2f,%.2f) -> (%.2f,%.2f,%.2f) dist=%.2f",
                currentPos.x, currentPos.y, currentPos.z,
                savedPos.x, savedPos.y, savedPos.z,
                Math.sqrt(distSq));
            transform.getPosition().assign(savedPos);
        }
    }
}
