package com.leafuke.minebackup.dedicated;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseGateTest {
    @Test
    void requiresThreeConsecutiveSamplesAndAcknowledgesOnce() {
        ReleaseGate gate = new ReleaseGate(3);
        assertFalse(gate.observe(true, true));
        assertFalse(gate.observe(false, true));
        assertFalse(gate.observe(true, true));
        assertFalse(gate.observe(true, true));
        assertTrue(gate.observe(true, true));
        assertFalse(gate.observe(true, true));
    }
}
