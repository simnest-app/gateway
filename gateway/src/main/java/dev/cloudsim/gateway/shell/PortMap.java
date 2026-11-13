package dev.cloudsim.gateway.shell;

import android.os.SystemClock;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Port mapping on the home router (docs/architecture/DIRECT_PATHS.md 4), when the owner turned it on: for each call and
 * self-test, the UDP port WebRTC took on the Wi-Fi is asked of the router (PCP, NAT-PMP or UPnP IGD, through libplum),
 * and the router's public address and port go into the answer as one more candidate. The mapping is removed when the
 * connection closes. A check when it is turned on tells whether the router can do it at all; until it could, calls
 * do not wait for it.
 */
final class PortMap {
    private static final int PENDING = 1, SUCCESS = 2, FAILURE = 3;
    /** Upper bound on how long an answer waits for the router, once the check found it can map. */
    private static final long CALL_WAIT_MS = 1500;

    private static volatile boolean on;
    private static volatile boolean loaded;
    /** off, checking, mapped, unsupported or not_public: what the gateway's self-test reports. */
    private static volatile String state = "off";
    private static int checks;

    static native int nInit();
    static native int nCreate(int port);
    static native String nQuery(int id);
    static native void nDestroy(int id);

    static void load(String libraryDir) {
        try {
            System.load(libraryDir + "/libplumjni.so");
            loaded = true;
        } catch (Throwable e) {
            System.out.println("port mapping unavailable: " + e);
        }
    }

    /** The owner's switch, from the app; turning it on checks the router once. */
    static synchronized void set(boolean enabled) {
        // Unchanged (the app reconnected): say again where it stands.
        if (enabled == on && !"off".equals(state)) { report(state); return; }
        on = enabled;
        if (!enabled || !loaded) {
            report(enabled ? "unsupported" : "off");
            return;
        }
        final int check = ++checks;
        report("checking");
        new Thread(() -> {
            // A port nothing listens on: the router's answer is what is wanted, and the mapping goes at once.
            Result r = map(40000 + (int) (Math.random() * 20000), 14_000);
            if (r.id >= 0) nDestroy(r.id);
            synchronized (PortMap.class) {
                if (check != checks || !on) return;
                report(r.ok ? (publicV4(r.host) ? "mapped" : "not_public") : "unsupported");
            }
            System.out.println("port mapping check: " + state + (r.ok ? " protocol=" + r.protocol : ""));
        }, "portmap-check").start();
    }

    private static void report(String s) {
        state = s;
        // Not Map.of: that only exists from Android 11, and the gateway runs from Android 10.
        try { BridgeMain.send(new JSONObject().put("type", "mapping_state").put("state", s)); } catch (org.json.JSONException ignored) {}
    }

    /**
     * The answer with the router's public address added, the mapping's id kept in [ids] to remove later. Unchanged
     * when off, when the router cannot map, or when it does not answer in time.
     */
    static String augment(String sdp, List<Integer> ids) {
        if (!on || !loaded || !"mapped".equals(state)) return sdp;
        Matcher host = Pattern.compile("a=candidate:\\S+ 1 udp (\\d+) (\\d+\\.\\d+\\.\\d+\\.\\d+) (\\d+) typ host").matcher(sdp);
        String ip = null;
        int port = 0;
        long hostPriority = 0;
        while (host.find()) {
            if (privateV4(host.group(2))) { hostPriority = Long.parseLong(host.group(1)); ip = host.group(2); port = Integer.parseInt(host.group(3)); break; }
        }
        if (ip == null) return sdp;
        long started = SystemClock.elapsedRealtime();
        Result r = map(port, CALL_WAIT_MS);
        if (r.id >= 0) synchronized (ids) { ids.add(r.id); }
        if (!r.ok || !publicV4(r.host)) {
            System.out.println("port mapping: none for this connection (" + (r.ok ? "not public" : "no answer") + ")");
            return sdp;
        }
        // Just below the Wi-Fi address it maps to (DIRECT_PATHS.md 3.1): above this phone's own data, which sorts
        // after the Wi-Fi, and above what STUN finds.
        String line = "a=candidate:7400 1 udp " + Math.max(1, hostPriority - 1) + " " + r.host + " " + r.port
            + " typ srflx raddr " + ip + " rport " + port + " generation 0";
        StringBuilder out = new StringBuilder();
        int sections = 0;
        for (String l : sdp.split("\r\n", -1)) {
            out.append(l).append("\r\n");
            if (l.startsWith("a=candidate:") && l.contains(" " + ip + " " + port + " typ host")) {
                out.append(line).append("\r\n");
                sections++;
            }
        }
        out.setLength(out.length() - 2);
        System.out.println("port mapping: added in " + (SystemClock.elapsedRealtime() - started) + " ms to " + sections + " section(s), protocol=" + r.protocol);
        return out.toString();
    }

    static void release(List<Integer> ids) {
        synchronized (ids) {
            for (int id : ids) { try { nDestroy(id); } catch (Throwable ignored) {} }
            ids.clear();
        }
    }

    private static final class Result {
        int id = -1;
        boolean ok;
        int protocol;
        int port;
        String host = "";
    }

    private static Result map(int port, long waitMs) {
        Result r = new Result();
        try {
            if (nInit() != 0) return r;
            r.id = nCreate(port);
            if (r.id < 0) return r;
            long end = SystemClock.elapsedRealtime() + waitMs;
            while (SystemClock.elapsedRealtime() < end) {
                String q = nQuery(r.id);
                if (q == null) return r;
                String[] p = q.split(" ", 4);
                int s = Integer.parseInt(p[0]);
                if (s == SUCCESS) {
                    r.ok = true;
                    r.protocol = Integer.parseInt(p[1]);
                    r.port = Integer.parseInt(p[2]);
                    r.host = p.length > 3 ? p[3].trim() : "";
                    return r;
                }
                if (s == FAILURE) return r;
                SystemClock.sleep(50);
            }
        } catch (Throwable e) {
            System.out.println("port mapping failed " + e);
        }
        return r;
    }

    static boolean privateV4(String a) {
        int[] p = octets(a);
        return p != null && (p[0] == 10 || (p[0] == 172 && p[1] >= 16 && p[1] <= 31) || (p[0] == 192 && p[1] == 168));
    }

    /** Reachable from the internet: not private, not the carriers' shared 100.64/10, not loopback or link-local. */
    static boolean publicV4(String a) {
        int[] p = octets(a);
        return p != null && !privateV4(a) && !(p[0] == 100 && p[1] >= 64 && p[1] <= 127) && p[0] != 127 && p[0] != 0
            && !(p[0] == 169 && p[1] == 254) && p[0] < 224;
    }

    private static int[] octets(String a) {
        String[] s = a.split("\\.");
        if (s.length != 4) return null;
        int[] out = new int[4];
        try {
            for (int i = 0; i < 4; i++) { out[i] = Integer.parseInt(s[i]); if (out[i] < 0 || out[i] > 255) return null; }
        } catch (NumberFormatException e) { return null; }
        return out;
    }
}
