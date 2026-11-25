package com.leafuke.minebackup.interop;

import static com.leafuke.minebackup.interop.InteropWire.*;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

public record InteropSnapshot(
        UUID operation,
        String kind,
        String target,
        long rev,
        String state,
        String disk,
        boolean recoveryPending,
        String reopen,
        JsonObject result,
        String followUp,
        Action step,
        boolean warnings) {
    public record Action(UUID id, String kind, UUID session, String target, UUID releases) {}

    public record Ack(String outcome, Boolean continuous, UUID handoffId) {
        public static Ack of(String outcome) {
            return new Ack(outcome, null, null);
        }
    }

    public static InteropSnapshot parse(JsonObject o) throws IOException {
        UUID op = uuid(o, "operationId");
        String kind = one(o, "kind", "backup", "backupAll", "restore", "setImportance");
        String target = string(o, "targetRef");
        long rev = integer(o, "rev", 1, 9_007_199_254_740_991L);
        String state = one(o, "state", "queued", "running", "unresolved", "finished");
        String disk = null, reopen = null;
        boolean pending = false;
        String follow = null;
        if (kind.equals("restore")) {
            disk = one(o, "disk", "untouched", "committed", "rolledBack", "unknown");
            reopen = one(o, "reopen", "blocked", "eligible", "notRequested");
            pending = bool(o, "recoveryPending");
            if (o.has("followUp"))
                follow =
                        one(
                                o,
                                "followUp",
                                "waiting",
                                "opened",
                                "scriptStarted",
                                "openFailed",
                                "abandoned",
                                "unconfirmed");
        }
        JsonObject result = null;
        if (state.equals("finished")) {
            result = o.getAsJsonObject("result");
            if (result == null) throw new IOException("missing_result");
            String outcome = one(result, "outcome", "succeeded", "failed", "cancelled");
            if (outcome.equals("failed")) string(result, "code");
            if (outcome.equals("succeeded")) {
                if (kind.equals("backup")) {
                    String effect = one(result, "effect", "created", "unchanged", "reused");
                    if (!effect.equals("unchanged")) string(result, "backupRef");
                }
                if (kind.equals("restore") || kind.equals("setImportance"))
                    string(result, "backupRef");
                if (kind.equals("setImportance") || result.has("important"))
                    bool(result, "important");
            }
        }
        Action step = null;
        if (o.has("step")) {
            var s = o.getAsJsonObject("step");
            String action = one(s, "kind", "saveAndFreeze", "thaw", "saveAndExit", "open");
            one(s, "status", "pending", "claimed");
            integer(s, "remainingMs", 0, Integer.MAX_VALUE);
            if ((action.equals("open") || action.equals("saveAndExit")) && !kind.equals("restore"))
                throw new IOException("invalid_action");
            if (action.equals("open")
                    && (pending || !"eligible".equals(reopen) || "unknown".equals(disk)))
                throw new IOException("unsafe_open");
            step =
                    new Action(
                            uuid(s, "stepId"),
                            action,
                            uuid(s, "sessionId"),
                            string(s, "targetRef"),
                            action.equals("thaw") ? uuid(s, "releasesStepId") : null);
        }
        boolean warnings = o.has("warningCodes") && !o.getAsJsonArray("warningCodes").isEmpty();
        return new InteropSnapshot(
                op, kind, target, rev, state, disk, pending, reopen, result, follow, step,
                warnings);
    }

    private static String one(JsonObject o, String key, String... values) throws IOException {
        String s = string(o, key);
        if (!Set.of(values).contains(s)) throw new IOException("invalid_" + key);
        return s;
    }
}
