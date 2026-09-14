package com.havalh6.viewer;

/**
 * Web Mercator ("slippy map") pixel math for the trip map snapshot. Pure Java so
 * the projection and the zoom choice are unit-tested off the car.
 */
final class TripMapMath {
    static final int TILE = 256;
    /** Web Mercator's latitude limit: beyond this the projection runs to infinity. */
    static final double MAX_LAT = 85.05112878;

    private TripMapMath() {}

    /** World pixel x of a longitude at zoom {@code z} (0 at -180°, TILE·2^z at +180°). */
    static double worldX(double lon, int z) {
        return (lon + 180.0) / 360.0 * TILE * (1L << z);
    }

    /** World pixel y of a latitude at zoom {@code z} (0 at the north edge). */
    static double worldY(double lat, int z) {
        double r = Math.toRadians(Math.max(-MAX_LAT, Math.min(MAX_LAT, lat)));
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * TILE * (1L << z);
    }

    /**
     * The largest zoom in [minZ, maxZ] at which the bounding box fits a
     * {@code width} x {@code height} image with {@code pad} pixels on every side.
     */
    static int fitZoom(double minLat, double maxLat, double minLon, double maxLon,
                       int width, int height, int pad, int minZ, int maxZ) {
        for (int z = maxZ; z > minZ; z--) {
            double dx = worldX(maxLon, z) - worldX(minLon, z);
            double dy = worldY(minLat, z) - worldY(maxLat, z);
            if (dx <= width - 2 * pad && dy <= height - 2 * pad) return z;
        }
        return minZ;
    }
}
