package com.leafuke.minebackup.api.v2;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiValueTypesTest {
    @Test
    void callerIdsAreNormalizedAndRestricted() {
        assertEquals("jea:incident", BackupRequest.create(" JEA:Incident ").callerId());
        assertThrows(IllegalArgumentException.class, () -> BackupRequest.create("bad/caller"));
        assertThrows(IllegalArgumentException.class, () -> BackupRequest.create("x".repeat(65)));
    }

    @Test
    void backupIdRejectsPathTraversalAndControls() {
        assertEquals("snapshot.7z", BackupId.of(" snapshot.7z ").value());
        for (String unsafe : new String[]{"", ".", "..", "../save", "a/b", "a\\b", "a\nb"}) {
            assertThrows(IllegalArgumentException.class, () -> BackupId.of(unsafe), unsafe);
        }
    }

    @Test
    void requestPreservesSafeParametersAndPresentation() {
        BackupRequest request = BackupRequest.create("jea", " incident ")
                .withParameter("Compression_Level", " 5 ")
                .withPresentation(OperationPresentation.callerManaged());
        assertEquals("incident", request.comment().orElseThrow());
        assertEquals(Map.of("compression_level", "5"), request.parameters());
        assertEquals(FeedbackPolicy.CALLER_MANAGED, request.presentation().feedbackPolicy());
        assertThrows(
                IllegalArgumentException.class,
                () -> request.withParameter("current_save", "false"));
    }

    @Test
    void restoreRetainsCommentBackupIdAndRestrictedParameters() {
        RestoreRequest request = RestoreRequest.file("deathrewind:restore", "safe.7z")
                .withComment(" fatal fall ")
                .withParameter("verify_archive", "true");
        assertEquals("safe.7z", request.backupId().orElseThrow().value());
        assertEquals("fatal fall", request.comment().orElseThrow());
        assertEquals("true", request.parameters().get("verify_archive"));
        assertThrows(
                IllegalArgumentException.class,
                () -> request.withParameter("comment", "override"));
    }

    @Test
    void presentationIsImmutableAndMissingTemplatesFallBack() {
        OperationPresentation original = OperationPresentation.defaults();
        OperationPresentation customized = original.withTemplate(
                MessageSlot.RESTORE_KICK,
                new MessageTemplate("deathrewind.restore.kick"));
        assertFalse(original.template(MessageSlot.RESTORE_KICK).isPresent());
        assertEquals(
                "deathrewind.restore.kick",
                customized.template(MessageSlot.RESTORE_KICK).orElseThrow().translationKey());
        assertThrows(
                UnsupportedOperationException.class,
                () -> customized.templates().put(
                        MessageSlot.RESTORE_FAILED,
                        new MessageTemplate("x")));
    }

    @Test
    void apiVersionIsTwo() {
        assertEquals(2, MineBackupApi.API_VERSION);
    }

    @Test
    void currentWorldAutomationStateEnforcesModeConstraints() {
        Instant next = Instant.parse("2026-08-10T10:00:00Z");
        var reminder = new CurrentWorldAutomationState(
                true,
                Optional.of("Redstone"),
                CurrentWorldAutomationMode.REMIND,
                Optional.of(Duration.ofMinutes(30)),
                Optional.of(next));

        assertEquals(CurrentWorldAutomationMode.REMIND, reminder.mode());
        assertThrows(IllegalArgumentException.class, () -> new CurrentWorldAutomationState(
                true, Optional.empty(), CurrentWorldAutomationMode.OFF,
                Optional.of(Duration.ofMinutes(1)), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CurrentWorldAutomationState(
                false, Optional.empty(), CurrentWorldAutomationMode.BACKUP,
                Optional.of(Duration.ofMinutes(1)), Optional.of(next)));
    }

    @Test
    void defaultApiMethodMapsOnlyLegacyEnabledStateToBackup() {
        MineBackupApi legacy = legacyApi(new RuntimeStatus(
                RuntimeEnvironment.INTEGRATED,
                true,
                false,
                Optional.of("not dedicated"),
                Optional.empty(),
                new AutoBackupState(
                        true,
                        Optional.of(Duration.ofMinutes(20)),
                        Optional.of(Instant.parse("2026-08-10T10:00:00Z"))),
                Optional.empty()));

        assertEquals(CurrentWorldAutomationMode.BACKUP, legacy.currentWorldAutomation().mode());
        assertEquals(Duration.ofMinutes(20), legacy.currentWorldAutomation().interval().orElseThrow());
    }

    private static MineBackupApi legacyApi(RuntimeStatus status) {
        return new MineBackupApi() {
            @Override
            public int apiVersion() {
                return API_VERSION;
            }

            @Override
            public OperationHandle<BackupResult> backupCurrent(BackupRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OperationHandle<RestoreResult> restoreCurrent(RestoreRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletionStage<BackupCatalogResult> listCurrentBackups(BackupCatalogRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RuntimeStatus runtimeStatus() {
                return status;
            }
        };
    }
}
