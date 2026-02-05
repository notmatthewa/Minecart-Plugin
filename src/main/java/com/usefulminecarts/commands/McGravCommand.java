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
 * Command to set rider gravity mode.
 * Usage: /mcgrav [0/1/2]
 */
public class McGravCommand extends AbstractCommand {

    public McGravCommand() {
        super("mcgrav", "Set rider gravity mode");
        this.addAliases("minecartgrav", "mcgravity");
        this.setPermissionGroup(GameMode.Adventure);

        // Add variant that takes an argument
        this.addUsageVariant(new SetGravVariant());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        // No argument - show current value
        int currentMode = MinecartConfig.getGravityMode();
        String modeName = currentMode == 0 ? "ZERO_VEL" : (currentMode == 1 ? "COUNTER" : "ZERO_ALL");
        context.sendMessage(Message.raw("Current gravity mode: " + currentMode + " (" + modeName + ")"));
        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("Usage: /mcgrav <0/1/2>"));
        context.sendMessage(Message.raw("  0 = ZERO_VEL (zero Y velocity)"));
        context.sendMessage(Message.raw("  1 = COUNTER (counteract gravity)"));
        context.sendMessage(Message.raw("  2 = ZERO_ALL (zero all velocity)"));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Variant that takes a mode argument.
     */
    private static class SetGravVariant extends CommandBase {
        private final RequiredArg<Integer> modeArg;

        public SetGravVariant() {
            super("Set gravity mode");
            this.modeArg = this.withRequiredArg("mode", "Gravity mode: 0, 1, or 2", ArgTypes.INTEGER);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            Integer mode = this.modeArg.get(context);
            if (mode == null) {
                context.sendMessage(Message.raw("Please provide a mode (0, 1, or 2)."));
                return;
            }

            if (MinecartConfig.setGravityMode(mode)) {
                String modeName = mode == 0 ? "ZERO_VEL" : (mode == 1 ? "COUNTER" : "ZERO_ALL");
                context.sendMessage(Message.raw("Gravity mode set to " + mode + " (" + modeName + ")"));
            } else {
                context.sendMessage(Message.raw("Invalid mode. Use 0, 1, or 2."));
            }
        }
    }
}
