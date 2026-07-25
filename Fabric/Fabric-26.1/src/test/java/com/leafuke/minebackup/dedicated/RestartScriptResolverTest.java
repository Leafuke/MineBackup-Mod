package com.leafuke.minebackup.dedicated;

import com.leafuke.minebackup.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartScriptResolverTest {
    @TempDir
    Path root;

    @Test
    void detectsExactlyOneWindowsCandidateAndPreservesSpaces() throws IOException {
        Path script = Files.writeString(root.resolve("start.cmd"), "@echo off");
        var result = RestartScriptResolver.resolve(config(""), root, "Windows 11");
        assertTrue(result.available());
        assertEquals(java.util.List.of("cmd.exe", "/c", script.toString()), result.command());
    }

    @Test
    void rejectsZeroAndMultipleCandidates() throws IOException {
        assertFalse(RestartScriptResolver.resolve(config(""), root, "Linux").available());
        Files.writeString(root.resolve("start.sh"), "#!/bin/sh");
        Files.writeString(root.resolve("run.sh"), "#!/bin/sh");
        assertFalse(RestartScriptResolver.resolve(config(""), root, "Linux").available());
    }

    @Test
    void explicitRelativeScriptWins() throws IOException {
        Path script = Files.writeString(root.resolve("custom.cmd"), "@echo off");
        var result = RestartScriptResolver.resolve(config("custom.cmd"), root, "Windows");
        assertTrue(result.available());
        assertEquals(script.toAbsolutePath().normalize(), result.script());
    }

    private static Config.DedicatedRestore config(String script) {
        return new Config.DedicatedRestore(
                Config.DedicatedRestoreMode.SIDECAR, script, 5, 8, 3600);
    }
}
