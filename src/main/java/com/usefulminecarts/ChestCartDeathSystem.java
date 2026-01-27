package com.usefulminecarts;

import com.hypixel.hytale.builtin.mounts.minecart.MinecartComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * System that drops chest minecart inventory items when the cart is destroyed.
 *
 * This system hooks into the damage event system and monitors when a minecart
 * is about to be destroyed (after receiving 3 hits). When destruction occurs,
 * it drops all inventory items at the cart's position.
 *
 * Based on the vanilla MountSystems$OnMinecartHit implementation:
 * - Minecarts are destroyed after 3 hits (within 10 second window)
 * - Hit counter resets if 10 seconds pass between hits
 */
public class ChestCartDeathSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Same constants as vanilla OnMinecartHit
    private static final int NUMBER_OF_HITS_TO_DESTROY = 3;
    private static final Duration HIT_RESET_TIME = Duration.ofSeconds(10);

    // Query for minecart entities
    private static final Query<EntityStore> QUERY = Archetype.of(
        MinecartComponent.getComponentType(),
        TransformComponent.getComponentType()
    );

    // Run BEFORE the vanilla OnMinecartHit system so we can drop items before the cart is removed
    // Vanilla OnMinecartHit uses: AFTER Gather, AFTER Filter, BEFORE Inspect
    // We use: AFTER Gather, BEFORE Filter - so we run before vanilla
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
        new SystemGroupDependency<>(Order.AFTER, DamageModule.get().getGatherDamageGroup()),
        new SystemGroupDependency<>(Order.BEFORE, DamageModule.get().getFilterDamageGroup())
    );

    public ChestCartDeathSystem() {
        LOGGER.atInfo().log("[ChestCartDeath] Chest cart damage monitoring system initialized!");
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage
    ) {
        // Get minecart component
        MinecartComponent minecart = (MinecartComponent) archetypeChunk.getComponent(
            index, MinecartComponent.getComponentType()
        );
        if (minecart == null) return;

        // Skip if no damage
        if (damage.getAmount() <= 0) return;

        // Get current time for reset check
        TimeResource timeResource = (TimeResource) commandBuffer.getResource(TimeResource.getResourceType());
        Instant now = timeResource.getNow();

        // Calculate effective hit count (matching vanilla logic)
        // We run BEFORE vanilla, so we need to predict what will happen
        int currentHits = minecart.getNumberOfHits();
        Instant lastHit = minecart.getLastHit();

        // Check if hit counter would reset (10 seconds since last hit)
        if (lastHit != null && now.isAfter(lastHit.plus(HIT_RESET_TIME))) {
            currentHits = 0;
        }

        // Predict: will this damage cause destruction?
        // Vanilla will increment hits, then check if == 3
        // So we check if current + 1 >= 3
        if (currentHits + 1 >= NUMBER_OF_HITS_TO_DESTROY) {
            // Cart is about to be destroyed - drop inventory!
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            if (ref == null) return;

            // Get UUID for inventory lookup
            UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
            if (uuidComp == null) return;
            UUID cartUuid = uuidComp.getUuid();

            // Get position for item drops
            TransformComponent transform = (TransformComponent) archetypeChunk.getComponent(
                index, TransformComponent.getComponentType()
            );
            if (transform == null) return;
            Vector3d position = transform.getPosition();

            LOGGER.atInfo().log("[ChestCartDeath] Cart %s is being destroyed! Dropping items...", cartUuid);

            // Drop inventory items (for chest carts)
            dropCartInventory(cartUuid, position, transform.getRotation(), store, commandBuffer);

            // Drop the cart itself as an item
            dropCartItem(minecart, position, transform.getRotation(), store, commandBuffer);
        }
    }

    /**
     * Drop the cart itself as an item pickup.
     * Uses the item ID stored in MinecartComponent (set when the cart was spawned).
     */
    private void dropCartItem(
            MinecartComponent minecart,
            Vector3d position,
            Vector3f rotation,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        try {
            String itemId = minecart.getSourceItem();
            if (itemId == null || itemId.isEmpty()) {
                LOGGER.atWarning().log("[ChestCartDeath] Cart has no source item ID, cannot drop cart item");
                return;
            }

            ItemStack cartItem = new ItemStack(itemId, 1);
            Holder<EntityStore> itemHolder = ItemComponent.generateItemDrop(
                store,
                cartItem,
                position.clone(),
                rotation.clone(),
                0.0f,
                1.0f,
                0.0f
            );

            if (itemHolder != null) {
                commandBuffer.addEntity(itemHolder, AddReason.SPAWN);
                LOGGER.atInfo().log("[ChestCartDeath] Dropped cart item '%s' at (%.1f, %.1f, %.1f)",
                    itemId, position.x, position.y, position.z);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("[ChestCartDeath] Failed to drop cart item: %s", e.getMessage());
        }
    }

    /**
     * Drop all items from a chest cart's inventory at the given position.
     */
    private void dropCartInventory(
            UUID cartUuid,
            Vector3d position,
            Vector3f rotation,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        // Check if this cart has an inventory
        SimpleItemContainer inventory = ChestMinecartStorage.getInventoryIfExists(cartUuid);
        if (inventory == null) {
            LOGGER.atInfo().log("[ChestCartDeath] Cart %s has no inventory to drop", cartUuid);
            return;
        }

        // Collect and drop all non-empty items
        int droppedCount = 0;
        for (short slot = 0; slot < inventory.getCapacity(); slot++) {
            ItemStack stack = inventory.getItemStack(slot);
            if (stack != null && !stack.isEmpty()) {
                try {
                    // Spawn item drop using the same method as vanilla
                    // Parameters: store, itemStack, position, rotation, pickupDelay, scale, lifetime
                    Holder<EntityStore> itemHolder = ItemComponent.generateItemDrop(
                        store,
                        stack,
                        position.clone(),
                        rotation.clone(),
                        0.0f,    // pickupDelay
                        1.0f,    // scale
                        0.0f     // lifetime (0 = default)
                    );

                    if (itemHolder != null) {
                        commandBuffer.addEntity(itemHolder, AddReason.SPAWN);
                        droppedCount++;
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().log("[ChestCartDeath] Failed to drop item %s: %s",
                        stack.getItemId(), e.getMessage());
                }
            }
        }

        if (droppedCount > 0) {
            LOGGER.atInfo().log("[ChestCartDeath] Dropped %d item stacks from cart %s at (%.1f, %.1f, %.1f)",
                droppedCount, cartUuid, position.x, position.y, position.z);
        } else {
            LOGGER.atInfo().log("[ChestCartDeath] Cart %s inventory was empty", cartUuid);
        }

        // Clean up storage
        ChestMinecartStorage.removeInventory(cartUuid);
    }
}
