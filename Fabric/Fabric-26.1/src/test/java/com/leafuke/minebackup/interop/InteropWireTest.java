package com.leafuke.minebackup.interop;

import static com.leafuke.minebackup.interop.InteropWire.*;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

class InteropWireTest {
    @TempDir Path temp;

    @Test
    void consumesHostFixturesAndPreservesLiteralEscapes() throws Exception {
        String source;
        try (var in = getClass().getResourceAsStream("/interop-v3/wire.json")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var samples = JsonParser.parseString(source).getAsJsonObject().getAsJsonArray("messages");
        assertEquals(7, samples.size());
        for (var sample : samples) {
            String data = encode(sample.getAsJsonObject());
            assertFalse(data.contains("&"));
            assertEquals(sample, decode(data, MAX_BYTES));
        }
        var submit = samples.get(0).getAsJsonObject();
        var first =
                reply(
                        samples.get(1).getAsJsonObject(),
                        uuid(submit, "rid"),
                        uuid(submit, "instanceId"));
        assertEquals("queued", InteropSnapshot.parse(first).state());
        assertThrows(
                java.io.IOException.class,
                () ->
                        reply(
                                samples.get(1).getAsJsonObject(),
                                UUID.randomUUID(),
                                uuid(submit, "instanceId")));
        var terminal =
                InteropSnapshot.parse(samples.get(4).getAsJsonObject().getAsJsonObject("body"));
        assertTrue(bool(terminal.result(), "important"));
        var unsafe = samples.get(5).getAsJsonObject().getAsJsonObject("body").deepCopy();
        unsafe.add(
                "step",
                object(
                        "stepId",
                        UUID.randomUUID(),
                        "kind",
                        "open",
                        "sessionId",
                        UUID.randomUUID(),
                        "targetRef",
                        "w",
                        "status",
                        "pending",
                        "remainingMs",
                        4000));
        assertThrows(java.io.IOException.class, () -> InteropSnapshot.parse(unsafe));
        assertEquals(
                "&*& \\u0026 世界", string(decode(encode(object("s", "&*& \\u0026 世界")), 100), "s"));
    }

    @Test
    void rejectsDuplicateFieldsWrongNumbersAndExcessDepth() {
        assertThrows(java.io.IOException.class, () -> decode("{\"v\":3,\"v\":2}", 100));
        assertThrows(java.io.IOException.class, () -> decode("{\"x\":NaN}", 100));
        assertThrows(
                java.io.IOException.class,
                () -> decode("{\"x\":" + "[".repeat(16) + "1" + "]".repeat(16) + "}", 100));
        assertThrows(java.io.IOException.class, () -> integer(object("n", "3"), "n", 3, 3));
        assertThrows(java.io.IOException.class, () -> uuid(object("id", "1-1-1-1-1"), "id"));
    }

    @Test
    void reservesIntersectingPhysicalRootsAndHonorsOnlyOwningRestore() throws Exception {
        Path guards = temp.resolve("guards"),
                world = Files.createDirectories(temp.resolve("saves/world"));
        var pending = WorldAdmission.enter(guards, world, null, null);
        assertThrows(
                java.io.IOException.class,
                () -> WorldAdmission.enter(guards, world.getParent(), null, null));
        assertThrows(
                java.io.IOException.class,
                () -> WorldAdmission.enter(guards, world, pending.id(), UUID.randomUUID()));
        assertThrows(java.io.IOException.class,()->WorldAdmission.enter(guards,world,pending.id(),pending.id()));
        pending.close();
        try (var second = WorldAdmission.enter(guards, world, null, null)) {
            assertNotNull(second.id());
        }
    }
    @Test void backendGrantOnlyCoversExactPhysicalWorldAndOriginalRecordIsNotBlindlyDeleted() throws Exception {
        Path guards=Files.createDirectories(temp.resolve("guard"));
        Path world=Files.createDirectories(temp.resolve("saves/world"));
        UUID operation=UUID.randomUUID(),domain=UUID.randomUUID();
        var roots=new com.google.gson.JsonArray();roots.add(world.getParent().toString());
        var record=object("format",1,"operationId",operation,"domainId",domain,"product","folderrewind","roots",roots);
        Path path=guards.resolve(operation+".json");InteropFiles.writeObject(path,record);
        assertThrows(java.io.IOException.class,()->WorldAdmission.enter(guards,world,operation,domain));
        roots.set(0,new com.google.gson.JsonPrimitive(world.toString()));InteropFiles.writeObject(path,record);
        try(var r=WorldAdmission.enter(guards,world,operation,domain)){assertNull(r.id());}
        assertTrue(Files.exists(path));Files.delete(path);
        var local=WorldAdmission.enter(guards,world,null,null);
        Path own=guards.resolve(local.id()+".json");
        var replaced=InteropFiles.readObject(own);replaced.addProperty("domainId",domain.toString());
        InteropFiles.writeObject(own,replaced);
        assertThrows(java.io.IOException.class,local::close);
        assertEquals(replaced,InteropFiles.readObject(own));
    }
}
