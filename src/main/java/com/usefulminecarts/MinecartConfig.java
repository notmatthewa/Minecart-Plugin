package com.usefulminecarts;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuration for minecart physics.
 * Values can be changed via chat commands.
 */
public class MinecartConfig {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String CONFIG_DIR = "mods/UsefulMinecarts/Data";
    private static final String CONFIG_FILE = "physics_config.properties";

    // Physics parameters with defaults
    private static double maxSpeed = 12.0;           // Maximum speed (blocks/s) - increased from 8.0
    private static double acceleration = 8.0;        // Gravity/slope acceleration (blocks/s²) - increased from 4.0
    private static double friction = 0.985;          // Friction multiplier per tick - reduced friction
    private static double cornerFriction = 0.95;     // Extra friction when turning corners
    private static double minSpeed = 0.005;          // Minimum speed before stopping
    private static double slopeBoost = 1.5;          // Multiplier for downhill acceleration
    private static double uphillDrag = 0.7;          // Extra drag when going uphill
    private static double initialPush = 0.3;         // Initial velocity when cart starts on slope
    private static double rotationSmoothing = 0.15;  // How quickly cart rotates to match direction (0-1)

    // Getters
    public static double getMaxSpeed() { return maxSpeed; }
    public static double getAcceleration() { return acceleration; }
    public static double getFriction() { return friction; }
    public static double getCornerFriction() { return cornerFriction; }
    public static double getMinSpeed() { return minSpeed; }
    public static double getSlopeBoost() { return slopeBoost; }
    public static double getUphillDrag() { return uphillDrag; }
    public static double getInitialPush() { return initialPush; }
    public static double getRotationSmoothing() { return rotationSmoothing; }

    // Setters with validation
    public static boolean setMaxSpeed(double value) {
        if (value < 0.1 || value > 50.0) return false;
        maxSpeed = value;
        save();
        return true;
    }

    public static boolean setAcceleration(double value) {
        if (value < 0.1 || value > 50.0) return false;
        acceleration = value;
        save();
        return true;
    }

    public static boolean setFriction(double value) {
        if (value < 0.5 || value > 1.0) return false;
        friction = value;
        save();
        return true;
    }

    public static boolean setCornerFriction(double value) {
        if (value < 0.5 || value > 1.0) return false;
        cornerFriction = value;
        save();
        return true;
    }

    public static boolean setMinSpeed(double value) {
        if (value < 0.0001 || value > 0.1) return false;
        minSpeed = value;
        save();
        return true;
    }

    public static boolean setSlopeBoost(double value) {
        if (value < 0.1 || value > 5.0) return false;
        slopeBoost = value;
        save();
        return true;
    }

    public static boolean setUphillDrag(double value) {
        if (value < 0.1 || value > 1.0) return false;
        uphillDrag = value;
        save();
        return true;
    }

    public static boolean setInitialPush(double value) {
        if (value < 0.01 || value > 2.0) return false;
        initialPush = value;
        save();
        return true;
    }

    public static boolean setRotationSmoothing(double value) {
        if (value < 0.01 || value > 1.0) return false;
        rotationSmoothing = value;
        save();
        return true;
    }

    /**
     * Reset all values to defaults.
     */
    public static void resetToDefaults() {
        maxSpeed = 12.0;
        acceleration = 8.0;
        friction = 0.985;
        cornerFriction = 0.95;
        minSpeed = 0.005;
        slopeBoost = 1.5;
        uphillDrag = 0.7;
        initialPush = 0.3;
        rotationSmoothing = 0.15;
        save();
    }

    /**
     * Get all current values as a formatted string.
     */
    public static String getStatus() {
        return String.format(
            "Minecart Physics Config:\n" +
            "  maxSpeed: %.2f blocks/s\n" +
            "  acceleration: %.2f blocks/s²\n" +
            "  friction: %.4f\n" +
            "  cornerFriction: %.4f\n" +
            "  minSpeed: %.5f\n" +
            "  slopeBoost: %.2f\n" +
            "  uphillDrag: %.2f\n" +
            "  initialPush: %.2f\n" +
            "  rotationSmoothing: %.2f",
            maxSpeed, acceleration, friction, cornerFriction,
            minSpeed, slopeBoost, uphillDrag, initialPush, rotationSmoothing
        );
    }

    /**
     * Load configuration from file.
     */
    public static void load() {
        Path configPath = Paths.get(CONFIG_DIR, CONFIG_FILE);
        if (!Files.exists(configPath)) {
            LOGGER.atInfo().log("[MinecartConfig] No config file found, using defaults");
            return;
        }

        try (InputStream in = Files.newInputStream(configPath)) {
            Properties props = new Properties();
            props.load(in);

            maxSpeed = Double.parseDouble(props.getProperty("maxSpeed", "12.0"));
            acceleration = Double.parseDouble(props.getProperty("acceleration", "8.0"));
            friction = Double.parseDouble(props.getProperty("friction", "0.985"));
            cornerFriction = Double.parseDouble(props.getProperty("cornerFriction", "0.95"));
            minSpeed = Double.parseDouble(props.getProperty("minSpeed", "0.005"));
            slopeBoost = Double.parseDouble(props.getProperty("slopeBoost", "1.5"));
            uphillDrag = Double.parseDouble(props.getProperty("uphillDrag", "0.7"));
            initialPush = Double.parseDouble(props.getProperty("initialPush", "0.3"));
            rotationSmoothing = Double.parseDouble(props.getProperty("rotationSmoothing", "0.15"));

            LOGGER.atInfo().log("[MinecartConfig] Loaded config from file");
        } catch (Exception e) {
            LOGGER.atWarning().log("[MinecartConfig] Failed to load config: %s", e.getMessage());
        }
    }

    /**
     * Save configuration to file.
     */
    public static void save() {
        try {
            Path dirPath = Paths.get(CONFIG_DIR);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }

            Path configPath = dirPath.resolve(CONFIG_FILE);
            Properties props = new Properties();
            props.setProperty("maxSpeed", String.valueOf(maxSpeed));
            props.setProperty("acceleration", String.valueOf(acceleration));
            props.setProperty("friction", String.valueOf(friction));
            props.setProperty("cornerFriction", String.valueOf(cornerFriction));
            props.setProperty("minSpeed", String.valueOf(minSpeed));
            props.setProperty("slopeBoost", String.valueOf(slopeBoost));
            props.setProperty("uphillDrag", String.valueOf(uphillDrag));
            props.setProperty("initialPush", String.valueOf(initialPush));
            props.setProperty("rotationSmoothing", String.valueOf(rotationSmoothing));

            try (OutputStream out = Files.newOutputStream(configPath)) {
                props.store(out, "UsefulMinecarts Physics Configuration");
            }

            LOGGER.atInfo().log("[MinecartConfig] Saved config to file");
        } catch (Exception e) {
            LOGGER.atWarning().log("[MinecartConfig] Failed to save config: %s", e.getMessage());
        }
    }
}
