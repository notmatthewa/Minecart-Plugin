package com.usefulminecarts.commands;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.usefulminecarts.MinecartConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * Command to show all minecart settings.
 * Usage: /mcstatus
 */
public class McStatusCommand extends AbstractCommand {

    public McStatusCommand() {
        super("mcstatus", "Show all minecart physics settings");
        this.addAliases("minecartstatus", "mcinfo", "mcconfig");
        this.setPermissionGroup(GameMode.Adventure);
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw(MinecartConfig.getStatus()));
        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("Commands: /mcspeed, /mcgrav, /mcdebug"));
        return CompletableFuture.completedFuture(null);
    }
}
