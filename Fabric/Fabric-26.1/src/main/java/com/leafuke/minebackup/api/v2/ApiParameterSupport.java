package com.leafuke.minebackup.api.v2;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ApiParameterSupport {
    private ApiParameterSupport() {
    }

    static Map<String, String> normalize(Map<String, String> values, Set<String> reserved) {
        Objects.requireNonNull(values, "parameters");
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, "parameter key");
            Objects.requireNonNull(value, "parameter value");
            String safeKey = key.trim().toLowerCase(Locale.ROOT);
            String safeValue = value.trim();
            if (safeKey.isEmpty() || safeValue.isEmpty()) {
                throw new IllegalArgumentException("FolderRewind parameters must not be blank");
            }
            if (reserved.contains(safeKey)
                    || "current_save".equals(safeKey)
                    || "request_id".equals(safeKey)
                    || "from".equals(safeKey)) {
                throw new IllegalArgumentException("Reserved FolderRewind parameter: " + safeKey);
            }
            normalized.put(safeKey, safeValue);
        });
        return Map.copyOf(normalized);
    }

    static Map<String, String> merge(
            Map<String, String> current,
            Map<String, String> additions,
            Set<String> reserved) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(current);
        merged.putAll(normalize(additions, reserved));
        return Map.copyOf(merged);
    }
}
