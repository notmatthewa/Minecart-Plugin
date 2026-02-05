package com.usefulminecarts.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.usefulminecarts.CustomMinecartRidingSystem;
import com.usefulminecarts.MinecartConfig;

import javax.annotation.Nonnull;

/**
 * Command to configure rider settings (gravity counter, gravity mode, debug logging).
 */
public class RiderVelCommand extends AbstractPlayerCommand {
    private final DefaultArg<String> valueArg = this.withDefaultArg("value", "velocity or setting", ArgTypes.STRING, "", "show current settings");

    public RiderVelCommand() {
        super("ridervel", "Set rider settings (velocity, gravity mode, debug)");
        this.addAliases("rv", "ridergrav", "rider");
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected String generatePermissionNode() {
        return "";
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        String valueStr = this.valueArg.get(context);

        if (valueStr == null || valueStr.isEmpty()) {
            // Show all rider settings
            int mode = MinecartConfig.getGravityMode();
            String modeName = mode == 0 ? "ZERO_VEL" : (mode == 1 ? "COUNTER" : "ZERO_ALL");
            context.sendMessage(Message.raw("=== Rider Settings ==="));
            context.sendMessage(Message.raw("Gravity counter: " + CustomMinecartRidingSystem.getRiderGravityCounterVel()));
            context.sendMessage(Message.raw("Gravity mode: " + mode + " (" + modeName + ")"));
            context.sendMessage(Message.raw("Debug frequency: " + MinecartConfig.getDebugLogFrequency() + " ticks"));
            context.sendMessage(Message.raw(""));
            context.sendMessage(Message.raw("Usage:"));
            context.sendMessage(Message.raw("  /mc rv <-10 to 10> - Set gravity counter"));
            context.sendMessage(Message.raw("  /mc rv grav0 - Mode 0 (zero velocity)"));
            context.sendMessage(Message.raw("  /mc rv grav1 - Mode 1 (counter gravity)"));
            context.sendMessage(Message.raw("  /mc rv grav2 - Mode 2 (zero all)"));
            context.sendMessage(Message.raw("  /mc rv debug0 - Disable debug logging"));
            context.sendMessage(Message.raw("  /mc rv debug30 - Log every 30 ticks"));
        } else if (valueStr.startsWith("grav")) {
            // Handle gravity mode: grav0, grav1, grav2
            try {
                int mode = Integer.parseInt(valueStr.substring(4));
                if (MinecartConfig.setGravityMode(mode)) {
                    String modeName = mode == 0 ? "ZERO_VEL" : (mode == 1 ? "COUNTER" : "ZERO_ALL");
                    context.sendMessage(Message.raw("Gravity mode set to " + mode + " (" + modeName + ")"));
                } else {
                    context.sendMessage(Message.raw("Invalid mode. Use grav0, grav1, or grav2."));
                }
            } catch (Exception e) {
                context.sendMessage(Message.raw("Invalid format. Use grav0, grav1, or grav2."));
            }
        } else if (valueStr.startsWith("debug")) {
            // Handle debug frequency: debug0, debug30, etc.
            try {
                int freq = Integer.parseInt(valueStr.substring(5));
                if (MinecartConfig.setDebugLogFrequency(freq)) {
                    if (freq == 0) {
                        context.sendMessage(Message.raw("Debug logging disabled"));
                    } else {
                        context.sendMessage(Message.raw("Debug logging every " + freq + " ticks"));
                    }
                } else {
                    context.sendMessage(Message.raw("Invalid frequency."));
                }
            } catch (Exception e) {
                context.sendMessage(Message.raw("Invalid format. Use debug0, debug30, etc."));
            }
        } else {
            // Handle velocity value
            try {
                float value = Float.parseFloat(valueStr);
                if (value >= -10f && value <= 10f) {
                    CustomMinecartRidingSystem.setRiderGravityCounterVel(value);
                    context.sendMessage(Message.raw("Rider gravity counter set to " + value));
                } else {
                    context.sendMessage(Message.raw("Invalid value. Must be between -10 and 10."));
                }
            } catch (NumberFormatException e) {
                context.sendMessage(Message.raw("Invalid input: " + valueStr));
                context.sendMessage(Message.raw("Use a number, grav0/1/2, or debug<N>"));
            }
        }
    }
}
