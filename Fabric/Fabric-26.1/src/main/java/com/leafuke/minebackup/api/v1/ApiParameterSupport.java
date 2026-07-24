package com.leafuke.minebackup.api.v1;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ApiParameterSupport {
    static final Set<String> COMMON_RESERVED =
            Set.of("cmd", "from", "request_id", "current_save");

    private ApiParameterSupport() {
    }

    static Map<String, String> normalize(
            Map<String, String> parameters,
            Set<String> operationReserved) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(operationReserved, "operationReserved");

        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = normalizeKey(entry.getKey());
            if (COMMON_RESERVED.contains(key) || operationReserved.contains(key)) {
                throw new IllegalArgumentException(
                        "Parameter '" + key + "' is managed by MineBackup");
            }
            String previous = normalized.put(
                    key,
                    Objects.requireNonNull(
                            entry.getValue(),
                            "Parameter value for '" + key + "'"));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate parameter after key normalization: " + key);
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    static Map<String, String> merge(
            Map<String, String> existing,
            Map<String, String> additions,
            Set<String> operationReserved) {
        Map<String, String> normalizedAdditions = normalize(additions, operationReserved);
        Map<String, String> merged = new LinkedHashMap<>(existing);
        merged.putAll(normalizedAdditions);
        return merged;
    }

    private static String normalizeKey(String key) {
        Objects.requireNonNull(key, "Parameter key");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Parameter key must not be empty");
        }
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (!((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_')) {
                throw new IllegalArgumentException(
                        "Invalid parameter key '" + key
                                + "': only ASCII letters, digits, and '_' are allowed");
            }
        }
        return key.toLowerCase(Locale.ROOT);
    }
}
