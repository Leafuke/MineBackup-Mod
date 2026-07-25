package com.leafuke.minebackup.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void rejectsUnsafeBackendEntry() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackupCatalogParser.parseLegacy("safe.7z;../escape.7z"));
    }
}
