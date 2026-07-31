package com.leafuke.minebackup.command;

import com.leafuke.minebackup.api.v2.BackupEntry;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class CurrentBackupListModel {
    static final int PAGE_SIZE = 5;
    private static final DateTimeFormatter DISPLAY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CurrentBackupListModel() {
    }

    static Page create(List<BackupEntry> source, int requestedPage, ZoneId zoneId) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(zoneId, "zoneId");

        List<BackupEntry> sorted = new ArrayList<>(source);
        sorted.sort(Comparator.comparing(
                (BackupEntry entry) -> entry.createdAt().orElse(Instant.MIN))
                .reversed());

        int totalPages = Math.max(1, (sorted.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int currentPage = Math.max(1, Math.min(totalPages, requestedPage));
        int fromIndex = Math.min((currentPage - 1) * PAGE_SIZE, sorted.size());
        int toIndex = Math.min(fromIndex + PAGE_SIZE, sorted.size());
        List<Row> rows = sorted.subList(fromIndex, toIndex).stream()
                .map(entry -> toRow(entry, zoneId))
                .toList();

        return new Page(
                currentPage,
                totalPages,
                sorted.size(),
                rows,
                currentPage > 1,
                currentPage < totalPages);
    }

    private static Row toRow(BackupEntry entry, ZoneId zoneId) {
        Optional<String> timestamp = entry.createdAt()
                .map(value -> DISPLAY_TIMESTAMP.withZone(zoneId).format(value));
        String fileName = entry.backupId().value();
        String restoreCommand = "/mb restore " + StringArgumentType.escapeIfRequired(fileName);
        return new Row(fileName, timestamp, entry.comment(), restoreCommand);
    }

    record Page(
            int currentPage,
            int totalPages,
            int totalEntries,
            List<Row> rows,
            boolean hasPrevious,
            boolean hasNext) {
        Page {
            rows = List.copyOf(rows);
        }
    }

    record Row(
            String fileName,
            Optional<String> timestamp,
            Optional<String> comment,
            String restoreCommand) {
        Row {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(comment, "comment");
            Objects.requireNonNull(restoreCommand, "restoreCommand");
        }
    }
}
