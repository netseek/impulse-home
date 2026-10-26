package com.havalh6.viewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The driving settings in force from {@link #t} until the next row of the same
 * trip: drive mode, energy mode, the HEV reserve sub-mode and its SOC target
 * (the slider). Each row also holds the trip's running totals at that moment,
 * so the stretch until the next row has its own distance, energy and fuel:
 * that is what lets a result be put down to a setting rather than to the trip.
 *
 * <p>Keys and values are Impulse's (CarConstants, MainMenu): drive mode as the
 * DRIVING card writes it; energy mode 0 HEV / 1 EVP / 3 EV; in HEV,
 * {@code power_reserve_config} 2 is Prioritário (hold the battery at the SOC
 * target, 20-80 %) and anything else is Inteligente. -1 is "not reported yet".
 */
final class TripSetting {
    static final String KEY_DRIVE_MODE = "car.drive_setting.drive_mode";
    static final String KEY_POWER_MODE = "car.ev_setting.power_model_config";
    static final String KEY_RESERVE = "car.ev_setting.power_reserve_config";
    static final String KEY_SOC_TARGET = "car.ev_setting.charge_soc_target_config";
    static final Set<String> KEYS = new HashSet<>(Arrays.asList(KEY_DRIVE_MODE, KEY_POWER_MODE, KEY_RESERVE, KEY_SOC_TARGET));

    static final int UNKNOWN = -1;
    static final int RESERVE_PRIORITY = 2;

    final long t;
    final int driveMode;
    final int powerMode;
    final int reserve;
    final int socTarget;
    // The trip's running totals when these settings took effect.
    final double km;
    final double kwhOut;
    final double kwhIn;
    final double fuelL;
    final double evKm;
    final long driveMs;
    final long idleMs;

    TripSetting(long t, int driveMode, int powerMode, int reserve, int socTarget,
                double km, double kwhOut, double kwhIn, double fuelL, double evKm, long driveMs, long idleMs) {
        this.t = t;
        this.driveMode = driveMode;
        this.powerMode = powerMode;
        this.reserve = reserve;
        this.socTarget = socTarget;
        this.km = km;
        this.kwhOut = kwhOut;
        this.kwhIn = kwhIn;
        this.fuelL = fuelL;
        this.evKm = evKm;
        this.driveMs = driveMs;
        this.idleMs = idleMs;
    }

    /** A setting value off the bus, or {@link #UNKNOWN} when it is not a plausible one for {@code key}. */
    static int parse(String key, String raw) {
        double v = TripSignals.parseNumber(raw);
        if (Double.isNaN(v) || v != Math.rint(v)) return UNKNOWN;
        int max = KEY_SOC_TARGET.equals(key) ? 100 : 31;
        return v >= 0 && v <= max ? (int) v : UNKNOWN;
    }

    /** The combination, for "did anything change". */
    static String signature(int driveMode, int powerMode, int reserve, int socTarget) {
        return driveMode + "|" + powerMode + "|" + reserve + "|" + socTarget;
    }

    static String driveLabel(int v) {
        switch (v) {
            case 2: return "Eco";
            case 0: return "Normal";
            case 1: return "Esportivo";
            case 3: return "Neve";
            case 4: return "Areia";
            case 5: return "Lama";
            case 11: return "AWD";
            default: return v == UNKNOWN ? "—" : "Modo " + v;
        }
    }

    static String powerLabel(int v) {
        switch (v) {
            case 0: return "HEV";
            case 1: return "EVP";
            case 3: return "EV";
            default: return v == UNKNOWN ? "—" : "Energia " + v;
        }
    }

    /** The HEV sub-mode; meaningless outside HEV, so "" there. */
    static String reserveLabel(int powerMode, int reserve) {
        if (powerMode != 0 || reserve == UNKNOWN) return "";
        return reserve == RESERVE_PRIORITY ? "Prioritário" : "Inteligente";
    }

    /** "Eco · HEV Prioritário 50%": what the driver chose, as the cluster names it. */
    static String label(int driveMode, int powerMode, int reserve, int socTarget) {
        String sub = reserveLabel(powerMode, reserve);
        String power = powerLabel(powerMode) + (sub.isEmpty() ? "" : " " + sub);
        if (powerMode == 0 && reserve == RESERVE_PRIORITY && socTarget != UNKNOWN) power += " " + socTarget + "%";
        return driveLabel(driveMode) + " · " + power;
    }

    String label() {
        return label(driveMode, powerMode, reserve, socTarget);
    }

    /** One stretch of a trip under one combination of settings. */
    static final class Segment {
        final TripSetting from;
        final long endT;
        final double km;
        final double kwhOut;
        final double kwhIn;
        final double fuelL;
        final double evKm;
        final long driveMs;
        final long idleMs;

        Segment(TripSetting from, long endT, double km, double kwhOut, double kwhIn, double fuelL,
                double evKm, long driveMs, long idleMs) {
            this.from = from;
            this.endT = endT;
            this.km = km;
            this.kwhOut = kwhOut;
            this.kwhIn = kwhIn;
            this.fuelL = fuelL;
            this.evKm = evKm;
            this.driveMs = driveMs;
            this.idleMs = idleMs;
        }

        /** Net pack energy per 100 km of this stretch, NaN under 0.1 km. */
        double kwhPer100Km() {
            return km >= 0.1 ? (kwhOut - kwhIn) * 100.0 / km : Double.NaN;
        }

        /** NaN below TripSummary's minimum fuel for a ratio. */
        double kmPerL() {
            return fuelL >= TripSummary.MIN_FUEL_FOR_RATIO_L ? km / fuelL : Double.NaN;
        }
    }

    /**
     * Stretches from the rows (in time order) to the trip's end totals: each row
     * runs until the next one. Totals only grow, so a negative difference (a
     * restored checkpoint, a corrected odometer) counts as 0.
     */
    static List<Segment> segments(List<TripSetting> rows, long endT, double km, double kwhOut, double kwhIn,
                                  double fuelL, double evKm, long driveMs, long idleMs) {
        List<Segment> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            TripSetting a = rows.get(i);
            TripSetting b = i + 1 < rows.size() ? rows.get(i + 1) : null;
            out.add(new Segment(a, b == null ? endT : b.t,
                    gain(a.km, b == null ? km : b.km),
                    gain(a.kwhOut, b == null ? kwhOut : b.kwhOut),
                    gain(a.kwhIn, b == null ? kwhIn : b.kwhIn),
                    gain(a.fuelL, b == null ? fuelL : b.fuelL),
                    gain(a.evKm, b == null ? evKm : b.evKm),
                    (long) gain(a.driveMs, b == null ? driveMs : b.driveMs),
                    (long) gain(a.idleMs, b == null ? idleMs : b.idleMs)));
        }
        return out;
    }

    private static double gain(double from, double to) {
        if (Double.isNaN(from) || Double.isNaN(to)) return 0;
        return Math.max(0, to - from);
    }
}
