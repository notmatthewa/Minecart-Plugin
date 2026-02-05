package com.usefulminecarts.commands;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.usefulminecarts.MinecartConfig;
import com.usefulminecarts.CustomMinecartRidingSystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * Main minecart command collection.
 * Usage: /mc <subcommand> [args]
 */
public class MinecartCommandCollection extends AbstractCommandCollection {

    public MinecartCommandCollection() {
        super("minecart", "Minecart physics commands");
        this.addAliases("mc", "cart");
        this.setPermissionGroup(GameMode.Adventure);

        // Register subcommands
        this.addSubCommand(new StatusCommand());
        this.addSubCommand(new GravCommand());
        this.addSubCommand(new DebugCommand());
        this.addSubCommand(new SpeedCommand());
        this.addSubCommand(new FrictionCommand());
        this.addSubCommand(new ResetCommand());
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected String generatePermissionNode() {
        return "";
    }

    // ========== Status Command (no args) ==========
    public static class StatusCommand extends AbstractCommand {
        public StatusCommand() {
            super("status", "Show all minecart settings");
            this.addAliases("info", "config");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            context.sendMessage(Message.raw(MinecartConfig.getStatus()));
            return CompletableFuture.completedFuture(null);
        }
    }

    // ========== Reset Command (no args) ==========
    public static class ResetCommand extends AbstractCommand {
        public ResetCommand() {
            super("reset", "Reset all settings to defaults");
            this.addAliases("defaults");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            MinecartConfig.resetToDefaults();
            context.sendMessage(Message.raw("Minecart settings reset to defaults!"));
            return CompletableFuture.completedFuture(null);
        }
    }

    // ========== Grav Command ==========
    public static class GravCommand extends AbstractCommand {
        public GravCommand() {
            super("grav", "Set rider gravity mode (0-3)");
            this.addAliases("gravity", "g");
            this.addUsageVariant(new SetGravVariant());
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            int mode = MinecartConfig.getGravityMode();
            String modeName = getModeName(mode);
            context.sendMessage(Message.raw("Gravity mode: " + mode + " (" + modeName + ")"));
            context.sendMessage(Message.raw(""));
            context.sendMessage(Message.raw("Usage: /mc grav <0-3>"));
            context.sendMessage(Message.raw("  0 = ZERO_VEL (match cart velocity)"));
            context.sendMessage(Message.raw("  1 = COUNTER (add upward force)"));
            context.sendMessage(Message.raw("  2 = ZERO_ALL (drift correction only)"));
            context.sendMessage(Message.raw("  3 = AGGRESSIVE (strong anti-gravity)"));
            return CompletableFuture.completedFuture(null);
        }

        private static String getModeName(int mode) {
            return switch (mode) {
                case 0 -> "ZERO_VEL";
                case 1 -> "COUNTER";
                case 2 -> "ZERO_ALL";
                case 3 -> "AGGRESSIVE";
                default -> "UNKNOWN";
            };
        }

        private static class SetGravVariant extends CommandBase {
            private final RequiredArg<Integer> modeArg;

            public SetGravVariant() {
                super("Set gravity mode value");
                this.modeArg = this.withRequiredArg("mode", "0-3", ArgTypes.INTEGER);
            }

            @Override
            protected void executeSync(@Nonnull CommandContext context) {
                Integer mode = this.modeArg.get(context);
                if (mode == null) return;

                if (MinecartConfig.setGravityMode(mode)) {
                    String modeName = getModeName(mode);
                    context.sendMessage(Message.raw("Gravity mode set to " + mode + " (" + modeName + ")"));
                } else {
                    context.sendMessage(Message.raw("Invalid mode. Use 0, 1, 2, or 3."));
                }
            }
        }
    }

    // ========== Debug Command ==========
    public static class DebugCommand extends AbstractCommand {
        public DebugCommand() {
            super("debug", "Set debug log frequency");
            this.addAliases("log", "d");
            this.addUsageVariant(new SetDebugVariant());
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            int freq = MinecartConfig.getDebugLogFrequency();
            if (freq == 0) {
                context.sendMessage(Message.raw("Debug logging: DISABLED"));
            } else {
                context.sendMessage(Message.raw("Debug logging: every " + freq + " ticks"));
            }
            context.sendMessage(Message.raw("Usage: /mc debug <ticks>  (0=disable)"));
            return CompletableFuture.completedFuture(null);
        }

        private static class SetDebugVariant extends CommandBase {
            private final RequiredArg<Integer> freqArg;

            public SetDebugVariant() {
                super("Set debug frequency");
                this.freqArg = this.withRequiredArg("ticks", "Log every N ticks", ArgTypes.INTEGER);
            }

            @Override
            protected void executeSync(@Nonnull CommandContext context) {
                Integer freq = this.freqArg.get(context);
                if (freq == null) return;

                if (MinecartConfig.setDebugLogFrequency(freq)) {
                    if (freq == 0) {
                        context.sendMessage(Message.raw("Debug logging disabled"));
                    } else {
                        context.sendMessage(Message.raw("Debug logging every " + freq + " ticks"));
                    }
                } else {
                    context.sendMessage(Message.raw("Invalid frequency."));
                }
            }
        }
    }

    // ========== Speed Command ==========
    public static class SpeedCommand extends AbstractCommand {
        public SpeedCommand() {
            super("speed", "Set max speed");
            this.addAliases("maxspeed", "s");
            this.addUsageVariant(new SetSpeedVariant());
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            context.sendMessage(Message.raw("Max speed: " + MinecartConfig.getMaxSpeed() + " blocks/s"));
            context.sendMessage(Message.raw("Usage: /mc speed <0.1-50>"));
            return CompletableFuture.completedFuture(null);
        }

        private static class SetSpeedVariant extends CommandBase {
            private final RequiredArg<Float> speedArg;

            public SetSpeedVariant() {
                super("Set max speed value");
                this.speedArg = this.withRequiredArg("speed", "Max speed in blocks/s", ArgTypes.FLOAT);
            }

            @Override
            protected void executeSync(@Nonnull CommandContext context) {
                Float value = this.speedArg.get(context);
                if (value == null) return;

                if (MinecartConfig.setMaxSpeed(value)) {
                    context.sendMessage(Message.raw("Max speed set to " + value + " blocks/s"));
                } else {
                    context.sendMessage(Message.raw("Invalid value. Must be 0.1-50."));
                }
            }
        }
    }

    // ========== Friction Command ==========
    public static class FrictionCommand extends AbstractCommand {
        public FrictionCommand() {
            super("friction", "Set friction");
            this.addAliases("fric", "f");
            this.addUsageVariant(new SetFrictionVariant());
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            context.sendMessage(Message.raw("Friction: " + MinecartConfig.getFriction()));
            context.sendMessage(Message.raw("Usage: /mc friction <0.5-1.0>"));
            return CompletableFuture.completedFuture(null);
        }

        private static class SetFrictionVariant extends CommandBase {
            private final RequiredArg<Float> fricArg;

            public SetFrictionVariant() {
                super("Set friction value");
                this.fricArg = this.withRequiredArg("friction", "Friction multiplier", ArgTypes.FLOAT);
            }

            @Override
            protected void executeSync(@Nonnull CommandContext context) {
                Float value = this.fricArg.get(context);
                if (value == null) return;

                if (MinecartConfig.setFriction(value)) {
                    context.sendMessage(Message.raw("Friction set to " + value));
                } else {
                    context.sendMessage(Message.raw("Invalid value. Must be 0.5-1.0."));
                }
            }
        }
    }
}
