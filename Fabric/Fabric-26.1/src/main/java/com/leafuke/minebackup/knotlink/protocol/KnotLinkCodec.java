package com.leafuke.minebackup.knotlink.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class KnotLinkCodec {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private KnotLinkCodec() {
    }

    public static Map<String, String> parse(String payload) throws KnotLinkProtocolException {
        if (payload == null || payload.isEmpty()) {
            throw new KnotLinkProtocolException("KnotLink v2 payload is empty");
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String segment : payload.split(";", -1)) {
            if (segment.isEmpty()) {
                throw new KnotLinkProtocolException("Empty KnotLink field is not allowed");
            }

            int separator = segment.indexOf('=');
            if (separator <= 0 || separator != segment.lastIndexOf('=')) {
                throw new KnotLinkProtocolException("Invalid KnotLink field: " + segment);
            }

            String key = normalizeKey(segment.substring(0, separator));
            if (!isValidKey(key)) {
                throw new KnotLinkProtocolException("Invalid KnotLink key: " + key);
            }
            if (values.containsKey(key)) {
                throw new KnotLinkProtocolException("Duplicate KnotLink key: " + key);
            }

            values.put(key, decodeValue(segment.substring(separator + 1)));
        }
        return Map.copyOf(values);
    }

    public static String serialize(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("KnotLink fields must not be empty");
        }

        StringBuilder result = new StringBuilder();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String key = normalizeKey(field.getKey());
            if (!isValidKey(key)) {
                throw new IllegalArgumentException("Invalid KnotLink key: " + field.getKey());
            }
            if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Duplicate KnotLink key: " + key);
            }

            if (!result.isEmpty()) {
                result.append(';');
            }
            result.append(key).append('=').append(encodeValue(field.getValue()));
        }
        return result.toString();
    }

    public static String encodeValue(String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte raw : bytes) {
            int valueByte = Byte.toUnsignedInt(raw);
            if (isUnreserved(valueByte)) {
                encoded.append((char) valueByte);
            } else {
                encoded.append('%')
                        .append(HEX[valueByte >>> 4])
                        .append(HEX[valueByte & 0x0F]);
            }
        }
        return encoded.toString();
    }

    public static String decodeValue(String encoded) throws KnotLinkProtocolException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char character = encoded.charAt(i);
            if (character == '%') {
                if (i + 2 >= encoded.length()) {
                    throw new KnotLinkProtocolException("Incomplete percent escape in KnotLink value");
                }
                int high = Character.digit(encoded.charAt(i + 1), 16);
                int low = Character.digit(encoded.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    throw new KnotLinkProtocolException("Invalid percent escape in KnotLink value");
                }
                bytes.write((high << 4) | low);
                i += 2;
            } else if (character == ',' || isUnreserved(character)) {
                bytes.write(character);
            } else {
                throw new KnotLinkProtocolException(
                        "KnotLink value contains an unescaped character: U+" + String.format("%04X", (int) character));
            }
        }

        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new KnotLinkProtocolException("KnotLink value is not valid UTF-8", exception);
        }
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT);
    }

    private static boolean isValidKey(String key) {
        if (key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char character = key.charAt(i);
            if (!((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnreserved(int value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '-'
                || value == '.'
                || value == '_'
                || value == '~';
    }
}
