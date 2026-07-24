package com.leafuke.minebackup.knotlink.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class KnotLinkRequest {
    public static final String CALLER_ID = "minebackup.mod";

    private final Map<String, String> fields;

    private KnotLinkRequest(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("KnotLink command must not be blank");
        }
        this.fields = new LinkedHashMap<>();
        this.fields.put("cmd", command.trim().toUpperCase(java.util.Locale.ROOT));
    }

    public static KnotLinkRequest command(String command) {
        return new KnotLinkRequest(command);
    }

    public KnotLinkRequest conversation() {
        return conversation(UUID.randomUUID());
    }

    public KnotLinkRequest conversation(UUID requestId) {
        fields.put("from", CALLER_ID);
        fields.put("request_id", java.util.Objects.requireNonNull(requestId, "requestId").toString());
        return this;
    }

    public KnotLinkRequest field(String key, Object value) {
        if (key == null || key.isBlank() || "cmd".equalsIgnoreCase(key)) {
            throw new IllegalArgumentException("Invalid KnotLink request field: " + key);
        }
        fields.put(key, value == null ? "" : String.valueOf(value));
        return this;
    }

    public String commandName() {
        return fields.get("cmd");
    }

    public String serialize() {
        return KnotLinkCodec.serialize(fields);
    }
}
