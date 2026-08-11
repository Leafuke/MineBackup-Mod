package com.leafuke.minebackup.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record WorldIdentity(String value, String displayName, String storageKey) {
    public WorldIdentity {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(storageKey, "storageKey");
        if (value.isBlank() || displayName.isBlank() || storageKey.isBlank()) {
            throw new IllegalArgumentException("World identity fields must not be blank");
        }
    }

    public static WorldIdentity resolve(
            Path gameDirectory,
            Path worldRoot,
            String displayName) throws IOException {
        Path game = gameDirectory.toRealPath().normalize();
        Path world = worldRoot.toRealPath().normalize();
        String value = world.startsWith(game)
                ? "relative:" + portable(game.relativize(world))
                : "absolute:" + portable(world);
        String normalizedName = normalizeDisplayName(displayName, world);
        return new WorldIdentity(value, normalizedName, sha256(value));
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String normalizeDisplayName(String displayName, Path world) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        Path fileName = world.getFileName();
        return fileName == null ? "world" : fileName.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
