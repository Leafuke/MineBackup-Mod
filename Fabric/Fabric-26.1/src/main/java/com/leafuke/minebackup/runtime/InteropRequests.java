package com.leafuke.minebackup.runtime;

import static com.leafuke.minebackup.interop.InteropWire.object;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.leafuke.minebackup.api.v2.BackendCapabilitiesResult;
import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.interop.InteropClient;
import com.leafuke.minebackup.interop.InteropSnapshot;
import com.leafuke.minebackup.interop.InteropWire;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;

import java.util.*;
import java.util.concurrent.*;

/** Projects the public Java API's operation options onto typed v3 calls. */
public final class InteropRequests implements KnotLinkGateway {
    private final InteropClient client;
    private final Map<UUID, CompletableFuture<KnotLinkResponse>> marks = new ConcurrentHashMap<>();

    public InteropRequests(InteropClient client) {
        this.client = client;
    }

    public InteropClient client() {
        return client;
    }

    public CompletableFuture<KnotLinkResponse> query(KnotLinkRequest request) {
        var b = client.binding();
        if (b == null) return CompletableFuture.completedFuture(error(client.status().code()));
        var p = request.parameters();
        String target = (String) p.getOrDefault("folder", b.choice().target());
        String file = (String) p.get("file");
        String kind = request.commandName();
        try {
            return switch (kind) {
                case "GET_IMPORTANCE" ->
                        client.catalog("importance", object("targetRef", target, "backupRef", file))
                                .thenApply(
                                        rows ->
                                                importance(
                                                        UUID.fromString(
                                                                (String) p.get("request_id")),
                                                        rows.get(0).getAsJsonObject()));
                case "GET_STATUS" ->
                        client.catalog("status", object())
                                .thenApply(
                                        rows -> {
                                            var status = rows.get(0).getAsJsonObject();
                                            return new KnotLinkResponse(
                                                    KnotLinkResponse.Status.OK,
                                                    "",
                                                    "enabled="
                                                            + status.get("accepting").getAsBoolean()
                                                            + ";initialized=true;active_tasks="
                                                            + status.get("activeOperations")
                                                                    .getAsInt(),
                                                    Map.of());
                                        });
                case "LIST_CONFIGS", "LIST_FOLDERS", "LIST_BACKUPS" -> {
                    String view =
                            kind.equals("LIST_CONFIGS")
                                    ? "configs"
                                    : kind.equals("LIST_FOLDERS") ? "targets" : "backups";
                    JsonObject filters =
                            view.equals("configs")
                                    ? object()
                                    : view.equals("targets")
                                            ? object("configRef", p.get("config_id"))
                                            : object("targetRef", target);
                    yield client.catalog(view, filters)
                            .thenApply(
                                    rows ->
                                            new KnotLinkResponse(
                                                    KnotLinkResponse.Status.OK,
                                                    "",
                                                    rows.toString(),
                                                    Map.of()));
                }
                case "BACKUP", "RESTORE", "MARK_IMPORTANT" -> {
                    UUID id = UUID.fromString((String) p.get("request_id"));
                    boolean explicit = !p.containsKey("current_save");
                    var opts = options(p);
                    if (explicit) opts.addProperty("explicitTarget", true);
                    if (file != null && !file.isBlank()) opts.addProperty("backupRef", file);
                    var taskKind =
                            kind.equals("BACKUP")
                                    ? "backup"
                                    : kind.equals("RESTORE") ? "restore" : "setImportance";
                    if (kind.equals("RESTORE")
                            && !(b.choice()
                                    .capabilities()
                                    .contains(
                                            b.world().environment().equals("dedicated")
                                                    ? "restoreSidecar"
                                                    : "restoreIntegrated")))
                        yield CompletableFuture.completedFuture(error("unsupported_option"));
                    if (kind.equals("RESTORE") && !opts.has("backupRef"))
                        yield CompletableFuture.completedFuture(error("stale_ref"));
                    if (kind.equals("MARK_IMPORTANT")) {
                        var result = new CompletableFuture<KnotLinkResponse>();
                        marks.put(id, result);
                        client.submit(id, taskKind, target, opts)
                                .whenComplete(
                                        (ignored, err) -> {
                                            if (err != null) {
                                                marks.remove(id, result);
                                                result.completeExceptionally(err);
                                            }
                                        });
                        yield result;
                    }
                    yield client.submit(id, taskKind, target, opts)
                            .thenApply(
                                    receipt ->
                                            new KnotLinkResponse(
                                                    KnotLinkResponse.Status.OK,
                                                    receipt.has("observation")
                                                            ? "result_unknown"
                                                            : "queued",
                                                    "",
                                                    Map.of()));
                }
                default -> CompletableFuture.completedFuture(error("unsupported_option"));
            };
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(error("unsupported_option"));
        }
    }

