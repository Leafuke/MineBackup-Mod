package com.leafuke.minebackup.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldAutomationConfigStoreTest {
    @TempDir
    Path root;

    @Test
    void relativeWorldIdentitySurvivesInstanceMove() throws Exception {
        WorldIdentity first = identity(root.resolve("first"), "saves/redstone", "Redstone");
        WorldIdentity moved = identity(root.resolve("moved"), "saves/redstone", "Redstone");

        assertEquals("relative:saves/redstone", first.value());
        assertEquals(first.storageKey(), moved.storageKey());
        assertEquals(64, first.storageKey().length());
    }

    @Test
    void renamedWorldGetsADifferentDisabledPlan() throws Exception {
        Path game = Files.createDirectories(root.resolve("game"));
        WorldIdentity first = identity(game, "saves/first", "First");
        WorldIdentity renamed = identity(game, "saves/renamed", "Renamed");
        WorldAutomationConfigStore store = new WorldAutomationConfigStore(root.resolve("config"));

        assertTrue(store.write(first, WorldAutomationConfigStore.Settings.backup(30)));
        assertTrue(store.load(first).settings().active());
        assertFalse(store.load(renamed).settings().active());
        assertNotEquals(first.storageKey(), renamed.storageKey());
    }

    @Test
    void writesAndLoadsWorldBoundSettings() throws Exception {
        Path game = Files.createDirectories(root.resolve("game"));
        WorldIdentity world = identity(game, "saves/world", "World");
        WorldAutomationConfigStore store = new WorldAutomationConfigStore(root.resolve("config"));

        assertTrue(store.write(world, WorldAutomationConfigStore.Settings.backup(45)));
        var loaded = store.load(world);

        assertTrue(loaded.valid());
        assertEquals(WorldAutomationConfigStore.Mode.BACKUP, loaded.settings().mode());
        assertEquals(45, loaded.settings().intervalMinutes());
    }

    @Test
    void mismatchedAndInvalidFilesStayDisabled() throws Exception {
        Path game = Files.createDirectories(root.resolve("game"));
        WorldIdentity world = identity(game, "saves/world", "World");
        WorldAutomationConfigStore store = new WorldAutomationConfigStore(root.resolve("config"));
        Files.createDirectories(store.pathFor(world).getParent());

        Properties properties = new Properties();
        properties.setProperty("world.identity", "relative:saves/other");
        properties.setProperty("automation.mode", "BACKUP");
        properties.setProperty("automation.intervalMinutes", "30");
        try (var writer = Files.newBufferedWriter(store.pathFor(world))) {
            properties.store(writer, "test");
        }
        assertFalse(store.load(world).valid());
        assertFalse(store.load(world).settings().active());

        properties.setProperty("world.identity", world.value());
        properties.setProperty("automation.intervalMinutes", "invalid");
        try (var writer = Files.newBufferedWriter(store.pathFor(world))) {
            properties.store(writer, "test");
        }
        assertFalse(store.load(world).valid());
        assertFalse(store.load(world).settings().active());
    }

    @Test
    void legacyGlobalIntervalMovesOnlyAfterWorldWrite() throws Exception {
        Path game = Files.createDirectories(root.resolve("game"));
        WorldIdentity world = identity(game, "saves/world", "World");
        WorldAutomationConfigStore store = new WorldAutomationConfigStore(root.resolve("config"));
        AtomicBoolean cleared = new AtomicBoolean();

        var result = store.migrateLegacyBackup(
                world,
                WorldAutomationConfigStore.Settings.off(),
                30,
                () -> {
                    cleared.set(true);
                    return true;
                });

        assertEquals(WorldAutomationConfigStore.Migration.MIGRATED, result.migration());
        assertEquals(30, store.load(world).settings().intervalMinutes());
        assertTrue(cleared.get());
    }

    @Test
    void failedWorldWriteDoesNotClearLegacyGlobalInterval() throws Exception {
        Path game = Files.createDirectories(root.resolve("game"));
        WorldIdentity world = identity(game, "saves/world", "World");
        Path unusableDirectory = Files.writeString(root.resolve("not-a-directory"), "blocked");
        WorldAutomationConfigStore store = new WorldAutomationConfigStore(unusableDirectory);
        AtomicBoolean cleared = new AtomicBoolean();

        var result = store.migrateLegacyBackup(
                world,
                WorldAutomationConfigStore.Settings.off(),
                30,
                () -> {
                    cleared.set(true);
                    return true;
                });

        assertEquals(
                WorldAutomationConfigStore.Migration.WORLD_WRITE_FAILED,
                result.migration());
        assertFalse(cleared.get());
        assertFalse(result.settings().active());
    }

    private static WorldIdentity identity(
            Path game,
            String relativeWorld,
            String displayName) throws Exception {
        Files.createDirectories(game);
        Path world = Files.createDirectories(game.resolve(relativeWorld));
        return WorldIdentity.resolve(game, world, displayName);
    }
}
