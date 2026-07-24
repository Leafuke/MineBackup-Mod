package com.leafuke.minebackup.api.v1;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record AutoBackupState(
        boolean enabled,
        Optional<Duration> interval,
        Optional<Instant> nextRun) {
    public AutoBackupState {
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(nextRun, "nextRun");
        if (!enabled && (interval.isPresent() || nextRun.isPresent())) {
            throw new IllegalArgumentException("Disabled automatic backup must not expose a schedule");
        }
    }

    public static AutoBackupState disabled() {
        return new AutoBackupState(false, Optional.empty(), Optional.empty());
    }
}
