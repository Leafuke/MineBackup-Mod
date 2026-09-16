package com.leafuke.minebackup.api.v2;

import java.util.Objects;
import java.util.Optional;

public record MessageTemplate(String translationKey, Optional<String> literalFallback) {
    public MessageTemplate {
        Objects.requireNonNull(translationKey, "translationKey");
        Objects.requireNonNull(literalFallback, "literalFallback");
        translationKey = translationKey.trim();
        if (translationKey.isEmpty() || translationKey.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("translationKey must not be blank or contain controls");
        }
        literalFallback = literalFallback.map(String::trim).filter(value -> !value.isEmpty());
    }

    public MessageTemplate(String translationKey) {
        this(translationKey, Optional.empty());
    }
}
