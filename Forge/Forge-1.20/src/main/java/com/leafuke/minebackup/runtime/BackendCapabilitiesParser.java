package com.leafuke.minebackup.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leafuke.minebackup.api.v2.BackendCapabilitiesResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class BackendCapabilitiesParser {
    private BackendCapabilitiesParser() {}

    static BackendCapabilitiesResult parse(String text) {
        if (text == null || text.length() > 1_048_576) throw new IllegalArgumentException("Missing or oversized capability manifest");
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        if (!"1.0".equals(string(root.get("specVersion")))) throw new IllegalArgumentException("Unsupported capability format");
        String version = string(root.get("manifestVersion"));
        if (version.isBlank()) throw new IllegalArgumentException("Missing manifest version");
        Map<String, BackendCapabilitiesResult.Command> commands = new LinkedHashMap<>();
        var functions = root.getAsJsonObject("openSocket");
        if (functions == null || functions.size() > 256) throw new IllegalArgumentException("Missing or oversized command manifest");
        for (var entry : functions.entrySet()) {
            var args = entry.getValue().getAsJsonObject().getAsJsonObject("args");
            if (args == null || args.size() > 128) throw new IllegalArgumentException("Invalid argument manifest");
            var cmd = args.getAsJsonObject("cmd");
            if (cmd == null || !"static".equals(string(cmd.get("type")))) throw new IllegalArgumentException("Missing static command");
            String command = string(cmd.get("value")).toUpperCase(Locale.ROOT);
            Map<String, BackendCapabilitiesResult.Parameter> parameters = new LinkedHashMap<>();
            for (var argument : args.entrySet()) {
                var definition = argument.getValue().getAsJsonObject();
                String type = string(definition.get("type"));
                List<String> choices = new ArrayList<>();
                if (definition.has("options")) {
                    for (var option : definition.getAsJsonArray("options")) {
                        var pair = option.getAsJsonArray();
                        if (pair.size() != 2) throw new IllegalArgumentException("Invalid parameter options");
                        choices.add(string(pair.get(1)));
                    }
                }
                String key = argument.getKey().toLowerCase(Locale.ROOT);
                if (parameters.put(key, new BackendCapabilitiesResult.Parameter(type,
                        definition.has("defaultVal") ? Optional.of(string(definition.get("defaultVal"))) : Optional.empty(), choices)) != null)
                    throw new IllegalArgumentException("Duplicate normalized parameter");
            }
            // Core and semantic selector aliases describe the same command; accept identical overlaps.
            var previous = commands.get(command);
            if (previous != null) {
                var merged = new LinkedHashMap<>(previous.parameters());
                parameters.forEach((key, value) -> {
                    var old = merged.putIfAbsent(key, value);
                    if (old != null && !old.equals(value)) throw new IllegalArgumentException("Conflicting capability declaration");
                });
                parameters = merged;
            }
            commands.put(command, new BackendCapabilitiesResult.Command(parameters));
        }
        return new BackendCapabilitiesResult(BackendCapabilitiesResult.Outcome.SUCCESS, version, commands, "");
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Capability fields must be strings");
        return value.getAsString();
    }
}
