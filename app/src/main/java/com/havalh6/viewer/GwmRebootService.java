package com.havalh6.viewer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * Runs inside Shizuku, as the same user that started it, so {@code reboot}
 * is allowed. Shizuku loads this class by name; it is not a manifest service.
 *
 * <p>It also opens the system's Bluetooth share for a trip export
 * ({@link #shareFile}). Some head units ship that activity unexported, so an
 * app cannot start it, while Shizuku's user (root on the MMI) can. Whatever
 * runs here runs as that user: both arguments are checked, and nothing goes
 * through a shell.
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

    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+");
    private static final Pattern EXPORT_DIR = Pattern.compile("/(storage/emulated/0|sdcard)/"
            + PersistBackup.DIR + "/" + TripExport.DIR);

    /**
     * {@code am start} of {@code component} with ACTION_SEND and the export zip at
     * {@code path} as a file:// stream. 0 when it started, 1 when am refused, -1
     * when an argument is not a share activity and an export zip.
     */
    @Override
    public int shareFile(String component, String path) {
        if (component == null || path == null || !COMPONENT.matcher(component).matches()) return -1;
        File zip = new File(path);
        String parent = zip.getParent();
        if (parent == null || !EXPORT_DIR.matcher(parent).matches()
                || !TripExport.isZipName(zip.getName()) || !zip.isFile()) return -1;
        try {
            Process process = Runtime.getRuntime().exec(new String[] {
                    "/system/bin/am", "start", "-f", "0x10000000",
                    "-a", "android.intent.action.SEND", "-t", "application/zip",
                    "-n", component, "--eu", "android.intent.extra.STREAM", "file://" + zip.getPath() });
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            try (InputStream in = process.getInputStream(); InputStream err = process.getErrorStream()) {
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                for (int n; (n = err.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            int code = process.waitFor();
            // am prints its refusals ("Error: ...", "Security exception: ...") and may still exit 0.
            String text = out.toString("UTF-8");
            return code == 0 && !text.contains("Error") && !text.contains("xception") ? 0 : 1;
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
