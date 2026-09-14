package com.havalh6.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TripMapMathTest {
    @Test
    public void projectionMatchesTheSlippyMapConvention() {
        assertEquals(128.0, TripMapMath.worldX(0, 0), 1e-9);
        assertEquals(256.0, TripMapMath.worldX(180, 0), 1e-9);
        assertEquals(0.0, TripMapMath.worldX(-180, 0), 1e-9);
        assertEquals(128.0, TripMapMath.worldY(0, 0), 1e-9);
        assertEquals(0.0, TripMapMath.worldY(TripMapMath.MAX_LAT, 0), 1e-3);
        // Zoom doubles the world.
        assertEquals(2 * TripMapMath.worldX(-43.1, 11), TripMapMath.worldX(-43.1, 12), 1e-6);
        // North is up: a more southerly point has a larger y.
        assertTrue(TripMapMath.worldY(-23.0, 10) > TripMapMath.worldY(-22.9, 10));
    }

    @Test
    public void aOneKilometreTripIsFramedAtStreetZoom() {
        // ~1 km north-south at this latitude.
        int z = TripMapMath.fitZoom(-22.909, -22.900, -43.101, -43.100, 640, 400, 36, 3, 17);
        assertTrue("got z" + z, z >= 14 && z <= 16);
    }

    @Test
    public void aLongTripZoomsOutUntilItFits() {
        // Rio de Janeiro to São Paulo.
        int z = TripMapMath.fitZoom(-23.55, -22.90, -46.63, -43.20, 640, 400, 36, 3, 17);
        assertTrue("got z" + z, z >= 6 && z <= 8);
        double dx = TripMapMath.worldX(-43.20, z) - TripMapMath.worldX(-46.63, z);
        assertTrue(dx <= 640 - 72);
        double wider = TripMapMath.worldX(-43.20, z + 1) - TripMapMath.worldX(-46.63, z + 1);
        assertTrue("one zoom closer would not fit", wider > 640 - 72);
    }

    @Test
    public void aSinglePointUsesTheClosestZoom() {
        assertEquals(17, TripMapMath.fitZoom(-22.9, -22.9, -43.1, -43.1, 640, 400, 36, 3, 17));
    }
}
