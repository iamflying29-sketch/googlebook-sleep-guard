package local.sleepguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Starts / checks the Sleep Guard helper on this device through Wireless debugging (adb over TLS
 * on 127.0.0.1) with Sleep Guard's OWN paired key ("SleepGuard@googlebook" - separate from any
 * other app's pairing). Used at boot, by the periodic health job and by the app's buttons. Never
 * runs anything but the fixed supervisor commands below.
 */
final class Starter {
    static final String TAG = "SleepGuard";
    static final String DIR = "/data/local/tmp/sleepguard";
    private static final String CH_PROBLEMS = "problems";
    private static final int NOTIF_PROBLEM = 1, NOTIF_ALERT = 2;
    private static final Object LOCK = new Object();

    enum Action { ENSURE, RESTART, STOP }

    static final class Result {
        boolean ok;
        boolean quiet; // nothing the user needs to do (e.g. Wi-Fi is down in deep sleep): no notification
        String summary = "";
        String status = "";
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("state", Context.MODE_PRIVATE);
    }

    static File keyFile(Context c) {
        return new File(c.getFilesDir(), "adb.p12");
    }

    static boolean provisioned(Context c) {
        return keyFile(c).exists() && prefs(c).contains("p12pass");
    }

    static String hex(int bytes) {
        byte[] b = new byte[bytes];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /**
     * Secret shared with the Linux terminal's script, derived from Sleep Guard's paired private key
     * (which only this app and that script hold): SHA-256 of a deterministic RSA signature of a
     * fixed label. The script uses it to check it talks to this app before sending the key, and to
     * trust only port files this app wrote. Null if not set up.
     */
    static String keySecret(Context ctx) {
        try {
            char[] pass = prefs(ctx).getString("p12pass", "").toCharArray();
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            try (InputStream in = new java.io.FileInputStream(keyFile(ctx))) {
                ks.load(in, pass);
            }
            java.security.PrivateKey key = (java.security.PrivateKey) ks.getKey(ks.aliases().nextElement(), pass);
            java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update("sleepguard-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(sig.sign())) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** HMAC-SHA256 with the key secret (hex), or "none". */
    static String keyMac(Context ctx, String msg) {
        String k = keySecret(ctx);
        if (k == null) return "none";
        byte[] kb = new byte[k.length() / 2];
        for (int i = 0; i < kb.length; i++) kb[i] = (byte) Integer.parseInt(k.substring(2 * i, 2 * i + 2), 16);
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(kb, "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "none";
        }
    }

    /** Routine check (boot, every 15 min, debugging switched): make sure the helper runs. */
    static Result check(Context ctx) {
        if (prefs(ctx).getBoolean("stopped", false)) return stoppedResult();
        return run(ctx, Action.ENSURE, true);
    }

    private static Result stoppedResult() {
        Result r = new Result();
        r.ok = true;
        r.summary = "Stopped by you (press Check / start to turn it back on).";
        return r;
    }

    static int adbWifi(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), "adb_wifi_enabled", 0);
    }

    /**
     * Proves that an adb connection really reaches Android's adb service (the shell user): only
     * the shell or the system can deliver a broadcast to ControlReceiver (it requires DUMP), so
     * another app imitating Wireless debugging on a local port can't pass. Nothing is sent over a
     * connection before this check.
     */
    static boolean proveShell(Context ctx, AdbClient adb) {
        String nonce = hex(16);
        try {
            int user = android.os.Process.myUid() / 100000;
            adb.exec("am broadcast --user " + user + " -n " + ctx.getPackageName()
                    + "/.ControlReceiver -a local.sleepguard.PROVE --es nonce " + nonce);
        } catch (Exception e) {
            return false;
        }
        long until = android.os.SystemClock.uptimeMillis() + 3000;
        while (android.os.SystemClock.uptimeMillis() < until) {
            if (ControlReceiver.PROVEN.remove(nonce)) return true;
            android.os.SystemClock.sleep(50);
        }
        Log.w(TAG, "an adb port did not prove to be Android's adb service, so it was ignored");
        return false;
    }

    static Result run(Context ctx, Action action) {
        return run(ctx, action, false);
    }

    /** auto=true: automatic check; re-reads the user's Stop under the lock so it never overrides it. */
    static Result run(Context ctx, Action action, boolean auto) {
        synchronized (LOCK) {
            if (auto && prefs(ctx).getBoolean("stopped", false)) return stoppedResult();
            if (!auto) prefs(ctx).edit().putBoolean("stopped", action == Action.STOP).commit();
            Result r = new Result();
            try {
                r = runLocked(ctx, action, auto);
            } catch (Exception e) {
                r.ok = false;
                r.summary = "Error: " + e;
                Log.w(TAG, "run failed", e);
            }
            prefs(ctx).edit().putLong("lastCheck", System.currentTimeMillis()).putString("lastSummary", r.summary)
                    .putString("lastStatus", r.status).putBoolean("lastOk", r.ok).apply();
            notifyResult(ctx, action, r);
            return r;
        }
    }

    private static Result runLocked(Context ctx, Action action, boolean auto) throws Exception {
        Result r = new Result();
        if (!provisioned(ctx)) {
            r.summary = "Not set up yet: Sleep Guard's pairing key has not been installed (run 'sleep-guard pair' in the Linux terminal).";
            return r;
        }
        byte[] p12 = Files.readAllBytes(keyFile(ctx).toPath());
        char[] pass = prefs(ctx).getString("p12pass", "").toCharArray();

        ensureWirelessDebugging(ctx);
        if (adbWifi(ctx) != 1) {
            if (!wifiConnected(ctx)) {
                // GooglebookOS turns Wireless debugging off while Wi-Fi is down (e.g. in deep sleep). The
                // helper doesn't need it while running; this app turns it back on when Wi-Fi returns.
                r.quiet = true;
                r.summary = "Waiting for Wi-Fi (Wireless debugging needs it). Sleep Guard keeps running meanwhile.";
            } else {
                r.summary = "Wireless debugging is off and could not be switched on. It can be switched on in Developer options.";
            }
            return r;
        }
        AdbClient adb = null;
        boolean sawOwnPort = false, sawImpostor = false;
        int port = prefs(ctx).getInt("port", -1);
        if (port > 0) {
            adb = tryConnect(port, p12, pass);
            if (adb != null && !proveShell(ctx, adb)) {
                adb.close();
                adb = null;
                sawImpostor = true;
                prefs(ctx).edit().remove("port").apply();
            }
        }
        if (adb == null) {
            for (int attempt = 0; attempt < 4 && adb == null; attempt++) {
                for (int[] hp : discoverOwnPorts(ctx, 15000)) {
                    sawOwnPort = true;
                    AdbClient c = tryConnect(hp[0], p12, pass);
                    if (c == null) continue;
                    if (!proveShell(ctx, c)) {
                        c.close();
                        sawImpostor = true;
                        continue;
                    }
                    adb = c;
                    port = hp[0];
                    break;
                }
                if (adb == null) Thread.sleep(5000);
            }
        }
        if (adb == null) {
            r.summary = sawImpostor
                    ? "Something on this Googlebook imitates Wireless debugging (it couldn't prove it is Android's adb service), so nothing was sent to it."
                    : sawOwnPort
                    ? "Wireless debugging no longer accepts Sleep Guard's key (the pairing \"SleepGuard\" was probably removed). Re-pair it: sleep-guard pair"
                    : "Could not reach Wireless debugging on this Googlebook. Is Wi-Fi on? It can be switched on in Developer options.";
            return r;
        }
        prefs(ctx).edit().putInt("port", port).apply();
        try {
            installScript(ctx, adb);
            publishPort(ctx, port);
            String out;
            switch (action) {
                case STOP:
                    out = adb.exec("sh " + DIR + "/start.sh stop");
                    break;
                case RESTART:
                    if (auto) {
                        out = adb.exec("sh " + DIR + "/start.sh restart"); // does nothing if the user stopped it
                    } else {
                        // One command: a connection drop in between can't leave it stopped.
                        out = adb.exec("sh " + DIR + "/start.sh stop >/dev/null; sleep 1; sh " + DIR + "/start.sh start");
                    }
                    break;
                default:
                    out = adb.exec("sh " + DIR + "/start.sh " + (auto ? "ensure" : "start"));
            }
            if (out.contains("stopped by user")) {
                prefs(ctx).edit().putBoolean("stopped", true).commit();
                return stoppedResult();
            }
            Thread.sleep(action == Action.STOP ? 300 : 2000);
            r.status = adb.exec("sh " + DIR + "/start.sh status").trim();
            // Only the status lines above the log count (old log lines may mention a past stand-down).
            String head = r.status.split("--- log ---", 2)[0];
            boolean running = head.startsWith("running");
            boolean standingDown = head.contains("STANDING DOWN:");
            if (action == Action.STOP) {
                r.ok = !running;
                r.summary = "Sleep Guard stopped (" + out.trim() + ").";
            } else if (standingDown) {
                r.ok = false;
                r.summary = "Sleep Guard is standing down (the laptop behaves like stock): " + Starter.line(head, "STANDING DOWN");
            } else {
                r.ok = running;
                r.summary = running ? "Sleep Guard is running." : "Sleep Guard did not start: " + out.trim();
            }
        } finally {
            adb.close();
        }
        return r;
    }

    private static void ensureWirelessDebugging(Context ctx) throws InterruptedException {
        try {
            // Only with the user's explicit consent (sleep-guard config usb_debugging on).
            if (prefs(ctx).getBoolean("keepUsbDebugging", false)) {
                Settings.Global.putInt(ctx.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            }
            if (adbWifi(ctx) == 1) {
                prefs(ctx).edit().putBoolean("wifiDropped", false).apply();
                return;
            }
            if (!wifiConnected(ctx)) {
                // GooglebookOS switches Wireless debugging off when Wi-Fi drops (e.g. in deep sleep):
                // switch it back on as soon as Wi-Fi returns, without the 10-minute pause below.
                prefs(ctx).edit().putBoolean("wifiDropped", true).commit();
                HealthJob.armNetworkTrigger(ctx);
                return;
            }
            boolean dropped = prefs(ctx).getBoolean("wifiDropped", false);
            long now = System.currentTimeMillis(), last = prefs(ctx).getLong("wifiAssertAt", 0);
            // Sleep Guard needs Wireless debugging to (re)start its helper, so it switches it back on
            // (to keep it off, press Stop: a stopped Sleep Guard leaves it alone). Not more often than
            // every 10 minutes, unless it went off because Wi-Fi dropped.
            if (!dropped && now - last < 600_000 && now >= last) return;
            prefs(ctx).edit().putLong("wifiAssertAt", now).putBoolean("wifiDropped", false).commit();
            Settings.Global.putInt(ctx.getContentResolver(), "adb_wifi_enabled", 1);
            Log.i(TAG, "turned Wireless debugging on");
            Thread.sleep(3000);
        } catch (SecurityException e) {
            Log.w(TAG, "cannot change Wireless debugging (WRITE_SECURE_SETTINGS missing)", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean wifiConnected(Context ctx) {
        android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return true;
        for (android.net.Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }

    private static AdbClient tryConnect(int port, byte[] p12, char[] pass) {
        try {
            return AdbClient.connect("127.0.0.1", port, p12, pass, 5000);
        } catch (Exception e) {
            Log.i(TAG, "port " + port + ": " + e);
            return null;
        }
    }

    /** Finds Wireless debugging ports advertised by this device itself (not phones on the Wi-Fi). */
    private static List<int[]> discoverOwnPorts(Context ctx, long timeoutMs) throws InterruptedException {
        Set<String> own = ownAddresses();
        NsdManager nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        final boolean[] closed = {false};
        List<int[]> found = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch gotOne = new CountDownLatch(1);
        // Callbacks run directly on Android's NSD thread (never a separate executor that could be
        // shut down and reject Android's late callbacks - that crashed the trackpad app once).
        java.util.concurrent.Executor ex = Runnable::run;
        List<NsdManager.ServiceInfoCallback> callbacks = Collections.synchronizedList(new ArrayList<>());
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String t, int e) { Log.w(TAG, "discovery failed " + e); gotOne.countDown(); }
            public void onStopDiscoveryFailed(String t, int e) { }
            public void onDiscoveryStarted(String t) { }
            public void onDiscoveryStopped(String t) { }
            public void onServiceLost(NsdServiceInfo i) { }
            public void onServiceFound(NsdServiceInfo info) {
                NsdManager.ServiceInfoCallback cb = new NsdManager.ServiceInfoCallback() {
                    public void onServiceInfoCallbackRegistrationFailed(int e) { }
                    public void onServiceLost() { }
                    public void onServiceInfoCallbackUnregistered() { }
                    public void onServiceUpdated(NsdServiceInfo s) {
                        boolean mine = false;
                        for (InetAddress a : s.getHostAddresses()) if (own.contains(a.getHostAddress())) mine = true;
                        if (mine && s.getPort() > 0) {
                            found.add(new int[]{s.getPort()});
                            gotOne.countDown();
                        }
                    }
                };
                synchronized (callbacks) {
                    if (closed[0]) return;
                    try {
                        nsd.registerServiceInfoCallback(info, ex, cb);
                        callbacks.add(cb);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "resolve failed", e);
                    }
                }
            }
        };
        nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener);
        gotOne.await(timeoutMs, TimeUnit.MILLISECONDS);
        Thread.sleep(500);
        try { nsd.stopServiceDiscovery(listener); } catch (RuntimeException ignored) { }
        synchronized (callbacks) {
            closed[0] = true;
            for (NsdManager.ServiceInfoCallback cb : callbacks) {
                try { nsd.unregisterServiceInfoCallback(cb); } catch (RuntimeException ignored) { }
            }
        }
        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        synchronized (found) {
            for (int[] p : found) unique.add(p[0]);
        }
        List<int[]> out = new ArrayList<>();
        for (int p : unique) out.add(new int[]{p});
        return out;
    }

    private static Set<String> ownAddresses() {
        Set<String> s = new java.util.HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses())) s.add(a.getHostAddress());
        } catch (Exception ignored) {
        }
        return s;
    }

