package com.leafuke.minebackup.api.v2;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** An observation, not a guarantee of plugin, current-world, or restore readiness. */
public record BackendStatusResult(
        Outcome outcome, ChannelState queryChannel, ChannelState signalChannel,
        ResponderState responder, Optional<Boolean> enabled, Optional<Boolean> initialized,
        OptionalInt activeTasks, OptionalInt activeAutomaticBackups, Optional<OperationFailure> failure) {
    public enum Outcome { SUCCESS, UNSUPPORTED, UNAVAILABLE, FAILED }
    public enum ChannelState { UNKNOWN, CONNECTED, UNREACHABLE }
    public enum ResponderState { UNKNOWN, ONLINE, OFFLINE }

    public BackendStatusResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(queryChannel, "queryChannel");
        Objects.requireNonNull(signalChannel, "signalChannel");
        Objects.requireNonNull(responder, "responder");
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(initialized, "initialized");
        Objects.requireNonNull(activeTasks, "activeTasks");
        Objects.requireNonNull(activeAutomaticBackups, "activeAutomaticBackups");
        Objects.requireNonNull(failure, "failure");
        if ((activeTasks.isPresent() && activeTasks.getAsInt() < 0)
                || (activeAutomaticBackups.isPresent() && activeAutomaticBackups.getAsInt() < 0)) {
            throw new IllegalArgumentException("Task counts cannot be negative");
        }
    }

    public static BackendStatusResult unavailable(Outcome outcome, OperationFailure failure) {
        return new BackendStatusResult(outcome, ChannelState.UNKNOWN, ChannelState.UNKNOWN,
                ResponderState.UNKNOWN, Optional.empty(), Optional.empty(),
                OptionalInt.empty(), OptionalInt.empty(), Optional.ofNullable(failure));
    }
}
