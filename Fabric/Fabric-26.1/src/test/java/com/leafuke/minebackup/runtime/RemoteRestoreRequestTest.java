package com.leafuke.minebackup.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteRestoreRequestTest {
    private static final UUID GENERATED_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000123");

    @Test
    void missingOrBlankFileRequestsLatestBackup() {
        var missing = RemoteRestoreRequest.parse(Map.of(), () -> GENERATED_ID);
        var blank = RemoteRestoreRequest.parse(Map.of("file", "  "), () -> GENERATED_ID);

        assertEquals(GENERATED_ID, missing.requestId());
        assertEquals("folderrewind:ui", missing.request().callerId());
        assertTrue(missing.request().backupId().isEmpty());
        assertTrue(blank.request().backupId().isEmpty());
    }

    @Test
    void selectedFileAndRequestIdArePreserved() {
        UUID requestId = UUID.randomUUID();
        var parsed = RemoteRestoreRequest.parse(Map.of(
                "file", "[Full][2026-07-30_11-29-54]World [safe].7z",
                "request_id", requestId.toString()));

        assertEquals(requestId, parsed.requestId());
        assertEquals(
                "[Full][2026-07-30_11-29-54]World [safe].7z",
                parsed.request().backupId().orElseThrow().value());
    }

    @Test
    void rejectsMalformedRequestIdAndUnsafeFile() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RemoteRestoreRequest.parse(Map.of("request_id", "not-a-uuid")));
        assertThrows(
                IllegalArgumentException.class,
                () -> RemoteRestoreRequest.parse(Map.of("file", "../escape.7z")));
    }
}
