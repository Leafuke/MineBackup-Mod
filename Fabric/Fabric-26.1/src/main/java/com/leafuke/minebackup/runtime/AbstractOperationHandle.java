package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v1.OperationHandle;
import com.leafuke.minebackup.api.v1.OperationPhase;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

abstract class AbstractOperationHandle<R> implements OperationHandle<R> {
    private final UUID id;
    private final String callerId;
    private final AtomicReference<OperationPhase> phase;
    private final CompletableFuture<R> completion = new CompletableFuture<>();

    AbstractOperationHandle(UUID id, String callerId, OperationPhase initialPhase) {
        this.id = Objects.requireNonNull(id, "id");
        this.callerId = Objects.requireNonNull(callerId, "callerId");
        phase = new AtomicReference<>(Objects.requireNonNull(initialPhase, "initialPhase"));
    }

    @Override
    public final UUID id() {
        return id;
    }

    @Override
    public final String callerId() {
        return callerId;
    }

    @Override
    public final OperationPhase phase() {
        return phase.get();
    }

    @Override
    public final CompletionStage<R> completion() {
        return completion;
    }

    final boolean transition(OperationPhase next) {
        Objects.requireNonNull(next, "next");
        while (true) {
            OperationPhase current = phase.get();
            if (current.isTerminal()) {
                return false;
            }
            if (phase.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    final boolean finish(OperationPhase terminalPhase, R result) {
        if (!terminalPhase.isTerminal()) {
            throw new IllegalArgumentException("Operation result requires a terminal phase");
        }
        if (!transition(terminalPhase)) {
            return false;
        }
        completion.complete(Objects.requireNonNull(result, "result"));
        return true;
    }
}
