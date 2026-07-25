package com.leafuke.minebackup.api.v2;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
