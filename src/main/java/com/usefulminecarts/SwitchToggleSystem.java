package com.usefulminecarts;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * System that handles track switch toggling via interaction (F key).
 * When a player interacts with a switch block, it toggles between straight and left states.
 */
public class SwitchToggleSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Post> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Block IDs for the switch states
    private static final String SWITCH_STRAIGHT_ID = "UsefulMinecarts_Rail_Switch";
    private static final String SWITCH_LEFT_ID = "*UsefulMinecarts_Rail_Switch_State_Definitions_Left";

    public SwitchToggleSystem() {
        super(UseBlockEvent.Post.class);
    }

    @Override
    public void handle(
        int index,
        ArchetypeChunk<EntityStore> chunk,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> buffer,
        UseBlockEvent.Post event
    ) {
        BlockType blockType = event.getBlockType();
        if (blockType == null) return;

        String blockId = blockType.getId();

        // Check if this is one of our switch blocks
        if (!blockId.contains("Rail_Switch")) {
            return;
        }

        // Get player info
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        Vector3i pos = event.getTargetBlock();
        World world = player.getWorld();
        if (world == null) return;

        // Get current rotation to preserve it
        int rotationIndex = world.getBlockRotationIndex(pos.x, pos.y, pos.z);

        // Determine current state and toggle
        boolean isCurrentlyLeft = blockId.contains("_Left") || blockId.contains("State_Definitions_Left");

        // Determine target block ID
        String newBlockId;
        String targetState;
        if (isCurrentlyLeft) {
            // Switch to straight
            targetState = "Straight";
            newBlockId = SWITCH_STRAIGHT_ID;
        } else {
            // Switch to left
            targetState = "Left";
            newBlockId = SWITCH_LEFT_ID;
        }

        // Set the new block with the same rotation
        world.setBlock(pos.x, pos.y, pos.z, newBlockId, rotationIndex);
        LOGGER.atInfo().log("[SwitchToggle] Toggled switch at (%d, %d, %d) to %s",
            pos.x, pos.y, pos.z, targetState);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }
}
