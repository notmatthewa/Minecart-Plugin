package com.usefulminecarts;

import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
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
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;

/**
 * Enforces server-authoritative minecart positions.
 *
 * Runs AFTER HandleMountInput and other mount systems, restoring minecart positions
 * that were calculated by MinecartPhysicsSystem. This ensures the server has full
 * control over cart position, regardless of client input.
 *
 * Works with BOTH vanilla MountedByComponent AND our custom NPC-style mounts
 * (tracked via MinecartRiderTracker).
 */
public class MinecartRailSnapSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Run AFTER HandleMountInput with FURTHEST priority to run as late as possible
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
        new SystemDependency<>(Order.AFTER, MountSystems.HandleMountInput.class, OrderPriority.FURTHEST)
    );

    @Nonnull
    private final Query<EntityStore> query;

    public MinecartRailSnapSystem() {
        // Query for ALL minecarts - we check for riders using both vanilla and custom systems
        this.query = Query.and(
            MinecartComponent.getComponentType()
        );
        LOGGER.atInfo().log("[MinecartRailSnap] Initialized - enforces server positions for ALL minecarts");
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

    private static int snapTickCounter = 0;

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        snapTickCounter++;
        boolean shouldLog = (snapTickCounter % 60 == 0);

        Ref<EntityStore> minecartRef = archetypeChunk.getReferenceTo(index);

        // Get NetworkId for lookup
        NetworkId networkId = store.getComponent(minecartRef, NetworkId.getComponentType());
        if (networkId == null) {
            return;
        }
        int entityId = networkId.getId();

        // Check if this cart has a rider (either vanilla or our custom system)
        boolean hasVanillaRider = false;
        boolean hasCustomRider = MinecartRiderTracker.hasRider(entityId);

        MountedByComponent mountedBy = store.getComponent(minecartRef, MountedByComponent.getComponentType());
        if (mountedBy != null) {
            List<Ref<EntityStore>> passengers = mountedBy.getPassengers();
            hasVanillaRider = passengers != null && !passengers.isEmpty();
        }

        // Only process carts with riders (either type)
        if (!hasVanillaRider && !hasCustomRider) {
            return;
        }

        if (shouldLog) {
            LOGGER.atInfo().log("[MinecartRailSnap] Processing cart %d (vanilla=%b, custom=%b)",
                entityId, hasVanillaRider, hasCustomRider);
        }

        // Get the position that was captured BEFORE HandleMountInput
        Vector3d savedPos = MinecartPositionTracker.trackedPositions.get(entityId);
        if (savedPos == null) {
            if (shouldLog) {
                LOGGER.atWarning().log("[MinecartRailSnap] No saved position for cart %d", entityId);
            }
            return;
        }

        // Get current transform (after HandleMountInput or NPC movement may have modified it)
        TransformComponent transform = commandBuffer.getComponent(minecartRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }

        Vector3d currentPos = transform.getPosition();

        // Always restore position to undo any client/vanilla movement
        // This ensures our physics system has full control
        double dx = currentPos.x - savedPos.x;
        double dy = currentPos.y - savedPos.y;
        double dz = currentPos.z - savedPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq > 0.0001) {  // Any change at all
            if (shouldLog || distSq > 0.5) {
                LOGGER.atInfo().log("[MinecartRailSnap] Cart %d moved! Restoring (%.2f,%.2f,%.2f) -> (%.2f,%.2f,%.2f), dist=%.2f",
                    entityId, currentPos.x, currentPos.y, currentPos.z, savedPos.x, savedPos.y, savedPos.z, Math.sqrt(distSq));
            }
            transform.getPosition().assign(savedPos);
        }

        // Reposition vanilla riders (MountedByComponent)
        if (hasVanillaRider && mountedBy != null) {
            List<Ref<EntityStore>> passengers = mountedBy.getPassengers();
            if (passengers != null) {
                for (Ref<EntityStore> riderRef : passengers) {
                    if (riderRef != null && riderRef.isValid()) {
                        // Get rider's MountedComponent for attachment offset
                        MountedComponent riderMount = store.getComponent(riderRef, MountedComponent.getComponentType());
                        Vector3f offset = new Vector3f(0, 1.0f, 0.3f); // Default offset
                        if (riderMount != null) {
                            offset = riderMount.getAttachmentOffset();
                        }

                        // Update rider position to match cart + offset
                        TransformComponent riderTransform = commandBuffer.getComponent(riderRef, TransformComponent.getComponentType());
                        if (riderTransform != null) {
                            riderTransform.getPosition().x = savedPos.x + offset.x;
                            riderTransform.getPosition().y = savedPos.y + offset.y;
                            riderTransform.getPosition().z = savedPos.z + offset.z;
                        }
                    }
                }
            }
        }

        // Reposition custom NPC-style rider (tracked in MinecartRiderTracker)
        if (hasCustomRider) {
            Ref<EntityStore> customRiderRef = MinecartRiderTracker.getRider(entityId);
            if (customRiderRef != null && customRiderRef.isValid()) {
                // Get offset from CustomMinecartRiderComponent
                CustomMinecartRiderComponent riderComp = store.getComponent(
                    customRiderRef, CustomMinecartRiderComponent.getComponentType()
                );
                Vector3f offset = new Vector3f(0, 1.0f, 0.3f); // Default offset
                if (riderComp != null) {
                    offset = riderComp.getAttachmentOffset();
                }

                // Update rider position to match cart + offset
                TransformComponent riderTransform = commandBuffer.getComponent(
                    customRiderRef, TransformComponent.getComponentType()
                );
                if (riderTransform != null) {
                    riderTransform.getPosition().x = savedPos.x + offset.x;
                    riderTransform.getPosition().y = savedPos.y + offset.y;
                    riderTransform.getPosition().z = savedPos.z + offset.z;
                    if (shouldLog) {
                        LOGGER.atInfo().log("[MinecartRailSnap] Positioned NPC-style rider at (%.2f,%.2f,%.2f)",
                            savedPos.x + offset.x, savedPos.y + offset.y, savedPos.z + offset.z);
                    }
                }
            }
        }
    }
}
