package com.havalh6.viewer;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class TelemetryBatchTest {
    @Test
    public void onlyFirstArrivalPostsUntilTheBatchDrains() {
        TelemetryBatch batch = new TelemetryBatch();
        assertNull(batch.drain());
        assertTrue(batch.add("door", "open"));
        assertFalse(batch.add("door", "closed"));
        assertFalse(batch.add("speed", "10"));
        assertNotNull(batch.drain());
        assertTrue(batch.add("door", "open"));
        assertFalse(batch.add("speed", "20"));
        assertNotNull(batch.drain());
        assertNull(batch.drain());
        assertNull(batch.drain());
        assertTrue(batch.add("speed", "30"));
    }

    @Test
    public void placeJsonSharesEscapingAndOrderWithPendingSignals() {
        TelemetryBatch batch = new TelemetryBatch();
        assertTrue(batch.add("speed", "10"));
        assertFalse(batch.add("place", "{\"name\":\"D'Avila\\road\"}"));
        assertEquals("if(window.onCarDataUpdate){window.onCarDataUpdate('speed','10');"
                + "window.onCarDataUpdate('place','{\\\"name\\\":\\\"D\\'Avila\\\\road\\\"}');}",
                batch.drain());
    }

    // Source contracts cover Android wiring while keeping these tests free of Android stubs.
    @Test
    public void placeCallbackUsesTheReceiverBatchWithNegativeControls() throws Exception {
        String source = mainActivitySource();
        assertPlaceBatchWiring(source);
        assertThrows(AssertionError.class, () -> assertPlaceBatchWiring(source.replace(
                "telemetryBatch.add(PlaceGlance.KEY, json)", "false")));
        int start = source.indexOf("        placeGlance = new PlaceGlance(");
        String prefix = source.substring(0, start);
        String callbackAndRest = source.substring(start);
        assertThrows(AssertionError.class, () -> assertPlaceBatchWiring(prefix
                + callbackAndRest.replace("mainHandler.post(telemetryFlush);", "")));
    }

    @Test
    public void destroyCancelsAndResetsBeforeDestroyingWebViewWithNegativeControls() throws Exception {
        String source = mainActivitySource();
        assertDestroyCleanup(source);
        assertThrows(AssertionError.class, () -> assertDestroyCleanup(source.replace(
                "mainHandler.removeCallbacks(telemetryFlush);", "")));
        int start = source.indexOf("    protected void onDestroy() {");
        String prefix = source.substring(0, start);
        String destroy = source.substring(start);
        assertThrows(AssertionError.class, () -> assertDestroyCleanup(prefix
                + destroy.replace("telemetryBatch.drain();", "")));
        assertThrows(AssertionError.class, () -> assertDestroyCleanup(prefix
                + destroy.replace("mainHandler.removeCallbacks(telemetryFlush);", "")
                .replace("webView.destroy();", "webView.destroy();\n"
                        + "        mainHandler.removeCallbacks(telemetryFlush);")));
    }

    private static String mainActivitySource() throws Exception {
        Path path = Paths.get("app/src/main/java/com/havalh6/viewer/MainActivity.java");
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/havalh6/viewer/MainActivity.java");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void assertPlaceBatchWiring(String source) {
        int start = source.indexOf("        placeGlance = new PlaceGlance(");
        assertTrue(start >= 0);
        String callback = source.substring(start, source.indexOf("        placeGlance.start();", start));
        assertTrue(callback.contains("telemetryCache.put(PlaceGlance.KEY, json);"));
        assertTrue(callback.contains("if (webView == null) return;"));
        assertTrue(callback.matches("(?s).*if \\(telemetryBatch.add\\(PlaceGlance.KEY, json\\)\\) \\{\\s*"
                + "mainHandler.post\\(telemetryFlush\\);\\s*\\}.*"));
        assertFalse(callback.contains("evaluateJavascript"));
        assertTrue(source.contains("if (telemetryBatch.add(key, value)) {\n"
                + "                    mainHandler.post(telemetryFlush);"));
    }

    private static void assertDestroyCleanup(String source) {
        String destroy = source.substring(source.indexOf("    protected void onDestroy() {"));
        int cancel = destroy.indexOf("mainHandler.removeCallbacks(telemetryFlush);");
        int reset = destroy.indexOf("telemetryBatch.drain();");
        int destroyView = destroy.indexOf("webView.destroy();");
        assertTrue("Cancel before destroying the WebView", cancel >= 0 && cancel < destroyView);
        assertTrue("Reset the canceled batch before destroying the WebView",
                reset > cancel && reset < destroyView);
    }

    @Test
    public void arrivalsKeepTheirOrderAcrossKeysAndRepeatedValues() {
        TelemetryBatch batch = new TelemetryBatch();
        batch.add("door", "open");
        batch.add("speed", "10");
        batch.add("door", "closed");
        batch.add("door", "closed");
        assertEquals("if(window.onCarDataUpdate){"
                + "window.onCarDataUpdate('door','open');"
                + "window.onCarDataUpdate('speed','10');"
                + "window.onCarDataUpdate('door','closed');"
                + "window.onCarDataUpdate('door','closed');}", batch.drain());
    }

    @Test
    public void drainEmptiesTheBatchAndAllowsAnotherBatch() {
        TelemetryBatch batch = new TelemetryBatch();
        assertNull(batch.drain());
        batch.add("a", "1");
        assertNotNull(batch.drain());
        assertNull(batch.drain());
        batch.add("b", "2");
        assertEquals("if(window.onCarDataUpdate){window.onCarDataUpdate('b','2');}",
                batch.drain());
        assertNull(batch.drain());
    }

    @Test
    public void callsAreGuardedWhenThePageHasNoTelemetryHandler() {
        TelemetryBatch batch = new TelemetryBatch();
        batch.add("key", "value");
        assertEquals("if(window.onCarDataUpdate){window.onCarDataUpdate('key','value');}",
                batch.drain());
    }

    @Test
    public void bothArgumentsEscapeQuotesBackslashesAndLineTerminators() {
        TelemetryBatch batch = new TelemetryBatch();
        String tricky = "'\"\\\n\r\u2028\u2029\u0001";
        batch.add(tricky, tricky);
        String literal = "'\\'\\\"\\\\\\n\\r\\u2028\\u2029\\u0001'";
        assertEquals("if(window.onCarDataUpdate){window.onCarDataUpdate("
                + literal + "," + literal + ");}", batch.drain());
    }

    @Test
    public void trickyValueHasTheExactLiteralNeededToRecoverItsText() {
        TelemetryBatch batch = new TelemetryBatch();
        // Literal escape-looking text must survive alongside actual separators.
        batch.add("text", "C:\\new\\'quoted\"\nnext\r\u2028\u2029\tend");
        assertEquals("if(window.onCarDataUpdate){window.onCarDataUpdate('text',"
                + "'C:\\\\new\\\\\\'quoted\\\"\\nnext\\r\\u2028\\u2029\\u0009end');}",
                batch.drain());
    }
}
