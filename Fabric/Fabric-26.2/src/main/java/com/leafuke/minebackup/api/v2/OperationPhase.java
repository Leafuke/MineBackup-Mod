package com.leafuke.minebackup.api.v2;

public enum OperationPhase {
    COUNTING_DOWN,
    SUBMITTING,
    RUNNING,
    HANDOFF,
    SUCCEEDED,
    CANCELLED,
    REJECTED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELLED || this == REJECTED || this == FAILED;
    }
}
