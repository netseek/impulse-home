package com.havalh6.viewer;

/**
 * Checks the battery range forecast against what the car actually does.
 *
 * <p>Nothing kept the forecast before: the page works it out again on every
 * render and throws it away, so "was it right?" had no answer. This keeps one
 * <b>cycle</b> per charge: the forecast when it started (the car's own EV range
 * and the page's history estimate), then a sample every time SOC moves a point
 * or EV km grows by {@link #SAMPLE_EV_KM}. If the forecast was right,
 * EV km driven + EV range left stays flat at the starting forecast all the way down.
 *
 * <p><b>Every cycle starts at a charge</b> (owner, 2026-09-22), so each one
 * checks a forecast from a freshly charged battery all the way down. Nothing is
 * recorded until the first charge after install. A cycle with driving missing
 * from it is not a fair test either: an odometer that moved while nothing was
 * recording ends the cycle ({@code gap}) rather than letting it read as a bad
 * forecast, and like running empty, the next cycle then waits for a charge.
 *
 * <p>Pure Java, run on the recorder's worker thread; persistence goes through
 * {@link Store} so the logic has JVM tests.
 */
final class RangeLedger {
    static final String KEY_EV_RANGE = "car.ev_info.electric_mode_remain_odometer";

    static final String START_CHARGE = "charge";
    static final String END_CHARGE = "charge";
    static final String END_DEPLETED = "depleted";
    static final String END_GAP = "gap";

    /** A sample when EV km has grown this much, even if SOC has not moved a point. */
    static final double SAMPLE_EV_KM = 2.0;
    /** Car EV range at or under this is "empty". */
    static final double EMPTY_KM = 0.5;
    /** Driven this far with the car's EV range at zero: the car is in hybrid for good and the cycle is over. */
    static final double DEPLETED_AFTER_KM = 5.0;
    /** Odometer movement not accounted for by recorded driving, beyond this: driving was missed. */
    static final double GAP_KM = 3.0;
    /** The page's estimate is only used this long after it was reported. */
    static final long HIST_FRESH_MS = 120_000L;
    /** Running totals reach disk at least this often while driving; samples go at once. */
    static final long SAVE_MS = 15_000L;
    /** Cycles kept, with their samples. */
    static final int KEEP_CYCLES = 40;

    interface Store {
        /** The newest cycle, open or not, or null. */
        Cycle loadLatestCycle();
        void saveCycle(Cycle c);
        void addSample(long cycleId, Sample s);
        void pruneCycles(int keep);
    }

    static final class Cycle {
        long id;
        String startKind;
        double soc0 = Double.NaN;
        double oem0 = Double.NaN;
        double hist0 = Double.NaN;
        double odo0 = Double.NaN;
        /** EV and total km driven since the cycle started. */
        double evKm;
        double km;
        long endMs;
        String endKind;
        double lastSoc = Double.NaN;
        double lastOem = Double.NaN;
        double lastHist = Double.NaN;
        int samples;
        // Running state, persisted so a restart carries on where it left off.
        double lastOdo = Double.NaN;
        double kmAtOdo;
        long tripStart;
        double tripEv;
        double tripKm;
        double zeroFromKm = Double.NaN;
        double sampleSoc = Double.NaN;
        double sampleEv;

        boolean open() {
            return endKind == null;
        }
    }

    static final class Sample {
        final long t;
        final double soc;
        final double evKm;
        final double km;
        final double oem;
        final double hist;

        Sample(long t, double soc, double evKm, double km, double oem, double hist) {
            this.t = t;
            this.soc = soc;
            this.evKm = evKm;
            this.km = km;
            this.oem = oem;
            this.hist = hist;
        }
    }

    private final Store store;
    private Cycle cycle;
    /** A charge was seen and no cycle has taken it yet: the next drive opens one. */
    private boolean armed;
    /**
     * SOC at the last tick of driving, or of the newest cycle after a restart. A
     * battery this much higher when driving resumes was plugged in.
     */
    private double driveSoc = Double.NaN;
    private double soc = Double.NaN;
    private double oem = Double.NaN;
    private double odo = Double.NaN;
    private double hist = Double.NaN;
    private double histSoc = Double.NaN;
    private long histAt;
    private long savedAt;

    RangeLedger(Store store) {
        this.store = store;
        Cycle last = store.loadLatestCycle();
        if (last != null) {
            if (last.open()) cycle = last;
            driveSoc = last.lastSoc;
        }
    }

    Cycle current() {
        return cycle;
    }

    void onSignal(String key, String raw) {
        double v = TripSignals.parseNumber(raw);
        switch (key) {
            case TripEngine.KEY_SOC:
                if (TripSignals.inRange(v, 0, 100)) soc = v;
                break;
            case KEY_EV_RANGE:
                if (TripSignals.inRange(v, 0, 1000)) oem = v;
                break;
            case TripEngine.KEY_ODOMETER:
                if (TripSignals.inRange(v, 0, 2_000_000)) odo = v;
                break;
            default:
                break;
        }
    }

    /**
     * The page's history-based EV range, in km, and the SOC it was worked out
     * for; NaN when it has none to give. Only used while that SOC is still the
     * car's: right after a charge the page is still quoting the old level.
     */
    void onHistForecast(double km, double atSoc, long t) {
        hist = km >= 0 && !Double.isInfinite(km) ? km : Double.NaN;
        histSoc = atSoc;
        histAt = t;
    }

    /**
     * TripEngine's CHARGE stop. Only the fallback: the ledger sees a charge itself
     * when driving resumes, but not with no SOC to compare against (the first
     * charge after install, when TripEngine primes its level from the last trip).
     * An open cycle has already been ended by then, so it is left alone.
     */
    void onCharge() {
        if (cycle == null) armed = true;
    }

