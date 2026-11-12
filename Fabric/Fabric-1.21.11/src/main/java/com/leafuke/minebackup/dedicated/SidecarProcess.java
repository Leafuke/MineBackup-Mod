package com.leafuke.minebackup.dedicated;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Blocking startup boundary, used only by the sidecar worker. */
final class SidecarProcess {
    @FunctionalInterface interface Starter { Process start() throws IOException; }
    @FunctionalInterface interface Probe { boolean ready() throws IOException; }
    @FunctionalInterface interface Pause { void await() throws InterruptedException; }
    private SidecarProcess() {}

    static Process start(Starter starter, Probe ready, BooleanSupplier current, Duration timeout,
            LongSupplier now, Pause pause) throws IOException, InterruptedException {
        if (!current.getAsBoolean()) throw new IOException("Restore session ended before sidecar startup");
        Process process = starter.start();
        boolean accepted = false;
        try {
            long deadline = now.getAsLong() + timeout.toNanos();
            while (now.getAsLong() < deadline) {
                if (!current.getAsBoolean()) throw new IOException("Restore session ended during sidecar startup");
                if (!process.isAlive()) throw new IOException("Sidecar exited before becoming ready: " + process.exitValue());
                if (ready.ready()) { accepted = true; return process; }
                pause.await();
            }
            throw new IOException("Timed out waiting for dedicated restore sidecar");
        } finally {
            if (!accepted) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }
}
