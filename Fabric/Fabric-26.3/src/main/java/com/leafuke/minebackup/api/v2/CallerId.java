package com.leafuke.minebackup.api.v2;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class CallerId {
    private static final Pattern SAFE = Pattern.compile("[a-z0-9._:-]{1,64}");

    private CallerId() {
    }

    static String normalize(String value) {
        Objects.requireNonNull(value, "callerId");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SAFE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "callerId must contain 1-64 lowercase namespace characters [a-z0-9._:-]");
        }
        return normalized;
    }
}
