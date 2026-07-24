package com.leafuke.minebackup.api.v1;

import org.junit.jupiter.api.Test;

import java.time.Duration;
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
