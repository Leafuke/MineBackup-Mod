package com.leafuke.minebackup.api.v1;

/** Observable lifecycle of a current-world operation. */
public enum OperationPhase {
    COUNTING_DOWN,
    SUBMITTING,
    RUNNING,
    SUCCEEDED,
    CANCELLED,
    REJECTED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED
                || this == CANCELLED
                || this == REJECTED
                || this == FAILED;
    }
}
