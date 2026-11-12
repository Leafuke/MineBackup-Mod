package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.RestoreRequest;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkCodec;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ParameterReliabilityTest {
    @Test void rejectsInvalidKeysAtRequestConstruction() {
        for (String key : List.of("cmd", "CMD", "from", "request_id", "current_save", "a;b", "a=b", "路径")) {
            assertThrows(IllegalArgumentException.class,
                    () -> BackupRequest.create("test").withParameter(key, "value"), key);
            assertThrows(IllegalArgumentException.class,
                    () -> RestoreRequest.latest("test").withParameter(key, "value"), key);
        }
        assertThrows(IllegalArgumentException.class, () -> BackupRequest.create("test")
                .withParameters(Map.of("Mode", "one", "mode", "two")));
        assertEquals("full", BackupRequest.create("test").withParameter(" BACKUP_MODE ", "full")
                .parameters().get("backup_mode"));
    }

    @Test void encodesListItemsBeforeJoiningAndPreservesScalarPunctuation() throws Exception {
        for (String key : List.of("backup_blacklist", "backup_whitelist", "scope_dimensions")) {
            assertTrue(KnotLinkRequest.command("BACKUP").parameter(key, "data/,, ,中文/%2C")
                    .serialize().contains(key + "=data%2F,%E4%B8%AD%E6%96%87%2F%252C"));
        }
        for (String key : List.of("restore_whitelist", "restore_preserve_paths")) {
            assertTrue(KnotLinkRequest.command("RESTORE").parameter(key, "data/,playerdata/")
                    .serialize().contains(key + "=data%2F,playerdata%2F"));
        }
        var scalar = KnotLinkRequest.command("BACKUP").field("comment", "a,b;中文%2C").serialize();
        assertEquals("a,b;中文%2C", KnotLinkCodec.parse(scalar).get("comment"));
        assertTrue(scalar.contains("a%2Cb%3B"));
        assertEquals("a%2Cb,c", KnotLinkCodec.encodeList(List.of("a,b", "c")));
    }
}
