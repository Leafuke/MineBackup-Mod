package com.leafuke.minebackup.dedicated;

/** Requires consecutive stable probes and emits release exactly once. */
final class ReleaseGate {
    private final int requiredStableSamples;
    private int stableSamples;
    private boolean released;

    ReleaseGate(int requiredStableSamples) {
        if (requiredStableSamples < 1) {
            throw new IllegalArgumentException("requiredStableSamples must be positive");
        }
        this.requiredStableSamples = requiredStableSamples;
    }

    boolean observe(boolean parentExited, boolean filesReleased) {
        if (released) {
            return false;
        }
        if (!parentExited || !filesReleased) {
            stableSamples = 0;
            return false;
        }
        if (++stableSamples < requiredStableSamples) {
            return false;
        }
        released = true;
        return true;
    }
}
