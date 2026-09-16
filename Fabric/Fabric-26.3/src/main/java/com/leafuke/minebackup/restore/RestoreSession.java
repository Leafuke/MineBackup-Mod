package com.leafuke.minebackup.restore;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class RestoreSession {
    private static final long HANDSHAKE_TTL_NANOS = Duration.ofSeconds(5).toNanos();

    private Phase phase = Phase.IDLE;
    private Handshake handshake;
    private long handshakeGeneration;
    private String coordinatedWorld;
    private String levelId;
    private boolean reopenLan;
    private int lanPort = -1;

    public synchronized long recordHandshake(
            String action,
            String world,
            String mainVersion,
            String minimumModVersion) {
        Optional<Action> parsedAction = Action.parse(action);
        String normalizedWorld = normalize(world);
        if (parsedAction.isEmpty() || normalizedWorld == null) {
            return -1L;
        }
        long now = System.nanoTime();
        if (phase != Phase.IDLE || (handshake != null && now <= handshake.expiresAtNanos())) {
            return -1L;
        }
        handshake = null;

        long generation = ++handshakeGeneration;
        handshake = new Handshake(
                generation,
                parsedAction.get(),
                normalizedWorld,
                mainVersion,
                minimumModVersion,
                now + HANDSHAKE_TTL_NANOS);
        return generation;
    }

    public synchronized void clearHandshake(long generation) {
        if (handshake != null && handshake.generation() == generation) {
            handshake = null;
        }
    }

    public synchronized boolean consumeHandshake(Action expectedAction, String world) {
        String normalizedWorld = normalize(world);
        if (handshake == null
                || normalizedWorld == null
                || System.nanoTime() > handshake.expiresAtNanos()
                || handshake.action() != expectedAction
                || !handshake.world().equals(normalizedWorld)) {
            handshake = null;
            return false;
        }
        if (expectedAction == Action.RESTORE) {
            coordinatedWorld = handshake.world();
        }
        handshake = null;
        return true;
    }

    public synchronized boolean beginRestore(String newLevelId, boolean shouldReopenLan, int previousLanPort) {
        if (phase != Phase.IDLE || coordinatedWorld == null || normalize(newLevelId) == null) {
            return false;
        }
        phase = Phase.RELEASING_SERVER;
        levelId = newLevelId.trim();
        reopenLan = shouldReopenLan;
        lanPort = previousLanPort;
        return true;
    }

    public synchronized boolean isReleasingServer() {
        return phase == Phase.RELEASING_SERVER;
    }

    public synchronized boolean markServerReleased() {
        if (phase != Phase.RELEASING_SERVER) {
            return false;
        }
        phase = Phase.WAITING_FOR_RESTORE;
        return true;
    }

    public synchronized boolean markRestoreSucceeded() {
        if (phase != Phase.WAITING_FOR_RESTORE) {
            return false;
        }
        phase = Phase.WAITING_FOR_REJOIN;
        return true;
    }

    public synchronized Optional<RejoinInfo> beginRejoin() {
        if (phase != Phase.WAITING_FOR_REJOIN || levelId == null) {
            return Optional.empty();
        }
        phase = Phase.REJOINING;
        return Optional.of(new RejoinInfo(levelId, reopenLan, lanPort));
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized boolean matchesActiveWorld(String world) {
        String normalizedWorld = normalize(world);
        return phase != Phase.IDLE
                && coordinatedWorld != null
                && coordinatedWorld.equals(normalizedWorld);
    }

    public synchronized Optional<String> activeWorld() {
        return Optional.ofNullable(coordinatedWorld);
    }

    public synchronized void reset() {
        phase = Phase.IDLE;
        handshake = null;
        coordinatedWorld = null;
        levelId = null;
        reopenLan = false;
        lanPort = -1;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public enum Phase {
        IDLE,
        RELEASING_SERVER,
        WAITING_FOR_RESTORE,
        WAITING_FOR_REJOIN,
        REJOINING
    }

    public enum Action {
        BACKUP,
        RESTORE;

        public static Optional<Action> parse(String rawAction) {
            if (rawAction == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(rawAction.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
    }

    public record RejoinInfo(String levelId, boolean reopenLan, int lanPort) {
        public RejoinInfo {
            Objects.requireNonNull(levelId, "levelId");
        }
    }

    private record Handshake(
            long generation,
            Action action,
            String world,
            String mainVersion,
            String minimumModVersion,
            long expiresAtNanos) {
    }
}
