package com.leafuke.minebackup.api.v2;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record CurrentWorldAutomationState(
        boolean currentWorldAvailable,
        Optional<String> worldName,
        CurrentWorldAutomationMode mode,
        Optional<Duration> interval,
        Optional<Instant> nextRun) {
    public CurrentWorldAutomationState {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(nextRun, "nextRun");
        if (!currentWorldAvailable && mode != CurrentWorldAutomationMode.OFF) {
            throw new IllegalArgumentException("Unavailable current world cannot have automation");
        }
        if (mode == CurrentWorldAutomationMode.OFF
                && (interval.isPresent() || nextRun.isPresent())) {
            throw new IllegalArgumentException("Disabled automation must not expose a schedule");
        }
        if (mode != CurrentWorldAutomationMode.OFF
                && (interval.isEmpty() || interval.orElseThrow().isZero()
                || interval.orElseThrow().isNegative())) {
            throw new IllegalArgumentException("Active automation requires a positive interval");
        }
    }

    public static CurrentWorldAutomationState unavailable() {
        return new CurrentWorldAutomationState(
                false, Optional.empty(), CurrentWorldAutomationMode.OFF,
                Optional.empty(), Optional.empty());
    }

    public static CurrentWorldAutomationState disabled(String worldName) {
        return new CurrentWorldAutomationState(
                true, Optional.ofNullable(worldName), CurrentWorldAutomationMode.OFF,
                Optional.empty(), Optional.empty());
    }
}
