package com.leafuke.minebackup.api.v2;

/** Cancellation never interrupts a restore already submitted to the backend. */
public enum RestoreCancelResult {
    CANCELLED, NOT_PENDING, ALREADY_SUBMITTED, UNSUPPORTED
}
