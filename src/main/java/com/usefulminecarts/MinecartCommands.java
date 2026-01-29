package com.usefulminecarts;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.GameMode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Chat commands for configuring minecart physics.
 * Uses subcommand pattern like BetterMap.
 */
public class MinecartCommands extends AbstractCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public MinecartCommands() {
        super("minecart", "Configure minecart physics. Use /mc for help.");
        this.addAliases("mc", "cart");
        this.setPermissionGroup(GameMode.Adventure);

        // Register subcommands
        this.addSubCommand(new StatusCommand());
        this.addSubCommand(new ResetCommand());
        this.addSubCommand(new RailInfoCommand());
        this.addSubCommand(new MaxSpeedCommand());
        this.addSubCommand(new AccelerationCommand());
        this.addSubCommand(new FrictionCommand());
        this.addSubCommand(new CornerFrictionCommand());
        this.addSubCommand(new SlopeBoostCommand());
        this.addSubCommand(new UphillDragCommand());
        this.addSubCommand(new InitialPushCommand());
        this.addSubCommand(new RotationCommand());
        this.addSubCommand(new RiderVelCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        // No subcommand provided - show help
        context.sendMessage(Message.raw("=== Minecart Physics Commands ==="));
        context.sendMessage(Message.raw("/mc status - Show current config"));
        context.sendMessage(Message.raw("/mc reset - Reset to defaults"));
        context.sendMessage(Message.raw("/mc railinfo - Debug rail info"));
        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("Settings:"));
        context.sendMessage(Message.raw("/mc maxspeed [val] - Max speed (blocks/s)"));
        context.sendMessage(Message.raw("/mc acceleration [val] - Slope gravity"));
        context.sendMessage(Message.raw("/mc friction [val] - Rolling friction"));
        context.sendMessage(Message.raw("/mc cornerfriction [val] - Turn friction"));
        context.sendMessage(Message.raw("/mc slopeboost [val] - Downhill boost"));
        context.sendMessage(Message.raw("/mc uphilldrag [val] - Uphill resistance"));
        context.sendMessage(Message.raw("/mc initialpush [val] - Starting velocity"));
        context.sendMessage(Message.raw("/mc rotation [val] - Rotation smoothing"));
        context.sendMessage(Message.raw("/mc ridervel [val] - Rider gravity counter"));
        return CompletableFuture.completedFuture(null);
    }

    // ========== Subcommand Classes ==========

    public static class StatusCommand extends AbstractCommand {
        public StatusCommand() {
            super("status", "Show current minecart physics config");
            this.addAliases("info", "config");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            context.sendMessage(Message.raw(MinecartConfig.getStatus()));
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class ResetCommand extends AbstractCommand {
        public ResetCommand() {
            super("reset", "Reset minecart physics to defaults");
            this.addAliases("defaults");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            MinecartConfig.resetToDefaults();
            context.sendMessage(Message.raw("Minecart physics reset to defaults!"));
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class RailInfoCommand extends AbstractCommand {
        private static final HytaleLogger RAIL_LOGGER = HytaleLogger.forEnclosingClass();

        public RailInfoCommand() {
            super("railinfo", "Debug rail you're looking at");
            this.addAliases("rail", "debug");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            if (!(context.sender() instanceof Player player)) {
                RAIL_LOGGER.atInfo().log("[RailInfo] Command requires player context");
                return CompletableFuture.completedFuture(null);
            }

            // Run on the player's world thread to access components safely
            return CompletableFuture.runAsync(() -> {
                Ref<EntityStore> ref = player.getReference();
                if (ref == null || !ref.isValid()) {
                    RAIL_LOGGER.atInfo().log("[RailInfo] Could not get player reference");
                    return;
                }

                Store<EntityStore> store = ref.getStore();
                UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());

                if (uuidComp != null) {
                    UUID playerUuid = uuidComp.getUuid();
                    RailDebugSystem.requestDebug(playerUuid);
                    RAIL_LOGGER.atInfo().log("[RailInfo] Debug requested for player %s", playerUuid);
                } else {
                    RAIL_LOGGER.atInfo().log("[RailInfo] Could not get player UUID");
                }
            }, player.getWorld());
        }
    }

    public static class MaxSpeedCommand extends AbstractCommand {
        private final OptionalArg<String> valueArg;

        public MaxSpeedCommand() {
            super("maxspeed", "Set max speed (0.1-50 blocks/s)");
            this.addAliases("speed");
            this.valueArg = this.withOptionalArg("value", "speed value", ArgTypes.STRING);
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            String valueStr = this.valueArg.get(context);
            if (valueStr == null) {
                context.sendMessage(Message.raw("Current maxSpeed: " + MinecartConfig.getMaxSpeed() + " blocks/s"));
                context.sendMessage(Message.raw("Usage: /mc maxspeed <0.1-50>"));
            } else {
                try {
                    double value = Double.parseDouble(valueStr);
                    if (MinecartConfig.setMaxSpeed(value)) {
                        context.sendMessage(Message.raw("Max speed set to " + value + " blocks/s"));
                    } else {
                        context.sendMessage(Message.raw("Invalid value. Must be between 0.1 and 50."));
                    }
                } catch (NumberFormatException e) {
                    context.sendMessage(Message.raw("Invalid number: " + valueStr));
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class AccelerationCommand extends AbstractCommand {
        private final OptionalArg<String> valueArg;

        public AccelerationCommand() {
            super("acceleration", "Set acceleration (0.1-50 blocks/s^2)");
            this.addAliases("accel", "gravity");
            this.valueArg = this.withOptionalArg("value", "acceleration value", ArgTypes.STRING);
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            String valueStr = this.valueArg.get(context);
            if (valueStr == null) {
                context.sendMessage(Message.raw("Current acceleration: " + MinecartConfig.getAcceleration() + " blocks/s^2"));
                context.sendMessage(Message.raw("Usage: /mc acceleration <0.1-50>"));
            } else {
                try {
                    double value = Double.parseDouble(valueStr);
                    if (MinecartConfig.setAcceleration(value)) {
                        context.sendMessage(Message.raw("Acceleration set to " + value + " blocks/s^2"));
                    } else {
                        context.sendMessage(Message.raw("Invalid value. Must be between 0.1 and 50."));
                    }
                } catch (NumberFormatException e) {
                    context.sendMessage(Message.raw("Invalid number: " + valueStr));
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class FrictionCommand extends AbstractCommand {
        private final OptionalArg<String> valueArg;

        public FrictionCommand() {
            super("friction", "Set friction (0.5-1.0)");
            this.valueArg = this.withOptionalArg("value", "friction value", ArgTypes.STRING);
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            String valueStr = this.valueArg.get(context);
            if (valueStr == null) {
                context.sendMessage(Message.raw("Current friction: " + MinecartConfig.getFriction()));
                context.sendMessage(Message.raw("Usage: /mc friction <0.5-1.0>"));
            } else {
                try {
                    double value = Double.parseDouble(valueStr);
                    if (MinecartConfig.setFriction(value)) {
                        context.sendMessage(Message.raw("Friction set to " + value));
                    } else {
                        context.sendMessage(Message.raw("Invalid value. Must be between 0.5 and 1.0."));
                    }
                } catch (NumberFormatException e) {
                    context.sendMessage(Message.raw("Invalid number: " + valueStr));
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class CornerFrictionCommand extends AbstractCommand {
        private final OptionalArg<String> valueArg;

        public CornerFrictionCommand() {
            super("cornerfriction", "Set corner friction (0.5-1.0)");
            this.addAliases("corner");
            this.valueArg = this.withOptionalArg("value", "corner friction value", ArgTypes.STRING);
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            String valueStr = this.valueArg.get(context);
            if (valueStr == null) {
                context.sendMessage(Message.raw("Current corner friction: " + MinecartConfig.getCornerFriction()));
                context.sendMessage(Message.raw("Usage: /mc cornerfriction <0.5-1.0>"));
            } else {
                try {
                    double value = Double.parseDouble(valueStr);
                    if (MinecartConfig.setCornerFriction(value)) {
                        context.sendMessage(Message.raw("Corner friction set to " + value));
                    } else {
                        context.sendMessage(Message.raw("Invalid value. Must be between 0.5 and 1.0."));
                    }
                } catch (NumberFormatException e) {
                    context.sendMessage(Message.raw("Invalid number: " + valueStr));
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class SlopeBoostCommand extends AbstractCommand {
        private final OptionalArg<String> valueArg;

        public SlopeBoostCommand() {
            super("slopeboost", "Set slope boost (0.1-5.0)");
            this.addAliases("slope", "boost");
            this.valueArg = this.withOptionalArg("value", "slope boost value", ArgTypes.STRING);
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            String valueStr = this.valueArg.get(context);
            if (valueStr == null) {
                context.sendMessage(Message.raw("Current slope boost: " + MinecartConfig.getSlopeBoost()));
                context.sendMessage(Message.raw("Usage: /mc slopeboost <0.1-5.0>"));
            } else {
                try {
                    double value = Double.parseDouble(valueStr);
                    if (MinecartConfig.setSlopeBoost(value)) {
                        context.sendMessage(Message.raw("Slope boost set to " + value));
                    } else {
                        context.sendMessage(Message.raw("Invalid value. Must be between 0.1 and 5.0."));
                    }
                } catch (NumberFormatException e) {
                    context.sendMessage(Message.raw("Invalid number: " + valueStr));
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class UphillDragCommand extends AbstractCommand {
        private final OptionalArg<String> valueArg;

        public UphillDragCommand() {
            super("uphilldrag", "Set uphill drag (0.1-1.0)");
            this.addAliases("uphill", "drag");
            this.valueArg = this.withOptionalArg("value", "uphill drag value", ArgTypes.STRING);
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            String valueStr = this.valueArg.get(context);
            if (valueStr == null) {
                context.sendMessage(Message.raw("Current uphill drag: " + MinecartConfig.getUphillDrag()));
                context.sendMessage(Message.raw("Usage: /mc uphilldrag <0.1-1.0>"));
            } else {
                try {
                    double value = Double.parseDouble(valueStr);
                    if (MinecartConfig.setUphillDrag(value)) {
                        context.sendMessage(Message.raw("Uphill drag set to " + value));
                    } else {
                        context.sendMessage(Message.raw("Invalid value. Must be between 0.1 and 1.0."));
                    }
                } catch (NumberFormatException e) {
                    context.sendMessage(Message.raw("Invalid number: " + valueStr));
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class InitialPushCommand extends AbstractCommand {
        private final OptionalArg<String> valueArg;

        public InitialPushCommand() {
            super("initialpush", "Set initial push (0.01-2.0)");
            this.addAliases("push", "initial");
            this.valueArg = this.withOptionalArg("value", "initial push value", ArgTypes.STRING);
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            String valueStr = this.valueArg.get(context);
            if (valueStr == null) {
                context.sendMessage(Message.raw("Current initial push: " + MinecartConfig.getInitialPush()));
                context.sendMessage(Message.raw("Usage: /mc initialpush <0.01-2.0>"));
            } else {
                try {
                    double value = Double.parseDouble(valueStr);
                    if (MinecartConfig.setInitialPush(value)) {
                        context.sendMessage(Message.raw("Initial push set to " + value));
                    } else {
                        context.sendMessage(Message.raw("Invalid value. Must be between 0.01 and 2.0."));
                    }
                } catch (NumberFormatException e) {
                    context.sendMessage(Message.raw("Invalid number: " + valueStr));
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class RotationCommand extends AbstractCommand {
        private final OptionalArg<String> valueArg;

        public RotationCommand() {
            super("rotation", "Set rotation smoothing (0.01-1.0)");
            this.addAliases("rot", "smooth");
            this.valueArg = this.withOptionalArg("value", "rotation smoothing value", ArgTypes.STRING);
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            String valueStr = this.valueArg.get(context);
            if (valueStr == null) {
                context.sendMessage(Message.raw("Current rotation smoothing: " + MinecartConfig.getRotationSmoothing()));
                context.sendMessage(Message.raw("Usage: /mc rotation <0.01-1.0>"));
            } else {
                try {
                    double value = Double.parseDouble(valueStr);
                    if (MinecartConfig.setRotationSmoothing(value)) {
                        context.sendMessage(Message.raw("Rotation smoothing set to " + value));
                    } else {
                        context.sendMessage(Message.raw("Invalid value. Must be between 0.01 and 1.0."));
                    }
                } catch (NumberFormatException e) {
                    context.sendMessage(Message.raw("Invalid number: " + valueStr));
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class RiderVelCommand extends AbstractCommand {
        private final OptionalArg<String> valueArg;

        public RiderVelCommand() {
            super("ridervel", "Set rider gravity counter velocity (-10 to 10)");
            this.addAliases("rv", "ridergrav");
            this.valueArg = this.withOptionalArg("value", "velocity value", ArgTypes.STRING);
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            String valueStr = this.valueArg.get(context);
            if (valueStr == null) {
                context.sendMessage(Message.raw("Current rider gravity counter: " + CustomMinecartRidingSystem.getRiderGravityCounterVel()));
                context.sendMessage(Message.raw("Usage: /mc ridervel <-10 to 10>"));
                context.sendMessage(Message.raw("Positive = upward push, Negative = downward, 0 = none"));
            } else {
                try {
                    float value = Float.parseFloat(valueStr);
                    if (value >= -10f && value <= 10f) {
                        CustomMinecartRidingSystem.setRiderGravityCounterVel(value);
                        context.sendMessage(Message.raw("Rider gravity counter set to " + value));
                    } else {
                        context.sendMessage(Message.raw("Invalid value. Must be between -10 and 10."));
                    }
                } catch (NumberFormatException e) {
                    context.sendMessage(Message.raw("Invalid number: " + valueStr));
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
