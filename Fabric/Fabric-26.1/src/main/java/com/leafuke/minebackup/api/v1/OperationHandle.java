package com.leafuke.minebackup.api.v1;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * A handle is returned as soon as MineBackup accepts or rejects a request.
 *
 * <p>Completion callbacks are not guaranteed to run on the Minecraft server
 * thread. Callers that touch game state must schedule that work themselves.</p>
 */
public interface OperationHandle<R> {
    UUID id();

    String callerId();

    OperationPhase phase();

    CompletionStage<R> completion();
}