    void observed(InteropSnapshot s) {
        if (!s.kind().equals("setImportance") || s.result() == null) return;
        var mark = marks.remove(s.operation());
        if (mark != null)
            mark.complete(
                    s.result().get("outcome").getAsString().equals("succeeded")
                            ? importance(s.operation(), s.result())
                            : error("operation_failed"));
    }

    void unconfirmed(UUID id) {
        var mark = marks.remove(id);
        if (mark != null)
            mark.completeExceptionally(
                    new com.leafuke.minebackup.knotlink.KnotLinkCommunicationException(
                            OperationFailure.Code.CROSS_PROCESS_STATE_UNCERTAIN,
                            "result_unknown",
                            null));
    }

    private static KnotLinkResponse importance(UUID id, JsonObject item) {
        return new KnotLinkResponse(
                KnotLinkResponse.Status.OK,
                "",
                "",
                Map.of(
                        "from",
                        KnotLinkRequest.CALLER_ID,
                        "request_id",
                        id.toString(),
                        "file",
                        item.get("backupRef").getAsString(),
                        "important",
                        item.get("important").getAsString()));
    }

    private static KnotLinkResponse error(String code) {
        return new KnotLinkResponse(KnotLinkResponse.Status.ERROR, code, "", Map.of("code", code));
    }

    public CompletableFuture<String> latest(String target) {
        var b = client.binding();
        if (b == null)
            return CompletableFuture.failedFuture(new InteropWire.Rejection("stale_binding"));
        return client.catalog(
                        "latest",
                        object("targetRef", target == null ? b.choice().target() : target))
                .thenApply(rows -> rows.get(0).getAsJsonObject().get("backupRef").getAsString());
    }

    public CompletableFuture<BackendCapabilitiesResult> capabilities() {
        var b = client.binding();
        if (b == null)
            return CompletableFuture.completedFuture(
                    BackendCapabilitiesResult.unavailable(
                            BackendCapabilitiesResult.Outcome.UNAVAILABLE, client.status().code()));
        var caps = b.choice().capabilities();
        var cmds = new LinkedHashMap<String, BackendCapabilitiesResult.Command>();
        if (caps.contains("backup")) {
            var params = new LinkedHashMap<String, BackendCapabilitiesResult.Parameter>();
            if (caps.contains("protect"))
                params.put(
                        "protect",
                        new BackendCapabilitiesResult.Parameter(
                                "optional", Optional.of("false"), List.of("true", "false")));
            if (caps.contains("selectedRegions"))
                for (var key : List.of("backup_scope", "scope_areas", "scope_dimensions"))
                    params.put(
                            key,
                            new BackendCapabilitiesResult.Parameter(
                                    "input", Optional.empty(), List.of()));
            if (caps.contains("backupOverrides"))
                for (var key :
                        List.of(
                                "backup_mode",
                                "backup_blacklist",
                                "backup_whitelist",
                                "compression_level",
                                "compression_method"))
                    params.put(
                            key,
                            new BackendCapabilitiesResult.Parameter(
                                    "input", Optional.empty(), List.of()));
            cmds.put("BACKUP", new BackendCapabilitiesResult.Command(params));
        }
        if (caps.contains("importance"))
            for (var cmd : List.of("GET_IMPORTANCE", "MARK_IMPORTANT"))
                cmds.put(cmd, new BackendCapabilitiesResult.Command(Map.of()));
        if (caps.contains("catalog"))
            cmds.put("LIST_BACKUPS", new BackendCapabilitiesResult.Command(Map.of()));
        if (caps.contains("restoreIntegrated") || caps.contains("restoreSidecar")) {
            var params = new LinkedHashMap<String, BackendCapabilitiesResult.Parameter>();
            if (caps.contains("preservePlayerData"))
                params.put(
                        "preserve_player_data",
                        new BackendCapabilitiesResult.Parameter(
                                "optional", Optional.empty(), List.of("true", "false")));
            if (caps.contains("restorePreservePaths"))
                params.put(
                        "restore_preserve_paths",
                        new BackendCapabilitiesResult.Parameter(
                                "input", Optional.empty(), List.of()));
            cmds.put("RESTORE", new BackendCapabilitiesResult.Command(params));
        }
        return CompletableFuture.completedFuture(
                new BackendCapabilitiesResult(
                        BackendCapabilitiesResult.Outcome.SUCCESS, "3", cmds, ""));
    }

