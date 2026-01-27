package com.usefulminecarts;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.hypixel.hytale.server.npc.NPCPlugin;
import it.unimi.dsi.fastutil.Pair;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Vector;

/**
 * Interaction that places a tied chicken on a rail when using a Capture Crate.
 * Extends SimpleBlockInteraction (same base as SpawnMinecartInteraction) so it
 * receives the target block position on left-click.
 *
 * Checks if the target block is a rail, logs all crate metadata,
 * and spawns a chicken entity on the rail.
 */
public class TiedChickenPlaceSystem extends SimpleBlockInteraction {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<TiedChickenPlaceSystem> CODEC =
        BuilderCodec.builder(TiedChickenPlaceSystem.class, TiedChickenPlaceSystem::new, SimpleBlockInteraction.CODEC).build();

    public TiedChickenPlaceSystem() {
        super();
    }

    @Override
    protected void interactWithBlock(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> buffer,
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext context,
            @Nonnull ItemStack heldItem,
            @Nonnull Vector3i targetPos,
            @Nonnull CooldownHandler cooldown
    ) {
        // Check if the target block is a rail
        BlockType blockType = world.getBlockType(targetPos.x, targetPos.y, targetPos.z);
        if (blockType == null) {
            LOGGER.atInfo().log("[TiedChicken] No block type at (%d, %d, %d)", targetPos.x, targetPos.y, targetPos.z);
            return;
        }

        String blockId = blockType.getId();
        if (!blockId.contains("Rail")) {
            LOGGER.atInfo().log("[TiedChicken] Block '%s' is not a rail, skipping", blockId);
            return;
        }

        // === LOG ALL CRATE METADATA ===
        String itemId = heldItem.getItemId();
        LOGGER.atInfo().log("[TiedChicken] === CAPTURE CRATE METADATA ===");
        LOGGER.atInfo().log("[TiedChicken] ItemId: %s", itemId);
        LOGGER.atInfo().log("[TiedChicken] Quantity: %d", heldItem.getQuantity());
        LOGGER.atInfo().log("[TiedChicken] Durability: %.2f / %.2f", heldItem.getDurability(), heldItem.getMaxDurability());
        LOGGER.atInfo().log("[TiedChicken] IsBroken: %b, IsUnbreakable: %b", heldItem.isBroken(), heldItem.isUnbreakable());
        LOGGER.atInfo().log("[TiedChicken] BlockKey: %s", heldItem.getBlockKey());

        BsonDocument metadata = heldItem.getMetadata();
        if (metadata != null) {
            LOGGER.atInfo().log("[TiedChicken] Metadata: %s", metadata.toJson());
        } else {
            LOGGER.atInfo().log("[TiedChicken] Metadata: null");
        }

        try {
            LOGGER.atInfo().log("[TiedChicken] Item toString: %s", heldItem.toString());
        } catch (Exception e) {
            LOGGER.atInfo().log("[TiedChicken] Could not toString item: %s", e.getMessage());
        }

        LOGGER.atInfo().log("[TiedChicken] === END METADATA ===");
        LOGGER.atInfo().log("[TiedChicken] Target rail '%s' at (%d, %d, %d)", blockId, targetPos.x, targetPos.y, targetPos.z);

        // Check if the crate has a captured chicken
        boolean hasCapturedEntity = metadata != null && metadata.containsKey("CapturedEntity");
        if (!hasCapturedEntity) {
            LOGGER.atInfo().log("[TiedChicken] Crate is empty (no CapturedEntity metadata), skipping");
            return;
        }

        // Spawn a chicken entity at the rail position
        try {
            // Position: center of block + small Y offset to sit on the rail
            Vector3d spawnPos = targetPos.toVector3d();
            spawnPos.add(0.5, 0.5, 0.5);

            Vector3f rotation = new Vector3f();

            // Create new entity holder (same pattern as SpawnMinecartInteraction)
            Holder<EntityStore> chickenHolder = EntityStore.REGISTRY.newHolder();

            // Add transform
            chickenHolder.addComponent(
                TransformComponent.getComponentType(),
                new TransformComponent(spawnPos, rotation)
            );

            // Add UUID
            chickenHolder.ensureComponent(UUIDComponent.getComponentType());

            // Try to use the Chicken_Tied model
            try {
                ModelAsset modelAsset = (ModelAsset) ModelAsset.getAssetMap().getAsset("Chicken_Tied");
                if (modelAsset == null) {
                    LOGGER.atWarning().log("[TiedChicken] Could not find 'Chicken_Tied' model asset, trying fallback...");
                    modelAsset = (ModelAsset) ModelAsset.getAssetMap().getAsset("Chicken");
                }
                if (modelAsset == null) {
                    modelAsset = ModelAsset.DEBUG;
                    LOGGER.atWarning().log("[TiedChicken] Using DEBUG model as fallback");
                }

                Model model = Model.createScaledModel(modelAsset, 0.7f);

                chickenHolder.addComponent(
                    PersistentModel.getComponentType(),
                    new PersistentModel(model.toReference())
                );
                chickenHolder.addComponent(
                    ModelComponent.getComponentType(),
                    new ModelComponent(model)
                );
                chickenHolder.addComponent(
                    BoundingBox.getComponentType(),
                    new BoundingBox(model.getBoundingBox())
                );

                LOGGER.atInfo().log("[TiedChicken] Created entity with model");
            } catch (Exception e) {
                LOGGER.atSevere().log("[TiedChicken] Failed to set up model: %s", e.getMessage());
                e.printStackTrace();
            }

            world.execute(() -> {
                Store<EntityStore> entityStore = world.getEntityStore().getStore();
                NPCPlugin npcPlugin = NPCPlugin.get();
                Pair<Ref<EntityStore>, INonPlayerCharacter> result = npcPlugin.spawnNPC(
                        entityStore,
                        "Chicken_Tied",
                        null,
                        spawnPos,
                        rotation
                );
                if (result == null) {
                    LOGGER.atSevere().log("[TiedChicken] NPCPlugin.spawnNPC returned null");
                    return;
                }else{
                    // Clear metadata to make the crate empty, then put it back
                    try {
                        ItemStack newItem = heldItem.withMetadata(null);
                        context.setHeldItem(newItem);

                        LOGGER.atInfo().log("[TiedChicken] Cleared crate metadata (now empty crate)");
                    } catch (Exception e) {
                        LOGGER.atWarning().log("[TiedChicken] Could not clear crate metadata: %s", e.getMessage());
                    }
                    LOGGER.atInfo().log("[TiedChicken] Spawned tied chicken NPC successfully");
                }
            });

        } catch (Exception e) {
            LOGGER.atSevere().log("[TiedChicken] Failed to spawn chicken: %s", e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected void simulateInteractWithBlock(@Nonnull InteractionType interactionType, @Nonnull InteractionContext interactionContext, @Nullable ItemStack itemStack, @Nonnull World world, @Nonnull Vector3i vector3i) {
        // No simulation needed for this interaction
    }
}
