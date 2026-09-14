package com.havalh6.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Before;
import org.junit.Test;

public class TripEngineTest {
    private static final long T0 = 1_757_000_000_000L;

    private final List<TripSummary> closed = new ArrayList<>();
    private final List<Long> discarded = new ArrayList<>();
    private final List<TripPoint> points = new ArrayList<>();
    private final List<TripStop> stops = new ArrayList<>();
    private final List<Long> stopTrips = new ArrayList<>();
    private int opened;
    private TripEngine engine;

    private final TripEngine.Listener listener = new TripEngine.Listener() {
        @Override public void onTripOpened(long startMs) { opened++; }
        @Override public void onPoint(long startMs, TripPoint point) { points.add(point); }
        @Override public void onTripClosed(TripSummary summary) { closed.add(summary); }
        @Override public void onTripDiscarded(long startMs) { discarded.add(startMs); }
        @Override public void onStop(long startMs, TripStop stop) { stops.add(stop); stopTrips.add(startMs); }
    };

    @Before
    public void setUp() {
        engine = new TripEngine(listener);
    }

    private void signal(String key, String value, long t) {
        engine.onSignal(key, value, t);
    }

    private static String odo(double km) {
        return String.format(Locale.US, "%.1f", km);
    }

    /**
     * Drives at a constant speed from {@code from} for {@code ms}, ticking every
     * second and emitting the 0.1 km odometer ticks a real car would. Returns the
     * odometer at the end.
     */
    private double cruise(double kmh, long from, long ms, double odoStart) {
        double lastTick = odoStart;
        for (long t = from; t <= from + ms; t += 1000) {
            double travelled = kmh * (t - from) / 3_600_000.0;
            double tick = Math.floor((odoStart + travelled) * 10 + 1e-9) / 10;
            if (tick > lastTick) {
                signal(TripEngine.KEY_ODOMETER, odo(tick), t);
                lastTick = tick;
            }
            engine.tick(t);
        }
        return lastTick;
    }

    private void closeAfterGap(long readyOffAt) {
        engine.tick(readyOffAt + TripEngine.MERGE_GAP_MS + 1);
    }

    @Test
    public void constantCruiseUsesOdometerAsDistanceOfRecord() {
        // Speedometers read high; the odometer is the true distance. The car
        // really covers 60 km/h (odometer ticks) while vehicle_speed says 63, so
        // the two sources disagree by 5% and only the odometer gives 6.0 km.
        signal(TripEngine.KEY_ODOMETER, "1000.0", T0 - 5000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "63", T0);
        double end = cruise(60, T0, 360_000, 1000.0);
        signal(TripEngine.KEY_READY, "0", T0 + 360_000);
        closeAfterGap(T0 + 360_000);

        assertEquals(1, closed.size());
        TripSummary s = closed.get(0);
        assertEquals(1006.0, end, 1e-9);
        assertEquals(6.0, s.km, 1e-9);
        assertEquals(6.3, s.kmIntegrated, 0.02);
        assertEquals(360_000, s.driveMs, 1000);
        assertEquals(T0 + 360_000, s.endMs);
        assertEquals(63.0, s.avgMovingKmh(), 0.2);
    }

    @Test
    public void aTripLeftOpenAcrossASleepClosesWhereItsDataStopped() {
        // Parked at home: the head unit slept before READY=0 reached the
        // recorder, so its thread froze believing the car was still READY.
        signal(TripEngine.KEY_ODOMETER, "100.0", T0 - 1000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        cruise(60, T0, 120_000, 100.0);
        long wake = T0 + 120_000 + 8 * 3_600_000L;   // next morning
        engine.closeInterrupted(wake);               // the recorder saw its ticks stop
        engine.tick(wake);
        assertEquals(1, closed.size());
        assertEquals(T0 + 120_000, closed.get(0).endMs);
        assertFalse(engine.isOpen());
        engine.tick(wake + 1000);
        assertFalse("no trip until READY is heard again", engine.isOpen());
        signal(TripEngine.KEY_READY, "1", wake + 1500);   // Impulse replays READY
        assertTrue(engine.isOpen());
        assertEquals(2, opened);
    }

    @Test
    public void guidanceHoldsTheTripAcrossALongStop() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        engine.onNavigation(true, 30_000, 1800, false, T0);
        cruise(60, T0, 120_000, 400.0);
        engine.onNavigation(true, 28_000, 1700, false, T0 + 120_000);
        long off = T0 + 121_000;
        signal(TripEngine.KEY_SPEED, "0", off);
        signal(TripEngine.KEY_READY, "0", off);
        for (long t = off; t <= off + 40 * 60_000L; t += 5000) engine.tick(t);   // 40 min rest stop
        assertTrue("guiding and not arrived: the stop does not end the trip", engine.isOpen());
        assertTrue(closed.isEmpty());
        signal(TripEngine.KEY_READY, "1", off + 40 * 60_000L + 1000);
        assertEquals("the same trip carries on", 1, opened);
        assertTrue(engine.isOpen());
    }

