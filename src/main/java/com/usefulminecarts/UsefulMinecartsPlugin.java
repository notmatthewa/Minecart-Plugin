package com.usefulminecarts;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

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
        getLogger().atInfo().log("[UsefulMinecarts] Registered CustomMinecartMount and TiedChicken interactions");

        // Register minecart physics commands
        this.getCommandRegistry().registerCommand(new MinecartCommands());
        getLogger().atInfo().log("[MinecartCommands] Registered /minecart command (aliases: /mc, /cart)");

        // Initialize storage (just sets up directory, no loading)
        ChestMinecartStorage.init();

        // Register input interceptor (runs BEFORE HandleMountInput to clear movement queue)
        this.getEntityStoreRegistry().registerSystem(new MinecartInputInterceptor());

        // Register the physics system (runs BEFORE HandleMountInput, calculates positions)
        this.getEntityStoreRegistry().registerSystem(new MinecartPhysicsSystem());

        // Register the rail snap system (runs AFTER HandleMountInput to restore physics positions)
        // This restores the position calculated by MinecartPhysicsSystem after client overrides it
        this.getEntityStoreRegistry().registerSystem(new MinecartRailSnapSystem());

        // Register the rail debug system for /mc railinfo command
        this.getEntityStoreRegistry().registerSystem(new RailDebugSystem());

        // Register the chest cart death system for dropping items when cart is destroyed
        this.getEntityStoreRegistry().registerSystem(new ChestCartDeathSystem());

        // Register the custom minecart riding system (positions riders on carts)
        this.getEntityStoreRegistry().registerSystem(new CustomMinecartRidingSystem());

        getLogger().atInfo().log("UsefulMinecarts loaded - Chest Rail Cart ready!");
        getLogger().atInfo().log("Edit physics_config.properties to configure minecart physics");
    }

    @Override
    protected void start() {
        super.start();
        getLogger().atInfo().log("UsefulMinecarts started!");
    }

    @Override
    protected void shutdown() {
        // Save all inventories and config before shutdown
        getLogger().atInfo().log("UsefulMinecarts shutting down...");
        ChestMinecartStorage.saveToFile();
        MinecartConfig.save();
        super.shutdown();
    }
}
