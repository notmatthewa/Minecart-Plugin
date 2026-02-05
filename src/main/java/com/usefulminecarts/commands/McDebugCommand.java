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
 * Command to set debug logging frequency.
 * Usage: /mcdebug [ticks]
 */
public class McDebugCommand extends AbstractCommand {

    public McDebugCommand() {
        super("mcdebug", "Set minecart debug logging frequency");
        this.addAliases("minecartdebug", "mclog");
        this.setPermissionGroup(GameMode.Adventure);

        // Add variant that takes an argument
        this.addUsageVariant(new SetDebugVariant());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        // No argument - show current value
        int current = MinecartConfig.getDebugLogFrequency();
        if (current == 0) {
            context.sendMessage(Message.raw("Debug logging: DISABLED"));
        } else {
            context.sendMessage(Message.raw("Debug logging: every " + current + " ticks"));
        }
        context.sendMessage(Message.raw("Usage: /mcdebug <ticks>  (0 to disable)"));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Variant that takes a frequency argument.
     */
    private static class SetDebugVariant extends CommandBase {
        private final RequiredArg<Integer> freqArg;

        public SetDebugVariant() {
            super("Set debug frequency");
            this.freqArg = this.withRequiredArg("ticks", "Log every N ticks (0=disabled)", ArgTypes.INTEGER);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            Integer freq = this.freqArg.get(context);
            if (freq == null) {
                context.sendMessage(Message.raw("Please provide a tick frequency."));
                return;
            }

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
