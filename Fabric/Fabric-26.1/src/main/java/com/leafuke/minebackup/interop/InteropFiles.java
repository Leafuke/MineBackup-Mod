package com.leafuke.minebackup.interop;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Small process-independent records. Nothing is written to a world directory. */
public final class InteropFiles {
    private InteropFiles() {}

    public record Endpoint(String product, UUID domain, UUID instance, String label) {
        public String choice() {
            return product + "/" + domain;
        }

        public String display() {
            return label + " (" + domain.toString().substring(0, 8) + ")";
        }

        static Endpoint read(Path f) throws IOException {
            JsonObject j = readObject(f);
            InteropWire.integer(j, "format", 1, 1);
            InteropWire.integer(j, "protocolMajor", 3, 3);
            String product = InteropWire.string(j, "product");
            if (!Set.of("folderrewind", "minebackup").contains(product))
                throw new IOException("unknown_product");
            UUID instance = InteropWire.uuid(j, "instanceId");
            if (!f.getFileName().toString().equals(instance + ".json"))
                throw new IOException("wrong_instance");
            return new Endpoint(
                    product,
                    InteropWire.uuid(j, "domainId"),
                    instance,
                    InteropWire.string(j, "label"));
        }
    }

    public static Path root() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.startsWith("windows")) {
            String local = System.getenv("LOCALAPPDATA");
            Path base =
                    local == null || local.isBlank()
                            ? home.resolve("AppData/Local")
                            : Path.of(local);
            return base.resolve("FolderRewind/interop-v3");
        }
        if(!os.contains("mac"))home=unixHome(home);
        return os.contains("mac")
                ? home.resolve("Library/Application Support/FolderRewind/interop-v3")
                : home.resolve(".local/state/folderrewind/interop-v3");
    }

    private static Path unixHome(Path fallback){
        // The native passwd entry is stable across GUI launchers and systemd environments.
        try{
            long uid=((Number)Files.getAttribute(Path.of("/proc/self"),"unix:uid")).longValue();
            try(var lines=Files.lines(Path.of("/etc/passwd"))){
                var entry=lines.map(s->s.split(":",-1)).filter(p->p.length==7 && p[2].equals(Long.toString(uid)))
                        .map(p->Path.of(p[5])).filter(Path::isAbsolute).findFirst();
                if(entry.isPresent())return entry.get();
            }
            // The VM initializes user.home from the native account database, including NSS.
        }catch(IOException | UnsupportedOperationException ignored){}
        return fallback;
    }

    static List<Endpoint> endpoints(Path directory) throws IOException {
        if (!Files.exists(directory)) return List.of();
        try (var paths = Files.newDirectoryStream(directory, "*.json")) {
            var endpoints = new ArrayList<Endpoint>();
            for (Path path : paths) {
                if (endpoints.size() >= 64) throw new IOException("too_many_endpoints");
                endpoints.add(Endpoint.read(path));
            }
            return List.copyOf(endpoints);
        }
    }

    public static JsonObject readObject(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] data = in.readNBytes(65537);
            if (data.length > 65536) throw new IOException("oversized_record");
            String text =
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
            return InteropWire.decode(text, 65536);
        }
    }

    public static void writeObject(Path file, JsonObject object) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".interop-", ".tmp");
        try {
            byte[] data = InteropWire.encode(object).getBytes(StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            if (!System.getProperty("os.name", "").startsWith("Windows")) {
                try (var dir = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
                    dir.force(true);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
