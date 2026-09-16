package com.leafuke.minebackup.api.v2;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Completion callbacks are not guaranteed to run on the Minecraft thread.
 * Callers must schedule Minecraft work themselves.
 */
public interface OperationHandle<R> {
    UUID id();

    String callerId();

    OperationPhase phase();

    CompletionStage<R> completion();
}
