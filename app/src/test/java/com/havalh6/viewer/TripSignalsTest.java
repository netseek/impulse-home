package com.havalh6.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TripSignalsTest {
    @Test
    public void fuelInstFollowsTheClusterThemeParser() {
        TripSignals.FuelRate running = TripSignals.parseFuelInst("{1,6.2}");
        assertEquals(TripSignals.FUEL_RUNNING, running.mode);
        assertEquals(6.2, running.value, 1e-9);

        TripSignals.FuelRate idle = TripSignals.parseFuelInst("{4.0, 0.8}");
        assertEquals(TripSignals.FUEL_IDLE, idle.mode);
        assertEquals(0.8, idle.value, 1e-9);

        // A bare number is metric 1, as Impulse's updateGasConsumption treats it.
        assertEquals(TripSignals.FUEL_RUNNING, TripSignals.parseFuelInst("7.5").mode);

        assertNull(TripSignals.parseFuelInst("{1,65535}"));
        assertNull(TripSignals.parseFuelInst("garbage"));
        assertNull(TripSignals.parseFuelInst("{1,-3}"));
    }

    @Test
    public void boolReadsTheFirstSlotOfAVector() {
        assertTrue(TripSignals.parseBool("{1,0}"));
        assertTrue(TripSignals.parseBool("1"));
        assertFalse(TripSignals.parseBool("0"));
        assertNull(TripSignals.parseBool(""));
    }

    @Test
    public void readyIsOffOnlyForZeroAndMinusOneLikeImpulse() {
        assertTrue(TripSignals.parseReady("1"));
        assertTrue(TripSignals.parseReady("2"));
        assertFalse(TripSignals.parseReady("0"));
        assertFalse(TripSignals.parseReady("-1"));
        assertFalse(TripSignals.parseReady("{0,1}"));
        assertNull(TripSignals.parseReady(" "));
    }

    @Test
    public void numberReadsTheSecondSlotOfAPair() {
        assertEquals(12345.6, TripSignals.parseNumber("12345.6"), 1e-9);
        assertEquals(17.3, TripSignals.parseNumber("{0,17.3}"), 1e-9);
        assertTrue(Double.isNaN(TripSignals.parseNumber("n/a")));
    }

    @Test
    public void flowIceSlot() {
        assertFalse(TripSignals.parseFlowIce("v1|ev|0|1|0"));
        assertTrue(TripSignals.parseFlowIce("v1|hybrid|1|1|0"));
        assertNull(TripSignals.parseFlowIce("v0|ev|0|1|0"));
        assertNull(TripSignals.parseFlowIce("v1|ev"));
    }

    @Test
    public void sentinelsAreRejected() {
        assertFalse(TripSignals.inRange(65535, 0, 100000));
        assertFalse(TripSignals.inRange(Double.NaN, 0, 1));
        assertTrue(TripSignals.inRange(42, 0, 100));
    }
}
