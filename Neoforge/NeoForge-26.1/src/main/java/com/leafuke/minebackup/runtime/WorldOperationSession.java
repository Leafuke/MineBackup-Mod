package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.restore.RestoreSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Pure state owned exclusively under CurrentWorldOperationCoordinator's monitor. */
final class WorldOperationSession {
    enum Stage { WAITING, HANDSHAKE, SAVING_BACKUP, FROZEN, PREPARING_RESTORE,
        RELEASING, RESTORING, WAITING_REJOIN, REJOINING, FINISHING }
    record Token(long serverGeneration, UUID operationId, long revision) {}
    record Timeout(OperationFailure.Code code, String message) {}

    private final UUID id;
    private final long serverGeneration;
    private final boolean restore;
    private final boolean remote;
    private final Set<String> worldAliases;
    private final LongSupplier clock;
    private final String wireRequestId;
    private final String wireFrom;
    private final Map<String, String> target = new HashMap<>();
    private Stage stage = Stage.WAITING;
    private RestoreSession.Action action;
    private long revision;
    private long deadline;
    private String world;
    private RestoreSession.RejoinInfo rejoin;
    private boolean frozen;
    private boolean restoreSucceeded;

    WorldOperationSession(UUID id, long serverGeneration, boolean restore, boolean remote,
            Set<String> worldAliases, Map<String, String> origin, LongSupplier clock) {
        this.id = id;
        this.serverGeneration = serverGeneration;
        this.restore = restore;
        this.remote = remote;
        this.worldAliases = Set.copyOf(worldAliases);
        this.clock = clock;
        wireRequestId = remote ? identityValue(origin, "request_id") : id.toString();
        wireFrom = remote ? identityValue(origin, "from") : KnotLinkRequest.CALLER_ID;
        waitForCoordination();
    }

    Token token() { return new Token(serverGeneration, id, revision); }
    boolean current(Token token) { return stage != Stage.FINISHING && token().equals(token); }
    boolean owns(Token token) {
        return token != null && token.operationId().equals(id) && token.serverGeneration() == serverGeneration;
    }
    Stage stage() { return stage; }
    boolean restore() { return restore; }
    boolean frozen() { return frozen; }
    String world() { return world; }
    String wireRequestId() { return wireRequestId; }
    String wireFrom() { return wireFrom; }
    long deadline() { return deadline; }
    boolean expired() { return stage != Stage.FINISHING && clock.getAsLong() >= deadline; }

    boolean handshake(Map<String, String> fields) {
        var parsed = RestoreSession.Action.parse(fields.get("action"));
        if (stage != Stage.WAITING || parsed.isEmpty() || !metadataMatches(fields, true)
                || !matchesWorld(fields.get("world")) || (!restore && parsed.get() != RestoreSession.Action.BACKUP)) {
            return false;
        }
        world = fields.get("world").trim();
        action = parsed.get();
        revision++;
        target.clear();
        captureTarget(fields);
        stage = Stage.HANDSHAKE;
        deadline = clock.getAsLong() + Duration.ofSeconds(5).toNanos();
        return true;
    }

    boolean consume(RestoreSession.Action expected, Map<String, String> fields) {
        if (stage != Stage.HANDSHAKE || expired() || action != expected
                || !metadataMatches(fields, true) || !targetMatches(fields, true)) return false;
        captureTarget(fields);
        stage = expected == RestoreSession.Action.BACKUP ? Stage.SAVING_BACKUP : Stage.PREPARING_RESTORE;
        deadline = clock.getAsLong() + Duration.ofSeconds(30).toNanos();
        return true;
    }

    boolean freeze(Token token) {
        if (!current(token) || stage != Stage.SAVING_BACKUP) return false;
        stage = Stage.FROZEN;
        frozen = true;
        deadline = clock.getAsLong() + Duration.ofMinutes(10).toNanos();
        return true;
    }

    boolean backupTerminal(Map<String, String> fields) {
        String event = fields.get("event");
        if (!("backup_success".equals(event) || "backup_failed".equals(event)
                || (("command_completed".equals(event) || "command_failed".equals(event))
                    && "BACKUP".equalsIgnoreCase(fields.get("command"))))) return false;
        if (stage == Stage.FINISHING || (restore && stage != Stage.FROZEN)) return false;
        boolean identified = metadataMatches(fields, false);
        // A local API backup's result must be correlated. Legacy GUI backups need a bound freeze.
        if (!identified && !((remote || restore) && stage == Stage.FROZEN
                && metadataMatches(fields, true) && targetMatches(fields, true))) return false;
        return targetMatches(fields, false);
    }

    void backupStageFinished() {
        frozen = false;
        revision++; // Saved-world acknowledgements from this substage are now stale.
        waitForCoordination();
    }

    boolean beginRestore(Token token, RestoreSession.RejoinInfo info) {
        if (!current(token) || stage != Stage.PREPARING_RESTORE) return false;
        rejoin = info;
        stage = Stage.RELEASING;
        deadline = clock.getAsLong() + Duration.ofSeconds(8).toNanos();
        return true;
    }