    private static JsonObject options(Map<String, Object> p) {
        var opts = object();
        var compression = object();
        var scope = object();
        p.forEach(
                (k, v) -> {
                    switch (k) {
                        case "cmd",
                                "from",
                                "request_id",
                                "current_save",
                                "config_id",
                                "folder",
                                "file" -> {}
                        case "comment" -> opts.addProperty("comment", (String) v);
                        case "backup_mode" ->
                                opts.addProperty(
                                        "mode", v.equals("incremental") ? "smart" : (String) v);
                        case "mode" -> opts.addProperty("mode", (String) v);
                        case "compression_level" ->
                                compression.addProperty("level", Integer.parseInt((String) v));
                        case "compression_method" -> compression.addProperty("method", (String) v);
                        case "backup_scope" ->
                                scope.addProperty(
                                        "kind",
                                        switch (((String) v).toLowerCase(Locale.ROOT)) {
                                            case "selected-regions" -> "selectedRegions";
                                            case "all", "full", "default", "none" -> "full";
                                            default -> throw new IllegalArgumentException(k);
                                        });
                        case "scope_dimensions" -> scope.add("dimensions", array(v));
                        case "scope_areas" -> {
                            var rows = new JsonArray();
                            for (String line : ((String) v).split("\\R")) {
                                var rect = new JsonArray();
                                for (String n : line.split(","))
                                    rect.add(Integer.parseInt(n.trim()));
                                if (rect.size() != 4) throw new IllegalArgumentException(k);
                                rows.add(rect);
                            }
                            scope.add("areas", rows);
                        }
                        case "backup_blacklist" -> opts.add("blacklist", array(v));
                        case "restore_whitelist", "backup_whitelist" ->
                                opts.add("whitelist", array(v));
                        case "restore_preserve_paths" -> opts.add("preservePaths", array(v));
                        case "protect",
                                "important",
                                "preserve_player_data",
                                "confirm_partial_clean" -> {
                            if (!"true".equalsIgnoreCase((String) v)
                                    && !"false".equalsIgnoreCase((String) v))
                                throw new IllegalArgumentException(k);
                            opts.addProperty(
                                    k.equals("preserve_player_data")
                                            ? "preservePlayerData"
                                            : k.equals("confirm_partial_clean")
                                                    ? "confirmPartialClean"
                                                    : k,
                                    Boolean.parseBoolean((String) v));
                        }
                        default -> throw new IllegalArgumentException(k);
                    }
                });
        if (!compression.isEmpty()) opts.add("compression", compression);
        if (!scope.isEmpty()) opts.add("scope", scope);
        return opts;
    }

    private static JsonArray array(Object input) {
        var a = new JsonArray();
        if (input instanceof List<?> list) list.forEach(v -> a.add((String) v));
        else for (var v : ((String) input).split(",", -1)) a.add(v);
        return a;
    }
}
