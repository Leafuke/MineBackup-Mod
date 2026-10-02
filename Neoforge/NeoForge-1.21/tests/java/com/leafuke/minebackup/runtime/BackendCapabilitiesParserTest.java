package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackendCapabilitiesResult;

public final class BackendCapabilitiesParserTest {
    private BackendCapabilitiesParserTest() {}
    private static void assertTrue(boolean condition) { if (!condition) throw new AssertionError(); }
    private static void assertEquals(Object expected, Object actual) { if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual); }
    private static void assertThrows(Class<? extends Throwable> type, Runnable call) {
        try { call.run(); } catch (Throwable error) { if (type.isInstance(error)) return; throw new AssertionError(error); }
        throw new AssertionError("Expected " + type.getName());
    }
    public static void main(String[] args) {
        parsesChoicesDefaultsAndCommandsImmutably();
        rejectsMalformedOrUnsupportedManifestInsteadOfAssumingSupport();
        unavailableCannotContainAdvertisedCommands();
        legacyApiDefaultsToUnsupported();
        System.out.println("Backend capability contracts passed");
    }
    private static final String MANIFEST = """
            {"specVersion":"1.0","manifestVersion":"3.0.0","openSocket":{"restore":{"args":{
              "cmd":{"type":"static","value":"RESTORE"},
              "mode":{"type":"optional","defaultVal":"clean","options":[["clean","clean"],["overwrite","overwrite"]]},
              "restore_preserve_paths":{"type":"input"}}}}}
            """;

    private static void parsesChoicesDefaultsAndCommandsImmutably() {
        var result = BackendCapabilitiesParser.parse(MANIFEST);
        assertEquals(BackendCapabilitiesResult.Outcome.SUCCESS, result.outcome());
        var mode = result.commands().get("RESTORE").parameters().get("mode");
        assertEquals("clean", mode.defaultValue().orElseThrow());
        assertEquals(java.util.List.of("clean", "overwrite"), mode.choices());
        assertThrows(UnsupportedOperationException.class, () -> result.commands().clear());
        assertTrue(result.commands().get("RESTORE").parameters().containsKey("restore_preserve_paths"));
    }

    private static void rejectsMalformedOrUnsupportedManifestInsteadOfAssumingSupport() {
        for (String malformed : java.util.List.of("{}", "[]", "not json", MANIFEST.replace("1.0", "2.0"),
                MANIFEST.replace("\"clean\",\"clean\"", "\"clean\""), MANIFEST.replace("\"type\":\"static\"", "\"type\":\"input\"")))
            assertThrows(RuntimeException.class, () -> BackendCapabilitiesParser.parse(malformed));
    }

    private static void unavailableCannotContainAdvertisedCommands() {
        assertTrue(BackendCapabilitiesResult.unavailable(BackendCapabilitiesResult.Outcome.UNSUPPORTED, "old").commands().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new BackendCapabilitiesResult(BackendCapabilitiesResult.Outcome.FAILED,
                "", BackendCapabilitiesParser.parse(MANIFEST).commands(), "failed"));
    }

    private static void legacyApiDefaultsToUnsupported() {
        var legacy = new com.leafuke.minebackup.api.v2.MineBackupApi() {
            public int apiVersion() { return 2; }
            public com.leafuke.minebackup.api.v2.RuntimeStatus runtimeStatus() { throw new AssertionError("Discovery must not mutate runtime"); }
            public com.leafuke.minebackup.api.v2.OperationHandle<com.leafuke.minebackup.api.v2.BackupResult> backupCurrent(com.leafuke.minebackup.api.v2.BackupRequest request) { throw new AssertionError("Unexpected backup"); }
            public com.leafuke.minebackup.api.v2.OperationHandle<com.leafuke.minebackup.api.v2.RestoreResult> restoreCurrent(com.leafuke.minebackup.api.v2.RestoreRequest request) { throw new AssertionError("Unexpected restore"); }
            public java.util.concurrent.CompletionStage<com.leafuke.minebackup.api.v2.BackupCatalogResult> listCurrentBackups(com.leafuke.minebackup.api.v2.BackupCatalogRequest request) { throw new AssertionError("Unexpected catalog query"); }
        };
        var result = legacy.backendCapabilities(com.leafuke.minebackup.api.v2.BackendCapabilitiesRequest.create("test:discovery")).toCompletableFuture().join();
        assertEquals(BackendCapabilitiesResult.Outcome.UNSUPPORTED, result.outcome());
        assertTrue(result.commands().isEmpty());
    }
}