    /**
     * About once a second. {@code tripStart} is 0 when no trip is open;
     * {@code tripEvKm} / {@code tripKm} are that trip's totals so far.
     */
    void onTick(long t, long tripStart, double tripEvKm, double tripKm) {
        if (tripStart == 0) {
            if (cycle != null && t - savedAt >= SAVE_MS) save(t);
            return;
        }
        boolean charged = charged();
        if (!Double.isNaN(soc)) driveSoc = soc;
        if (charged) {
            // Before counting: the driving since a charge belongs to the next cycle.
            if (cycle != null) end(END_CHARGE, t);
            armed = true;
        }
        if (cycle == null) {
            if (armed) openCycle(t, tripStart, tripEvKm, tripKm);
            return;
        }
        accumulate(tripStart, tripEvKm, tripKm);
        if (missedDriving()) {
            end(END_GAP, t);
            return;
        }
        double h = histNow(t);
        if (Double.isNaN(cycle.hist0) && !Double.isNaN(h) && cycle.km < 1.0) cycle.hist0 = h;
        if (!Double.isNaN(oem)) {
            if (oem <= EMPTY_KM) {
                if (Double.isNaN(cycle.zeroFromKm)) cycle.zeroFromKm = cycle.km;
            } else {
                cycle.zeroFromKm = Double.NaN;
            }
        }
        boolean socMoved = !Double.isNaN(soc) && (Double.isNaN(cycle.sampleSoc) || Math.abs(soc - cycle.sampleSoc) >= 1.0);
        if (socMoved || cycle.evKm - cycle.sampleEv >= SAMPLE_EV_KM) sample(t, h);
        if (!Double.isNaN(cycle.zeroFromKm) && cycle.km - cycle.zeroFromKm >= DEPLETED_AFTER_KM) {
            end(END_DEPLETED, t);
            return;
        }
        if (t - savedAt >= SAVE_MS) save(t);
    }

    /** Before the process may go: put the running totals on disk. */
    void flush(long t) {
        if (cycle != null) save(t);
    }

    private void openCycle(long t, long tripStart, double tripEvKm, double tripKm) {
        double h = histNow(t);
        if (Double.isNaN(soc) || Double.isNaN(oem)) return;
        // A car with nothing in the battery (or an HEV, which has no EV range) has nothing to forecast.
        if (oem <= EMPTY_KM && !(h > EMPTY_KM)) return;
        Cycle c = new Cycle();
        c.id = t;
        c.startKind = START_CHARGE;
        c.soc0 = soc;
        c.oem0 = oem;
        c.hist0 = h;
        c.odo0 = odo;
        c.lastOdo = odo;
        c.kmAtOdo = 0;
        // Counted from here: whatever this trip drove before the cycle belongs to the one before.
        c.tripStart = tripStart;
        c.tripEv = tripEvKm;
        c.tripKm = tripKm;
        cycle = c;
        armed = false;
        sample(t, h);
        store.pruneCycles(KEEP_CYCLES);
    }

    private void accumulate(long tripStart, double tripEvKm, double tripKm) {
        Cycle c = cycle;
        if (tripStart != c.tripStart) {
            c.tripStart = tripStart;
            c.tripEv = 0;
            c.tripKm = 0;
        }
        double dEv = tripEvKm - c.tripEv;
        double dKm = tripKm - c.tripKm;
        // The engine's totals only grow; a drop is a trip we did not see open. Rebase, count nothing.
        if (dEv > 0) c.evKm += dEv;
        if (dKm > 0) c.km += dKm;
        c.tripEv = tripEvKm;
        c.tripKm = tripKm;
    }

    /**
     * The battery is well above where driving left it. {@link #driveSoc} follows
     * SOC every tick while driving, engine charging included, so a jump this size
     * only happens across a stop: a plug charge. Detected here rather than taken
     * from TripEngine's CHARGE stop, which is only final minutes into the next
     * drive -- by then those minutes would have been credited to the old cycle.
     */
    private boolean charged() {
        return !Double.isNaN(soc) && !Double.isNaN(driveSoc) && soc - driveSoc >= TripEngine.CHARGE_MIN_PCT;
    }

    /** The odometer moved further than the recorded driving explains: a drive happened while nothing was recording. */
    private boolean missedDriving() {
        Cycle c = cycle;
        if (Double.isNaN(odo)) return false;
        if (Double.isNaN(c.lastOdo)) {
            c.lastOdo = odo;
            c.kmAtOdo = c.km;
            return false;
        }
        double unexplained = (odo - c.lastOdo) - (c.km - c.kmAtOdo);
        if (unexplained > GAP_KM) return true;
        if (odo != c.lastOdo) {
            c.lastOdo = odo;
            c.kmAtOdo = c.km;
        }
        return false;
    }

    private void sample(long t, double h) {
        Cycle c = cycle;
        c.sampleSoc = soc;
        c.sampleEv = c.evKm;
        c.lastSoc = soc;
        c.lastOem = oem;
        c.lastHist = h;
        c.samples++;
        store.addSample(c.id, new Sample(t, soc, c.evKm, c.km, oem, h));
        save(t);
    }

    private void end(String kind, long t) {
        Cycle c = cycle;
        c.endMs = t;
        c.endKind = kind;
        store.saveCycle(c);
        savedAt = t;
        cycle = null;
    }

    private void save(long t) {
        store.saveCycle(cycle);
        savedAt = t;
    }

    private double histNow(long t) {
        if (histAt == 0 || t - histAt > HIST_FRESH_MS) return Double.NaN;
        if (Double.isNaN(soc) || !(Math.abs(histSoc - soc) <= 1.0)) return Double.NaN;
        return hist;
    }
}
