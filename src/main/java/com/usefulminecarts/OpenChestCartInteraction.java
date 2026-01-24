package com.usefulminecarts;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Custom interaction that opens the chest cart inventory.
 * Called from JSON when player presses F while crouching and looking at the cart.
 */
public class OpenChestCartInteraction extends SimpleInstantInteraction {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<OpenChestCartInteraction> CODEC =
        BuilderCodec.builder(OpenChestCartInteraction.class, OpenChestCartInteraction::new, SimpleInstantInteraction.CODEC).build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldown) {
        final CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        if (buffer == null) {
            return;
        }

        final Ref<EntityStore> playerRef = context.getEntity();
        final Store<EntityStore> store = buffer.getStore();

        // Get the player component
        Player player = store.getComponent(playerRef, Player.getComponentType());
        if (player == null) {
            LOGGER.atInfo().log("OpenChestCartInteraction: No player found");
            return;
        }

        // Get the target entity (the minecart we're interacting with)
        Ref<EntityStore> targetEntity = context.getTargetEntity();
        if (targetEntity == null || !targetEntity.isValid()) {
            LOGGER.atInfo().log("OpenChestCartInteraction: No target entity");
            return;
        }

        // Get UUID of the minecart for inventory storage
        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp =
            store.getComponent(targetEntity, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        UUID cartUuid = uuidComp != null ? uuidComp.getUuid() : UUID.randomUUID();

        LOGGER.atInfo().log("OpenChestCartInteraction: Opening chest for cart %s", cartUuid);

        // Get or create the inventory for this minecart
        SimpleItemContainer inventory = ChestMinecartStorage.getOrCreateInventory(cartUuid);

        // Create the ContainerWindow
        ContainerWindow window = new ContainerWindow(inventory);

        // Open the inventory page with our container window
        boolean success = player.getPageManager().setPageWithWindows(playerRef, store, Page.Inventory, true, window);
        LOGGER.atInfo().log("OpenChestCartInteraction: Container opened, success: %s", success);
    }
}