    /** Writes the bundled supervisor script (kept in sync with the app version) to the device. */
    private static void installScript(Context ctx, AdbClient adb) throws Exception {
        byte[] script;
        try (InputStream in = ctx.getAssets().open("start.sh")) {
            script = in.readAllBytes();
        }
        String b64 = Base64.encodeToString(script, Base64.NO_WRAP);
        String out = adb.exec("mkdir -p " + DIR + " && chmod 700 " + DIR + " && echo " + b64 + " | base64 -d > " + DIR
                + "/start.sh.new-app && mv " + DIR + "/start.sh.new-app " + DIR + "/start.sh && chmod 700 " + DIR + "/start.sh && echo ok");
        if (!out.trim().endsWith("ok")) throw new Exception("could not install start.sh: " + out);
    }

    /**
     * Leaves "IP:port mac" of Wireless debugging in Documents/Sleep Guard/adb-port.txt (via
     * MediaStore), where the Linux terminal's sleep-guard script finds it after a reboot. Signed
     * with the secret only this app and that script share.
     */
    private static void publishPort(Context ctx, int port) {
        try {
            String ip = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.getName().startsWith("wlan")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address) ip = a.getHostAddress();
            }
            String addr = (ip == null ? "" : ip) + ":" + port;
            // Unchanged address (IP and port) = nothing to write.
            if (addr.equals(prefs(ctx).getString("publishedAddr", null)) && prefs(ctx).getString("portUri", null) != null) return;
            String text = addr + " " + keyMac(ctx, "port:" + addr) + "\n";
            android.content.ContentResolver cr = ctx.getContentResolver();
            android.net.Uri files = android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String rel = android.os.Environment.DIRECTORY_DOCUMENTS + "/Sleep Guard/";
            android.net.Uri uri = null;
            String saved = prefs(ctx).getString("portUri", null);
            if (saved != null) {
                try (java.io.OutputStream out = cr.openOutputStream(android.net.Uri.parse(saved), "wt")) {
                    if (out != null) {
                        out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        prefs(ctx).edit().putString("publishedAddr", addr).apply();
                        return;
                    }
                } catch (Exception e) {
                    prefs(ctx).edit().remove("portUri").apply();
                }
            }
            try (android.database.Cursor c = cr.query(files, new String[]{android.provider.MediaStore.MediaColumns._ID},
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{rel, "adb-port%.txt"}, null)) {
                if (c != null && c.moveToFirst()) uri = android.content.ContentUris.withAppendedId(files, c.getLong(0));
            }
            if (uri == null) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "adb-port.txt");
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel);
                uri = cr.insert(files, v);
            }
            if (uri == null) return;
            try (java.io.OutputStream out = cr.openOutputStream(uri, "wt")) {
                out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            prefs(ctx).edit().putString("portUri", uri.toString()).putString("publishedAddr", addr).apply();
        } catch (Exception e) {
            Log.i(TAG, "publishPort: " + e);
        }
    }

    // ---- notifications --------------------------------------------------------------------------

    private static NotificationManager channel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_PROBLEMS, "Problems and alerts", NotificationManager.IMPORTANCE_DEFAULT));
        return nm;
    }

    private static void notifyResult(Context ctx, Action action, Result r) {
        if (r.quiet) return;
        NotificationManager nm = channel(ctx);
        if (action == Action.STOP || r.ok) {
            nm.cancel(NOTIF_PROBLEM);
            prefs(ctx).edit().remove("lastProblem").apply();
            return;
        }
        String text = r.summary;
        long lastAt = prefs(ctx).getLong("lastProblemAt", 0);
        if (!text.equals(prefs(ctx).getString("lastProblem", "")) || System.currentTimeMillis() - lastAt > 86_400_000) {
            prefs(ctx).edit().putString("lastProblem", text).putLong("lastProblemAt", System.currentTimeMillis()).apply();
            post(ctx, nm, NOTIF_PROBLEM, "Sleep Guard needs attention", text);
        }
    }

    /** From the helper (via ControlReceiver ALERT): e.g. it verified itself on a new GooglebookOS build or stood down. */
    static void alert(Context ctx, String title, String text) {
        String key = title + "|" + text;
        if (key.equals(prefs(ctx).getString("lastAlert", "")) && System.currentTimeMillis() - prefs(ctx).getLong("lastAlertAt", 0) < 86_400_000) return;
        prefs(ctx).edit().putString("lastAlert", key).putLong("lastAlertAt", System.currentTimeMillis()).apply();
        post(ctx, channel(ctx), NOTIF_ALERT, title, text);
    }

    private static void post(Context ctx, NotificationManager nm, int id, String title, String text) {
        PendingIntent open = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CH_PROBLEMS)
                .setSmallIcon(R.drawable.ic_moon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }

    static String line(String text, String prefix) {
        for (String l : text.split("\n")) if (l.trim().startsWith(prefix)) return l.trim();
        return "";
    }
}
