package com.usefulminecarts;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System that handles chest minecart interactions.
 * When a player presses F while crouching and looking at a chest minecart, opens the chest UI.
 */
public class ChestCartInteractionSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    private final Query<EntityStore> query;

    // Track recent F presses per player (timestamp of last F press)
    private final Map<UUID, Long> lastUsePressTime = new ConcurrentHashMap<>();

    // Track cooldowns to prevent spam
    private final Map<UUID, Long> lastOpenTime = new ConcurrentHashMap<>();
    private static final long OPEN_COOLDOWN_MS = 500;
    private static final long USE_PRESS_WINDOW_MS = 200; // How recent an F press must be

    public ChestCartInteractionSystem() {
        this.query = Query.and(Player.getComponentType());
    }

    /**
     * Called from event listener when player presses F (Use)
     */
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Track F (Use) key presses
        if (event.getActionType() == InteractionType.Use) {
            Player player = event.getPlayer();
            if (player != null) {
                UUID playerUuid = player.getUuid();
                lastUsePressTime.put(playerUuid, System.currentTimeMillis());
                LOGGER.atInfo().log("Player %s pressed Use (F)", playerUuid);
            }
        }
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        final Holder<EntityStore> holder = EntityUtils.toHolder(index, archetypeChunk);
        final Player player = holder.getComponent(Player.getComponentType());
        final PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());

        if (player == null || playerRef == null) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();

        // Get movement states to check if crouching
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        MovementStatesComponent moveComp = store.getComponent(ref, MovementStatesComponent.getComponentType());
        if (moveComp == null) {
            return;
        }

        MovementStates states = moveComp.getMovementStates();

        // Must be crouching
        if (!states.crouching) {
            return;
        }

        // Check if player recently pressed F (Use)
        Long lastUseTime = lastUsePressTime.get(playerUuid);
        long now = System.currentTimeMillis();
        if (lastUseTime == null || (now - lastUseTime) > USE_PRESS_WINDOW_MS) {
            return; // No recent F press
        }

        // Clear the use press so we don't trigger multiple times
        lastUsePressTime.remove(playerUuid);

        // Check cooldown
        Long lastOpen = lastOpenTime.get(playerUuid);
        if (lastOpen != null && (now - lastOpen) < OPEN_COOLDOWN_MS) {
            return;
        }

        // Check if player is looking at an entity
        Ref<EntityStore> targetEntity = TargetUtil.getTargetEntity(ref, commandBuffer);
        if (targetEntity == null || !targetEntity.isValid()) {
            return;
        }

        // Check if the entity has a model (minecarts have models)
        ModelComponent modelComp = commandBuffer.getComponent(targetEntity, ModelComponent.getComponentType());
        if (modelComp == null) {
            return;
        }

        // Update cooldown
        lastOpenTime.put(playerUuid, now);

        LOGGER.atInfo().log("Player %s opening chest minecart (F + crouch)!", playerUuid);

        // Get or create the inventory for this minecart
        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp =
            store.getComponent(targetEntity, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        UUID cartUuid = uuidComp != null ? uuidComp.getUuid() : UUID.randomUUID();

        // Get the SimpleItemContainer for this cart
        SimpleItemContainer inventory = ChestMinecartStorage.getOrCreateInventory(cartUuid);

        // Create the ContainerWindow
        ContainerWindow window = new ContainerWindow(inventory);

        // Use setPageWithWindows to open the inventory page with our container window
        boolean success = player.getPageManager().setPageWithWindows(ref, store, Page.Inventory, true, window);
        LOGGER.atInfo().log("Container opened with Page.Inventory, success: %s", success);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
