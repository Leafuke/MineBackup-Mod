package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackendCapabilitiesResult;
import com.leafuke.minebackup.api.v2.BackendStatusResult;
import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.knotlink.KnotLinkCommunicationException;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkProtocolException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** World-scoped read-only queries, intentionally independent of the operation gate. */
final class BackendQueries {
    private final KnotLinkGateway gateway;
    private final Supplier<Object> origin;
    private final Supplier<BackendStatusResult.ChannelState> signal;

    BackendQueries(KnotLinkGateway gateway, Supplier<Object> origin,
            Supplier<BackendStatusResult.ChannelState> signal) {
        this.epoch = () -> 0L;
        this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        this.origin = java.util.Objects.requireNonNull(origin, "origin");
        this.signal = java.util.Objects.requireNonNull(signal, "signal");
    }

    private final java.util.function.LongSupplier epoch;
    private static final java.util.concurrent.atomic.AtomicLong GENERATIONS = new java.util.concurrent.atomic.AtomicLong();
    private Object cachedWorld;
    private long cachedEpoch, generation, failedUntil;
    private CompletableFuture<BackendCapabilitiesResult> cached;

    BackendQueries(KnotLinkGateway gateway, Supplier<Object> origin,
            Supplier<BackendStatusResult.ChannelState> signal, java.util.function.LongSupplier epoch) {
        this.gateway = java.util.Objects.requireNonNull(gateway);
        this.origin = java.util.Objects.requireNonNull(origin);
        this.signal = java.util.Objects.requireNonNull(signal);
        this.epoch = java.util.Objects.requireNonNull(epoch);
    }

    CompletionStage<BackendCapabilitiesResult> capabilities() { return capabilities(false); }

    synchronized CompletionStage<BackendCapabilitiesResult> capabilities(boolean refresh) {
        Object world = origin.get();
        long connection = epoch.getAsLong();
        if (world == null) {
            cached = null; cachedWorld = null;
            return CompletableFuture.completedFuture(BackendCapabilitiesResult.unavailable(
                    BackendCapabilitiesResult.Outcome.UNAVAILABLE, "No active Minecraft server"));
        }
        if (cached != null && cachedWorld == world && cachedEpoch == connection
                && (!cached.isDone() || !refresh && (failedUntil == 0 || System.nanoTime() < failedUntil)))
            return cached.thenApply(value -> value);
        cachedWorld = world; cachedEpoch = connection; failedUntil = 0;
        long ticket = generation = GENERATIONS.incrementAndGet();
        CompletableFuture<BackendCapabilitiesResult> result = new CompletableFuture<>();
        cached = result;
        try {
            gateway.query(KnotLinkRequest.command("GET_CAPABILITIES")).whenComplete((response, error) -> {
                BackendCapabilitiesResult value;
                try {
                    if (error != null) value = BackendCapabilitiesResult.unavailable(
                            BackendCapabilitiesResult.Outcome.FAILED, KnotLinkCommunicationException.failure(error).message());
                    else if (response == null || !response.isOk()) value = BackendCapabilitiesResult.unavailable(
                            BackendCapabilitiesResult.Outcome.FAILED, response == null ? "Missing backend response" : response.displayMessage());
                    else value = BackendCapabilitiesParser.parse(response.fields().get("func_list"));
                } catch (RuntimeException malformed) {
                    value = BackendCapabilitiesResult.unavailable(BackendCapabilitiesResult.Outcome.FAILED, "Invalid capability manifest");
                }
                synchronized (this) {
                    if (origin.get() != world || epoch.getAsLong() != connection || generation != ticket) {
                        result.complete(BackendCapabilitiesResult.unavailable(
                                BackendCapabilitiesResult.Outcome.UNAVAILABLE, "Capability session changed"));
                        return;
                    }
                    if (value.outcome() != BackendCapabilitiesResult.Outcome.SUCCESS)
                        failedUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    result.complete(new BackendCapabilitiesResult(value.outcome(), value.manifestVersion(), value.commands(), value.detail(), ticket));
                }
            });
        } catch (RuntimeException unavailable) {
            failedUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            result.complete(BackendCapabilitiesResult.unavailable(BackendCapabilitiesResult.Outcome.FAILED, unavailable.toString()));
        }
        // A caller timing out/cancelling its future must not poison the shared discovery.
        return result.thenApply(value -> value);
    }

    CompletionStage<BackendStatusResult> status() {
        Object world = origin.get();
        if (world == null) return CompletableFuture.completedFuture(BackendStatusResult.unavailable(
                BackendStatusResult.Outcome.UNAVAILABLE,
                new OperationFailure(OperationFailure.Code.NO_ACTIVE_SERVER, "No active Minecraft server")));
        return gateway.query(KnotLinkRequest.command("GET_STATUS")).handle((response, error) -> {
            if (origin.get() != world) return BackendStatusResult.unavailable(
                    BackendStatusResult.Outcome.UNAVAILABLE,
                    new OperationFailure(OperationFailure.Code.SERVER_STOPPED, "Originating world closed"));
            if (error != null) return failed(KnotLinkCommunicationException.failure(error));
            if (response == null) return failed(new OperationFailure(OperationFailure.Code.PROTOCOL_ERROR, "Missing backend response"));
            if (!response.isOk()) return observedFailure(new OperationFailure(
                    OperationFailure.Code.BACKEND_REJECTED, response.displayMessage()), BackendStatusResult.ResponderState.ONLINE);
            try { return BackendStatusParser.parse(response, signal.get()); }
            catch (KnotLinkProtocolException malformed) { return observedFailure(new OperationFailure(
                    OperationFailure.Code.PROTOCOL_ERROR, malformed.getMessage()), BackendStatusResult.ResponderState.ONLINE); }
        });
    }

    private BackendStatusResult failed(OperationFailure failure) {
        if (failure.code() == OperationFailure.Code.BACKEND_OFFLINE) {
            return observedFailure(failure, BackendStatusResult.ResponderState.OFFLINE);
        }
        var channel = switch (failure.code()) {
            case KNOTLINK_UNREACHABLE, CONNECTION_CLOSED -> BackendStatusResult.ChannelState.UNREACHABLE;
            case RESPONSE_TIMEOUT, PROTOCOL_ERROR -> BackendStatusResult.ChannelState.CONNECTED;
            default -> BackendStatusResult.ChannelState.UNKNOWN;
        };
        return new BackendStatusResult(BackendStatusResult.Outcome.FAILED, channel, signal.get(),
                BackendStatusResult.ResponderState.UNKNOWN, Optional.empty(), Optional.empty(),
                OptionalInt.empty(), OptionalInt.empty(), Optional.of(failure));
    }

    private BackendStatusResult observedFailure(OperationFailure failure, BackendStatusResult.ResponderState responder) {
        return new BackendStatusResult(BackendStatusResult.Outcome.FAILED,
                BackendStatusResult.ChannelState.CONNECTED, signal.get(), responder,
                Optional.empty(), Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), Optional.of(failure));
    }
}
