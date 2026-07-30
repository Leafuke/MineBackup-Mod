package com.leafuke.minebackup.runtime;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackupCatalogParserTest {
    @Test
    void parsesEmptyAndDeduplicatesLegacyEntries() {
        assertEquals(0, BackupCatalogParser.parseLegacy("").size());
        var entries = BackupCatalogParser.parseLegacy("a.7z;b.7z;a.7z");
        assertEquals(2, entries.size());
        assertEquals("a.7z", entries.getFirst().backupId().value());
    }

    @Test
    void extractsTimestampAndOptionalCommentFromFolderRewindNames() {
        var entries = BackupCatalogParser.parseLegacy(
                "[Full][2026-07-30_11-29-54]NBTExplorer [before boss].7z;"
                        + "[Smart][2026-07-30_12-00-00]NBTExplorer.zip;"
                        + "[Overwrite][2026-07-30_12-30-00]NBTExplorer [latest].7Z",
                ZoneId.of("Asia/Shanghai"));

        assertEquals(3, entries.size());
        assertEquals(
                Instant.parse("2026-07-30T03:29:54Z"),
                entries.getFirst().createdAt().orElseThrow());
        assertEquals("before boss", entries.getFirst().comment().orElseThrow());
        assertTrue(entries.get(1).comment().isEmpty());
        assertEquals("latest", entries.get(2).comment().orElseThrow());
    }

    @Test
    void leavesUnknownOrInvalidNamesAsLegacyEntries() {
        var entries = BackupCatalogParser.parseLegacy(
                "[Full][2026-02-30_11-29-54]World [invalid date].7z;legacy backup.zip",
                ZoneId.of("UTC"));

        assertEquals(2, entries.size());
        assertTrue(entries.getFirst().createdAt().isEmpty());
        assertTrue(entries.getFirst().comment().isEmpty());
        assertTrue(entries.get(1).createdAt().isEmpty());
    }

    @Test
    void rejectsUnsafeBackendEntry() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackupCatalogParser.parseLegacy("safe.7z;../escape.7z"));
    }
}
