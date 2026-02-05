package com.usefulminecarts;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.usefulminecarts.commands.MinecartCommandCollection;

import javax.annotation.Nonnull;

/**
 * UsefulMinecarts Plugin
 *
 * Adds the Chest Rail Cart - a minecart with built-in storage.
 * Press F while crouching and looking at the cart to open the chest.
 * Press F (not crouching) to ride.
 *
 * Physics Configuration:
 *   Edit mods/UsefulMinecarts/Data/physics_config.properties to configure:
 *   - maxSpeed: Maximum speed in blocks/s (default: 12.0)
 *   - acceleration: Slope gravity in blocks/s² (default: 8.0)
 *   - friction: Rolling friction per tick (default: 0.985)
 *   - cornerFriction: Extra friction when turning (default: 0.95)
 *   - slopeBoost: Downhill acceleration multiplier (default: 1.5)
 *   - uphillDrag: Uphill deceleration factor (default: 0.7)
 *   - initialPush: Starting velocity on slopes (default: 0.3)
 *   - rotationSmoothing: How fast cart rotates to face direction (default: 0.15)
 */
public class UsefulMinecartsPlugin extends JavaPlugin {

    public static UsefulMinecartsPlugin INSTANCE;

    /**
     * Flag to indicate the plugin is shutting down.
     * Systems should check this and skip processing to avoid race conditions
     * with the server's player removal sequence.
     */
    private static volatile boolean shuttingDown = false;

    private MountMovementPacketFilter mountMovementFilter;

    /**
     * Check if the plugin is shutting down.
     * Systems should skip processing if this returns true.
     */
    public static boolean isShuttingDown() {
        return shuttingDown;
    }

    public UsefulMinecartsPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        INSTANCE = this;
    }

    @Override
    protected void setup() {
        super.setup();

        // Load physics configuration from file
        MinecartConfig.load();

        // Register our custom rider component (for server-authoritative riding)
        var riderComponentType = this.getEntityStoreRegistry()
            .registerComponent(CustomMinecartRiderComponent.class, "CustomMinecartRider", CustomMinecartRiderComponent.CODEC);
        CustomMinecartRiderComponent.setComponentType(riderComponentType);
        getLogger().atInfo().log("[UsefulMinecarts] Registered CustomMinecartRiderComponent");

        // Register our custom interaction types
        var interactions = this.getCodecRegistry(Interaction.CODEC);
        interactions.register("UsefulMinecartsOpenChestCart", OpenChestCartInteraction.class, OpenChestCartInteraction.CODEC);
        interactions.register("UsefulMinecartsMount", MinecartMountInteraction.class, MinecartMountInteraction.CODEC);
        interactions.register("UsefulMinecartsCustomMount", CustomMinecartMount.class, CustomMinecartMount.CODEC);
        interactions.register("UsefulMinecartsTiedChicken", TiedChickenPlaceSystem.class, TiedChickenPlaceSystem.CODEC);
//        interactions.register("UsefulMinecartsRailWrench", RailWrenchInteraction.class, RailWrenchInteraction.CODEC);
        getLogger().atInfo().log("[UsefulMinecarts] Registered interactions: CustomMinecartMount, TiedChicken, RailWrench");

        // Register minecart physics commands
        this.getCommandRegistry().registerCommand(new MinecartCommandCollection());
        getLogger().atInfo().log("[MinecartCommands] Registered /mc command with subcommands: status, grav, debug, speed, friction, reset");

        // Initialize storage (just sets up directory, no loading)
        ChestMinecartStorage.init();

        // Register MountMovement packet filter BEFORE ECS systems
        // This blocks MountMovement packets for minecarts at the network level,
        // preventing the client from overriding server-calculated positions
        mountMovementFilter = new MountMovementPacketFilter();
        mountMovementFilter.register();

        // Register the input blocker FIRST - clears movement queue before HandleMountInput
        // can apply client-predicted positions to the cart entity
        this.getEntityStoreRegistry().registerSystem(new MinecartMountInputBlocker());

        // Register the physics system (calculates server-authoritative positions)
        this.getEntityStoreRegistry().registerSystem(new MinecartPhysicsSystem());

        // Register the rail debug system for /mc railinfo command
        this.getEntityStoreRegistry().registerSystem(new RailDebugSystem());

        // Register the chest cart death system for dropping items when cart is destroyed
        this.getEntityStoreRegistry().registerSystem(new ChestCartDeathSystem());

        // Register the custom minecart riding system (positions riders on carts)
        this.getEntityStoreRegistry().registerSystem(new CustomMinecartRidingSystem());

        // Register the rail path visualizer for debugging
        this.getEntityStoreRegistry().registerSystem(new RailPathVisualizer());

        getLogger().atInfo().log("UsefulMinecarts loaded!");
    }

    @Override
    protected void start() {
        super.start();
        getLogger().atInfo().log("UsefulMinecarts started!");
    }

    @Override
    protected void shutdown() {
        // Set shutdown flag FIRST to stop all systems from processing
        shuttingDown = true;
        getLogger().atInfo().log("UsefulMinecarts shutting down...");

        // Clear all rider tracking data to prevent systems from accessing stale references
        // This must happen before the server starts removing player entities
        getLogger().atInfo().log("[UsefulMinecarts] Clearing rider tracking data...");
        MinecartRiderTracker.clear();
        CustomMinecartRidingSystem.clearAllTracking();
        MinecartMountInputBlocker.clearAll();
        RailPathVisualizer.disableAll();

        if (mountMovementFilter != null) {
            mountMovementFilter.unregister();
        }
        ChestMinecartStorage.saveToFile();
        MinecartConfig.save();
        super.shutdown();
    }
}
