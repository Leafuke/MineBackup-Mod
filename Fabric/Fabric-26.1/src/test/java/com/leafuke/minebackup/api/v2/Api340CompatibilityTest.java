package com.leafuke.minebackup.api.v2;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Api340CompatibilityTest {
    @TempDir Path temporary;

    @Test void implementationAndConsumerCompiledAgainstOldInterfaceStillLoad() throws Exception {
        Path source = temporary.resolve("MineBackupApi.java");
        Path legacy = temporary.resolve("Legacy340Fixture.java");
        Files.writeString(source, """
                package com.leafuke.minebackup.api.v2;
                public interface MineBackupApi {
                    int API_VERSION = 2;
                    int apiVersion();
                    OperationHandle<BackupResult> backupCurrent(BackupRequest request);
                    OperationHandle<RestoreResult> restoreCurrent(RestoreRequest request);
                    java.util.concurrent.CompletionStage<BackupCatalogResult> listCurrentBackups(BackupCatalogRequest request);
                    RuntimeStatus runtimeStatus();
                }
                """);
        Files.writeString(legacy, """
                package com.leafuke.minebackup.api.v2;
                public class Legacy340Fixture implements MineBackupApi {
                    public int apiVersion() { return 2; }
                    public OperationHandle<BackupResult> backupCurrent(BackupRequest request) { return null; }
                    public OperationHandle<RestoreResult> restoreCurrent(RestoreRequest request) { return null; }
                    public java.util.concurrent.CompletionStage<BackupCatalogResult> listCurrentBackups(BackupCatalogRequest request) { return null; }
                    public RuntimeStatus runtimeStatus() { return null; }
                    public static int oldConsumer(MineBackupApi api) { return api.apiVersion(); }
                }
                """);
        // Resolve public value types from their actual output directory, without loading Minecraft.
        String classpath = Path.of(MineBackupApi.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", classpath, "-d", temporary.toString(), source.toString(), legacy.toString()));
        Files.delete(temporary.resolve("com/leafuke/minebackup/api/v2/MineBackupApi.class"));
        try (var loader = new URLClassLoader(new java.net.URL[] {temporary.toUri().toURL()}, MineBackupApi.class.getClassLoader())) {
            Class<?> type = loader.loadClass("com.leafuke.minebackup.api.v2.Legacy340Fixture");
            var api = (MineBackupApi) type.getConstructor().newInstance();
            assertEquals(2, type.getMethod("oldConsumer", MineBackupApi.class).invoke(null, api));
            assertEquals(RestoreCancelResult.UNSUPPORTED,
                    api.cancelRestore(RestoreCancelRequest.create("test", UUID.randomUUID())));
            assertEquals(BackendStatusResult.Outcome.UNSUPPORTED,
                    api.backendStatus(BackendStatusRequest.create("test")).toCompletableFuture().get().outcome());
            assertEquals(BackendCapabilitiesResult.Outcome.UNSUPPORTED,
                    api.backendCapabilities(BackendCapabilitiesRequest.create("test")).toCompletableFuture().get().outcome());
        }
    }

    @Test void requestsNormalizeCallerAndRequireAnOperationId() {
        assertEquals("test:addon", RestoreCancelRequest.create(" TEST:Addon ", UUID.randomUUID()).callerId());
        assertEquals("test:addon", BackendStatusRequest.create(" TEST:Addon ").callerId());
        assertThrows(NullPointerException.class, () -> RestoreCancelRequest.create("test", null));
        assertThrows(IllegalArgumentException.class, () -> BackendStatusRequest.create("bad/id"));
    }
}