    boolean prepareDedicated(Token token, int startupSeconds) {
        if (!current(token) || stage != Stage.PREPARING_RESTORE) return false;
        // The worker enforces the configured child deadline; allow its bounded cleanup too.
        deadline = clock.getAsLong() + Duration.ofSeconds(Math.max(30L, startupSeconds + 5L)).toNanos();
        return true;
    }

    boolean released(Token token) {
        if (!current(token) || stage != Stage.RELEASING) return false;
        stage = Stage.RESTORING;
        deadline = clock.getAsLong() + Duration.ofHours(1).toNanos();
        return true;
    }

    boolean restoreFinished(Map<String, String> fields) {
        if (stage != Stage.RESTORING || !lifecycleMatches(fields)) return false;
        String status = fields.getOrDefault("status", "");
        if (!("success".equalsIgnoreCase(status) || "failure".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status))) return false;
        if ("success".equalsIgnoreCase(status)) {
            restoreSucceeded = true;
            stage = Stage.WAITING_REJOIN;
            deadline = clock.getAsLong() + Duration.ofSeconds(30).toNanos();
        }
        return true;
    }

    RestoreSession.RejoinInfo beginRejoin(Map<String, String> fields) {
        if (stage != Stage.WAITING_REJOIN || !lifecycleMatches(fields)) return null;
        stage = Stage.REJOINING;
        // Client ticks retain their existing 500-tick limit; this bounds a lost client callback.
        deadline = clock.getAsLong() + Duration.ofSeconds(30).toNanos();
        return rejoin;
    }

    boolean restoreTerminal(Map<String, String> fields) {
        if (!restore || stage == Stage.FINISHING) return false;
        return switch (fields.getOrDefault("event", "")) {
            case "command_failed" -> "RESTORE".equalsIgnoreCase(fields.get("command"))
                    && metadataMatches(fields, false) && targetMatches(fields, false);
            case "restore_cancelled" -> stage != Stage.WAITING && lifecycleMatches(fields);
            case "hot_restore_complete" -> restoreSucceeded
                    && (stage == Stage.WAITING_REJOIN || stage == Stage.REJOINING) && lifecycleMatches(fields);
            default -> false;
        };
    }

    boolean correlated(Map<String, String> fields) {
        return stage != Stage.FINISHING && metadataMatches(fields, false) && targetMatches(fields, false);
    }

    boolean releasing() { return stage == Stage.RELEASING; }
    boolean preserveOnStop() {
        return stage == Stage.RELEASING || stage == Stage.RESTORING
                || stage == Stage.WAITING_REJOIN || stage == Stage.REJOINING;
    }
    void finishing() { stage = Stage.FINISHING; }

    Timeout timeout() {
        var code = restore ? OperationFailure.Code.RESTORE_FAILED : OperationFailure.Code.SAVE_TIMEOUT;
        if (stage == Stage.RELEASING) code = OperationFailure.Code.WORLD_RELEASE_TIMEOUT;
        if (stage == Stage.WAITING_REJOIN || stage == Stage.REJOINING) code = OperationFailure.Code.REJOIN_FAILED;
        return new Timeout(code, "Timed out in " + stage + "; backend outcome is unconfirmed");
    }

    private void waitForCoordination() {
        stage = Stage.WAITING;
        deadline = clock.getAsLong() + Duration.ofSeconds(30).toNanos();
    }

    private boolean lifecycleMatches(Map<String, String> fields) {
        return metadataMatches(fields, true) && targetMatches(fields, true);
    }

    private boolean metadataMatches(Map<String, String> fields, boolean allowMissing) {
        String requestId = identityValue(fields, "request_id");
        String from = identityValue(fields, "from");
        if (from != null && wireFrom != null && !from.equals(wireFrom)) return false;
        if (requestId == null) return allowMissing;
        return requestId.equals(wireRequestId);
    }

    private boolean matchesWorld(String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        String normalized = candidate.trim().replace('\\', '/');
        return worldAliases.contains(normalized);
    }

    private boolean targetMatches(Map<String, String> fields, boolean requireTarget) {
        boolean identified = false;
        for (String key : new String[] {"world", "config", "config_id", "folder", "folder_id"}) {
            String actual = value(fields, key);
            String expected = target.get(key);
            if (actual == null) continue;
            if ("world".equals(key)) {
                if (!matchesWorld(actual) || (world != null && !world.equals(actual))) return false;
                identified = true;
            } else if (expected != null) {
                // MineBackup's pre_hot_backup uses numeric config plus stable config_id;
                // its terminal event puts that stable ID in config instead.
                boolean configAlias = ("config".equals(key) || "config_id".equals(key))
                        && (actual.equals(target.get("config")) || actual.equals(target.get("config_id")));
                if (!actual.equals(expected) && !configAlias) return false;
                if ("folder".equals(key) || "folder_id".equals(key)) identified = true;
            } else if ("folder".equals(key) && world != null && world.equals(actual)) {
                identified = true;
            }
        }
        return !requireTarget || identified;
    }

    private void captureTarget(Map<String, String> fields) {
        for (String key : new String[] {"world", "config", "config_id", "folder", "folder_id"}) {
            String value = value(fields, key);
            if (value != null) target.put(key, value);
        }
    }

    private static String value(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String identityValue(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? null : value;
    }
}
