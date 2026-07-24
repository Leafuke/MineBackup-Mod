package com.leafuke.minebackup.api.v1;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiValueTypesTest {
    @Test
    void backupRequestNormalizesCallerAndComment() {
        BackupRequest request = new BackupRequest(
                "  just_enough_accident  ",
                Optional.of("  Creeper warning  "));

        assertEquals("just_enough_accident", request.callerId());
        assertEquals(Optional.of("Creeper warning"), request.comment());
    }

    @Test
    void blankCallerIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackupRequest.create(" "));
    }

    @Test
    void restoreFileFactoryUsesConfiguredCountdown() {
        RestoreRequest request = RestoreRequest.file("jea", " save.7z ");

        assertEquals(Optional.of("save.7z"), request.fileName());
        assertEquals(
                RestoreExecutionPolicy.CONFIGURED_COUNTDOWN,
                request.executionPolicy());
        assertEquals(
                RestoreExecutionPolicy.IMMEDIATE,
                request.immediate().executionPolicy());
    }

    @Test
    void additionalParametersAreNormalizedImmutableAndDefensivelyCopied() {
        Map<String, String> source = new HashMap<>();
        source.put("Compression_Method", "zstd level=9");

        BackupRequest request = new BackupRequest(
                "jea",
                Optional.empty(),
                source);
        source.put("backup_mode", "full");

        assertEquals(
                Map.of("compression_method", "zstd level=9"),
                request.parameters());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.parameters().put("backup_mode", "full"));
    }

    @Test
    void fluentParametersCanReplaceValuesAndSurviveRestorePolicyChange() {
        BackupRequest backup = BackupRequest.create("jea")
                .withParameter("backup_mode", "incremental")
                .withParameter("BACKUP_MODE", "full");
        RestoreRequest restore = RestoreRequest.latest("jea")
                .withParameter("verify_archive", "true")
                .immediate();

        assertEquals("full", backup.parameters().get("backup_mode"));
        assertEquals("true", restore.parameters().get("verify_archive"));
    }

    @Test
    void ownedAndMalformedParametersAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackupRequest.create("jea")
                        .withParameter("current_save", "false"));
        assertThrows(
                IllegalArgumentException.class,
                () -> BackupRequest.create("jea")
                        .withParameter("comment", "override"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RestoreRequest.latest("jea")
                        .withParameter("file", "other.7z"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RestoreRequest.latest("jea")
                        .withParameter("bad-key", "value"));
    }

    @Test
    void disabledAutomaticBackupHasNoSchedule() {
        AutoBackupState state = AutoBackupState.disabled();

        assertTrue(state.interval().isEmpty());
        assertTrue(state.nextRun().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoBackupState(
                        false,
                        Optional.of(Duration.ofMinutes(5)),
                        Optional.empty()));
    }
}
