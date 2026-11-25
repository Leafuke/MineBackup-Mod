package com.leafuke.minebackup.interop;

import static com.leafuke.minebackup.interop.InteropWire.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.leafuke.minebackup.interop.InteropFiles.Endpoint;
import com.leafuke.minebackup.knotlink.sdk.SignalSubscriber;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Owns route binding and read-only observation. Only submit() may create a backend operation. */
public final class InteropClient implements AutoCloseable {
    public record World(UUID session, Path path, String environment) {}

    public record Choice(
            Endpoint endpoint,
            String target,
            String scope,
            String label,
            Set<String> capabilities) {
        public String key() {
            return endpoint.choice() + "/" + target;
        }
    }

    public record Binding(World world, Choice choice, UUID id) {
        public Endpoint endpoint() {
            return choice.endpoint();
        }
    }

    public record Observation(String backend, InteropSnapshot snapshot) {}

    private volatile Observation latest;

    public Observation latest() {
        return latest;
    }

    public record Status(String code, String detail, List<Choice> choices) {}

    public interface Listener {
        void snapshot(InteropSnapshot value, boolean visible);

        void unconfirmed(UUID id, boolean restore);

        default void actionAcknowledged(UUID operation, String kind) {}

        CompletionStage<InteropSnapshot.Ack> perform(
                Binding binding,
                InteropSnapshot value,
                long startBefore,
                BooleanSupplier stillCurrent);
    }

    interface Subscription extends AutoCloseable {
        boolean connected();

        void close();
    }

    @FunctionalInterface
    interface Subscribe {
        Subscription connect(
                Endpoint endpoint,
                World world,
                java.util.function.Consumer<String> messages,
                Runnable disconnected)
                throws Exception;
    }

    @FunctionalInterface
    public interface Transport {
        JsonObject call(UUID instance, String type, JsonObject body, int timeout) throws Exception;
    }

