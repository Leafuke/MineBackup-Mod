package com.leafuke.minebackup.interop;

import static com.leafuke.minebackup.interop.InteropWire.*;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import java.util.UUID;

record HandoffProof(Path dir, UUID id) {
    static Optional<HandoffProof> find(Path restart, Path world, InteropClient.Choice target)
            throws IOException {
        if (!Files.isDirectory(restart)) return Optional.empty();
        HandoffProof found = null;
        try (var children = Files.newDirectoryStream(restart)) {
            for (Path dir : children) {
                if (!Files.isDirectory(dir)
                        || Files.exists(dir.resolve("bound.json"))
                        || !Files.isRegularFile(dir.resolve("launch.json"))
                        || !Files.isRegularFile(dir.resolve("committed.json"))) continue;
                var h = InteropFiles.readObject(dir.resolve("handoff.json"));
                if (!uuid(h, "domainId").equals(target.endpoint().domain())
                        || !string(h, "targetRef").equals(target.target())
                        || !Files.isSameFile(Path.of(string(h, "world")), world)) continue;
                UUID id = uuid(h, "handoffId");
                if (!uuid(InteropFiles.readObject(dir.resolve("launch.json")), "handoffId")
                                .equals(id)
                        || !uuid(
                                        InteropFiles.readObject(dir.resolve("committed.json")),
                                        "handoffId")
                                .equals(id)) continue;
                if (Files.isRegularFile(dir.resolve("result.json"))) {
                    var result = InteropFiles.readObject(dir.resolve("result.json"));
                    if (!uuid(result, "handoffId").equals(id)
                            || "failed".equals(string(result, "outcome"))) continue;
                }
                if (found != null) return Optional.empty();
                found = new HandoffProof(dir, id);
            }
        }
        return Optional.ofNullable(found);
    }

    void confirm(JsonObject reply) throws IOException {
        if (reply.has("handoffId") && uuid(reply, "handoffId").equals(id))
            InteropFiles.writeObject(dir.resolve("bound.json"), object("handoffId", id));
    }
}
