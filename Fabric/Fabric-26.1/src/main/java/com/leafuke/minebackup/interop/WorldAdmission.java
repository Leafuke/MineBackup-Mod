package com.leafuke.minebackup.interop;

import static com.leafuke.minebackup.interop.InteropWire.*;

import com.google.gson.JsonArray;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;

/** Shared byte-range lock and physical-tree overlap convention used by both backup products. */
public final class WorldAdmission {
    private WorldAdmission() {}

    public record Reservation(Path directory, UUID id) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (id == null) return;
            locked(
                    directory,
                    () -> {
                        Files.deleteIfExists(directory.resolve(id + ".json"));
                        return null;
                    });
        }
    }

    public static Reservation enter(Path world, UUID allowed, UUID domain) throws IOException {
        return enter(InteropFiles.root().resolve("guards"), world, allowed, domain);
    }

    static Reservation enter(Path dir, Path world, UUID allowed, UUID domain) throws IOException {
        Path actual = world.toRealPath();
        return locked(
                dir,
                () -> {
                    boolean own = false;
                    try (var records = Files.newDirectoryStream(dir, "*.json")) {
                        for (Path record : records) {
                            var j = InteropFiles.readObject(record);
                            integer(j, "format", 1, 1);
                            var id = uuid(j, "operationId");
                            var owner = uuid(j, "domainId");
                            string(j, "product");
                            if (!record.getFileName().toString().equals(id + ".json"))
                                throw new IOException("invalid_guard");
                            var roots = j.getAsJsonArray("roots");
                            if (roots == null || roots.isEmpty())
                                throw new IOException("invalid_guard");
                            for (var root : roots) {
                                Path path = Path.of(root.getAsString());
                                if (!path.isAbsolute()) throw new IOException("invalid_guard");
                                Path physical =
                                        Files.exists(path) ? path.toRealPath() : path.normalize();
                                if (actual.startsWith(physical) || physical.startsWith(actual)) {
                                    if (id.equals(allowed) && owner.equals(domain)) own = true;
                                    else throw new IOException("world_in_use");
                                }
                            }
                        }
                    }
                    if (allowed != null) {
                        if (!own) throw new IOException("missing_open_guard");
                        return new Reservation(dir, null);
                    }
                    UUID id = UUID.randomUUID();
                    var roots = new JsonArray();
                    roots.add(actual.toString());
                    var record =
                            object(
                                    "format",
                                    1,
                                    "operationId",
                                    id,
                                    "domainId",
                                    id,
                                    "product",
                                    "minebackup-mod",
                                    "roots",
                                    roots);
                    InteropFiles.writeObject(dir.resolve(id + ".json"), record);
                    return new Reservation(dir, id);
                });
    }

    @FunctionalInterface
    private interface IO<T> {
        T run() throws IOException;
    }

    private static synchronized <T> T locked(Path dir, IO<T> work) throws IOException {
        Files.createDirectories(dir);
        try (var channel =
                        FileChannel.open(
                                dir.resolve("admission.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.lock(0, 1, false)) {
            return work.run();
        } catch (RuntimeException bad) {
            throw new IOException("invalid_guard", bad);
        }
    }
}