    private final Path endpoints, state;
    private final Transport transport;
    private final Subscribe subscribe;
    private boolean legacyDiagnostics;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Listener listener;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    r -> Thread.ofPlatform().daemon().name("minebackup-v3-clock").unstarted(r));
    private final ThreadPoolExecutor queries = workers(4, "query");
    private final ThreadPoolExecutor control = workers(2, "control");
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();
    private final AtomicBoolean discovering = new AtomicBoolean();
    private volatile World world;
    private volatile Binding binding;
    private volatile Status status = new Status("offline", "", List.of());
    private volatile Subscription subscriber;
    private volatile boolean closed, handoff;
    private volatile long retryAt;
    private volatile long lastDirectoryStamp = Long.MIN_VALUE;
    private int retrySeconds = 1;

    public InteropClient(Path state, Listener listener) {
        this(
                InteropFiles.root().resolve("endpoints"),
                state,
                InteropWire::call,
                InteropClient::subscribe,
                listener);
        legacyDiagnostics = true;
    }

    InteropClient(
            Path endpoints,
            Path state,
            Transport transport,
            Subscribe subscribe,
            Listener listener) {
        this.endpoints = endpoints;
        this.state = state;
        this.transport = transport;
        this.subscribe = subscribe;
        this.listener = listener;
        timer.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    private static Subscription subscribe(
            Endpoint e,
            World w,
            java.util.function.Consumer<String> messages,
            Runnable disconnected)
            throws Exception {
        var subscriber =
                new SignalSubscriber(
                        "frmc3", "s." + e.instance() + "." + w.session(), "127.0.0.1", 6372, 5000);
        subscriber.setSignalListener(messages::accept);
        subscriber.setDisconnectListener(ignored -> disconnected.run());
        try {
            subscriber.start();
        } catch (Exception ex) {
            subscriber.stop();
            throw ex;
        }
        return new Subscription() {
            public boolean connected() {
                return subscriber.isRunning();
            }

            public void close() {
                subscriber.stop();
            }
        };
    }

    private static ThreadPoolExecutor workers(int size, String name) {
        return new ThreadPoolExecutor(
                size,
                size,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                Thread.ofPlatform().daemon().name("minebackup-v3-" + name + "-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private <T> CompletableFuture<T> async(Executor executor, Callable<T> work) {
        try {
            CompletableFuture<T> result =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return work.call();
                                } catch (Exception error) {
                                    throw new CompletionException(error);
                                }
                            },
                            executor);
            pending.add(result);
            result.whenComplete((v, e) -> pending.remove(result));
            if (closed) result.completeExceptionally(new CancellationException("client_closed"));
            return result;
        } catch (RejectedExecutionException full) {
            return CompletableFuture.failedFuture(full);
        }
    }

    public Status status() {
        return status;
    }

    public Binding binding() {
        return binding;
    }

    public boolean signalConnected() {
        var s = subscriber;
        return s != null && s.connected();
    }

    public synchronized void enter(Path path, boolean dedicated) {
        leave(false);
        world =
                new World(
                        UUID.randomUUID(),
                        path.toAbsolutePath().normalize(),
                        dedicated ? "dedicated" : "integrated");
        retryAt = 0;
        refresh();
    }

    public synchronized void leave(boolean controlled) {
        handoff = controlled;
        if (controlled) return;
        World previous = world;
        world = null;
        Binding old = binding;
        binding = null;
        detachSignal();
        status = new Status("offline", "", List.of());
        if (old != null && old.id() != null)
            async(
                    queries,
                    () ->
                            transport.call(
                                    old.endpoint().instance(),
                                    "leave",
                                    object("sessionId", previous.session(), "bindingId", old.id()),
                                    2000));
    }

    private synchronized void detachSignal() {
        Subscription old = subscriber;
        subscriber = null;
        if (old != null)
            async(
                    queries,
                    () -> {
                        old.close();
                        return null;
                    });
    }

    public CompletableFuture<Status> refresh() {
        return refresh(null);
    }

    public CompletableFuture<Status> select(String choice) {
        return refresh(Objects.requireNonNull(choice));
    }

    private CompletableFuture<Status> refresh(String selected) {
        World origin = world;
        if (!discovering.compareAndSet(false, true))
            return CompletableFuture.completedFuture(status);
        if (selected != null
                && binding != null
                && !binding.choice().key().equals(selected)
                && watches.values().stream()
                        .anyMatch(
                                w -> w.session.equals(binding.world().session()) && !w.detached)) {
            discovering.set(false);
            return CompletableFuture.completedFuture(
                    setStatus("busy", binding.endpoint().display(), status.choices()));
        }
        return async(
                        queries,
                        () -> {
                            try {
                                return discover(origin, selected);
                            } catch (Exception error) {
                                if (world == origin) setStatus(code(error), "", List.of());
                                return status;
                            } finally {
                                discovering.set(false);
                            }
                        })
                .whenComplete(
                        (v, e) -> {
                            if (e != null) discovering.set(false);
                        });
    }

    private Status discover(World origin, String selected) throws Exception {
        var candidates = new ArrayList<Choice>();
        var routes = InteropFiles.endpoints(endpoints);
        String problem = "offline", detail = "";
        boolean unverified = false;
        String saved = origin == null ? null : readPreference(origin);
        var verifiedDomains = new java.util.HashSet<UUID>();
        for (Endpoint endpoint : routes) {
            try {
                var hello = hello(endpoint);
                if (!verifiedDomains.add(endpoint.domain()))
                    throw new Rejection("target_ambiguous");
                String ready = string(hello, "minecraftInterop");
                if (!ready.equals("ready")) {
                    problem =
                            ready.equals("upgradePlugin")
                                    ? "upgrade_plugin"
                                    : "plugin_" + string(hello, "pluginState");
                    detail = endpoint.display();
                    continue;
                }
                if (origin == null) {
                    problem = "online";
                    detail = endpoint.display();
                    continue;
                }
                var matches = transport.call(endpoint.instance(), "match", worldBody(origin), 5000);
                var caps = new HashSet<String>();
                for (var c : hello.getAsJsonArray("capabilities")) caps.add(c.getAsString());
                for (var item : matches.getAsJsonArray("targets")) {
                    var t = item.getAsJsonObject();
                    candidates.add(
                            new Choice(
                                    endpoint,
                                    string(t, "targetRef"),
                                    string(t, "scope"),
                                    string(t, "label"),
                                    Set.copyOf(caps)));
                }
                if (matches.getAsJsonArray("targets").isEmpty())
                    candidates.add(
                            new Choice(endpoint, "", "none", endpoint.label(), Set.copyOf(caps)));
                if (problem.equals("offline")) {
                    problem = "target_not_found";
                    detail = endpoint.display();
                }
            } catch (Exception error) {
                if (error instanceof Rejection failure
                        && failure.code().equals("target_ambiguous")) {
                    synchronized (this) {
                        binding = null;
                        detachSignal();
                    }
                    return setStatus("target_ambiguous", endpoint.display(), List.of());
                }
                unverified = true;
                problem = code(error);
                detail = endpoint.display();
            }
        }
        if (routes.isEmpty() && legacyDiagnostics) problem = diagnoseLegacy();
        if (world != origin || closed) return status;
        if (origin == null) {
            recover();
            return setStatus(problem, detail, List.of());
        }
        String wanted = selected != null ? selected : saved;
        List<Choice> matching =
                wanted == null
                        ? candidates.stream().filter(c -> !c.target().isEmpty()).toList()
                        : candidates.stream().filter(c -> c.key().equals(wanted)).toList();
        if (wanted == null && matching.isEmpty() && candidates.size() == 1) matching = candidates;
        if (matching.size() != 1 || (unverified && wanted == null)) {
            if (binding != null && binding.world() == origin) {
                // An existing preference may only continue with the exact same verified endpoint.
                synchronized (this) {
                    binding = null;
                    detachSignal();
                }
            }
            if (!candidates.isEmpty())
                problem =
                        matching.isEmpty() && wanted != null
                                ? "preferred_unavailable"
                                : "target_ambiguous";
            recover();
            return setStatus(problem, detail, List.copyOf(candidates));
        }
        Choice chosen = matching.getFirst();
        Binding old = binding;
        if (old != null
                && old.world() == origin
                && old.choice().equals(chosen)
                && signalConnected()) {
            recover();
            return setStatus(
                    chosen.target().isEmpty() ? "target_not_found" : "connected",
                    chosen.endpoint().display(),
                    List.copyOf(candidates));
        }
        Subscription next =
                subscribe.connect(
                        chosen.endpoint(),
                        origin,
                        raw -> changed(chosen.endpoint(), origin, raw),
                        () -> {
                            if (world == origin) {
                                retryAt = 0;
                                watches.values().forEach(w -> w.next = 0);
                            }
                        });
        boolean adopted = false;
        try {
            UUID id = null;
            if (!chosen.target().isEmpty()) {
                var request = worldBody(origin);
                request.addProperty("targetRef", chosen.target());
                var proof =
                        origin.environment().equals("dedicated")
                                ? HandoffProof.find(
                                        state.resolveSibling("restart").resolve("v3"),
                                        origin.path(),
                                        chosen)
                                : Optional.<HandoffProof>empty();
                proof.ifPresent(p -> request.addProperty("handoffId", p.id().toString()));
                var bound = transport.call(chosen.endpoint().instance(), "bind", request, 5000);
                if (proof.isPresent()) {
                    if (bound.has("handoffId")) proof.get().confirm(bound);
                    else
                        timer.schedule(
                                () -> retryBind(origin, chosen, proof.get()), 2, TimeUnit.SECONDS);
                }
                if (!string(bound, "targetRef").equals(chosen.target()))
                    throw new IOException("stale_binding");
                id = uuid(bound, "bindingId");
            }
            Binding live = new Binding(origin, chosen, id);
            synchronized (this) {
                if (world != origin || closed) return status;
                detachSignal();
                subscriber = next;
                binding = live;
                adopted = true;
            }
            if (selected != null || saved == null && live.id() != null)
                writePreference(origin, chosen.key());
            if (live.id() != null) sync(live);
            recover();
            retrySeconds = 1;
            return setStatus(
                    chosen.target().isEmpty() ? "target_not_found" : "connected",
                    chosen.endpoint().display(),
                    List.copyOf(candidates));
        } finally {
            if (!adopted) next.close();
        }
    }

    private static String diagnoseLegacy() {
        // Read-only diagnostic on the former route. It can never select a write endpoint.
        try (var q =
                new com.leafuke.minebackup.knotlink.sdk.OpenSocketQuerier(
                        "0x00000020", "0x00000010", "127.0.0.1", 6376, 1000, 1024 * 1024)) {
            String raw = q.query("cmd=GET_CAPABILITIES", 1, TimeUnit.SECONDS);
            if ("offline".equals(raw)) return "backend_offline";
            var reply = com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse.parse(raw);
            if (!reply.isOk() || !reply.fields().containsKey("func_list")) return "unverified";
            var manifest = decode(reply.fields().get("func_list"), 1024 * 1024);
            if ("1.0".equals(string(manifest, "specVersion"))
                    && Set.of("MineBackup", "FolderRewind").contains(string(manifest, "appName")))
                return "protocol_mismatch";
            return "unverified";
        } catch (java.net.ConnectException e) {
            return "relay_unreachable";
        } catch (TimeoutException e) {
            return "response_timeout";
        } catch (Exception e) {
            return "unverified";
        }
    }

    private void retryBind(World origin, Choice selected, HandoffProof proof) {
        Binding current = binding;
        if (current == null || world != origin || !current.choice().equals(selected) || closed)
            return;
        async(
                queries,
                () -> {
                    try {
                        var request = worldBody(origin);
                        request.addProperty("targetRef", selected.target());
                        request.addProperty("handoffId", proof.id().toString());
                        var reply =
                                transport.call(
                                        selected.endpoint().instance(), "bind", request, 5000);
                        if (!uuid(reply, "bindingId").equals(current.id())
                                || !string(reply, "targetRef").equals(selected.target()))
                            return null;
                        proof.confirm(reply);
                        if (!reply.has("handoffId"))
                            timer.schedule(
                                    () -> retryBind(origin, selected, proof), 30, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        if (!closed)
                            timer.schedule(
                                    () -> retryBind(origin, selected, proof), 30, TimeUnit.SECONDS);
                    }
                    return null;
                });
    }

    private JsonObject hello(Endpoint endpoint) throws Exception {
        var h = transport.call(endpoint.instance(), "hello", object(), 5000);
        integer(h, "protocolMajor", 3, 3);
        if (!uuid(h, "domainId").equals(endpoint.domain())
                || !string(h, "product").equals(endpoint.product()))
            throw new Rejection("wrong_instance");
        return h;
    }

    private JsonObject worldBody(World w) throws IOException {
        return object(
                "sessionId",
                w.session(),
                "worldRoot",
                w.path().toRealPath().toString(),
                "environment",
                w.environment());
    }

    private Status setStatus(String code, String detail, List<Choice> choices) {
        return status = new Status(code, detail, List.copyOf(choices));
    }

    private Path preference(World w) {
        return state.resolve("bindings")
                .resolve(
                        UUID.nameUUIDFromBytes(
                                        w.path()
                                                .toString()
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                + ".json");
    }

    private String readPreference(World w) throws IOException {
        Path file = preference(w);
        return Files.exists(file) ? string(InteropFiles.readObject(file), "choice") : null;
    }

    private void writePreference(World w, String key) throws IOException {
        InteropFiles.writeObject(preference(w), object("choice", key));
    }

    private static String code(Exception error) {
        return error instanceof Rejection r
                ? r.code()
                : error instanceof java.net.ConnectException
                        ? "relay_unreachable"
                        : error instanceof TimeoutException ? "response_timeout" : "unverified";
    }

    private void changed(Endpoint e, World origin, String raw) {
        try {
            var hint = decode(raw, 4096);
            if (!string(hint, "type").equals("changed")
                    || integer(hint, "v", 3, 3) != 3
                    || !uuid(hint, "instanceId").equals(e.instance())
                    || !uuid(hint, "sessionId").equals(origin.session())) return;
            integer(hint, "rev", 1, 9_007_199_254_740_991L);
            Binding b = binding;
            if (b == null || b.world() != origin || !b.endpoint().equals(e)) return;
            UUID id = uuid(hint, "operationId");
            Watch w =
                    watches.computeIfAbsent(
                            id, k -> new Watch(e, origin.session(), id, null, null));
            if (w.endpoint.equals(e)) poll(w, true);
        } catch (Exception ignored) {
            /* Unrelated or invalid advisory frames never run a game action. */
        }
    }

    private void sync(Binding b) throws Exception {
        var list =
                transport
                        .call(
                                b.endpoint().instance(),
                                "sync",
                                object("sessionId", b.world().session(), "bindingId", b.id()),
                                2000)
                        .getAsJsonArray("operations");
        for (var item : list) {
            UUID id = uuid(item.getAsJsonObject(), "operationId");
            var w =
                    watches.computeIfAbsent(
                            id, k -> new Watch(b.endpoint(), b.world().session(), id, null, null));
            poll(w, true);
        }
    }

    private Path openingFence(Path root) throws IOException {
        return state.resolve("closed-worlds")
                .resolve(
                        UUID.nameUUIDFromBytes(
                                        root.toRealPath()
                                                .toString()
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                + ".json");
    }

    public void rememberExit(Path root, Binding target, UUID operation) throws IOException {
        InteropFiles.writeObject(
                openingFence(root),
                object("operationId", operation, "domainId", target.endpoint().domain()));
    }

    /** Called on the client's I/O executor before acquiring a fresh physical open reservation. */
    public UUID checkManualOpen(Path root) throws Exception {
        Path file = openingFence(root);
        if (!Files.exists(file)) return null;
        var record = InteropFiles.readObject(file);
        UUID id = uuid(record, "operationId");
        var current = observe(uuid(record, "domainId"), id).get(10, TimeUnit.SECONDS);
        if (!"finished".equals(current.state())
                || current.recoveryPending()
                || !"eligible".equals(current.reopen())
                || !"restore".equals(current.kind())
                || "unknown".equals(current.disk())) throw new Rejection("world_in_use");
        return id;
    }

    public void opened(Path root, UUID id) throws IOException {
        if (id == null) return;
        var file = openingFence(root);
        if (Files.isRegularFile(file)
                && uuid(InteropFiles.readObject(file), "operationId").equals(id))
            Files.deleteIfExists(file);
    }

    public CompletableFuture<InteropSnapshot> observe(UUID domain, UUID operation) {
        return async(
                queries,
                () -> {
                    var endpointsForDomain =
                            InteropFiles.endpoints(endpoints).stream()
                                    .filter(e -> e.domain().equals(domain))
                                    .toList();
                    if (endpointsForDomain.size() != 1)
                        throw new Rejection(
                                endpointsForDomain.isEmpty()
                                        ? "backend_offline"
                                        : "target_ambiguous");
                    var endpoint = endpointsForDomain.getFirst();
                    hello(endpoint);
                    var result =
                            InteropSnapshot.parse(
                                    transport.call(
                                            endpoint.instance(),
                                            "get",
                                            object("operationId", operation),
                                            5000));
                    if (!result.operation().equals(operation))
                        throw new IOException("wrong_operation");
                    return result;
                });
    }

    public CompletableFuture<JsonArray> catalog(String view, JsonObject filters) {
        Binding b = binding;
        return async(
                queries,
                () -> {
                    if (b == null) throw new Rejection("stale_binding");
                    JsonObject req = filters.deepCopy();
                    req.addProperty("view", view);
                    var items = new JsonArray();
                    var cursors = new HashSet<String>();
                    do {
                        var body = transport.call(b.endpoint().instance(), "catalog", req, 5000);
                        if (binding != b) throw new Rejection("stale_binding");
                        if (!body.has("items")) {
                            items.add(body);
                            break;
                        }
                        var page = body.getAsJsonArray("items");
                        if (page.size() > 100 || items.size() + page.size() > 16384)
                            throw new IOException("oversized_catalog");
                        items.addAll(page);
                        if (!body.has("nextCursor")) break;
                        String cursor = string(body, "nextCursor");
                        if (!cursors.add(cursor)) throw new IOException("invalid_cursor");
                        req.addProperty("cursor", cursor);
                    } while (true);
                    return items;
                });
    }

    public CompletableFuture<JsonObject> submit(
            UUID id, String kind, String target, JsonObject options) {
        Binding b = binding;
        return async(
                queries,
                () -> {
                    if (b == null || world != b.world()) throw new Rejection("stale_binding");
                    String targetRef = target == null ? b.choice().target() : target;
                    if (b.id() == null && (!options.has("explicitTarget") || targetRef.isBlank()))
                        throw new Rejection("stale_binding");
                    if (kind.equals("restore")
                            && b.choice().scope().equals("container")
                            && targetRef.equals(b.choice().target()))
                        throw new Rejection("target_unverified");
                    var h = hello(b.endpoint());
                    long receiptWindow =
                            System.nanoTime()
                                    + TimeUnit.MILLISECONDS.toNanos(
                                            integer(h, "admissionTtlMs", 1, 120000));
                    var body =
                            object(
                                    "operationId",
                                    id,
                                    "sessionId",
                                    b.world().session(),
                                    "targetRef",
                                    targetRef,
                                    "kind",
                                    kind,
                                    "admissionRef",
                                    string(h, "admissionRef"),
                                    "options",
                                    options);
                    if (b.id() != null) body.addProperty("bindingId", b.id().toString());
                    if (binding != b) throw new Rejection("stale_binding");
                    var w = new Watch(b.endpoint(), b.world().session(), id, kind, targetRef);
                    synchronized (watches) {
                        if (watches.containsKey(id) || Files.exists(pointer(id)))
                            throw new Rejection("request_conflict");
                        w.busy.set(true);
                        persist(w);
                        watches.put(id, w);
                    }
                    try {
                        JsonObject receipt;
                        try {
                            receipt = transport.call(b.endpoint().instance(), "submit", body, 5000);
                        } catch (Rejection rejected) {
                            if (!rejected.code().equals("backend_offline")) {
                                forget(w);
                                throw rejected;
                            }
                            throw rejected;
                        } catch (Exception first) {
                            try {
                                receipt = get(w);
                            } catch (Rejection missing) {
                                if (!missing.code().equals("result_unknown")
                                        || !kind.equals("backup")
                                        || options.has("protect")
                                        || System.nanoTime() >= receiptWindow
                                        || binding != b) throw first;
                                receipt =
                                        transport.call(
                                                b.endpoint().instance(), "submit", body, 5000);
                            }
                        }
                        accept(w, receipt);
                        return receipt;
                    } catch (Exception error) {
                        if (watches.containsKey(id)) {
                            w.next = 0;
                            return object("observation", "unconfirmed");
                        }
                        throw error;
                    } finally {
                        w.busy.set(false);
                    }
                });
    }

    private static final class Watch {
        volatile Endpoint endpoint;
        final UUID session, id;
        volatile String kind, target;
        InteropSnapshot previous;
        long rev, lastSeen = System.nanoTime();
        volatile long next;
        volatile boolean detached;
        final AtomicBoolean busy = new AtomicBoolean();
        final Map<UUID, RunStep> steps = new ConcurrentHashMap<>();

        Watch(Endpoint e, UUID session, UUID id, String kind, String target) {
            this.endpoint = e;
            this.session = session;
            this.id = id;
            this.kind = kind;
            this.target = target;
        }
    }

    private static final class RunStep {
        final InteropSnapshot.Action action;
        volatile InteropSnapshot.Ack ack;
        volatile boolean completed;

        RunStep(InteropSnapshot.Action action) {
            this.action = action;
        }
    }

    private void persist(Watch w) throws IOException {
        InteropFiles.writeObject(
                pointer(w.id),
                object(
                        "product",
                        w.endpoint.product(),
                        "domainId",
                        w.endpoint.domain(),
                        "instanceId",
                        w.endpoint.instance(),
                        "ownerPid",
                        ProcessHandle.current().pid(),
                        "ownerStarted",
                        ProcessHandle.current()
                                .info()
                                .startInstant()
                                .map(java.time.Instant::toString)
                                .orElse("unknown"),
                        "sessionId",
                        w.session,
                        "operationId",
                        w.id,
                        "kind",
                        w.kind,
                        "targetRef",
                        w.target));
    }

    private Path pointer(UUID id) {
        return state.resolve("pending").resolve(id + ".json");
    }

    private void forget(Watch w) throws IOException {
        Files.deleteIfExists(pointer(w.id));
        watches.remove(w.id, w);
    }

    private void recover() throws Exception {
        Path dir = state.resolve("pending");
        if (!Files.isDirectory(dir)) return;
        var endpointsByDomain = InteropFiles.endpoints(endpoints);
        try (var files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path path : files) {
                var r = InteropFiles.readObject(path);
                UUID id = uuid(r, "operationId"), domain = uuid(r, "domainId");
                if (r.has("ownerPid")) {
                    long pid = integer(r, "ownerPid", 1, Long.MAX_VALUE);
                    if (pid != ProcessHandle.current().pid()
                            && ProcessHandle.of(pid)
                                    .filter(ProcessHandle::isAlive)
                                    .filter(
                                            p ->
                                                    p.info()
                                                            .startInstant()
                                                            .map(
                                                                    at ->
                                                                            at.toString()
                                                                                    .equals(
                                                                                            r.get(
                                                                                                            "ownerStarted")
                                                                                                    .getAsString()))
                                                            .orElse(true))
                                    .isPresent()) continue;
                }
                String product = string(r, "product");
                var matches =
                        endpointsByDomain.stream()
                                .filter(
                                        e ->
                                                e.domain().equals(domain)
                                                        && e.product().equals(product))
                                .toList();
                if (matches.size() != 1) continue;
                var e = matches.getFirst();
                var existing = watches.get(id);
                if (existing != null && existing.endpoint.equals(e)) {
                    if (existing.detached) existing.next = 0;
                    existing.detached = false;
                    continue;
                }
                hello(e);
                var recovered =
                        new Watch(
                                e,
                                uuid(r, "sessionId"),
                                id,
                                string(r, "kind"),
                                string(r, "targetRef"));
                watches.put(id, recovered);
                poll(recovered, true);
            }
        }
    }

    private void tick() {
        if (closed) return;
        if (world != null && !handoff) {
            try {
                long stamp =
                        Files.exists(endpoints)
                                ? Files.getLastModifiedTime(endpoints).toMillis()
                                : 0;
                if (lastDirectoryStamp != stamp) {
                    lastDirectoryStamp = stamp;
                    retryAt = 0;
                    refresh();
                }
            } catch (IOException ignored) {
                /* A failed directory read cannot invalidate existing receipts. */
            }
        }
        if (world != null
                && !handoff
                && (!signalConnected() || binding == null)
                && System.nanoTime() >= retryAt) {
            retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(retrySeconds);
            retrySeconds = Math.min(30, retrySeconds * 2);
            refresh();
        }
        for (Watch w : watches.values()) {
            if (w.detached && System.nanoTime() - w.lastSeen >= Duration.ofHours(24).toNanos())
                watches.remove(w.id, w);
            else if (System.nanoTime() >= w.next) poll(w, false);
        }
    }

    private JsonObject get(Watch w) throws Exception {
        return transport.call(
                w.endpoint.instance(),
                "get",
                object("operationId", w.id, "sessionId", w.session),
                2000);
    }

    private void poll(Watch w, boolean wake) {
        if (!w.busy.compareAndSet(false, true)) {
            if (wake) w.next = 0;
            return;
        }
        w.next = System.nanoTime() + Duration.ofSeconds(signalConnected() ? 60 : 2).toNanos();
        async(
                        control,
                        () -> {
                            try {
                                accept(w, get(w));
                            } catch (Exception error) {
                                if (error instanceof Rejection refused
                                        && "backend_offline".equals(refused.code())) {
                                    if (handoff || world == null)
                                        async(
                                                queries,
                                                () -> {
                                                    recover();
                                                    return null;
                                                });
                                    else refresh();
                                }
                                if (System.nanoTime() - w.lastSeen
                                        >= Duration.ofMinutes(2).toNanos()) {
                                    if (!w.detached) {
                                        w.detached = true;
                                        listener.unconfirmed(w.id, "restore".equals(w.kind));
                                    }
                                    setStatus(
                                            "result_unknown",
                                            w.id.toString().substring(0, 8),
                                            status.choices());
                                    w.next = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                                } else w.next = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                            } finally {
                                w.busy.set(false);
                            }
                            return null;
                        })
                .exceptionally(
                        error -> {
                            w.busy.set(false);
                            return null;
                        });
    }

    private void accept(Watch w, JsonObject body) throws Exception {
        var value = InteropSnapshot.parse(body);
        if (watches.get(w.id) != w
                || !w.id.equals(value.operation())
                || w.kind != null && !w.kind.equals(value.kind())
                || w.target != null && !w.target.equals(value.target()))
            throw new IOException("wrong_operation");
        if (w.kind == null) {
            Binding current = binding;
            if (current == null
                    || !current.endpoint().equals(w.endpoint)
                    || value.step() == null && !current.choice().target().equals(value.target())) {
                watches.remove(w.id, w);
                return;
            }
            w.kind = value.kind();
            w.target = value.target();
            if (!value.state().equals("finished")) persist(w);
        }
        if (value.rev() < w.rev) return;
        if (w.previous != null
                && (value.rev() == w.rev && !w.previous.equals(value)
                        || w.previous.result() != null
                                && !w.previous.result().equals(value.result())))
            throw new IOException("snapshot_conflict");
        w.previous = value;
        boolean fresh = value.rev() > w.rev;
        w.rev = value.rev();
        w.lastSeen = System.nanoTime();
        w.detached = false;
        if (fresh) {
            latest = new Observation(w.endpoint.display(), value);
            listener.snapshot(value, world != null && world.session().equals(w.session));
        }
        if (value.state().equals("unresolved"))
            setStatus(
                    value.recoveryPending() ? "recovery_required" : "result_unknown",
                    w.id.toString().substring(0, 8),
                    status.choices());
        Binding b = binding;
        if (value.step() != null
                && b != null
                && b.world().session().equals(w.session)
                && b.endpoint().equals(w.endpoint)
                && value.step().session().equals(w.session)
                && value.step().target().equals(b.choice().target())) {
            runStep(w, b, value);
        } else if (value.state().equals("finished")
                && (!value.kind().equals("restore")
                        || Set.of(
                                        "opened",
                                        "openFailed",
                                        "scriptStarted",
                                        "abandoned",
                                        "unconfirmed")
                                .contains(Objects.toString(value.followUp(), ""))
                        || value.reopen().equals("notRequested"))) forget(w);
    }

    private void runStep(Watch w, Binding b, InteropSnapshot value) throws Exception {
        var action = value.step();
        var previous = w.steps.get(action.id());
        if (previous != null) {
            if (previous.ack != null && !previous.completed) acknowledge(w, previous);
            return;
        }
        var request =
                object(
                        "operationId",
                        w.id,
                        "sessionId",
                        w.session,
                        "stepId",
                        action.id(),
                        "verb",
                        "claim");
        long sent = System.nanoTime();
        var grant = transport.call(w.endpoint.instance(), "step", request, 2000);
        if (!uuid(grant, "stepId").equals(action.id())
                || !string(grant, "kind").equals(action.kind()))
            throw new IOException("invalid_claim");
        long deadline =
                sent
                        + TimeUnit.MILLISECONDS.toNanos(
                                integer(grant, "remainingMs", 0, Integer.MAX_VALUE));
        var run = new RunStep(action);
        if (w.steps.putIfAbsent(action.id(), run) != null) return;
        BooleanSupplier live =
                () -> binding == b && world == b.world() && watches.get(w.id) == w && !closed;
        CompletionStage<InteropSnapshot.Ack> done;
        if (!live.getAsBoolean() || System.nanoTime() >= deadline)
            done = CompletableFuture.completedFuture(InteropSnapshot.Ack.of("abandoned"));
        else {
            try {
                done = listener.perform(b, value, deadline, live);
            } catch (RuntimeException error) {
                done = CompletableFuture.failedFuture(error);
            }
        }
        done.whenComplete(
                (ack, error) -> {
                    run.ack = error == null && ack != null ? ack : InteropSnapshot.Ack.of("failed");
                    w.next = 0;
                    // Same step may already be in flight. A lost ACK is retried by the periodic
                    // read.
                    async(
                            control,
                            () -> {
                                synchronized (run) {
                                    if (!run.completed) acknowledge(w, run);
                                }
                                return null;
                            });
                });
    }

    private void acknowledge(Watch w, RunStep run) throws Exception {
        var ack = run.ack;
        var body =
                object(
                        "operationId",
                        w.id,
                        "sessionId",
                        w.session,
                        "stepId",
                        run.action.id(),
                        "verb",
                        "ack",
                        "outcome",
                        ack.outcome());
        if (ack.continuous() != null) body.addProperty("continuous", ack.continuous());
        if (ack.handoffId() != null) body.addProperty("handoffId", ack.handoffId().toString());
        var confirmed =
                InteropSnapshot.parse(transport.call(w.endpoint.instance(), "step", body, 2000));
        if (!confirmed.operation().equals(w.id)
                || !confirmed.target().equals(w.target)
                || !confirmed.kind().equals(w.kind)) throw new IOException("wrong_operation");
        run.completed = true;
        w.next = 0;
        listener.actionAcknowledged(w.id, run.action.kind());
    }

    @Override
    public synchronized void close() {
        closed = true;
        world = null;
        binding = null;
        if (subscriber != null) subscriber.close();
        timer.shutdownNow();
        control.shutdownNow();
        queries.shutdownNow();
        pending.forEach(
                task -> task.completeExceptionally(new CancellationException("client_closed")));
    }
}
