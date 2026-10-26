package com.havalh6.viewer;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * QR CODE: a phone on the same Wi-Fi as the head unit (its hotspot, or the
 * network it joined) downloads an export by scanning a code, iPhone included,
 * which Bluetooth file transfer cannot reach.
 *
 * <p>A one-export HTTP server, the smallest that does the job: GET only, the
 * three files of one export behind a random token (TripExport.linkRoute), on
 * the Wi-Fi interfaces only, gone after {@link #TTL_MS} or when the page closes
 * the code. One connection at a time on its own thread; nothing else is served.
 */
final class TripExportLink {
    private static final String TAG = "H6Trip";
    static final long TTL_MS = 5 * 60_000L;
    private static final int READ_TIMEOUT_MS = 10_000;

    private static ServerSocket server;
    private static Thread thread;

    private TripExportLink() {}

    /** Where a phone reaches the server: a label for the driver and the URL in the code. */
    static final class Link {
        final String label;
        final String url;

        Link(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    /**
     * Serves the export {@code stem} (its .zip, .csv and .json in {@code dir})
     * and returns one link per Wi-Fi address; empty when the head unit is on no
     * Wi-Fi. A running server is replaced.
     */
    static synchronized List<Link> start(Context c, File dir, String stem) throws IOException {
        stop();
        List<Link> links = new ArrayList<>();
        List<InetAddress> addrs = wifiAddresses();
        if (addrs.isEmpty()) return links;
        String token = TripExport.newLinkToken(new SecureRandom());
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(0));
        s.setSoTimeout(1_000);
        int clientIp = clientIp(c);
        for (InetAddress a : addrs) {
            String host = a.getHostAddress();
            boolean joined = clientIp != 0 && ipv4(a) == clientIp;
            links.add(new Link(joined ? "Outra rede Wi-Fi" : "Wi-Fi do carro",
                    "http://" + host + ":" + s.getLocalPort() + "/" + token + "/"));
        }
        // The car's own hotspot first: a phone in the car is most likely on it.
        Collections.sort(links, (x, y) -> Boolean.compare(!x.label.startsWith("Wi-Fi"), !y.label.startsWith("Wi-Fi")));
        long until = System.currentTimeMillis() + TTL_MS;
        server = s;
        thread = new Thread(() -> serve(s, dir, stem, token, until), "h6-trip-link");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        Log.w(TAG, "export link on port " + s.getLocalPort() + " for " + stem);
        return links;
    }

    static synchronized void stop() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        server = null;
        thread = null;
    }

    private static void serve(ServerSocket s, File dir, String stem, String token, long until) {
        try {
            while (!s.isClosed() && System.currentTimeMillis() < until) {
                Socket client;
                try {
                    client = s.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    // stop() closed the socket.
                    break;
                }
                try (Socket conn = client) {
                    // Only a peer on a Wi-Fi network this unit is on; never the car's internal VLANs.
                    if (!onWifi(conn.getLocalAddress())) continue;
                    conn.setSoTimeout(READ_TIMEOUT_MS);
                    answer(conn, dir, stem, token);
                } catch (IOException e) {
                    Log.w(TAG, "export link request failed: " + e.getMessage());
                }
            }
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            synchronized (TripExportLink.class) {
                if (server == s) {
                    server = null;
                    thread = null;
                }
            }
            Log.w(TAG, "export link closed");
        }
    }

    private static void answer(Socket conn, File dir, String stem, String token) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.US_ASCII));
        String line = in.readLine();
        // Drain the headers; nothing in them changes the answer.
        String h;
        do {
            h = in.readLine();
        } while (h != null && !h.isEmpty());
        OutputStream out = conn.getOutputStream();
        String[] parts = line == null ? new String[0] : line.split(" ");
        String name = parts.length >= 2 && "GET".equals(parts[0]) ? TripExport.linkRoute(parts[1], token, stem) : null;
        if (name == null) {
            head(out, "404 Not Found", "text/plain; charset=utf-8", 9, null);
            out.write("Not found".getBytes(StandardCharsets.US_ASCII));
        } else if (name.isEmpty()) {
            byte[] page = TripExport.linkPage(stem).getBytes(StandardCharsets.UTF_8);
            head(out, "200 OK", "text/html; charset=utf-8", page.length, null);
            out.write(page);
        } else {
            File f = new File(dir, name);
            if (!f.isFile()) {
                head(out, "404 Not Found", "text/plain; charset=utf-8", 9, null);
                out.write("Not found".getBytes(StandardCharsets.US_ASCII));
            } else {
                head(out, "200 OK", TripExport.linkType(name), f.length(), name);
                byte[] buf = new byte[64 * 1024];
                try (InputStream fin = new FileInputStream(f)) {
                    for (int n; (n = fin.read(buf)) > 0; ) out.write(buf, 0, n);
                }
            }
        }
        out.flush();
    }

    private static void head(OutputStream out, String status, String type, long length, String attachment) throws IOException {
        StringBuilder b = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("Cache-Control: no-store\r\nConnection: close\r\n");
        if (attachment != null) b.append("Content-Disposition: attachment; filename=\"").append(attachment).append("\"\r\n");
        out.write(b.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
    }

    /** IPv4 addresses of the Wi-Fi interfaces that are up: the unit's hotspot and the network it joined. */
    private static List<InetAddress> wifiAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !TripExport.isWifiInterface(ni.getName())) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a);
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "export link: no interfaces", e);
        }
        return out;
    }

    private static boolean onWifi(InetAddress local) {
        for (InetAddress a : wifiAddresses()) {
            if (a.equals(local)) return true;
        }
        return false;
    }

    /** The address on the network the unit joined as a client (0 when none), to tell it from the hotspot. */
    @SuppressWarnings("deprecation")
    private static int clientIp(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            return info == null ? 0 : info.getIpAddress();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** WifiInfo's little-endian int for an IPv4 address. */
    private static int ipv4(InetAddress a) {
        byte[] b = a.getAddress();
        return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
    }

}
