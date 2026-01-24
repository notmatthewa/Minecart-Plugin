package com.usefulminecarts;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores inventory data for chest minecarts.
 * Persists to disk and loads lazily when chests are accessed.
 */
public class ChestMinecartStorage {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int DATA_VERSION = 1;

    public static final short INVENTORY_SIZE = 27;

    // Runtime storage
    private static final Map<UUID, SimpleItemContainer> inventories = new ConcurrentHashMap<>();

    // Saved data loaded from file (loaded once, lazily)
    private static Map<UUID, List<SavedItem>> savedData = null;
    private static Path storageDir = null;
    private static boolean initialized = false;

    /**
     * Initialize the storage directory.
     */
    public static void init() {
        if (initialized) return;

        Path serverRoot = Paths.get(".").toAbsolutePath().normalize();
        storageDir = serverRoot.resolve("mods").resolve("UsefulMinecarts").resolve("Data");

        LOGGER.atInfo().log("Storage directory: %s", storageDir.toString());

        try {
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
            }
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to create storage directory: %s", e.getMessage());
        }

        initialized = true;
    }

    /**
     * Get or create an inventory for a minecart.
     * Lazily loads from saved data if available.
     */
    public static SimpleItemContainer getOrCreateInventory(UUID minecartUuid) {
        return inventories.computeIfAbsent(minecartUuid, uuid -> {
            // Try to restore from saved data
            loadSavedDataIfNeeded();

            SimpleItemContainer container = new SimpleItemContainer(INVENTORY_SIZE);

            if (savedData != null) {
                List<SavedItem> items = savedData.get(uuid);
                if (items != null && !items.isEmpty()) {
                    LOGGER.atInfo().log("Restoring %d items for cart %s", items.size(), uuid);
                    for (SavedItem item : items) {
                        try {
                            ItemStack stack = new ItemStack(item.itemId, item.quantity);
                            container.addItemStack(stack);
                        } catch (Exception e) {
                            LOGGER.atWarning().log("Failed to restore item %s: %s", item.itemId, e.getMessage());
                        }
                    }
                }
            }

            return container;
        });
    }

    /**
     * Load saved data from file (only once).
     */
    private static void loadSavedDataIfNeeded() {
        if (savedData != null || storageDir == null) {
            return;
        }

        savedData = new HashMap<>();

        Path file = storageDir.resolve("inventories.dat");
        if (!Files.exists(file)) {
            LOGGER.atInfo().log("No save file found, starting fresh");
            return;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int version = in.readInt();
            if (version != DATA_VERSION) {
                LOGGER.atWarning().log("Unknown data version: %d", version);
                return;
            }

            int cartCount = in.readInt();
            LOGGER.atInfo().log("Loading %d cart inventories...", cartCount);

            for (int c = 0; c < cartCount; c++) {
                // Read cart UUID
                long uuidMost = in.readLong();
                long uuidLeast = in.readLong();
                UUID cartUuid = new UUID(uuidMost, uuidLeast);

                // Read items
                int itemCount = in.readInt();
                List<SavedItem> items = new ArrayList<>(itemCount);

                for (int i = 0; i < itemCount; i++) {
                    String itemId = in.readUTF();
                    int quantity = in.readInt();
                    items.add(new SavedItem(itemId, quantity));
                }

                savedData.put(cartUuid, items);
                LOGGER.atInfo().log("Loaded %d items for cart %s", itemCount, cartUuid);
            }

        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to load save file: %s", e.getMessage());
        }
    }

    /**
     * Save all inventories to file.
     */
    public static void saveToFile() {
        if (storageDir == null) {
            LOGGER.atWarning().log("Storage not initialized, cannot save");
            return;
        }

        Path file = storageDir.resolve("inventories.dat");

        // Collect data to save
        Map<UUID, List<SavedItem>> dataToSave = new HashMap<>();

        for (Map.Entry<UUID, SimpleItemContainer> entry : inventories.entrySet()) {
            SimpleItemContainer container = entry.getValue();
            List<SavedItem> items = new ArrayList<>();

            for (short i = 0; i < container.getCapacity(); i++) {
                ItemStack stack = container.getItemStack(i);
                if (stack != null && !stack.isEmpty()) {
                    items.add(new SavedItem(stack.getItemId(), stack.getQuantity()));
                }
            }

            if (!items.isEmpty()) {
                dataToSave.put(entry.getKey(), items);
            }
        }

        if (dataToSave.isEmpty()) {
            LOGGER.atInfo().log("No inventories to save");
            return;
        }

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(DATA_VERSION);
            out.writeInt(dataToSave.size());

            for (Map.Entry<UUID, List<SavedItem>> entry : dataToSave.entrySet()) {
                UUID uuid = entry.getKey();
                List<SavedItem> items = entry.getValue();

                // Write cart UUID
                out.writeLong(uuid.getMostSignificantBits());
                out.writeLong(uuid.getLeastSignificantBits());

                // Write items
                out.writeInt(items.size());
                for (SavedItem item : items) {
                    out.writeUTF(item.itemId);
                    out.writeInt(item.quantity);
                }
            }

            LOGGER.atInfo().log("Saved %d cart inventories", dataToSave.size());

        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to save: %s", e.getMessage());
        }
    }

    /**
     * Remove inventory when a minecart is destroyed.
     */
    public static void removeInventory(UUID minecartUuid) {
        inventories.remove(minecartUuid);
    }

    /**
     * Simple data class for saved items.
     */
    private static class SavedItem {
        final String itemId;
        final int quantity;

        SavedItem(String itemId, int quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }
    }
}
