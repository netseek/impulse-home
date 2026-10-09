package com.havalh6.viewer;

import java.io.IOException;

/**
 * Runs inside Shizuku, as the same user that started it, so {@code reboot}
 * is allowed. Shizuku loads this class by name; it is not a manifest service.
 */
public class GwmRebootService extends IGwmRebootService.Stub {
    @Override
    public void destroy() {
        System.exit(0);
    }

    /**
     * Starts {@link GwmHub#REBOOT_COMMAND}. Returns 0 when the process is
     * still running (a reboot does not exit) and the process's status if it
     * already failed.
     */
    @Override
    public int reboot() {
        try {
            Process process = Runtime.getRuntime().exec(new String[] { GwmHub.REBOOT_COMMAND });
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                return 0;
            }
        } catch (IOException e) {
            return -1;
        }
    }
}
