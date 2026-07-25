package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackupEntry;
import com.leafuke.minebackup.api.v2.BackupId;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

final class BackupCatalogParser {
    private BackupCatalogParser() {
    }

    static List<BackupEntry> parseLegacy(String data) {
        LinkedHashSet<BackupId> entries = new LinkedHashSet<>();
        if (data != null && !data.isBlank()) {
            Arrays.stream(data.split(";"))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(BackupId::of)
                    .forEach(entries::add);
        }
        return entries.stream().map(BackupEntry::legacy).toList();
    }
}
