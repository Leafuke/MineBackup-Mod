package com.leafuke.minebackup.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandHelpRegistryTest {
    @Test
    void helpEntriesAreFilteredByCapability() {
        assertEquals(
                List.of("save", "backup"),
                CommandHelpRegistry.visiblePaths(
                        capability -> capability == CommandCapability.BACKUP));
        assertEquals(
                List.of("restore", "confirm", "stop"),
                CommandHelpRegistry.visiblePaths(
                        capability -> capability == CommandCapability.RESTORE));
    }
}
