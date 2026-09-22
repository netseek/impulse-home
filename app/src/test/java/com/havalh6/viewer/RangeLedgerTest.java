package com.havalh6.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class RangeLedgerTest {
    private static final long T0 = 1_757_000_000_000L;
    private static final long TRIP = T0 - 60_000L;
    private static final long HOUR = 3_600_000L;

    /** In-memory Store: the newest cycle is the one with the highest id. */
    private static final class MemStore implements RangeLedger.Store {
        final List<RangeLedger.Cycle> cycles = new ArrayList<>();
        final List<RangeLedger.Sample> samples = new ArrayList<>();

        @Override public RangeLedger.Cycle loadLatestCycle() {
            RangeLedger.Cycle best = null;
            for (RangeLedger.Cycle c : cycles) if (best == null || c.id > best.id) best = c;
            return best;
        }

        @Override public void saveCycle(RangeLedger.Cycle c) {
            if (!cycles.contains(c)) cycles.add(c);
        }

        @Override public void addSample(long cycleId, RangeLedger.Sample s) {
            samples.add(s);
        }

        @Override public void pruneCycles(int keep) {}

        RangeLedger.Cycle byEnd(String kind) {
            for (RangeLedger.Cycle c : cycles) if (kind.equals(c.endKind)) return c;
            return null;
        }
    }

    private MemStore store;
    private RangeLedger ledger;
    private long t;

    @Before
    public void setUp() {
        store = new MemStore();
        ledger = new RangeLedger(store);
        t = T0;
    }

    private void car(double soc, double oem, double odo) {
        ledger.onSignal(TripEngine.KEY_SOC, String.valueOf(soc));
        ledger.onSignal(RangeLedger.KEY_EV_RANGE, String.valueOf(oem));
        ledger.onSignal(TripEngine.KEY_ODOMETER, String.valueOf(odo));
    }

    private void tick(long trip, double ev, double km) {
        t += 1_000L;
        ledger.onTick(t, trip, ev, km);
    }

    /** Drive at 40%, park, come back charged to 90%: the first cycle opens. */
    private void chargeAndSetOff(long trip) {
        car(40, 60, 1000);
        tick(TRIP - 10 * HOUR, 0, 0);
        car(90, 140, 1000);
        tick(trip, 0, 0);
    }

    @Test
    public void nothingIsRecordedBeforeTheFirstCharge() {
        car(80, 120, 1000);
        tick(TRIP, 0, 0);
        car(78, 116, 1004);
        tick(TRIP, 4, 4);
        assertNull(ledger.current());
        assertTrue(store.samples.isEmpty());
    }

    @Test
    public void aChargeOpensACycleWithTheForecastOfThatMoment() {
        car(40, 60, 1000);
        tick(TRIP - 10 * HOUR, 0, 0);
        car(90, 140, 1000);
        ledger.onHistForecast(125, 90, t);
        tick(TRIP, 0.3, 0.4);
        RangeLedger.Cycle c = ledger.current();
        assertNotNull(c);
        assertEquals(RangeLedger.START_CHARGE, c.startKind);
        assertEquals(90, c.soc0, 1e-9);
        assertEquals(140, c.oem0, 1e-9);
        assertEquals(125, c.hist0, 1e-9);
        // What this trip drove before the cycle opened is not counted.
        assertEquals(0, c.evKm, 1e-9);
        assertEquals(1, store.samples.size());
    }

    @Test
    public void theEnginesChargeStopArmsTheFirstCycleWhenThereIsNoSocToCompare() {
        // Installed at the charger: no driving SOC seen before, so only TripEngine knows.
        car(90, 140, 1000);
        tick(TRIP, 0, 0);
        assertNull(ledger.current());
        ledger.onCharge();
        tick(TRIP, 1, 1);
        assertNotNull(ledger.current());
        assertEquals(90, ledger.current().soc0, 1e-9);
    }

    @Test
    public void engineChargingWhileDrivingIsNotAPlugCharge() {
        car(30, 40, 1000);
        tick(TRIP, 0, 0);
        for (int i = 1; i <= 20; i++) {
            car(30 + i, 40 + i, 1000 + i);
            tick(TRIP, 0, i);
        }
        assertNull(ledger.current());
    }

    @Test
    public void countsDrivingAcrossTripsAndSamplesPerSocPoint() {
        chargeAndSetOff(TRIP);
        car(89.4, 138, 1003);
        tick(TRIP, 1.5, 3);
        assertEquals(1, store.samples.size());  // under a point: no sample
        car(88.9, 136, 1005);
        tick(TRIP, 5, 5);
        assertEquals(2, store.samples.size());
        long trip2 = TRIP + HOUR;
        tick(trip2, 0.5, 0.5);
        car(87, 132, 1010);
        tick(trip2, 4, 5);
        RangeLedger.Cycle c = ledger.current();
        assertEquals(9, c.evKm, 1e-9);
        assertEquals(10, c.km, 1e-9);
    }

    @Test
    public void aStaleOrMismatchedHistoryEstimateIsNotUsed() {
        car(40, 60, 1000);
        tick(TRIP - 10 * HOUR, 0, 0);
        car(90, 140, 1000);
        ledger.onHistForecast(60, 40, t);  // worked out for 40%: from before the charge
        tick(TRIP, 0, 0);
        assertTrue(Double.isNaN(ledger.current().hist0));
        ledger.onHistForecast(125, 90, t);
        tick(TRIP, 0.2, 0.2);
        // Backfilled while the cycle has barely moved.
        assertEquals(125, ledger.current().hist0, 1e-9);
    }

    @Test
    public void theNextChargeEndsTheCycleAndStartsAnother() {
        chargeAndSetOff(TRIP);
        car(89, 138, 1002);
        tick(TRIP, 2, 2);
        long trip2 = TRIP + 8 * HOUR;
        car(99, 155, 1002);
        tick(trip2, 0.1, 0.1);
        RangeLedger.Cycle old = store.byEnd(RangeLedger.END_CHARGE);
        assertNotNull(old);
        // The new trip's first metres went to the new cycle, not the old one.
        assertEquals(2, old.evKm, 1e-9);
        RangeLedger.Cycle c = ledger.current();
        assertEquals(99, c.soc0, 1e-9);
        assertEquals(155, c.oem0, 1e-9);
        // TripEngine's own CHARGE stop lands minutes later: it must not end this one.
        ledger.onCharge();
        tick(trip2, 1, 1);
        assertEquals(c.id, ledger.current().id);
    }

    @Test
    public void runningEmptyEndsTheCycleAndOnlyAChargeStartsAnother() {
        car(40, 5, 1000);
        tick(TRIP - 10 * HOUR, 0, 0);
        car(50, 8, 1000);
        tick(TRIP, 0, 0);
        assertNotNull(ledger.current());
        car(48, 0, 1001);
        tick(TRIP, 1, 1);
        car(48, 0, 1007);
        tick(TRIP, 1, 7);
        assertNull(ledger.current());
        assertNotNull(store.byEnd(RangeLedger.END_DEPLETED));
        tick(TRIP, 1, 8);
        assertNull(ledger.current());
        // A restart does not start one either.
        ledger = new RangeLedger(store);
        tick(TRIP, 1, 9);
        assertNull(ledger.current());
        // Charged.
        car(90, 140, 1010);
        tick(TRIP + HOUR, 0, 0);
        assertNotNull(ledger.current());
        assertEquals(90, ledger.current().soc0, 1e-9);
    }

    @Test
    public void unrecordedDrivingEndsTheCycleAndTheNextWaitsForACharge() {
        chargeAndSetOff(TRIP);
        car(89, 138, 1002);
        tick(TRIP, 2, 2);
        // The odometer jumped 20 km that no trip accounts for.
        car(80, 120, 1022);
        tick(TRIP + HOUR, 0.1, 0.1);
        assertNotNull(store.byEnd(RangeLedger.END_GAP));
        assertNull(ledger.current());
    }

    @Test
    public void aRestartCarriesOnTheOpenCycle() {
        chargeAndSetOff(TRIP);
        car(88, 136, 1004);
        tick(TRIP, 4, 4);
        ledger.flush(t);
        RangeLedger.Cycle before = ledger.current();
        ledger = new RangeLedger(store);
        car(87, 134, 1006);
        tick(TRIP, 6, 6);
        assertEquals(before.id, ledger.current().id);
        assertEquals(6, ledger.current().evKm, 1e-9);
    }

    @Test
    public void aChargeWhileTheProcessWasDownIsStillSeenAfterARestart() {
        chargeAndSetOff(TRIP);
        car(60, 90, 1040);
        tick(TRIP, 40, 40);
        ledger.flush(t);
        long id = ledger.current().id;
        ledger = new RangeLedger(store);
        car(95, 150, 1040);
        tick(TRIP + 10 * HOUR, 0, 0);
        assertNotNull(store.byEnd(RangeLedger.END_CHARGE));
        assertTrue(ledger.current().id != id);
    }
}
