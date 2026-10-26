package com.havalh6.viewer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * EXPORTAR VIAGENS: the file layout of a trip export, kept pure so the JVM
 * tests can pin it. {@link TripStore#exportTrips} fills it from the database.
 *
 * <p>Three files per export, written side by side under
 * {@code /sdcard/HavalH6Viewer/exports} (docs/features/energy-workspace.md):
 * {@code <stem>.json} holds everything recorded (every stored trip with its raw
 * trip-computer values, kept route, all its samples while they are kept, and its
 * refuel / charge stops, plus the daily totals), and {@code <stem>.csv} one row
 * per trip for a spreadsheet. Numbers keep the dot decimal; the CSV is RFC 4180
 * (comma, CRLF, quoted when needed). {@code <stem>.zip} carries both, for
 * ENVIAR POR BLUETOOTH (TripExportProvider): a phone takes a zip where it may
 * refuse a .json, and the JSON deflates to a fraction of its size over a slow link.
 */
final class TripExport {
    /** Bumped when a field changes meaning; adding a field does not bump it. */
    static final int FORMAT_VERSION = 1;
    static final String FORMAT = "impulse-trips";
    static final String DIR = "exports";

    /** CSV columns: the local start and end, then the trip's keys as the page reads them (TripStore.tripRow). */
    static final String[] CSV_COLUMNS = {
            "start", "end", "startMs", "endMs", "day", "startPlace", "endPlace",
            "km", "kmIntegrated", "odoStart", "odoEnd", "driveMs", "idleMs", "maxKmh",
            "evKm", "evMs", "evShare", "kwhOut", "kwhIn", "kwhPer100Km", "fuelL", "kmPerL",
            "fuelPctStart", "fuelPctEnd", "socStart", "socEnd",
            "stops", "harshAccel", "harshBrake", "climbM",
            "startLat", "startLon", "endLat", "endLon",
            "guided", "arrived", "plannedKm", "plannedS", "guidedKm", "guidedS", "detailKept",
            "settingsStart", "settingsMain", "settingChanges",
    };

    private static final Pattern ZIP_NAME = Pattern.compile("viagens-\\d{8}-\\d{6}\\.zip");

    private TripExport() {}

    /** "1726221600000,1726308000000" from the page: the set of trips to export; unreadable entries are dropped. */
    static java.util.Set<Long> parseStarts(String list) {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        if (list == null) return out;
        for (String part : list.split(",")) {
            try {
                long v = Long.parseLong(part.trim());
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    // ── QR CODE (TripExportLink) ───────────────────────────────────────────

    private static final Pattern STEM = Pattern.compile("viagens-\\d{8}-\\d{6}");
    private static final Pattern WIFI_IFACE = Pattern.compile("(wlan|swlan|softap|ap)\\d*");
    private static final char[] TOKEN_CHARS = "abcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    /** 26 characters from a 32-letter alphabet: 130 bits, and nothing a camera misreads (no 0/o, 1/l). */
    static String newLinkToken(java.util.Random random) {
        char[] out = new char[26];
        for (int i = 0; i < out.length; i++) out[i] = TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)];
        return new String(out);
    }

    /**
     * What a request target asks for: "" for the page that lists the export,
     * one of its three files by name, or null for anything else (404). The
     * token is the first path segment; nothing outside the export is reachable.
     */
    static String linkRoute(String target, String token, String stem) {
        if (target == null || token == null || token.isEmpty() || !STEM.matcher(stem).matches()) return null;
        int q = target.indexOf('?');
        String path = q >= 0 ? target.substring(0, q) : target;
        String prefix = "/" + token + "/";
        if (!path.startsWith(prefix)) return null;
        String rest = path.substring(prefix.length());
        if (rest.isEmpty()) return "";
        for (String ext : new String[]{".zip", ".csv", ".json"}) {
            if (rest.equals(stem + ext)) return rest;
        }
        return null;
    }

    static String linkType(String name) {
        if (name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".csv")) return "text/csv; charset=utf-8";
        return "application/json; charset=utf-8";
    }

    /** The page the code opens: the three files. Relative links, so any address of the unit works. */
    static String linkPage(String stem) {
        return "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Viagens</title><style>body{font:17px -apple-system,system-ui,sans-serif;margin:24px;"
                + "color:#111}a{display:block;margin:12px 0;padding:16px;border-radius:12px;background:#0b6e79;"
                + "color:#fff;text-decoration:none;font-weight:600}small{display:block;font-weight:400;opacity:.85}"
                + "</style></head><body><h1>Viagens exportadas</h1>"
                + "<a href=\"" + stem + ".zip\" download>Baixar tudo (.zip)<small>JSON completo e planilha</small></a>"
                + "<a href=\"" + stem + ".csv\" download>Planilha (.csv)<small>Uma linha por viagem</small></a>"
                + "<a href=\"" + stem + ".json\" download>Dados completos (.json)<small>Rota, amostras, abastecimentos</small></a>"
                + "<p>O link funciona por 5 minutos e só na rede Wi-Fi da central.</p></body></html>";
    }

    /** Interfaces a phone can share with the unit: its hotspot and the network it joined. Never eth/vlan/tun. */
    static boolean isWifiInterface(String name) {
        return name != null && WIFI_IFACE.matcher(name).matches();
    }

    /** The code's dark modules as one SVG path in module units, with a 4-module quiet zone ({@code size + 8} square). */
    static String qrSvgPath(io.nayuki.qrcodegen.QrCode qr) {
        StringBuilder b = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) b.append('M').append(x + 4).append(',').append(y + 4).append("h1v1h-1z");
            }
        }
        return b.toString();
    }

    /** An export zip's bare name, as fileStem makes it: what TripExportProvider may serve. */
    static boolean isZipName(String name) {
        return name != null && ZIP_NAME.matcher(name).matches();
    }

    /** {@code files} into {@code out}, each under its own name. */
    static void zip(File out, File... files) throws IOException {
        byte[] buf = new byte[64 * 1024];
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            for (File f : files) {
                z.putNextEntry(new ZipEntry(f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    for (int n; (n = in.read(buf)) > 0; ) z.write(buf, 0, n);
                }
                z.closeEntry();
            }
        }
    }

    /** {@code viagens-20261025-143005}: sorts by time, and two exports a second apart never collide. */
    static String fileStem(long nowMs) {
        return "viagens-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(nowMs));
    }

    /** Wall-clock time for the CSV's start / end columns, in the head unit's zone. */
    static String localTime(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    static String csvHeader() {
        return csvLine(CSV_COLUMNS);
    }

    /** One CSV record, CRLF-terminated. A null is an empty field. */
    static String csvLine(Object[] values) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) b.append(',');
            b.append(csvField(values[i]));
        }
        return b.append("\r\n").toString();
    }

    static String csvField(Object v) {
        if (v == null) return "";
        String s;
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "";
            // Whole values without ".0", others plain (never 1.0E-4): what a spreadsheet reads as a number.
            s = d == Math.rint(d) && Math.abs(d) < 1e15
                    ? Long.toString((long) d)
                    : new java.math.BigDecimal(d).round(new java.math.MathContext(10)).stripTrailingZeros().toPlainString();
        } else {
            s = String.valueOf(v);
        }
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        return quote ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
