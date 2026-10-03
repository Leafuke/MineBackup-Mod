package com.leafuke.minebackup.api.v2;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
final class CompletedOperationHandle<R> implements OperationHandle<R> {
    private final UUID id = UUID.randomUUID();
    private final String caller; private final R result;
    CompletedOperationHandle(String caller, R result) { this.caller = CallerId.normalize(caller); this.result = result; }
    public UUID id() { return id; }
    public String callerId() { return caller; }
    public OperationPhase phase() { return OperationPhase.REJECTED; }
    public CompletionStage<R> completion() { return CompletableFuture.completedFuture(result); }
}
