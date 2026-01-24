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

        // Register our custom interaction type
        var interactions = this.getCodecRegistry(Interaction.CODEC);
        interactions.register("UsefulMinecartsOpenChestCart", OpenChestCartInteraction.class, OpenChestCartInteraction.CODEC);

        // Initialize storage (just sets up directory, no loading)
        ChestMinecartStorage.init();

        getLogger().atInfo().log("UsefulMinecarts loaded - Chest Rail Cart ready!");
    }

    @Override
    protected void start() {
        super.start();
        getLogger().atInfo().log("UsefulMinecarts started!");
    }

    @Override
    protected void shutdown() {
        // Save all inventories before shutdown
        getLogger().atInfo().log("UsefulMinecarts shutting down...");
        ChestMinecartStorage.saveToFile();
        super.shutdown();
    }
}
