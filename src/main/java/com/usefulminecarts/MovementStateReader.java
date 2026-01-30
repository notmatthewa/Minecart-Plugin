package com.usefulminecarts;

import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;

import java.util.List;

public class MovementStateReader {

    private boolean walking;
    private boolean crouching;
    private boolean test;

    public MovementStateReader(List<PlayerInput.InputUpdate> queue) {
        for (PlayerInput.InputUpdate entry : queue) {
            if (entry instanceof PlayerInput.SetMovementStates sms) {
                walking = sms.movementStates().walking;
                crouching = sms.movementStates().crouching;
                test = sms.movementStates().gliding;
            }
        }
    }

    public boolean isWalking() {
        return walking;
    }

    public boolean isCrouching() {
        return crouching;
    }

    public boolean isGliding() {
        return test;
    }
}
