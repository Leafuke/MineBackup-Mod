package com.leafuke.minebackup.client;

import net.minecraft.network.chat.Component;

import java.util.Objects;

public record RestoreUiMessages(Component rejoining, Component succeeded) {
    public RestoreUiMessages {
        Objects.requireNonNull(rejoining, "rejoining");
        Objects.requireNonNull(succeeded, "succeeded");
    }
}
