package com.leafuke.minebackup.dedicated;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

/** A child JVM cannot see a mod loader's virtual file systems. */
final class SidecarClasspath {
    private static final String ENTRY = "com/leafuke/minebackup/dedicated/DedicatedRestoreSidecar.class";
    private SidecarClasspath() {}

    static String resolve(List<Path> origins) {
        var usable = origins.stream().filter(SidecarClasspath::containsSidecar)
                .map(path -> path.toAbsolutePath().normalize().toString()).distinct().toList();
        if (usable.isEmpty()) throw new IllegalStateException("No physical MineBackup sidecar JAR or class directory");
        return String.join(File.pathSeparator, usable);
    }

    private static boolean containsSidecar(Path path) {
        if (path == null || !"file".equalsIgnoreCase(path.getFileSystem().provider().getScheme())) return false;
        if (Files.isDirectory(path)) return Files.isRegularFile(path.resolve(ENTRY));
        if (!Files.isRegularFile(path)) return false;
        try (var archive = new ZipFile(path.toFile())) {
            return archive.getEntry(ENTRY) != null;
        } catch (IOException exception) {
            return false;
        }
    }
}
