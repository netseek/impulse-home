package com.havalh6.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GwmHubTest {
    @Test
    public void fiveLinksKeepTheTwoRowOrder() {
        GwmHub.Action[] actions = GwmHub.actions();
        assertEquals(5, actions.length);
        assertEquals(3, countRow(actions, 0));
        assertEquals(2, countRow(actions, 1));

        assertEquals(GwmHub.HOME, actions[0].packageName);
        assertEquals("com.beantechs.applist", actions[0].packageName);
        assertEquals("Início", actions[0].label);
        assertEquals(0, actions[0].row);
        assertFalse(actions[0].reboot);

        assertEquals(GwmHub.ENERGY, actions[1].packageName);
        assertEquals("Energia", actions[1].label);
        assertFalse(actions[1].reboot);

        assertNull(actions[2].packageName);
        assertEquals("Reiniciar", actions[2].label);
        assertTrue(actions[2].reboot);
        assertEquals("/system/bin/reboot", GwmHub.REBOOT_COMMAND);

        assertEquals(GwmHub.SYSTEM, actions[3].packageName);
        assertEquals("Sistema", actions[3].label);
        assertEquals(1, actions[3].row);
        assertEquals(GwmHub.CAR, actions[4].packageName);
        assertEquals("Veículo", actions[4].label);
        assertEquals(1, actions[4].row);
    }

    @Test
    public void packagesAreTheFourLaunchTargets() {
        String[] pkgs = GwmHub.packages();
        assertEquals(4, pkgs.length);
        boolean applist = false;
        boolean launcher = false;
        for (String pkg : pkgs) {
            if ("com.beantechs.applist".equals(pkg)) applist = true;
            if ("com.beantechs.launcher".equals(pkg)) launcher = true;
        }
        assertTrue(applist);
        assertFalse(launcher);
    }

    @Test
    public void cardIsTheBottomLeftTwoByOne() {
        // Board 1200×400 at (100, 40), gap 8. Hand-checked integer cells:
        // cell 193×196, so a 2×1 on row 1 is (100, 244) 394×196.
        GwmHub.Cell card = GwmHub.cardRect(100, 40, 1300, 440, 8);
        assertEquals(100, card.left);
        assertEquals(244, card.top);
        assertEquals(394, card.width);
        assertEquals(196, card.height);
        // The top row would start on the board's top edge.
        assertTrue(card.top > 40);
    }

    private static int countRow(GwmHub.Action[] actions, int row) {
        int n = 0;
        for (GwmHub.Action action : actions) {
            if (action.row == row) n++;
        }
        return n;
    }
}
