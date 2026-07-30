package com.leafuke.minebackup.command;

import com.leafuke.minebackup.api.v2.BackupEntry;
import com.leafuke.minebackup.api.v2.BackupId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentBackupListModelTest {
    @Test
    void sortsNewestFirstAndPaginatesFiveEntries() {
        List<BackupEntry> entries = new ArrayList<>();
        entries.add(legacy("legacy.zip"));
        for (int hour = 0; hour < 7; hour++) {
            entries.add(entry(
                    "backup-" + hour + ".7z",
                    "2026-07-30T0" + hour + ":00:00Z",
                    hour == 6 ? "latest" : null));
        }

        var first = CurrentBackupListModel.create(entries, 1, ZoneId.of("UTC"));
        assertEquals(1, first.currentPage());
        assertEquals(2, first.totalPages());
        assertEquals(8, first.totalEntries());
        assertEquals(5, first.rows().size());
        assertEquals("backup-6.7z", first.rows().getFirst().fileName());
        assertEquals("2026-07-30 06:00:00", first.rows().getFirst().timestamp().orElseThrow());
        assertEquals("latest", first.rows().getFirst().comment().orElseThrow());
        assertFalse(first.hasPrevious());
        assertTrue(first.hasNext());

        var last = CurrentBackupListModel.create(entries, 99, ZoneId.of("UTC"));
        assertEquals(2, last.currentPage());
        assertEquals(3, last.rows().size());
        assertEquals("legacy.zip", last.rows().getLast().fileName());
        assertTrue(last.rows().getLast().timestamp().isEmpty());
        assertTrue(last.hasPrevious());
        assertFalse(last.hasNext());
    }

    @Test
    void keepsUnparseableEntriesStableAndEscapesRestoreCommand() {
        var page = CurrentBackupListModel.create(
                List.of(legacy("first old.zip"), legacy("quoted \"old\".7z")),
                1,
                ZoneId.of("UTC"));

        assertEquals("first old.zip", page.rows().getFirst().fileName());
        assertEquals("/mb restore \"first old.zip\"", page.rows().getFirst().restoreCommand());
        assertEquals(
                "/mb restore \"quoted \\\"old\\\".7z\"",
                page.rows().get(1).restoreCommand());
    }

    @Test
    void emptyCatalogUsesSingleEmptyPage() {
        var page = CurrentBackupListModel.create(List.of(), 5, ZoneId.of("UTC"));

        assertEquals(1, page.currentPage());
        assertEquals(1, page.totalPages());
        assertEquals(0, page.totalEntries());
        assertTrue(page.rows().isEmpty());
        assertFalse(page.hasPrevious());
        assertFalse(page.hasNext());
    }

    private static BackupEntry entry(String fileName, String createdAt, String comment) {
        return new BackupEntry(
                BackupId.of(fileName),
                Optional.of(Instant.parse(createdAt)),
                OptionalLong.empty(),
                Optional.ofNullable(comment));
    }

    private static BackupEntry legacy(String fileName) {
        return BackupEntry.legacy(BackupId.of(fileName));
    }
}
