package com.leafuke.minebackup.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Pure file-system probe shared by integrated restore and the dedicated
 * sidecar. A restore acknowledgement is safe only after both the session lock
 * and representative world files can be opened for writing.
 */
public final class WorldReleaseProbe {
    private WorldReleaseProbe() {
    }

    public static boolean isReleased(Path root) {
        return canAcquireSessionLock(root) && canAccessCriticalFiles(root);
    }

    static boolean canAcquireSessionLock(Path root) {
        Path lockPath = root.resolve("session.lock");
        if (!Files.exists(lockPath)) {
            return true;
        }
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            return lock != null;
        } catch (OverlappingFileLockException | IOException exception) {
            return false;
        }
    }

    static boolean canAccessCriticalFiles(Path root) {
        if (!canOpenForWrite(root.resolve("level.dat"))
                || !canOpenForWrite(root.resolve("level.dat_old"))) {
            return false;
        }
        Path regionDirectory = root.resolve("region");
        if (!Files.isDirectory(regionDirectory)) {
            return true;
        }
        try (java.util.stream.Stream<Path> files = Files.list(regionDirectory)) {
            Path sample = files.filter(Files::isRegularFile).findFirst().orElse(null);
            return sample == null || canOpenForWrite(sample);
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean canOpenForWrite(Path path) {
        if (!Files.isRegularFile(path)) {
            return true;
        }
        try (FileChannel ignored = FileChannel.open(path, StandardOpenOption.WRITE)) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