    @Test
    public void endingGuidanceWhileParkedLetsTheUsualRuleEndTheTrip() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        engine.onNavigation(true, 30_000, 1800, false, T0);
        cruise(60, T0, 120_000, 500.0);
        long off = T0 + 121_000;
        signal(TripEngine.KEY_READY, "0", off);
        for (long t = off; t <= off + 20 * 60_000L; t += 5000) engine.tick(t);
        assertTrue(engine.isOpen());
        long cancel = off + 20 * 60_000L;
        engine.onNavigation(false, Double.NaN, Double.NaN, false, cancel);   // cancelled in Android Auto
        engine.tick(cancel + 1000);
        assertEquals(1, closed.size());
        assertEquals("ends when the car was switched off", off, closed.get(0).endMs);
        assertFalse(closed.get(0).arrived);
    }

    @Test
    public void parkedAtTheDestinationIsNotHeld() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "30", T0);
        engine.onNavigation(true, 3000, 400, false, T0);
        cruise(30, T0, 120_000, 800.0);
        engine.onNavigation(true, 250, 30, false, T0 + 120_000);             // at the door, guidance not cleared
        long off = T0 + 121_000;
        signal(TripEngine.KEY_READY, "0", off);
        closeAfterGap(off);
        assertEquals(1, closed.size());
        assertTrue(closed.get(0).arrived);
    }

    @Test
    public void guidanceThatWentSilentStopsHoldingTheTrip() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        engine.onNavigation(true, 30_000, 1800, false, T0);
        cruise(60, T0, 120_000, 600.0);
        long off = T0 + 121_000;
        signal(TripEngine.KEY_READY, "0", off);
        for (long t = off; t <= T0 + TripEngine.GUIDANCE_HOLD_MAX_MS - 1000; t += 10_000) engine.tick(t);
        assertTrue(engine.isOpen());
        engine.tick(T0 + TripEngine.GUIDANCE_HOLD_MAX_MS + 1000);
        assertEquals(1, closed.size());
        assertEquals(off, closed.get(0).endMs);
    }

    @Test
    public void aShortSleepWhileGuidingKeepsTheTrip() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        engine.onNavigation(true, 30_000, 1800, false, T0);
        cruise(60, T0, 120_000, 700.0);
        long wake = T0 + 120_000 + 15 * 60_000L;   // the head unit slept at a stop
        engine.closeInterrupted(wake);
        assertTrue(engine.isOpen());
        signal(TripEngine.KEY_READY, "1", wake + 500);
        assertEquals(1, opened);
        assertTrue(engine.isOpen());
    }

    @Test
    public void finishingWhileReadyStartsTheNextTripThere() {
        signal(TripEngine.KEY_ODOMETER, "200.0", T0 - 1000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        double o = cruise(60, T0, 120_000, 200.0);
        long tap = T0 + 120_000;
        engine.finishNow(tap);
        assertEquals(1, closed.size());
        assertEquals(tap, closed.get(0).endMs);
        assertEquals(2.0, closed.get(0).km, 1e-9);
        assertTrue("still READY: a new trip starts at the tap", engine.isOpen());
        cruise(60, tap, 120_000, o);
        signal(TripEngine.KEY_READY, "0", tap + 120_000);
        closeAfterGap(tap + 120_000);
        assertEquals(2, closed.size());
        assertEquals(tap, closed.get(1).startMs);
        assertEquals(2.0, closed.get(1).km, 1e-9);
    }

    @Test
    public void finishingAfterReadyOffEndsAtReadyOffAndStartsNothing() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        cruise(60, T0, 120_000, 300.0);
        signal(TripEngine.KEY_SPEED, "0", T0 + 121_000);
        signal(TripEngine.KEY_READY, "0", T0 + 122_000);
        engine.finishNow(T0 + 180_000);
        assertEquals(1, closed.size());
        assertEquals(T0 + 122_000, closed.get(0).endMs);
        assertFalse(engine.isOpen());
        assertEquals(1, opened);
    }

    @Test
    public void shortStopKeepsOneTripAndExcludesThePause() {
        signal(TripEngine.KEY_ODOMETER, "500.0", T0 - 1000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        double o = cruise(60, T0, 180_000, 500.0);
        signal(TripEngine.KEY_SPEED, "0", T0 + 180_000);
        signal(TripEngine.KEY_READY, "0", T0 + 181_000);
        for (long t = T0 + 181_000; t <= T0 + 481_000; t += 1000) engine.tick(t);  // 5 min fuel stop
        long resume = T0 + 481_000;
        signal(TripEngine.KEY_READY, "1", resume);
        signal(TripEngine.KEY_SPEED, "60", resume);
        cruise(60, resume, 180_000, o);
        signal(TripEngine.KEY_READY, "0", resume + 180_000);
        closeAfterGap(resume + 180_000);

        assertEquals(1, opened);
        assertEquals(1, closed.size());
        TripSummary s = closed.get(0);
        assertEquals(6.0, s.km, 1e-9);
        assertEquals(360_000, s.driveMs, 2000);
        assertTrue("the parked, READY-off pause is not idle time", s.idleMs < 5000);
    }

    @Test
    public void longStopSplitsTrips() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "50", T0);
        cruise(50, T0, 120_000, 0);
        signal(TripEngine.KEY_READY, "0", T0 + 120_000);
        long back = T0 + 120_000 + TripEngine.MERGE_GAP_MS + 60_000;
        signal(TripEngine.KEY_READY, "1", back);
        cruise(50, back, 120_000, 1.6);
        signal(TripEngine.KEY_READY, "0", back + 120_000);
        closeAfterGap(back + 120_000);

        assertEquals(2, opened);
        assertEquals(2, closed.size());
        assertEquals(T0 + 120_000, closed.get(0).endMs);
    }

    @Test
    public void packEnergyIsSplitIntoOutAndRecovered() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "0", T0);
        signal(TripEngine.KEY_VOLTAGE, "300", T0);
        signal(TripEngine.KEY_CURRENT, "20", T0);
        long t = T0;
        for (; t <= T0 + 3_600_000; t += 1000) engine.tick(t);
        signal(TripEngine.KEY_CURRENT, "-10", t);
        long end = t + 1_800_000;
        for (; t <= end; t += 1000) engine.tick(t);

        TripSummary live = engine.live(end);
        assertNotNull(live);
        assertEquals(6.0, live.kwhOut, 0.01);
        assertEquals(1.5, live.kwhIn, 0.01);
    }

    @Test
    public void fuelIntegratesRunningPerDistanceAndIdlePerHour() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "100", T0);
        signal(TripEngine.KEY_FUEL_INST, "{1,6.0}", T0);
        long t = T0;
        for (; t <= T0 + 360_000; t += 1000) engine.tick(t);          // 10 km at 6 L/100
        signal(TripEngine.KEY_SPEED, "0", t);
        signal(TripEngine.KEY_FUEL_INST, "{4,1.0}", t);
        long end = t + 360_000;
        for (; t <= end; t += 1000) engine.tick(t);                    // 6 min idle at 1 L/h

        TripSummary live = engine.live(end);
        assertEquals(0.6 + 0.1, live.fuelL, 0.005);
    }

    @Test
    public void evShareCountsDistanceWithTheEngineOff() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "40", T0);
        signal(TripEngine.KEY_FLOW, "v1|ev|0|1|0", T0);
        long t = T0;
        for (; t <= T0 + 300_000; t += 1000) engine.tick(t);
        signal(TripEngine.KEY_ICE, "1", t);
        long end = t + 300_000;
        for (; t <= end; t += 1000) engine.tick(t);

        TripSummary live = engine.live(end);
        assertEquals(0.5, live.evKm / live.kmIntegrated, 0.01);
    }

    @Test
    public void anAllElectricDriveReadsAllElectric() {
        // Owner report 2026-09-14: the engine never ran and the trip read 99%.
        signal(TripEngine.KEY_ODOMETER, "900.0", T0 - 1000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "57", T0);                  // speedometer reads under the odometer
        long t = T0;
        for (; t <= T0 + 20_000; t += 1000) engine.tick(t);     // engine state not heard yet
        signal(TripEngine.KEY_FLOW, "v1|ev|0|1|0", t);
        cruise(60, t, 300_000, 900.0);
        signal(TripEngine.KEY_READY, "0", t + 300_000);
        closeAfterGap(t + 300_000);
        assertEquals(1, closed.size());
        TripSummary s = closed.get(0);
        assertEquals(1.0, s.evShare(), 1e-9);
        assertEquals(s.km, s.evKm, 1e-9);
    }

    @Test
    public void clockJumpsAreNotIntegrated() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        engine.tick(T0 + 1000);
        double before = engine.live(T0 + 1000).kmIntegrated;
        engine.tick(T0 + 86_400_000L);      // NTP/GNSS correction forward a day
        assertEquals(before, engine.live(T0 + 86_400_000L).kmIntegrated, 1e-9);
        engine.tick(T0);                    // and back again
        assertEquals(before, engine.live(T0).kmIntegrated, 1e-9);
    }

    @Test
    public void harshBrakingCountsOncePerEvent() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "80", T0);
        signal(TripEngine.KEY_SPEED, "60", T0 + 1000);   // -5.6 m/s²
        signal(TripEngine.KEY_SPEED, "40", T0 + 2000);   // still the same stop
        signal(TripEngine.KEY_SPEED, "38", T0 + 3000);   // eased off
        signal(TripEngine.KEY_SPEED, "37", T0 + 4000);
        signal(TripEngine.KEY_SPEED, "15", T0 + 5000);   // a second hard stop
        signal(TripEngine.KEY_SPEED, "30", T0 + 5100);   // too close to differentiate: ignored

        TripSummary live = engine.live(T0 + 6000);
        assertEquals(2, live.harshBrake);
        assertEquals(0, live.harshAccel);
    }

    @Test
    public void stopsCountReturnsToStandstillAfterMoving() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "0", T0);
        signal(TripEngine.KEY_SPEED, "3", T0 + 10_000);   // creeping does not arm a stop
        signal(TripEngine.KEY_SPEED, "0", T0 + 20_000);
        signal(TripEngine.KEY_SPEED, "30", T0 + 30_000);
        signal(TripEngine.KEY_SPEED, "0", T0 + 40_000);
        assertEquals(1, engine.live(T0 + 41_000).stops);
    }

    @Test
    public void readyBlipInTheDrivewayIsDiscarded() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "0", T0);
        for (long t = T0; t <= T0 + 20_000; t += 1000) engine.tick(t);
        signal(TripEngine.KEY_READY, "0", T0 + 20_000);
        closeAfterGap(T0 + 20_000);
        assertTrue(closed.isEmpty());
        assertEquals(1, discarded.size());
    }

    @Test
    public void checkpointRoundTripsAndResumesTheSameTrip() {
        signal(TripEngine.KEY_ODOMETER, "200.0", T0 - 1000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        signal(TripEngine.KEY_FUEL_INST, "{1,5.0}", T0);
        signal("car.basic.avg_fuel_consumption", "15.2", T0);
        double o = cruise(60, T0, 120_000, 200.0);
        String saved = engine.checkpoint();

        // Process restarts 60 s later; Impulse replays the car's state.
        TripEngine revived = new TripEngine(listener);
        engine = revived;
        long back = T0 + 180_000;
        revived.restore(TripState.decode(saved), back);
        assertTrue(revived.isOpen());
        signal(TripEngine.KEY_ODOMETER, odo(o), back);
        signal(TripEngine.KEY_SPEED, "60", back);
        signal(TripEngine.KEY_FUEL_INST, "{1,5.0}", back);
        signal(TripEngine.KEY_READY, "1", back);
        o = cruise(60, back, 120_000, o);
        signal(TripEngine.KEY_READY, "0", back + 120_000);
        closeAfterGap(back + 120_000);

        assertEquals(1, closed.size());
        TripSummary s = closed.get(0);
        assertEquals(T0, s.startMs);
        assertEquals(4.0, s.km, 1e-9);
        assertEquals(0.2, s.fuelL, 0.005);
        assertEquals("15.2", s.rawFirst.get("car.basic.avg_fuel_consumption"));
    }

    @Test
    public void staleCheckpointClosesAtItsLastSample() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        cruise(60, T0, 120_000, 0);
        String saved = engine.checkpoint();

        TripEngine revived = new TripEngine(listener);
        revived.restore(TripState.decode(saved), T0 + 120_000 + TripEngine.MERGE_GAP_MS + 1);
        assertFalse(revived.isOpen());
        assertEquals(1, closed.size());
        assertEquals(T0 + 120_000, closed.get(0).endMs);
    }

    @Test
    public void pointsCarryAFixOnlyWhileItIsFresh() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "30", T0);
        engine.onLocation(-22.9, -43.1, 12, 5, T0);
        engine.tick(T0);
        engine.tick(T0 + 10_000);   // fix is 10 s old by now
        assertEquals(2, points.size());
        assertTrue(points.get(0).hasFix());
        assertFalse(points.get(1).hasFix());
    }

    @Test
    public void climbIgnoresGpsNoiseBelowTheHysteresis() {
        signal(TripEngine.KEY_READY, "1", T0);
        double[] alts = {10, 12, 10.5, 12.5, 11, 20, 18, 30};
        for (int i = 0; i < alts.length; i++) engine.onLocation(-22.9, -43.1, alts[i], 5, T0 + i * 1000L);
        assertEquals(20.0, engine.live(T0 + 9000).climbM, 1e-9);
    }

    @Test
    public void implausibleOdometerJumpIsNotDistance() {
        // Emulator 2026-09-13: a resumed trip received an odometer 1000 km above
        // its start and reported 1001 km.
        signal(TripEngine.KEY_ODOMETER, "1000.0", T0 - 1000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        cruise(60, T0, 60_000, 1000.0);                       // 1.0 km -> 1001.0
        signal(TripEngine.KEY_ODOMETER, "2001.0", T0 + 61_000);  // replayed / corrupt value
        cruise(60, T0 + 61_000, 60_000, 2001.0);              // 1.0 km -> 2002.0
        signal(TripEngine.KEY_READY, "0", T0 + 121_000);
        closeAfterGap(T0 + 121_000);

        assertEquals(1, closed.size());
        assertEquals(2.0, closed.get(0).km, 1e-9);
        assertEquals(1, closed.get(0).odoJumps);
    }

    @Test
    public void oneOdometerSpikeDoesNotLatchOutLaterReadings() {
        // Emulator 2026-09-13: after a spike to 9999.0 every real reading was
        // below the "last" value and was dropped for the rest of the trip.
        signal(TripEngine.KEY_ODOMETER, "1000.0", T0 - 1000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        cruise(60, T0, 60_000, 1000.0);                        // 1.0 km -> 1001.0
        signal(TripEngine.KEY_ODOMETER, "9999.0", T0 + 61_000);   // spike
        cruise(60, T0 + 61_000, 60_000, 1001.0);               // real values resume: 1001.1 .. 1002.0
        signal(TripEngine.KEY_READY, "0", T0 + 121_000);
        closeAfterGap(T0 + 121_000);

        assertEquals(1, closed.size());
        // The spike and the first real tick after it each re-base: 0.1 km lost, not 1 km.
        assertEquals(1.9, closed.get(0).km, 1e-9);
        assertEquals(2, closed.get(0).odoJumps);
    }

    @Test
    public void aFewMillilitresDoNotMakeAKmPerLitreFigure() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        signal(TripEngine.KEY_FUEL_INST, "{1,0.5}", T0);
        for (long t = T0; t <= T0 + 60_000; t += 1000) engine.tick(t);   // 1 km at 0.5 L/100
        TripSummary live = engine.live(T0 + 60_000);
        assertEquals(0.005, live.fuelL, 0.0005);
        assertTrue(Double.isNaN(live.kmPerLitre()));
    }

    @Test
    public void guidedTripRecordsThePlanAndTheArrival() {
        signal(TripEngine.KEY_ODOMETER, "100.0", T0 - 1000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        engine.onNavigation(true, 1200, 90, false, T0);
        long t = T0;
        double o = 100.0;
        for (int i = 1; i <= 12; i++) {                       // 100 m every 6 s
            long at = T0 + i * 6000L;
            for (; t < at; t += 1000) engine.tick(t);
            o = Math.round((o + 0.1) * 10) / 10.0;
            signal(TripEngine.KEY_ODOMETER, odo(o), at);
            engine.onNavigation(true, Math.max(0, 1200 - i * 100), Math.max(0, 90 - i * 7.5), false, at);
        }
        signal(TripEngine.KEY_READY, "0", T0 + 80_000);
        closeAfterGap(T0 + 80_000);

        TripSummary s = closed.get(0);
        assertTrue(s.guided);
        assertTrue(s.arrived);
        assertEquals(1.2, s.plannedKm, 1e-9);
        assertEquals(90, s.plannedS);
        // Arrived when 100 m remained: at the 11th tick, 1.1 km and 66 s in.
        assertEquals(1.1, s.guidedKm, 1e-9);
        assertEquals(66, s.guidedS);
    }

    @Test
    public void guidanceEndingFarFromTheDestinationIsNotAnArrival() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        engine.onNavigation(true, 5000, 400, false, T0);
        for (long t = T0; t <= T0 + 60_000; t += 1000) engine.tick(t);
        engine.onNavigation(true, 4000, 320, false, T0 + 60_000);
        engine.onNavigation(false, Double.NaN, Double.NaN, false, T0 + 61_000);
        signal(TripEngine.KEY_READY, "0", T0 + 62_000);
        closeAfterGap(T0 + 62_000);

        TripSummary s = closed.get(0);
        assertTrue(s.guided);
        assertFalse(s.arrived);
        assertEquals(61, s.guidedS);
    }

    @Test
    public void guidanceClearedAtTheDoorCountsAsArrivedAndOnlyGuidedDistanceCounts() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        long t = T0;
        for (; t <= T0 + 60_000; t += 1000) engine.tick(t);   // 1 km before guidance starts
        engine.onNavigation(true, 700, 45, false, t);
        long guided = t;
        for (; t <= guided + 30_000; t += 1000) engine.tick(t);   // 0.5 km guided
        engine.onNavigation(true, 200, 12, false, t);
        engine.onNavigation(false, Double.NaN, Double.NaN, false, t);
        signal(TripEngine.KEY_READY, "0", t + 1000);
        closeAfterGap(t + 1000);

        TripSummary s = closed.get(0);
        assertTrue(s.arrived);
        // 30 s guided plus the 1 s step each signal edge advances: 31 s at 60 km/h.
        assertEquals(0.517, s.guidedKm, 0.005);
        // Everything, guided or not: 60 + 1 + 30 + 1 + 1 s.
        assertEquals(1.55, s.km, 0.005);
    }

    @Test
    public void noGuidanceLeavesTheTripUnguided() {
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        for (long t = T0; t <= T0 + 60_000; t += 1000) engine.tick(t);
        engine.onNavigation(false, Double.NaN, Double.NaN, false, T0 + 60_000);
        TripSummary live = engine.live(T0 + 60_000);
        assertFalse(live.guided);
        assertTrue(Double.isNaN(live.plannedKm));
    }

    @Test
    public void refuelDuringAStopIsOneStopWithLitres() {
        signal(TripEngine.KEY_FUEL_PCT, "40", T0 - 1000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        engine.onLocation(-22.9, -43.1, 10, 5, T0 + 1000);
        for (long t = T0; t <= T0 + 60_000; t += 1000) engine.tick(t);
        signal(TripEngine.KEY_SPEED, "0", T0 + 61_000);
        signal(TripEngine.KEY_READY, "0", T0 + 62_000);
        signal(TripEngine.KEY_FUEL_PCT, "70", T0 + 200_000);   // gauge climbs during the stop
        signal(TripEngine.KEY_FUEL_PCT, "98", T0 + 230_000);   // and settles higher
        long resume = T0 + 300_000;
        signal(TripEngine.KEY_READY, "1", resume);
        signal(TripEngine.KEY_SPEED, "60", resume);
        for (long t = resume; t <= resume + TripEngine.LEVEL_SETTLE_MS + 2000; t += 1000) engine.tick(t);

        assertEquals(1, stops.size());
        TripStop s = stops.get(0);
        assertEquals(TripStop.REFUEL, s.kind);
        assertEquals(40, s.before, 1e-9);
        assertEquals(98, s.after, 1e-9);
        assertEquals(58 * 55 / 100.0, s.amount, 1e-9);
        assertEquals(-22.9, s.lat, 1e-9);
        assertEquals(T0, (long) stopTrips.get(0));
    }

    @Test
    public void refuelWhileParkedBetweenTripsAttachesToTheNextTrip() {
        // After a restart: the last stored trip ended with 20% in the tank. The
        // snapshot replay delivers the refilled gauge before READY=0, so the stop
        // is noticed with no trip open and must be held for the next one.
        engine.primeParked(20, Double.NaN, -22.95, -43.2, T0 - 3_600_000L);
        signal(TripEngine.KEY_FUEL_PCT, "85", T0 - 30_000);
        signal(TripEngine.KEY_READY, "0", T0 - 10_000);
        assertTrue("no trip is open yet", stops.isEmpty());
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "30", T0);
        for (long t = T0; t <= T0 + TripEngine.LEVEL_SETTLE_MS + 5000; t += 1000) engine.tick(t);

        assertEquals(1, stops.size());
        assertEquals(T0, (long) stopTrips.get(0));
        assertEquals(65 * 55 / 100.0, stops.get(0).amount, 1e-9);
        assertEquals(-22.95, stops.get(0).lat, 1e-9);
    }

    @Test
    public void smallGaugeChangesAndDrivingChangesAreNotStops() {
        engine.primeParked(50, Double.NaN, -22.9, -43.1, T0 - 60_000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_FUEL_PCT, "52", T0 + 5000);     // gauge noise
        for (long t = T0; t <= T0 + TripEngine.LEVEL_SETTLE_MS + 5000; t += 1000) engine.tick(t);
        signal(TripEngine.KEY_FUEL_PCT, "70", T0 + TripEngine.LEVEL_SETTLE_MS + 10_000);  // long after setting off
        assertTrue(stops.isEmpty());
    }

    @Test
    public void batteryRiseAcrossAStopIsAChargeInPercent() {
        signal(TripEngine.KEY_SOC, "30", T0 - 1000);
        signal(TripEngine.KEY_READY, "1", T0);
        signal(TripEngine.KEY_SPEED, "60", T0);
        for (long t = T0; t <= T0 + 60_000; t += 1000) engine.tick(t);
        signal(TripEngine.KEY_READY, "0", T0 + 62_000);
        signal(TripEngine.KEY_SOC, "75", T0 + 100_000);
        long resume = T0 + 120_000;
        signal(TripEngine.KEY_READY, "1", resume);
        for (long t = resume; t <= resume + TripEngine.LEVEL_SETTLE_MS + 2000; t += 1000) engine.tick(t);

        assertEquals(1, stops.size());
        assertEquals(TripStop.CHARGE, stops.get(0).kind);
        assertEquals(45, stops.get(0).amount, 1e-9);
        TripSummary live = engine.live(resume + TripEngine.LEVEL_SETTLE_MS + 2000);
        assertEquals(30, live.socStart, 1e-9);
        assertEquals(75, live.socEnd, 1e-9);
    }

    @Test
    public void noTripWithoutReady() {
        signal(TripEngine.KEY_SPEED, "60", T0);
        engine.tick(T0 + 1000);
        assertNull(engine.live(T0 + 1000));
        assertEquals(0, opened);
    }
}
