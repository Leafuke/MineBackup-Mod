package com.leafuke.minebackup.knotlink.protocol;

import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class KnotLinkRequest {
    public static final String CALLER_ID = "minebackup.mod";

    private static final Set<String> BACKUP_LIST_PARAMETERS =
            Set.of("backup_blacklist", "backup_whitelist", "scope_dimensions");
    private static final Set<String> RESTORE_LIST_PARAMETERS =
            Set.of("restore_whitelist", "restore_preserve_paths");

    private final String command;
    private final Map<String, KnotLinkCodec.FieldValue> fields;

    private KnotLinkRequest(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("KnotLink command must not be blank");
        }
        this.fields = new LinkedHashMap<>();
        this.command = command.trim().toUpperCase(Locale.ROOT);
        this.fields.put("cmd", new KnotLinkCodec.ScalarValue(this.command));
    }

    public static KnotLinkRequest command(String command) {
        return new KnotLinkRequest(command);
    }

    public KnotLinkRequest conversation() {
        return conversation(UUID.randomUUID());
    }

    public KnotLinkRequest conversation(UUID requestId) {
        field("from", CALLER_ID);
        field("request_id", java.util.Objects.requireNonNull(requestId, "requestId").toString());
        return this;
    }

    public KnotLinkRequest field(String key, Object value) {
        validateFieldKey(key);
        fields.put(key, new KnotLinkCodec.ScalarValue(value == null ? "" : String.valueOf(value)));
        return this;
    }

    /** Items are unencoded text; separators are emitted only after each item is encoded. */
    public KnotLinkRequest listField(String key, List<String> values) {
        validateFieldKey(key);
        fields.put(key, new KnotLinkCodec.ListValue(values));
        return this;
    }

    /** API map list values are comma-separated text, never pre-encoded protocol values. */
    public KnotLinkRequest parameter(String key, String value) {
        validateFieldKey(key);
        Set<String> lists = switch (command) {
            case "BACKUP" -> BACKUP_LIST_PARAMETERS;
            case "RESTORE" -> RESTORE_LIST_PARAMETERS;
            default -> Set.of();
        };
        return lists.contains(key.toLowerCase(Locale.ROOT))
                ? listField(key, Arrays.asList((value == null ? "" : value).split(",", -1)))
                : field(key, value);
    }

    private static void validateFieldKey(String key) {
        if (key == null || key.isBlank() || "cmd".equalsIgnoreCase(key)) {
            throw new IllegalArgumentException("Invalid KnotLink request field: " + key);
        }
    }

    public String commandName() {
        return command;
    }

    public String serialize() {
        return KnotLinkCodec.serializeFields(fields);
    }
}
