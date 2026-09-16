package com.leafuke.minebackup.update;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record VersionNumber(int major, int minor, int patch) implements Comparable<VersionNumber> {
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-+].*)?$");

    public static Optional<VersionNumber> parse(String rawVersion) {
        if (rawVersion == null) {
            return Optional.empty();
        }
        Matcher matcher = VERSION_PATTERN.matcher(rawVersion.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new VersionNumber(
                    Integer.parseInt(matcher.group(1)),
                    parsePart(matcher.group(2)),
                    parsePart(matcher.group(3))));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public static boolean isAtLeast(String current, String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        Optional<VersionNumber> currentVersion = parse(current);
        Optional<VersionNumber> requiredVersion = parse(required);
        return currentVersion.isPresent()
                && requiredVersion.isPresent()
                && currentVersion.get().compareTo(requiredVersion.get()) >= 0;
    }

    @Override
    public int compareTo(VersionNumber other) {
        int majorComparison = Integer.compare(major, other.major);
        if (majorComparison != 0) {
            return majorComparison;
        }
        int minorComparison = Integer.compare(minor, other.minor);
        return minorComparison != 0 ? minorComparison : Integer.compare(patch, other.patch);
    }

    private static int parsePart(String part) {
        return part == null ? 0 : Integer.parseInt(part);
    }
}
