package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.RestoreRequest;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

record RemoteRestoreRequest(UUID requestId, RestoreRequest request) {
    private static final String CALLER_ID = "folderrewind:ui";

    RemoteRestoreRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(request, "request");
    }

    static RemoteRestoreRequest parse(Map<String, String> fields) {
        return parse(fields, UUID::randomUUID);
    }

    static RemoteRestoreRequest parse(
            Map<String, String> fields,
            Supplier<UUID> requestIdGenerator) {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(requestIdGenerator, "requestIdGenerator");

        UUID requestId = parseRequestId(fields.get("request_id"), requestIdGenerator);
        String file = fields.get("file");
        RestoreRequest request = file == null || file.isBlank()
                ? RestoreRequest.latest(CALLER_ID)
                : RestoreRequest.file(CALLER_ID, file);
        return new RemoteRestoreRequest(requestId, request);
    }

    private static UUID parseRequestId(String raw, Supplier<UUID> requestIdGenerator) {
        if (raw == null || raw.isBlank()) {
            return Objects.requireNonNull(requestIdGenerator.get(), "generated requestId");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("request_id must be a UUID", exception);
        }
    }
}
