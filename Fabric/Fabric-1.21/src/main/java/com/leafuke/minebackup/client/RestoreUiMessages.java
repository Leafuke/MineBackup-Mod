package com.leafuke.minebackup.client;

import net.minecraft.text.Text;

import java.util.Objects;

public record RestoreUiMessages(Text rejoining, Text succeeded) {
    public RestoreUiMessages {
        Objects.requireNonNull(rejoining, "rejoining");
        Objects.requireNonNull(succeeded, "succeeded");
    }
}
