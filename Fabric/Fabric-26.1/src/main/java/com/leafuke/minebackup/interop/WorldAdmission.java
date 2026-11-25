package com.leafuke.minebackup.interop;

import static com.leafuke.minebackup.interop.InteropWire.*;

import com.google.gson.JsonArray;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Shared byte-range lock and physical-tree overlap convention used by both backup products. */
public final class WorldAdmission {
    private WorldAdmission() {}

    public record Reservation(Path directory, UUID id, JsonObject expected, FileChannel owner) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (id == null) return;
            locked(
                    directory,
                    () -> {
                        Path marker=directory.resolve(id+".json");
                        try{
                            if(Files.exists(marker)){
                                if(!InteropFiles.readObject(marker).equals(expected))throw new IOException("registration_changed");
                                Files.delete(marker);
                            }
                        }finally{owner.close();}
                        Files.deleteIfExists(directory.resolve(id+".mod-open"));
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
                                    if(id.equals(allowed) && owner.equals(domain) && physical.equals(actual)
                                            && !string(j,"product").equals("minebackup-mod"))own=true;
                                    else if(allowed==null && roots.size()==1 && physical.equals(actual) && string(j,"product").equals("minebackup-mod")
                                            && retireAbandonedManualOpen(dir,j,physical))continue;
                                    else throw new IOException("world_in_use");
                                }
                            }
                        }
                    }
                    if (allowed != null) {
                        if (!own) throw new IOException("missing_open_guard");
                        return new Reservation(dir,null,null,null);
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
                    var ownerMarker=dir.resolve(id+".mod-open");
                    var channel=FileChannel.open(ownerMarker,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,StandardOpenOption.READ);
                    try {
                        channel.lock(0,1,false);
                        var bytes=ByteBuffer.wrap(encode(record).getBytes(StandardCharsets.UTF_8));
                        while(bytes.hasRemaining())channel.write(bytes);
                        channel.force(true);
                        InteropFiles.writeObject(dir.resolve(id+".json"),record);
                        return new Reservation(dir,id,record,channel);
                    }catch(Exception failure){channel.close();throw failure;}
                });
    }

    /** Only a crashed manual-open registration carrying our matching owner marker may be retired. */
    private static boolean retireAbandonedManualOpen(Path dir,JsonObject record,Path physical)throws IOException {
        UUID id=uuid(record,"operationId");
        Path marker=dir.resolve(id+".mod-open");
        if(!Files.isRegularFile(marker))return false;
        try(var owner=FileChannel.open(marker,StandardOpenOption.READ,StandardOpenOption.WRITE);
            FileLock lease=owner.tryLock(0,1,false)){
            if(lease==null || owner.size()>65536 || owner.size()<=0) return false;
            var buffer=ByteBuffer.allocate((int)owner.size());
            while(buffer.hasRemaining()&&owner.read(buffer)!=-1){}
            buffer.flip();
            if(!decode(StandardCharsets.UTF_8.newDecoder().decode(buffer).toString(),65536).equals(record)
                    || !com.leafuke.minebackup.runtime.WorldReleaseProbe.isReleased(physical))return false;
            Files.delete(dir.resolve(id+".json"));
        }catch(OverlappingFileLockException held){return false;}
        Files.deleteIfExists(marker);
        return true;
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
