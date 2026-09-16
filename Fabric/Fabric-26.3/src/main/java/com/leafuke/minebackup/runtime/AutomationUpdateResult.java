package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.CurrentWorldAutomationState;
import com.leafuke.minebackup.api.v2.OperationFailure;

import java.util.Objects;
import java.util.Optional;

public record AutomationUpdateResult(
        boolean success,
        CurrentWorldAutomationState state,
        Optional<OperationFailure> failure) {
    public AutomationUpdateResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(failure, "failure");
    }
}
