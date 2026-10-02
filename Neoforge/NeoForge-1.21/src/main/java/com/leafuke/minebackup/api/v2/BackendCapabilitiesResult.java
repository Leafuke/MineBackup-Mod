package com.leafuke.minebackup.api.v2;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable declaration snapshot. Declarations do not establish plugin or world readiness. */
public record BackendCapabilitiesResult(
        Outcome outcome, String manifestVersion, Map<String, Command> commands, String detail) {
    public enum Outcome { SUCCESS, UNSUPPORTED, UNAVAILABLE, FAILED }

    public BackendCapabilitiesResult {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(manifestVersion);
        commands = Map.copyOf(commands);
        Objects.requireNonNull(detail);
        if (outcome != Outcome.SUCCESS && !commands.isEmpty())
            throw new IllegalArgumentException("Failed discovery cannot advertise commands");
    }

    public record Command(Map<String, Parameter> parameters) {
        public Command { parameters = Map.copyOf(parameters); }
    }

    public record Parameter(String type, Optional<String> defaultValue, List<String> choices) {
        public Parameter {
            Objects.requireNonNull(type);
            Objects.requireNonNull(defaultValue);
            choices = List.copyOf(choices);
        }
    }

    public static BackendCapabilitiesResult unavailable(Outcome outcome, String detail) {
        return new BackendCapabilitiesResult(outcome, "", Map.of(), detail);
    }
}
