package com.leafuke.minebackup.dedicated;

import static com.leafuke.minebackup.interop.InteropWire.object;

import static org.junit.jupiter.api.Assertions.*;

import com.leafuke.minebackup.interop.InteropFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

class InteropRestoreSidecarTest {
    @TempDir Path dir;

    @Test
    void processStartReceiptSurvivesMissingAckOrSecondChild() throws Exception {
        var owner = object("operationId", UUID.randomUUID(), "handoffId", UUID.randomUUID());
        InteropRestoreSidecar.result(dir, owner, "scriptStarted");
        InteropRestoreSidecar.result(dir, owner, "unconfirmed");
        assertEquals(
                "scriptStarted",
                InteropFiles.readObject(dir.resolve("result.json")).get("outcome").getAsString());
        assertThrows(
                IllegalStateException.class,
                () ->
                        InteropRestoreSidecar.result(
                                dir,
                                object(
                                        "operationId",
                                        UUID.randomUUID(),
                                        "handoffId",
                                        UUID.randomUUID()),
                                "failed"));
    }
}
