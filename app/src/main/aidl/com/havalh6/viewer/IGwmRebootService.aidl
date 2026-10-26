package com.havalh6.viewer;

interface IGwmRebootService {
    // Transaction reserved by Shizuku so it can stop the user service.
    void destroy() = 16777114;
    int reboot() = 1;
    // ENVIAR POR BLUETOOTH where the share activity is not exported (TripExportProvider).
    int shareFile(String component, String path) = 2;
}
