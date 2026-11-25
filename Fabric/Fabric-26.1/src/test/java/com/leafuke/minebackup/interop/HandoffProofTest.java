package com.leafuke.minebackup.interop;

import static com.leafuke.minebackup.interop.InteropWire.object;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class HandoffProofTest {
    @TempDir Path dir;

    @Test
    void successorRequiresWorldDomainAndBothCommitMarkers() throws Exception {
        Path world = Files.createDirectory(dir.resolve("world"));
        Path restarts = Files.createDirectory(dir.resolve("handoffs"));
        UUID id = UUID.randomUUID();
        Path folder = Files.createDirectory(restarts.resolve(id.toString()));
        var endpoint =
                new InteropFiles.Endpoint(
                        "minebackup", UUID.randomUUID(), UUID.randomUUID(), "one");
        var choice = new InteropClient.Choice(endpoint, "target", "world", "World", Set.of());
        InteropFiles.writeObject(
                folder.resolve("handoff.json"),
                object(
                        "handoffId",
                        id,
                        "domainId",
                        endpoint.domain(),
                        "targetRef",
                        "target",
                        "world",
                        world.toString()));
        InteropFiles.writeObject(folder.resolve("committed.json"), object("handoffId", id));
        assertTrue(HandoffProof.find(restarts, world, choice).isEmpty());
        InteropFiles.writeObject(folder.resolve("launch.json"), object("handoffId", id));
        var proof = HandoffProof.find(restarts, world, choice).orElseThrow();
        proof.confirm(object("handoffId", UUID.randomUUID()));
        assertTrue(HandoffProof.find(restarts, world, choice).isPresent());
        proof.confirm(object("handoffId", id));
        assertTrue(HandoffProof.find(restarts, world, choice).isEmpty());
    }
}
