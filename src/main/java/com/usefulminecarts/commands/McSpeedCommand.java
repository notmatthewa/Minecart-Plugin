package com.usefulminecarts.commands;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.usefulminecarts.MinecartConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * Command to set minecart max speed.
 * Usage: /mcspeed [value]
 */
public class McSpeedCommand extends AbstractCommand {

    public McSpeedCommand() {
        super("mcspeed", "Set minecart max speed");
        this.addAliases("minecartspeed");
        this.setPermissionGroup(GameMode.Adventure);

        // Add variant that takes an argument
        this.addUsageVariant(new SetSpeedVariant());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        // No argument - show current value
        context.sendMessage(Message.raw("Current maxSpeed: " + MinecartConfig.getMaxSpeed() + " blocks/s"));
        context.sendMessage(Message.raw("Usage: /mcspeed <0.1-50>"));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Variant that takes a speed argument.
     */
    private static class SetSpeedVariant extends CommandBase {
        private final RequiredArg<Float> speedArg;

        public SetSpeedVariant() {
            super("Set max speed value");
            this.speedArg = this.withRequiredArg("speed", "Max speed in blocks/s (0.1-50)", ArgTypes.FLOAT);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            Float value = this.speedArg.get(context);
            if (value == null) {
                context.sendMessage(Message.raw("Please provide a speed value."));
                return;
            }

            if (MinecartConfig.setMaxSpeed(value)) {
                context.sendMessage(Message.raw("Max speed set to " + value + " blocks/s"));
            } else {
                context.sendMessage(Message.raw("Invalid value. Must be between 0.1 and 50."));
            }
        }
    }
}
