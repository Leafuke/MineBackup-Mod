package com.leafuke.minebackup.interop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.leafuke.minebackup.knotlink.sdk.OpenSocketQuerier;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** The application payload only. The SDK owns the frame and routing prefix. */
public final class InteropWire {
    public static final int MAX_BYTES = 256 * 1024;

    private InteropWire() {}

    public static final class Rejection extends IOException {
        private final String code;

        public Rejection(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static JsonObject object(Object... fields) {
        if ((fields.length & 1) != 0) throw new IllegalArgumentException("Missing value");
        var json = new JsonObject();
        for (int i = 0; i < fields.length; i += 2) {
            String key = (String) fields[i];
            Object value = fields[i + 1];
            if (value instanceof JsonElement e) json.add(key, e);
            else if (value instanceof String s) json.addProperty(key, s);
            else if (value instanceof UUID u) json.addProperty(key, u.toString());
            else if (value instanceof Boolean b) json.addProperty(key, b);
            else if (value instanceof Number n) json.addProperty(key, n);
            else throw new IllegalArgumentException("Unsupported field " + key);
        }
        return json;
    }

    public static String encode(JsonObject value) throws IOException {
        String data = value.toString().replace("&", "\\u0026");
        if (data.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IOException("oversized_frame");
        return data;
    }

    public static JsonObject decode(String raw, int budget) throws IOException {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > budget)
            throw new IOException("invalid_message");
        try (var in = new JsonReader(new StringReader(raw))) {
            in.setStrictness(Strictness.STRICT);
            var root = read(in, 0);
            if (!root.isJsonObject() || in.peek() != JsonToken.END_DOCUMENT)
                throw new IOException("invalid_message");
            return root.getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IOException("invalid_message", ex);
        }
    }

    private static JsonElement read(JsonReader in, int depth) throws IOException {
        return switch (in.peek()) {
            case BEGIN_OBJECT -> {
                if (depth >= 16) throw new IOException("invalid_depth");
                var result = new JsonObject();
                in.beginObject();
                while (in.hasNext()) {
                    String key = in.nextName();
                    if (result.has(key)) throw new IOException("duplicate_field");
                    result.add(key, read(in, depth + 1));
                }
                in.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                if (depth >= 16) throw new IOException("invalid_depth");
                var result = new JsonArray();
                in.beginArray();
                while (in.hasNext()) result.add(read(in, depth + 1));
                in.endArray();
                yield result;
            }
            case STRING -> new JsonPrimitive(in.nextString());
            case BOOLEAN -> new JsonPrimitive(in.nextBoolean());
            case NUMBER -> new JsonPrimitive(new BigDecimal(in.nextString()));
            default -> throw new IOException("invalid_message");
        };
    }

    public static String string(JsonObject o, String key) throws IOException {
        var value = o.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) throw new IOException("invalid_" + key);
        return value.getAsString();
    }

    public static boolean bool(JsonObject o, String key) throws IOException {
        var v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean())
            throw new IOException("invalid_" + key);
        return v.getAsBoolean();
    }

    public static long integer(JsonObject o, String key, long min, long max) throws IOException {
        var v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
            throw new IOException("invalid_" + key);
        try {
            long n = v.getAsBigDecimal().longValueExact();
            if (n >= min && n <= max) return n;
        } catch (ArithmeticException ignored) {
        }
        throw new IOException("invalid_" + key);
    }

    public static UUID uuid(JsonObject o, String key) throws IOException {
        String text = string(o, key);
        try {
            UUID id = UUID.fromString(text);
            if (id.toString().equals(text)) return id;
        } catch (IllegalArgumentException ignored) {
        }
        throw new IOException("invalid_" + key);
    }

    public static JsonObject call(UUID instance, String type, JsonObject body, int budgetMs)
            throws Exception {
        return callAt(6376,instance,type,body,budgetMs);
    }
    static JsonObject callAt(int queryPort,UUID instance,String type,JsonObject body,int budgetMs)throws Exception {
        UUID rid = UUID.randomUUID();
        String payload =
                encode(
                        object(
                                "v",
                                3,
                                "type",
                                type,
                                "rid",
                                rid,
                                "instanceId",
                                instance,
                                "body",
                                body));
        long start = System.nanoTime();
        try (var q =
                new OpenSocketQuerier(
                        "frmc3", "q." + instance, "127.0.0.1", queryPort, budgetMs, MAX_BYTES)) {
            long remaining = budgetMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
            String reply = q.query(payload, remaining, TimeUnit.MILLISECONDS);
            if ("offline".equals(reply)) throw new Rejection("backend_offline");
            return reply(decode(reply, MAX_BYTES), rid, instance);
        }
    }

    public static JsonObject reply(JsonObject reply, UUID rid, UUID instance) throws IOException {
        integer(reply, "v", 3, 3);
        if (!"reply".equals(string(reply, "type"))
                || !uuid(reply, "rid").equals(rid)
                || !uuid(reply, "instanceId").equals(instance))
            throw new IOException("wrong_instance");
        if (!bool(reply, "ok")) {
            if (!reply.has("error") || !reply.get("error").isJsonObject())
                throw new IOException("invalid_error");
            throw new Rejection(string(reply.getAsJsonObject("error"), "code"));
        }
        if (!reply.has("body") || !reply.get("body").isJsonObject())
            throw new IOException("invalid_body");
        return reply.getAsJsonObject("body");
    }
}
