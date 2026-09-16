package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackupEntry;
import com.leafuke.minebackup.api.v2.BackupId;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BackupCatalogParser {
    private static final Pattern STANDARD_NAME = Pattern.compile(
            "^\\[[^\\[\\]]+]\\[(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})](.+)\\.(7z|zip)$",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd_HH-mm-ss")
            .withResolverStyle(ResolverStyle.STRICT);

    private BackupCatalogParser() {
    }

    static List<BackupEntry> parseLegacy(String data) {
        return parseLegacy(data, ZoneId.systemDefault());
    }

    static List<BackupEntry> parseLegacy(String data, ZoneId zoneId) {
        LinkedHashSet<BackupId> entries = new LinkedHashSet<>();
        if (data != null && !data.isBlank()) {
            Arrays.stream(data.split(";"))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(BackupId::of)
                    .forEach(entries::add);
        }
        return entries.stream().map(entry -> parseEntry(entry, zoneId)).toList();
    }

    private static BackupEntry parseEntry(BackupId backupId, ZoneId zoneId) {
        Matcher matcher = STANDARD_NAME.matcher(backupId.value());
        if (!matcher.matches()) {
            return BackupEntry.legacy(backupId);
        }

        String nameBody = matcher.group(2);
        Optional<String> comment = Optional.empty();
        int commentStart = nameBody.lastIndexOf(" [");
        if (commentStart >= 0 && nameBody.endsWith("]")) {
            comment = Optional.of(nameBody.substring(commentStart + 2, nameBody.length() - 1));
        }

        try {
            LocalDateTime createdAt = LocalDateTime.parse(matcher.group(1), FILE_TIMESTAMP);
            return new BackupEntry(
                    backupId,
                    Optional.of(createdAt.atZone(zoneId).toInstant()),
                    OptionalLong.empty(),
                    comment);
        } catch (DateTimeException exception) {
            return BackupEntry.legacy(backupId);
        }
    }
}
