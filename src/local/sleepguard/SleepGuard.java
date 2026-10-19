package local.sleepguard;

import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.system.Os;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sleep Guard helper for the Lenovo Googlebook 15 (GooglebookOS). The laptop sleeps exactly as
 * designed; Sleep Guard only stops it from diving straight back into deep sleep while the USB-C
 * charger is changing.
 *
 * Background (2026-10-10 investigation): a charger plug or unplug while the laptop is in lid-closed
 * deep sleep collides with the USB-C power controller's work; the EC once logged "Detected sleep
 * hang!" and twice hard-reset (cutting all power). With the lid closed, GooglebookOS's
 * DesktopPowerService force-disables every ordinary (PARTIAL) wakelock, so after a charger wake the
 * laptop re-enters S0ix at once while the USB-C controller is still busy.
 *
 * The keep-awake lock (2026-10-18): Android's PARTIAL_SLEEP_WAKE_LOCK (level 0x200, permission
 * ACQUIRE_SLEEP_LOCK, which the adb shell user holds). GooglebookOS's force-disable only touches
 * level PARTIAL (PowerManagerService.setWakeLockDisabledStateLocked), and the power service keeps
 * the kernel awake for this level in every state, asleep included (adjustWakeLockSummary keeps the
 * CPU bit). So Sleep Guard holds the laptop awake WITHOUT changing the lid state at all. Versions
 * before this one emulated a lid opening (a base-state override) at every wake, which made
 * GooglebookOS run its whole lid-opened routine (audio unmuted and muted again, keyboard and
 * trackpad switched on and off, the glow bar lit for a few seconds) about 140 times a day.
 *
 * The lock must never stop the user from waking the laptop (2026-10-19 failure: asleep with the lid
 * open, the laptop could not be woken by any key or by the power button for a whole 20 s charger
 * settle, because PowerManagerService.wakePowerGroupLocked refuses EVERY wake request, the lid and
 * the power button included, while a PARTIAL_SLEEP_WAKE_LOCK is held: hasWakeLockKeepingGroupAsleep).
 * Therefore:
 *  - With the lid open an ordinary PARTIAL wakelock (level 1) is used: GooglebookOS force-disables
 *    that level only in the CLOSED state (DesktopPowerService.handleDisableWakeLock), and the power
 *    service never refuses a wake for it.
 *  - The sleep lock is held only with the lid closed, and never for long. A lid opening or a power
 *    button press while it is held releases it at once, and if GooglebookOS's own wake request had
 *    been refused meanwhile (it is not repeated), Sleep Guard wakes the laptop itself
 *    (IPowerManager.wakeUp; the shell holds DEVICE_POWER).
 *
 * Behaviour (the user's choice, 2026-10-10 05:25: "as designed"):
 *  - Closing the lid: stock. Deep sleep at once (glow bar off), charger or not. Nothing held.
 *  - Each wake from deep sleep with the lid closed (a charger change wakes the laptop): within ~5 ms
 *    Sleep Guard takes the sleep lock for GRAB_MS while it checks the charger (every 25 ms).
 *    Changed: a 20 s settle; otherwise straight back to sleep.
 *  - A charger change with the screen off and the lid open (the laptop asleep with the lid open): a
 *    20 s settle with an ordinary wakelock, which never blocks a wake. Whenever the screen is on the
 *    laptop is in use and cannot fall asleep, so Sleep Guard does nothing at all then (it only logs
 *    the change).
 *  - When a window ends the lock is released and GooglebookOS sleeps as it would have anyway.
 *
 * Safety / update-proofing:
 *  - The lock belongs to this process. If it dies GooglebookOS drops it.
 *  - Startup self-test of everything it relies on (the lid switch, the battery report, the device
 *    state report, the sleep lock itself); a changed interface means STAND DOWN, recorded for this
 *    GooglebookOS build, user alerted. Something only temporarily unavailable means exit EXIT_RETRY.
 *  - Live check 150 ms into each lid-closed settle (and the first lid-closed hold on a new build):
 *    the power service must list our lock as active and must be holding its kernel suspend blocker.
 *    Failure means stand down. The first success on a GooglebookOS build is announced.
 *  - Never calls System.exit while holding grabLock; awake-time budgets for wake holds and settles.
 *  - Watchdog: a stalled main loop releases everything and exits (the supervisor restarts it).
 *  - Reads of service reports never block a service (see dump()); every command has a time limit;
 *    service calls every 500 ms (every 2 s while the laptop is in use with the lid open and the
 *    screen on, every 100 ms for 3 s after a wake, lid or charger change); the
 *    5 ms wake detector only reads two clocks and runs only while the laptop can deep sleep.
 *  - Single instance (lock file in the shell-only directory). Never deletes anything; never
 *    injects input; never changes the lid state. The only thing it ever asks of the display is a
 *    wake-up, and only right after the user opened the lid or pressed the power button.
 */
public final class SleepGuard {
    static final int STATE_CLOSED = 0, STATE_OPEN = 2;
    /** Android's PARTIAL_SLEEP_WAKE_LOCK: the one level GooglebookOS does not force-disable. */
    static final int PARTIAL_SLEEP_WAKE_LOCK = 0x200;
    /** Android's ordinary PARTIAL_WAKE_LOCK: used with the lid open (never blocks a wake-up). */
    static final int PARTIAL_WAKE_LOCK = 1;
    /** PowerManager wake reasons (hidden constants) and the evdev power key. */
    static final int WAKE_REASON_POWER_BUTTON = 1, WAKE_REASON_WAKE_KEY = 6, WAKE_REASON_LID = 9, KEY_POWER = 116;
    // A wake hold is kept short (the charger change showed up 18 and 87 ms after the wake in the
    // 2026-10-10 tests). Only a real charger change keeps the laptop awake (SETTLE_MS).
    static final long SETTLE_MS = 20_000, GRAB_MS = 300, CHECK_AFTER_MS = 150;
    static final long PASS_MS = 40, POLL_MS = 500, POLL_FAST_MS = 100, POLL_GRAB_MS = 25, FAST_MS = 3000, DETECT_MS = 5;
    /** Lid open and screen on: the laptop is in use and cannot fall asleep; only a slow look at the charger. */
    static final long POLL_IN_USE_MS = 2000;
    static final String DIR = "/data/local/tmp/sleepguard";
    static final String LOG = DIR + "/sleepguard.log";
    static final String STANDDOWN = DIR + "/standdown"; // "none" or "STANDING DOWN: <reason> | build=<fp>"
    static final String VERIFIED = DIR + "/verified";   // last build fingerprint verified live
    static final int EXIT_WATCHDOG = 4, EXIT_STANDDOWN = 5, EXIT_RETRY = 6, EXIT_OTHER_RUNNING = 7;

    /** Something is only temporarily unavailable: the supervisor starts the helper again later. */
    static final class Retry extends Exception {
        Retry(String why) { super(why); }
    }

    static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS zzz", Locale.US);
    static FileOutputStream logOut;
    static int logWrites;
    /** Set once the process is on its way out: nothing may call System.exit again (that would hang). */
    static volatile boolean exiting;

    /** Lines also go to stdout, except in the supervised run (its stdout file would grow without end). */
    static volatile boolean echoStdout = true;
    /** Set once run() has finished starting up (the startup guard in main() watches it). */
    static volatile boolean startupDone;
    /** The single-instance lock, held for the life of the process (a local could be collected). */
    static java.nio.channels.FileLock heldLock;

    static synchronized void log(String s, boolean sync) {
        String line = FMT.format(new Date()) + " " + s + "\n";
        if (echoStdout) System.out.print(line);
        try {
            if (logOut == null) logOut = new FileOutputStream(LOG, true);
            if (++logWrites % 50 == 0 && logOut.getChannel().size() > 2_000_000) {
                // Keep the log small also while one helper runs for weeks (older half kept as .1).
                logOut.close();
                new java.io.File(LOG).renameTo(new java.io.File(LOG + ".1"));
                logOut = new FileOutputStream(LOG, true);
            }
            logOut.write(line.getBytes(StandardCharsets.UTF_8));
            if (sync) logOut.getFD().sync(); // survive a sudden power loss
        } catch (IOException e) {
            logOut = null; // reopened at the next line
        }
    }

    static String fingerprint() {
        return exec("getprop", "ro.build.fingerprint").trim();
    }

    /**
     * Runs a command with a hard 10 s limit (killed after that) and at most 256 KB of output.
     * Output is drained on its own thread while the command runs, so it can never block on a full pipe.
     */
    static String exec(String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            final InputStream in = p.getInputStream();
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                int n;
                try {
                    while ((n = in.read(buf)) > 0) if (out.size() < 262_144) out.write(buf, 0, n);
                } catch (IOException ignored) { }
            }, "exec-drain");
            t.setDaemon(true);
            t.start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log("command timed out and was stopped: " + String.join(" ", cmd), false);
            }
            t.join(2000);
            return out.toString("UTF-8"); // ByteArrayOutputStream is synchronized
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            return "";
        } finally {
            if (p != null) { try { p.getInputStream().close(); } catch (IOException ignored) { } }
        }
    }

    static void background(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    static void writeFile(String path, String text) {
        try (FileOutputStream f = new FileOutputStream(path, false)) {
            f.write(text.getBytes(StandardCharsets.UTF_8));
            f.getFD().sync();
        } catch (IOException e) {
            log("could not write " + path + ": " + e, false);
        }
    }

    static String readFile(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** Tells the user via the Sleep Guard app (falls back to a shell notification). At most ~30 s. */
    static Thread alert(String title, String text) {
        Thread t = new Thread(() -> {
            String user = exec("am", "get-current-user").trim();
            if (!user.matches("\\d+")) user = "10";
            String out = exec("am", "broadcast", "--user", user, "-n", "local.sleepguard/.ControlReceiver",
                    "-a", "local.sleepguard.ALERT", "--es", "title", title, "--es", "text", text);
            if (!out.contains("data=\"ok\"")) {
                exec("cmd", "notification", "post", "-S", "bigtext", "-t", title, "sleepguard", text);
            }
        }, "alert");
        t.start();
        return t;
    }

    /**
     * Never act on a setup that isn't what this code was written and tested for. Main thread only,
     * never while holding grabLock (the shutdown hook needs it).
     */
    static void standDown(String reason, SleepGuard g) {
        if (exiting) return;
        exiting = true;
        log("STANDING DOWN: " + reason, true);
        if (g != null) g.releaseAll("standing down");
        writeFile(STANDDOWN, "STANDING DOWN: " + reason + " | build=" + fingerprint() + "\n");
        if (g != null) g.heartbeat = SystemClock.uptimeMillis(); // the notification wait isn't a stall
        Thread a = alert("Sleep Guard stood down",
                "Sleep Guard switched itself off safely. The laptop now behaves exactly like stock. Reason: " + reason
                        + ". After GooglebookOS updates this is expected if something it relies on changed; it will try "
                        + "again after the next update, or run 'sleep-guard start' in the Linux terminal.");
        try {
            a.join(25_000); // let the notification go out before exiting
        } catch (InterruptedException ignored) { }
        System.exit(EXIT_STANDDOWN);
    }

    /** Temporarily unavailable: exit; the supervisor starts a fresh helper later (no stand-down). */
    static void retryLater(String reason) {
        if (exiting) return;
        exiting = true;
        log("will retry later: " + reason, true);
        System.exit(EXIT_RETRY);
    }

    // ---------------------------------------------------------------- services (all via reflection)
    final Method getService;
    final IBinder powerBinder;     // checked every pass: dead = system services restarted
    final Object power;            // IPowerManager
    final Method isInteractive, acquireWakeLock, releaseWakeLock, wakeUp;
    final Object wake = new Object();
    final Object grabLock = new Object();
    int dockedId = -1;

    // Shared with the wake detector, the lid thread, the watchdog and the shutdown hook.
    volatile boolean started;            // the main loop runs
    volatile long protectUntil = 0;      // uptime until which a protection window (grab/settle) runs
    volatile long fastUntil = 0;         // uptime until which the main loop polls fast
    volatile IBinder settleLock, wakeLock;    // changed only under grabLock
    volatile int settleLevel;                 // the settle lock's level (sleep lock with the lid closed)
    volatile long sleepLockReleasedAt;        // uptime of the last sleep lock release (a late user action still gets a wake)
    volatile boolean docked;                  // GooglebookOS's base state is DOCKED (lid closed, external display)
    volatile long lockSince = 0;         // uptime when the current lid-closed lock was taken (live check)
    volatile long settleUntil = 0;
    volatile boolean lidClosed;
    volatile long lidChangedAt = 0;
    volatile boolean lidReaderFailed;
    volatile long heartbeat = SystemClock.uptimeMillis();
    volatile boolean lidResync;          // the lid reader may have missed events (SYN_DROPPED / reopened)
    final java.util.concurrent.atomic.AtomicInteger holdsThisClose = new java.util.concurrent.atomic.AtomicInteger();
    volatile boolean screenOnShared;     // the main loop's latest screen reading (for the wake detector)
    int lockFailures;                    // consecutive refused wake-hold locks, under grabLock
    volatile String lockFailure;         // handled by the main loop: stand down

    /** ReflectiveOperationException etc. = an interface changed (stand down); Retry = not available yet. */
    SleepGuard() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        getService = sm.getMethod("getService", String.class);
        powerBinder = (IBinder) getService.invoke(null, "power");
        if (powerBinder == null) throw new Retry("GooglebookOS's system services aren't available yet");
        power = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, powerBinder);
        isInteractive = method(power.getClass(), "isInteractive", 0);
        acquireWakeLock = method(power.getClass(), "acquireWakeLock", 8);
        releaseWakeLock = method(power.getClass(), "releaseWakeLock", 2);
        wakeUp = method(power.getClass(), "wakeUp", 4); // (long eventTime, int reason, String details, String opPackageName)
    }

    static Method method(Class<?> c, String name, int argc) throws NoSuchMethodException {
        for (Method m : c.getMethods())
            if (m.getName().equals(name) && m.getParameterCount() == argc) return m;
        throw new NoSuchMethodException(c.getSimpleName() + "." + name + " with " + argc + " parameters");
    }

    boolean servicesAlive() {
        return powerBinder.isBinderAlive();
    }

    static final long DUMP_DEADLINE_MS = 3000;
    static final int DUMP_MAX_BYTES = 1_000_000;

    /**
     * A service's dump text, built so it can NEVER block the service (a rule of this project:
     * "Never hang, block or overload the system"):
     *  - IBinder.dumpAsync is one-way: this process never waits inside the service;
     *  - the pipe is drained WHILE the service writes, with poll() and a hard total deadline;
     *  - output is capped; on deadline or cap (or any error) the read end is CLOSED, so the
     *    service's next write fails with EPIPE instead of waiting on us.
     * Returns "" when the report wasn't complete in time (callers treat that as "unknown").
     */
    String dump(String service) {
        return dump(service, DUMP_DEADLINE_MS, DUMP_MAX_BYTES);
    }

    String dump(String service, long deadlineMs, int maxBytes) {
        FileDescriptor[] p = null;
        try {
            IBinder b = (IBinder) getService.invoke(null, service);
            if (b == null) return "";
            p = Os.pipe();
            try {
                b.dumpAsync(p[1], new String[0]);
            } finally {
                Os.close(p[1]); // the service has its own copy; EOF arrives when it finishes
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            long end = SystemClock.uptimeMillis() + deadlineMs;
            android.system.StructPollfd pfd = new android.system.StructPollfd();
            pfd.fd = p[0];
            pfd.events = (short) android.system.OsConstants.POLLIN;
            while (true) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) return "";                                   // too slow: give up (read end closed below)
                pfd.revents = 0;
                if (Os.poll(new android.system.StructPollfd[]{pfd}, (int) Math.min(left, 500)) == 0) continue;
                int n = Os.read(p[0], buf, 0, buf.length);
                if (n <= 0) break;                                          // EOF: report complete
                if (out.size() + n > maxBytes) return "";                   // too big: give up
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) {
                try { Os.close(p[0]); } catch (Exception ignored) { }    // always hang up: the writer can never block
            }
        }
    }

    /**
     * Live check from the power service's report while our lock is held: our lock must be listed
     * and not DISABLED, and the power service must be holding its kernel suspend blocker (that is
     * what actually keeps the laptop from sleeping). Returns null if fine, "" if the report is
     * unavailable, else what's wrong.
     */
    String lockProblem(String tag) {
        String d = dump("power");
        if (d.isEmpty() || !d.contains("Wake Locks:")) return ""; // never act on a missing report
        boolean listed = false;
        for (String line : d.split("\n")) {
            if (line.contains("'" + tag + "'")) {
                if (line.contains("DISABLED")) return "Sleep Guard's keep-awake lock is disabled by GooglebookOS";
                listed = true;
            }
        }
        if (!listed) return "Sleep Guard's keep-awake lock is not listed by the power service";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("PowerManagerService\\.WakeLocks: ref count=(\\d+)").matcher(d);
        if (!m.find()) return "the power service report no longer shows its suspend blockers";
        if (parseInt(m.group(1), 0) < 1) return "the power service is not keeping the laptop awake for Sleep Guard's lock";
        return null;
    }

    static Boolean chargerFrom(String batteryReport) {
        if (!batteryReport.contains("AC powered:") || !batteryReport.contains("USB powered:")) return null;
        return batteryReport.matches("(?s).*(AC|USB|Wireless|Dock) powered: true.*");
    }

    /** null = unknown (keep previous). */
    Boolean chargerConnected() {
        return chargerFrom(dump("battery"));
    }

    static int stateFrom(String deviceStateReport, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(field + "=Optional\\[DeviceState\\{identifier=(\\d+),").matcher(deviceStateReport);
        return m.find() ? parseInt(m.group(1), -1) : -1;
    }

    static int parseInt(String digits, int fallback) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Wireless debugging's current port from GooglebookOS's adb report (~5 KB): 0 = off, -1 = unknown.
     * After Wi-Fi drops in deep sleep GooglebookOS restarts it by itself on a NEW port without
     * changing any setting (seen 2026-10-11 01:24/01:31), so nothing tells the Sleep Guard app.
     */
    int adbWifiPort() {
        String d = dump("adb");
        if (!d.contains("adb_wifi")) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("tls_port=(\\d+)").matcher(d);
        return m.find() ? parseInt(m.group(1), -1) : 0;
    }

    /** The port of a request the app did not confirm (e.g. it was busy being unfrozen): retried. */
    static volatile int kickFailedPort = 0;

    /**
     * Asks the Sleep Guard app to run a check now; it tries this port first and publishes it. The
     * app answers "kicked"; without that answer the main loop asks again at its next port check.
     */
    static void kickApp(int port, String why) {
        background("kick-app", () -> {
            String user = exec("am", "get-current-user").trim();
            if (!user.matches("\\d+")) user = "10";
            String out = exec("am", "broadcast", "--user", user, "-n", "local.sleepguard/.ControlReceiver",
                    "-a", "local.sleepguard.KICK", "--ei", "port", String.valueOf(port));
            if (out.contains("kicked")) {
                kickFailedPort = 0;
                log("asked the Sleep Guard app to publish the new address (" + why + ")", false);
            } else {
                kickFailedPort = port;
                log("the Sleep Guard app did not confirm the request to publish the new address (" + why + "): asking again later", false);
            }
        });
    }

    /** The physical (base) lid state from the device state report (synchronous); -1 = unknown. */
    int baseState() {
        return stateFrom(dump("device_state"), "mBaseState");
    }

    static final String UNKNOWN = "?";

    /** Identifier of the DOCKED state (lid closed + external display), -1 if this build has none. */
    static int dockedStateId(String printStates) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("identifier=(\\d+), name='DOCKED'").matcher(printStates);
        return m.find() ? parseInt(m.group(1), -1) : -1;
    }

    /**
     * Everything this code relies on, checked before it acts. Returns null if all good, a reason
     * if something changed (stand down); throws Retry if something is only unavailable right now.
     */
    String selfTest(String lidDev, String printStates) throws Retry {
        if (printStates.isEmpty()) throw new Retry("the device state list is unavailable right now");
        if (!printStates.contains("identifier=" + STATE_CLOSED + ", name='CLOSED'") || !printStates.contains("identifier=" + STATE_OPEN + ", name='OPEN'"))
            return "GooglebookOS device states changed (CLOSED=0 / OPEN=2 not found)";
        if (UNKNOWN.equals(lidDev)) throw new Retry("the input device list is unavailable right now");
        if (lidDev == null) return "lid switch input device not found";
        String battery = dump("battery");
        if (battery.isEmpty()) throw new Retry("the battery report is unavailable right now");
        if (chargerFrom(battery) == null) return "battery report format changed";
        String ds = dump("device_state");
        if (ds.isEmpty()) throw new Retry("the device state report is unavailable right now");
        if (stateFrom(ds, "mBaseState") == -1) return "device state report format changed";
        // The sleep lock itself: taken and released once (nothing visible happens).
        String lock = sleepLockProblem();
        if (lock != null) return lock;
        return null;
    }

    /** Tries the sleep lock and the ordinary lock once each; null if accepted, else why GooglebookOS refused. */
    String sleepLockProblem() throws Retry {
        for (int level : new int[]{PARTIAL_SLEEP_WAKE_LOCK, PARTIAL_WAKE_LOCK}) {
            // The sleep lock is never taken, not even for a microsecond, while the laptop is asleep with
            // an unknown lid state (it would refuse a wake request arriving at that instant).
            if (level == PARTIAL_SLEEP_WAKE_LOCK && !interactive()) continue;
            IBinder b = new Binder();
            try {
                acquireWakeLock.invoke(power, b, level, "SleepGuard:selftest", "com.android.shell", null, null, -1, null);
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                if (c instanceof android.os.DeadObjectException) throw new Retry("the power service is restarting");
                return "GooglebookOS refuses the " + (level == PARTIAL_WAKE_LOCK ? "ordinary" : "sleep") + " wake lock (" + c + ")";
            }
            try {
                releaseWakeLock.invoke(power, b, 0);
            } catch (Exception ignored) { }
        }
        return null;
    }

    // ---------------------------------------------------------------- actions
    // Wake holds: never skip one; keep them short when there are many (an awake-time budget, real time).
    static final long HOLD_BUDGET_WINDOW_MS = 15 * 60_000, HOLD_BUDGET_MS = 15_000, GRAB_SHORT_MS = 150;
    // Charger settles: more than this many charger changes in the window means the charger is flapping.
    static final long SETTLE_BUDGET_WINDOW_MS = 15 * 60_000;
    static final int SETTLE_BUDGET_COUNT = 20;

    /** Records a charger change (real time) and tells whether settles are suspended for flapping. */
    static boolean settleBudgetExceeded(ArrayDeque<Long> settleTimes) {
        long real = SystemClock.elapsedRealtime();
        while (!settleTimes.isEmpty() && real - settleTimes.peekFirst() > SETTLE_BUDGET_WINDOW_MS) settleTimes.pollFirst();
        settleTimes.addLast(real);
        return settleTimes.size() > SETTLE_BUDGET_COUNT;
    }

    final ArrayDeque<long[]> holdHistory = new ArrayDeque<>(); // {realtime start, length ms}, under grabLock
    final java.util.concurrent.atomic.AtomicLong protectEnd = new java.util.concurrent.atomic.AtomicLong();

    /** Protection runs at least until this uptime (never shortened by a concurrent caller). */
    void extendProtection(long untilUptime) {
        synchronized (protectEnd) { // two callers at once: the larger value is also the one written last
            protectUntil = protectEnd.accumulateAndGet(untilUptime, Math::max);
        }
    }

    /**
     * The laptop woke from deep sleep with the lid closed: keep it awake briefly so a charger
     * change can be noticed. From the wake detector; never exits. The lock first, logs after.
     */
    void startGrab(String why) {
        if (exiting || !lidClosed) return; // e.g. the wake was the lid opening
        long len;
        long real = SystemClock.elapsedRealtime();
        boolean taken = false, lidRace = false;
        long now;
        synchronized (grabLock) {
            long used = 0;
            while (!holdHistory.isEmpty() && real - holdHistory.peekFirst()[0] > HOLD_BUDGET_WINDOW_MS) holdHistory.pollFirst();
            for (long[] h : holdHistory) used += h[1];
            len = used >= HOLD_BUDGET_MS ? GRAB_SHORT_MS : GRAB_MS; // many wakes: still check, just shorter
            now = SystemClock.uptimeMillis();
            // The window first, then the lock, both under grabLock: the main loop's "window over" release
            // re-checks the window under the same lock, so it can never release a lock taken a moment ago.
            extendProtection(now + len);
            if (wakeLock == null) {
                wakeLock = acquireLock("SleepGuard:wake", PARTIAL_SLEEP_WAKE_LOCK);
                taken = wakeLock != null;
                if (taken) {
                    lockSince = SystemClock.uptimeMillis();
                    lockFailures = 0;
                    holdHistory.addLast(new long[]{real, len}); // only real holds count against the budget
                } else if (++lockFailures >= 5) {
                    lockFailure = "the keep-awake lock for wake holds keeps being refused";
                }
            }
            if (taken && !lidClosed) {
                // The lid opened at this very moment (the wake WAS the lid opening): the sleep lock must
                // not stay for even a few ms, and the laptop must wake (see userWoke).
                lidRace = true;
                protectEnd.set(0);
                protectUntil = 0;
                IBinder b = wakeLock;
                wakeLock = null;
                releaseLock(b);
                sleepLockReleasedAt = SystemClock.uptimeMillis();
            }
        }
        fastUntil = Math.max(fastUntil, now + FAST_MS);
        if (taken) holdsThisClose.incrementAndGet();
        if (taken && !lidRace) log("holding the laptop awake " + len + " ms (sleep lock): " + why, false);
        if (lidRace) {
            log("hold over: the lid opened as the hold began", false);
            background("lid-wake", () -> ensureAwake("the lid opened", WAKE_REASON_LID));
        }
        synchronized (wake) { wake.notifyAll(); }
    }

    /**
     * The user acted (opened the lid, pressed a key or a button, moved a mouse) while the sleep lock may
     * be held: release it at once (while it is held GooglebookOS refuses every wake request, this one
     * included), and after a lid opening also make sure the laptop wakes. From the input reader threads. A settle that was
     * running with the lid open continues with the ordinary wakelock (it never blocks a wake-up).
     */
    void userWoke(String what, int reason, boolean onlyWhenAsleep) {
        boolean held;
        synchronized (grabLock) {
            held = wakeLock != null || (settleLock != null && settleLevel == PARTIAL_SLEEP_WAKE_LOCK);
        }
        if (!held) {
            // Nothing that could refuse a wake (the usual case: this costs nothing). Unless a sleep lock
            // was released within the last second: the user's action may have come while it was still
            // held and been refused, and this reader thread simply ran late (a slow disk sync, a busy
            // resume). Then make sure the laptop wakes anyway; ensureAwake does nothing if it is awake.
            if (reason == WAKE_REASON_LID && SystemClock.uptimeMillis() - sleepLockReleasedAt < 1000)
                background("user-wake", () -> ensureAwake(what, reason));
            return;
        }
        // A key, button or mouse event while the screen is on is the user's work, or the power button
        // putting the laptop to sleep: not a wake request (the lid opening always is one). Asked live.
        if (onlyWhenAsleep && interactive()) return;
        IBinder w, st;
        long now;
        boolean goesOn = false;
        synchronized (grabLock) {
            w = wakeLock;
            wakeLock = null;
            st = settleLock != null && settleLevel == PARTIAL_SLEEP_WAKE_LOCK ? settleLock : null;
            if (st != null) settleLock = null;
            protectEnd.set(0);
            protectUntil = 0;
            releaseLock(w);
            releaseLock(st);
            if (w != null || st != null) sleepLockReleasedAt = SystemClock.uptimeMillis();
            // The wake-up first, the log lines (with their disk syncs) after. Also when another thread
            // released the lock a moment ago: the user's wake request may still have been refused.
            // Only for the lid: GooglebookOS always wakes on a lid opening, so repeating that request is
            // exactly its own behaviour. Keys, buttons and mouse motion only release the lock: whether they
            // wake a closed laptop is GooglebookOS's decision (tested 2026-10-19 02:02: it does not wake on
            // the power button with the lid closed, and Sleep Guard's own wake-up lit the closed screen).
            // The next press or movement reaches GooglebookOS with the lock gone.
            if (!onlyWhenAsleep) background("user-wake", () -> ensureAwake(what, reason));
            if (w == null && st == null) return; // another thread was first; nothing to log
            now = SystemClock.uptimeMillis();
            if (onlyWhenAsleep && st != null && lidClosed && now < settleUntil) background("settle-resume", () -> resumeSettle(what));
            if (st != null && !lidClosed && now < settleUntil) {
                settleLevel = PARTIAL_WAKE_LOCK;
                settleLock = acquireLock("SleepGuard:settle", PARTIAL_WAKE_LOCK);
                goesOn = settleLock != null;
            }
        }
        if (w != null) log("hold over: " + what, false);
        if (st != null) log("settle lock released: " + what, false);
        if (goesOn) log("the settle goes on with an ordinary wakelock for " + ((settleUntil - now) / 1000) + " s", false);
    }

    /**
     * A lid-closed settle was released for a key or mouse event. If GooglebookOS did not wake the laptop
     * on it (with the lid closed it does not), the charger still needs its settle: take the sleep lock
     * again for the rest of it one second later, while the laptop is still asleep.
     */
    void resumeSettle(String what) {
        SystemClock.sleep(1000);
        if (exiting || interactive()) return; // awake: in use, nothing to protect
        boolean resumed = false;
        long left;
        synchronized (grabLock) {
            left = settleUntil - SystemClock.uptimeMillis();
            if (lidClosed && settleLock == null && wakeLock == null && left > 500) {
                settleLevel = PARTIAL_SLEEP_WAKE_LOCK;
                settleLock = acquireLock("SleepGuard:settle", PARTIAL_SLEEP_WAKE_LOCK);
                if (settleLock != null) { lockSince = SystemClock.uptimeMillis(); resumed = true; }
            }
        }
        if (resumed) log("the settle goes on with the sleep lock for " + (left / 1000) + " s (GooglebookOS did not wake on " + what + ")", true);
    }

    /**
     * A settle that holds the sleep lock although the lid is open (the lid opened between the main
     * loop's lid snapshot and its acquire, or a lid re-read found it open): swap to the ordinary lock
     * and make sure the laptop is awake. Cheap when there is nothing to do.
     */
    void fixSettleLidOpen() {
        if (lidClosed || !(wakeLock != null || (settleLock != null && settleLevel == PARTIAL_SLEEP_WAKE_LOCK))) return;
        userWoke("the lid opened", WAKE_REASON_LID, false);
    }

    /**
     * After the sleep lock was released because the user acted: GooglebookOS's own wake request may
     * have been refused while the lock was held, and it is not repeated. If the screen is still off,
     * wake the laptop here (IPowerManager.wakeUp; the adb shell holds DEVICE_POWER).
     */
    void ensureAwake(String what, int reason) {
        for (int i = 0; i < 3; i++) {
            if (interactive()) {
                log(i == 0 ? what + " while Sleep Guard held or had just released its lock: the laptop woke by itself"
                        : "woke the laptop after " + what + " (GooglebookOS's own wake-up had been refused while Sleep Guard held its sleep lock)", true);
                return;
            }
            try {
                wakeUp.invoke(power, SystemClock.uptimeMillis(), reason, "SleepGuard:" + what, "com.android.shell");
            } catch (Exception e) {
                log("WAKE-UP CALL FAILED after " + what + ": " + e.getCause(), true);
                return;
            }
            SystemClock.sleep(150);
        }
        log(interactive() ? "woke the laptop after " + what + " (it took more than one request)"
                : "THE LAPTOP DID NOT WAKE after " + what + " although Sleep Guard asked GooglebookOS three times", true);
    }

    /**
     * A keep-awake lock (CPU only, no display); null if it couldn't be taken. level: the sleep lock
     * (survives GooglebookOS's lid-closed force-disable, but refuses every wake-up while held: lid
     * closed only) or the ordinary PARTIAL lock (lid open).
     */
    IBinder acquireLock(String tag, int level) {
        try {
            IBinder b = new Binder();
            // acquireWakeLock(IBinder lock, int flags, String tag, String packageName, WorkSource ws,
            //                 String historyTag, int displayId, IWakeLockCallback callback)
            // The flags must be exactly the level (the power service rejects extra flags for the sleep lock).
            acquireWakeLock.invoke(power, b, level, tag, "com.android.shell", null, null, -1, null);
            return b;
        } catch (Exception e) {
            log("wakelock " + tag + " FAILED: " + e.getCause(), false); // no disk sync under grabLock
            return null;
        }
    }

    void releaseLock(IBinder b) {
        if (b == null) return;
        try {
            releaseWakeLock.invoke(power, b, 0);
        } catch (Exception e) {
            log("wakelock release FAILED: " + e.getCause(), false); // no disk sync under grabLock
        }
    }

    /** windowOver: release only if the protection window has really ended (checked under the lock). */
    void releaseWake(String why, boolean windowOver) {
        IBinder b;
        synchronized (grabLock) {
            if (windowOver && SystemClock.uptimeMillis() < protectUntil) return; // a fresh hold: keep it
            b = wakeLock;
            wakeLock = null;
            if (b != null) sleepLockReleasedAt = SystemClock.uptimeMillis();
        }
        if (b == null) return;
        releaseLock(b);
        if (why != null) log("hold over: " + why, false);
    }

    void releaseSettle(String why) {
        IBinder b;
        synchronized (grabLock) {
            b = settleLock;
            settleLock = null;
            if (b != null && settleLevel == PARTIAL_SLEEP_WAKE_LOCK) sleepLockReleasedAt = SystemClock.uptimeMillis();
        }
        if (b == null) return;
        releaseLock(b);
        log(why == null ? "settle done, wakelock released" : "settle lock released: " + why, true);
    }

    /** Everything handed back (stand-down, exit). */
    void releaseAll(String why) {
        protectEnd.set(0);
        protectUntil = 0;
        releaseWake(why, false);
        releaseSettle(null);
    }

    boolean interactive() {
        try {
            return (Boolean) isInteractive.invoke(power);
        } catch (Exception e) {
            if (!servicesAlive()) retryLater("GooglebookOS's system services restarted");
            return screenOnShared; // a passing failure: keep the last reading
        }
    }

    static long sleepOffset() {
        return SystemClock.elapsedRealtime() - SystemClock.uptimeMillis(); // grows by the time spent in deep sleep
    }

    // ---------------------------------------------------------------- lid
    /**
     * The shell can't read input device names from sysfs/procfs, but getevent can (via ioctl).
     * Returns the device, null if there's no lid switch, UNKNOWN if getevent gave no answer.
     */
    static String findLidDevice() {
        return findDevice("Lid Switch");
    }

    /** The input device with this exact name; null if there is none, UNKNOWN if getevent gave no answer. */
    static String findDevice(String name) {
        String out = exec("/system/bin/getevent", "-lp");
        if (!out.contains("add device")) return UNKNOWN;
        java.util.regex.Pattern add = java.util.regex.Pattern.compile("add device \\d+: (/dev/input/event\\d+)");
        String dev = null;
        for (String line : out.split("\n")) {
            java.util.regex.Matcher m = add.matcher(line);
            if (m.find()) dev = m.group(1);
            else if (dev != null && line.trim().equals("name:     \"" + name + "\"")) return dev;
        }
        return null;
    }

    /** Every input device that has keys or buttons, as "path\tname" lines; UNKNOWN if getevent gave no answer. */
    static java.util.List<String> keyDevices() {
        String out = exec("/system/bin/getevent", "-lp");
        java.util.List<String> r = new java.util.ArrayList<>();
        if (!out.contains("add device")) { r.add(UNKNOWN); return r; }
        java.util.regex.Pattern add = java.util.regex.Pattern.compile("add device \\d+: (/dev/input/event\\d+)");
        String dev = null, name = "?";
        boolean keys = false, touch = false;
        for (String line : (out + "\nadd device 0: /dev/input/event99999").split("\n")) { // the sentinel flushes the last device
            java.util.regex.Matcher m = add.matcher(line);
            if (m.find()) {
                // Not the firmware's pseudo devices (the ACPI "Video Bus" and the audio jacks report display and
                // jack changes on their own; a wake from them would not be the user's), and not touch devices
                // (the trackpad and the touchscreen cannot be used with the lid closed, and they would stream events).
                if (dev != null && keys && !touch && !name.equals("Video Bus") && !name.contains("Jack") && !name.contains("HDMI"))
                    r.add(dev + "\t" + name);
                dev = m.group(1); name = "?"; keys = false; touch = false;
            } else if (dev != null && line.trim().startsWith("name:")) {
                name = line.trim().substring(5).trim().replace("\"", "");
            } else if (dev != null && line.trim().startsWith("KEY (0001):")) {
                keys = true;
            } else if (dev != null && line.trim().startsWith("ABS (0003):")) {
                touch = true;
            }
        }
        return r;
    }

    final java.util.Set<String> watchedDevices = java.util.concurrent.ConcurrentHashMap.newKeySet();
    final java.util.concurrent.atomic.AtomicBoolean scanning = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Starts a key reader for every key or button device not yet watched (the power buttons, the
     * keyboard, a docked keyboard or mouse). Run at start and again, in the background, whenever the
     * lid closes or a lid-closed settle starts, so a device plugged in later is covered too.
     */
    void watchKeyDevices(boolean announce) {
        if (!scanning.compareAndSet(false, true)) return;
        try {
            java.util.List<String> devs = keyDevices();
            if (devs.size() == 1 && UNKNOWN.equals(devs.get(0))) {
                if (announce) log("the input device list is unavailable: only the lid switch is watched for now", true);
                return;
            }
            java.util.List<String> added = new java.util.ArrayList<>();
            for (String d : devs) {
                String path = d.substring(0, d.indexOf('\t')), name = d.substring(d.indexOf('\t') + 1);
                if (!watchedDevices.add(path)) continue;
                added.add(name);
                Thread t = new Thread(() -> keyReader(path, name), "keys-" + path.substring(path.lastIndexOf('/') + 1));
                t.setDaemon(true);
                t.start();
            }
            if (announce || !added.isEmpty()) log("watching for key presses on: " + (added.isEmpty() ? "(no new device)" : added), true);
        } finally {
            scanning.set(false);
        }
    }

    /**
     * Watches one key or button device: a press while the sleep lock is held (the laptop asleep) must
     * release it and wake the laptop, because GooglebookOS's own wake for the press is refused while
     * the lock is held. Only reads; gives up after 5 read failures in a row (the lid safety net stays).
     */
    void keyReader(String dev, String name) {
        byte[] ev = new byte[24];
        int failures = 0;
        long lastMotion = 0; // mouse motion comes in bursts: at most one check per 200 ms per device
        while (!exiting) {
            try (FileInputStream in = new FileInputStream(dev)) {
                while (true) {
                    int off = 0;
                    while (off < 24) {
                        int n = in.read(ev, off, 24 - off);
                        if (n < 0) throw new IOException("EOF");
                        off += n;
                    }
                    failures = 0;
                    ByteBuffer bb = ByteBuffer.wrap(ev).order(ByteOrder.LITTLE_ENDIAN);
                    int type = bb.getShort(16) & 0xffff, code = bb.getShort(18) & 0xffff, value = bb.getInt(20);
                    if (type == 1 /* EV_KEY */ && value == 1) {
                        // Docked (lid closed, external display): GooglebookOS wakes on keys, so repeat a refused
                        // wake there too. With the lid simply closed it does not wake on keys, and neither do we.
                        if (code == KEY_POWER) userWoke("the power button was pressed", WAKE_REASON_POWER_BUTTON, !docked);
                        else userWoke("a key was pressed (" + name + ")", WAKE_REASON_WAKE_KEY, !docked);
                    } else if (type == 2 /* EV_REL: a mouse moved */) {
                        long t = SystemClock.uptimeMillis();
                        if (t - lastMotion >= 200) {
                            lastMotion = t;
                            userWoke("the mouse moved (" + name + ")", WAKE_REASON_WAKE_KEY, true);
                        }
                    }
                }
            } catch (Throwable e) { // never let this thread die quietly
                log("key reader (" + name + ", " + dev + ") error: " + e + (failures + 1 >= 5 ? " (giving up on this device)" : " (retrying)"), false);
                if (++failures >= 5) { watchedDevices.remove(dev); return; } // a removed device: re-added if it comes back
                SystemClock.sleep(2000);
            }
        }
    }

    void lidReader(String dev) {
        byte[] ev = new byte[24]; // struct input_event on 64-bit: timeval(16) type(2) code(2) value(4)
        int failures = 0;
        while (true) {
            try (FileInputStream in = new FileInputStream(dev)) {
                while (true) {
                    int off = 0;
                    while (off < 24) {
                        int n = in.read(ev, off, 24 - off);
                        if (n < 0) throw new IOException("EOF");
                        off += n;
                    }
                    failures = 0;
                    ByteBuffer bb = ByteBuffer.wrap(ev).order(ByteOrder.LITTLE_ENDIAN);
                    int type = bb.getShort(16) & 0xffff, code = bb.getShort(18) & 0xffff, value = bb.getInt(20);
                    if (type == 0 /* EV_SYN */ && code == 3 /* SYN_DROPPED */) {
                        lidResync = true; // events were lost: the main loop re-reads the lid state
                        synchronized (wake) { wake.notifyAll(); }
                    }
                    if (type == 5 /* EV_SW */ && code == 0 /* SW_LID */) {
                        lidChangedAt = SystemClock.uptimeMillis();
                        lidClosed = value != 0;
                        if (lidClosed) {
                            holdsThisClose.set(0);
                            log("lid closed", true);
                        } else {
                            userWoke("the lid opened", WAKE_REASON_LID, false); // before the log and its disk sync
                            log("lid opened (" + holdsThisClose.get() + " wake holds while it was closed)", true);
                        }
                        synchronized (wake) { wake.notifyAll(); }
                    }
                }
            } catch (Throwable e) { // never let this thread die quietly: without it the lid state would freeze
                log("lid reader error: " + e + " (retrying)", false);
                lidResync = true; // whatever happened while it was closed is re-read
                if (++failures >= 5) {
                    lidReaderFailed = true; // the main loop exits; the supervisor starts a fresh helper
                    synchronized (wake) { wake.notifyAll(); }
                    return;
                }
                SystemClock.sleep(2000);
                lidResync = true; // again: the lid may have moved during these 2 s, with nobody listening
                String again = findLidDevice(); // the device may have been re-created under another name
                if (again != null && !UNKNOWN.equals(again) && !again.equals(dev)) {
                    log("lid switch device moved to " + again, true);
                    dev = again;
                }
            }
        }
    }

    /**
     * Notices a wake from deep sleep within ~5 ms (it only reads two clocks) while the lid is
     * closed; otherwise it checks every 100 ms.
     */
    void wakeDetector() {
        long last = sleepOffset();
        while (!exiting) {
            // 5 ms whenever the lid is closed (also while protecting: a deep sleep can start right
            // after a window ends, and the rest of a longer sleep would run only after the next wake).
            // Not while the screen is on (e.g. docked with the lid closed): the laptop is in use then.
            SystemClock.sleep(started && lidClosed && !screenOnShared ? DETECT_MS : 100);
            long off = sleepOffset();
            try {
                if (off - last > 20 && started && !exiting && lidClosed && wakeLock == null && settleLock == null) {
                    startGrab("woke from deep sleep with the lid closed");
                }
            } catch (Throwable t) { // never let the detector die quietly: without it there are no wake holds
                log("wake detector error: " + t, true);
                SystemClock.sleep(1000);
            }
            last = off;
        }
    }

    // ---------------------------------------------------------------- main loop
    void run() throws Exception {
        String dev = null, problem = null, printStates = "";
        for (int attempt = 1; ; attempt++) {
            try {
                dev = findLidDevice();
                printStates = exec("cmd", "device_state", "print-states");
                problem = selfTest(dev, printStates);
                break;
            } catch (Retry r) {
                if (attempt >= 3) retryLater(r.getMessage());
                SystemClock.sleep(10_000); // e.g. right after boot, when everything is busy
            }
        }
        if (problem != null) standDown(problem, null);
        final String lidDev = dev;
        String fp = fingerprint();
        boolean verifiedBuild = fp.equals(readFile(VERIFIED));
        dockedId = dockedStateId(printStates);

        // The starting point from the report (synchronous).
        int base = -1;
        for (int i = 0; i < 5 && base == -1; i++) {
            base = baseState();
            if (base == -1) SystemClock.sleep(1000);
        }
        if (base == -1) retryLater("the lid state is unknown right now");
        Thread t = new Thread(() -> lidReader(lidDev), "lid");
        t.setDaemon(true);
        t.start();
        // Every key and button device (power buttons, keyboard, a docked keyboard or mouse): a press while
        // the sleep lock is held releases it and wakes the laptop.
        watchKeyDevices(true);
        if (lidChangedAt == 0) lidClosed = base == STATE_CLOSED || base == dockedId;
        docked = dockedId != -1 && base == dockedId;
        lidResync = true; // the first pass re-reads it: the lid may have moved before the reader was listening
        Boolean c = chargerConnected();
        if (c == null) retryLater("the battery report is unavailable right now");
        boolean charger = c;
        log("start: build " + (verifiedBuild ? "verified" : "NOT yet verified (will verify at the first lid-closed hold)")
                + ", lid=" + (lidClosed ? "closed" : "open") + ", state seen by GooglebookOS=" + base
                + ", charger=" + charger + ", dev=" + lidDev + ", key devices watched=" + watchedDevices.size()
                + ", keep-awake: sleep lock with the lid closed, ordinary wakelock with the lid open, wake safety net", true);

        Thread wd = new Thread(() -> {
            while (true) {
                boolean holding = wakeLock != null || settleLock != null;
                SystemClock.sleep(holding ? 2000 : 10_000);
                // (30 s while holding: one slow pass may spend several 3 s report deadlines on a sluggish resume)
                // uptimeMillis doesn't advance in deep sleep, so sleep never looks like a stall. While
                // a lock is held a stall is called much sooner (the lock would keep the laptop awake).
                if (SystemClock.uptimeMillis() - heartbeat > (holding ? 30_000 : 60_000)) {
                    // Exit for sure within 3 s even if a call below blocks (the system also drops our
                    // locks when this process dies).
                    background("halt", () -> { SystemClock.sleep(3000); Runtime.getRuntime().halt(EXIT_WATCHDOG); });
                    log("WATCHDOG: main loop stalled: releasing everything and exiting", true);
                    exiting = true;
                    protectUntil = 0;
                    releaseLock(settleLock);
                    releaseLock(wakeLock);
                    Runtime.getRuntime().halt(EXIT_WATCHDOG);
                }
            }
        }, "watchdog");
        wd.setDaemon(true);
        wd.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            exiting = true;
            if (servicesAlive()) releaseAll("exit"); // dead services: the system drops everything anyway
        }));
        started = true;
        startupDone = true;
        Thread det = new Thread(this::wakeDetector, "wake-detector");
        det.setDaemon(true);
        det.start();

        boolean prevCharger = charger;
        boolean prevLid = lidClosed;
        boolean screenOn = interactive();
        long lastPollAt = SystemClock.uptimeMillis(), chargerUnknownSince = 0, lastLidBackstop = lastPollAt;
        boolean prevScreenOn = screenOn;
        long windowCheckAt = 0;     // live check pending in the current lid-closed window
        int checkTries = 0;
        boolean checkFailedOnce = false;
        long lastOffset = sleepOffset();
        int lastAdbPort = adbWifiPort();           // Wireless debugging's port (see adbWifiPort)
        long adbCheckAt = SystemClock.uptimeMillis() + 30_000, adbFastUntil = 0;
        int kickTries = 0;
        // The port may have moved while no helper was running: let the app publish it (it skips the write
        // when the address is unchanged).
        if (lastAdbPort > 0) kickApp(lastAdbPort, "Sleep Guard started");
        ArrayDeque<Long> settleTimes = new ArrayDeque<>(); // charger changes (real time) for the settle budget
        boolean flapLogged = false;
        while (true) {
            if (exiting) { // shutting down: never act again
                SystemClock.sleep(1000);
                continue;
            }
            long now = SystemClock.uptimeMillis();
            heartbeat = now;
            if (!servicesAlive()) retryLater("GooglebookOS's system services restarted");
            long offset = sleepOffset();
            if (offset - lastOffset > 50) { // just woke up from deep sleep
                fastUntil = Math.max(fastUntil, now + FAST_MS);
                if (!lidClosed) {
                    // Wi-Fi re-joins after a wake with the lid open too, and Wireless debugging then
                    // moves to a new port: look for it often for a while.
                    adbFastUntil = now + 120_000;
                    adbCheckAt = Math.min(adbCheckAt, now + 3000);
                } else if (wakeLock != null || settleLock != null) {
                    // The laptop slept although a lock was held (it should not; logged by the live
                    // check): give the charger poll a full hold after this wake anyway.
                    extendProtection(now + GRAB_MS);
                }
            }
            lastOffset = offset;
            final boolean lid = lidClosed;              // one snapshot per pass
            final boolean lidEvent = lid != prevLid;
            if (lidEvent) fastUntil = Math.max(fastUntil, now + FAST_MS);
            if (lidEvent && lid) background("input-scan", () -> watchKeyDevices(false)); // a docked keyboard or mouse plugged in since
            final boolean fast = now < fastUntil;
            final boolean grabbing = now < protectUntil && settleLock == null && wakeLock != null;
            if (now - lastPollAt >= (grabbing ? POLL_GRAB_MS : fast ? POLL_FAST_MS : screenOn ? POLL_IN_USE_MS : POLL_MS)) {
                lastPollAt = now;
                Boolean cc = chargerConnected();
                if (cc != null) {
                    charger = cc;
                    chargerUnknownSince = 0;
                } else if (chargerUnknownSince == 0) {
                    chargerUnknownSince = now;
                } else if (now - chargerUnknownSince > 60_000) {
                    retryLater("the battery report has been unavailable for a minute");
                }
                prevScreenOn = screenOn;
                screenOn = interactive();
                screenOnShared = screenOn;
                now = SystemClock.uptimeMillis();
            }

            if (lidReaderFailed) retryLater("the lid switch could not be read (a fresh helper looks for it again)");
            if (lockFailure != null) {
                if (!servicesAlive()) retryLater("GooglebookOS's system services restarted");
                standDown(lockFailure, this);
            }
            // A backstop once a minute while the lid reads closed and nothing is held: a lid state stuck at
            // "closed" with the lid really open would bring back wake holds that refuse the user's wake-ups.
            if (lidClosed && wakeLock == null && settleLock == null && now - lastLidBackstop >= 60_000) {
                lastLidBackstop = now;
                lidResync = true;
            }
            if (lidResync) {
                // Events may have been lost: GooglebookOS's own lid state is the truth.
                lidResync = false;
                long before = lidChangedAt;
                int b = baseState();
                if (b != -1 && lidChangedAt == before) { // a real lid event during the report wins
                    boolean closed = b == STATE_CLOSED || b == dockedId;
                    docked = dockedId != -1 && b == dockedId;
                    if (closed != lidClosed) {
                        lidChangedAt = SystemClock.uptimeMillis();
                        lidClosed = closed;
                        log("lid state re-read: " + (closed ? "closed" : "open"), true);
                        if (!closed) fixSettleLidOpen();
                    }
                }
            }

            if (charger != prevCharger && settleBudgetExceeded(settleTimes)) {
                // A charger that keeps connecting and disconnecting (a loose plug, a failing cable or
                // dock) would otherwise keep the laptop awake without end: back to stock until it calms down.
                if (!flapLogged) log("the charger keeps connecting and disconnecting (" + settleTimes.size() + " changes in "
                        + (SETTLE_BUDGET_WINDOW_MS / 60_000) + " minutes): no keep-awake settles until it is steady again", true);
                flapLogged = true;
                prevCharger = charger;
            } else if (charger != prevCharger && screenOn && prevScreenOn && (screenOn = interactive())) {
                // The laptop is in use (screen on since before this change, lid open or docked): it cannot fall
                // asleep, so there is nothing to protect and Sleep Guard stays out of the way entirely (no lock,
                // nothing held). Asked again live: the poll runs only every 2 s while the screen is on. A screen
                // that just turned on (the plug itself may have lit it) does not count as in use: the display
                // can time out within the 20 s, so that change gets its settle (the ordinary lock, lid open).
                flapLogged = false;
                log("charger " + (charger ? "CONNECTED" : "DISCONNECTED") + " (screen on, the laptop is in use): nothing to do", true);
                prevCharger = charger;
            } else if (charger != prevCharger) {
                screenOnShared = screenOn;
                flapLogged = false;
                // The lock first, logs after.
                extendProtection(now + SETTLE_MS);
                fastUntil = Math.max(fastUntil, now + FAST_MS);
                settleUntil = now + SETTLE_MS;
                IBinder got;
                synchronized (grabLock) {
                    if (settleLock == null) {
                        // The sleep lock only with the lid closed (it refuses every wake-up while held); with
                        // the lid open the ordinary wakelock works and never gets in the user's way. The lid is
                        // read again here: the snapshot above is older than this pass's service calls.
                        settleLevel = lidClosed ? PARTIAL_SLEEP_WAKE_LOCK : PARTIAL_WAKE_LOCK;
                        settleLock = acquireLock("SleepGuard:settle", settleLevel);
                        lockSince = SystemClock.uptimeMillis();
                    }
                    got = settleLock;
                }
                fixSettleLidOpen(); // the lid opened during the acquire
                if (got == null) {
                    if (!servicesAlive()) retryLater("GooglebookOS's system services restarted");
                    standDown("the keep-awake lock for the 20-second settle was refused", this);
                }
                final int level = settleLevel;
                log("charger " + (charger ? "CONNECTED" : "DISCONNECTED") + " (lid " + (lidClosed ? "closed" : "open")
                        + ", screen " + (screenOn ? "on" : "off") + "): keeping the laptop awake " + (SETTLE_MS / 1000) + " s ("
                        + (level == PARTIAL_SLEEP_WAKE_LOCK ? "sleep lock" : "ordinary wakelock") + ")", true);
                if (settleLevel == PARTIAL_SLEEP_WAKE_LOCK) background("input-scan", () -> watchKeyDevices(false));
                prevCharger = charger;
            }
            boolean swapRefused = false, swapped = false;
            if (lidEvent && lid && settleLock != null && settleLevel == PARTIAL_WAKE_LOCK && now < settleUntil) {
                // The lid closed during a settle: GooglebookOS now force-disables ordinary wakelocks, so the
                // rest of the settle needs the sleep lock (the lid is closed: it cannot block the user).
                synchronized (grabLock) {
                    if (settleLock != null && settleLevel == PARTIAL_WAKE_LOCK && lidClosed) { // the lid read live
                        IBinder old = settleLock;
                        IBinder b = acquireLock("SleepGuard:settle", PARTIAL_SLEEP_WAKE_LOCK);
                        if (b != null) {
                            settleLevel = PARTIAL_SLEEP_WAKE_LOCK;
                            settleLock = b;
                            lockSince = SystemClock.uptimeMillis();
                            releaseLock(old);
                            windowCheckAt = 0; // a fresh lid-closed lock: live check it
                            swapped = true;
                        } else {
                            // GooglebookOS has force-disabled the ordinary lock by now: it holds nothing. Refusing
                            // the sleep lock with the lid closed means the protection cannot work (same policy as
                            // the settle acquire): stand down, below, outside grabLock.
                            settleLock = null;
                            releaseLock(old);
                            swapRefused = true;
                        }
                    }
                }
                if (swapped) log("lid closed during a settle: the sleep lock takes over for the rest of it", true);
                if (swapRefused) {
                    if (!servicesAlive()) retryLater("GooglebookOS's system services restarted");
                    standDown("the keep-awake lock for the rest of a settle was refused when the lid closed", this);
                }
                fixSettleLidOpen(); // the lid opened again during the swap
            }
            fixSettleLidOpen(); // also covers a lid state re-read that found the lid open

            final boolean protecting = now < protectUntil;
            if (!protecting && wakeLock != null) releaseWake("normal lid-closed sleep", true);
            if (lidEvent && !lid && wakeLock != null) releaseWake("lid opened", false);
            if (settleLock != null && now >= settleUntil) releaseSettle(null);

            // Live check once per lid-closed window: GooglebookOS must really keep the laptop awake
            // for our lock (every settle, and the first wake hold on a not yet verified build).
            final boolean holding = settleLock != null || wakeLock != null;
            if (holding && lid && windowCheckAt == 0 && (settleLock != null || !verifiedBuild)
                    && !(wakeLock == null && settleLevel == PARTIAL_WAKE_LOCK)) {
                windowCheckAt = Math.max(now, lockSince + CHECK_AFTER_MS);
                checkTries = 0;
                checkFailedOnce = false;
            }
            if (!holding) windowCheckAt = 0;
            // The live check is about the sleep lock with the lid closed only: not after the lid opened, and
            // never against the ordinary lock.
            if (windowCheckAt != 0 && windowCheckAt != -1 && (!lid || (wakeLock == null && settleLevel == PARTIAL_WAKE_LOCK))) windowCheckAt = -1;
            if (windowCheckAt != 0 && windowCheckAt != -1 && now >= windowCheckAt) {
                String tag = settleLock != null ? "SleepGuard:settle" : wakeLock != null ? "SleepGuard:wake" : null;
                String p = tag == null ? null : lockProblem(tag);
                if (tag == null) {
                    windowCheckAt = -1; // nothing held to check (window ending)
                } else if (p != null && p.isEmpty()) {
                    windowCheckAt = ++checkTries >= 3 ? -1 : now + 500; // report unavailable: retry
                } else if (p != null && !checkFailedOnce) {
                    checkFailedOnce = true; // look again shortly before concluding anything
                    windowCheckAt = now + 400;
                    extendProtection(now + 600);
                } else if (p != null) {
                    boolean stillHeld;
                    synchronized (grabLock) { // a user action may have released the lock during the report
                        stillHeld = lidClosed && (wakeLock != null || (settleLock != null && settleLevel == PARTIAL_SLEEP_WAKE_LOCK));
                    }
                    if (!stillHeld) windowCheckAt = -1;
                    else standDown(p + " with the lid closed (the protection can't work)", this);
                } else {
                    windowCheckAt = -1; // done for this window
                    boolean stillSleepLock;
                    synchronized (grabLock) { // the lid may have opened during the report: then nothing was proven
                        stillSleepLock = lidClosed && (wakeLock != null || (settleLock != null && settleLevel == PARTIAL_SLEEP_WAKE_LOCK));
                    }
                    if (stillSleepLock) log("live check OK: lid closed, GooglebookOS keeps the laptop awake for Sleep Guard's lock", true);
                    if (stillSleepLock && !verifiedBuild) {
                        verifiedBuild = true;
                        writeFile(VERIFIED, fp + "\n");
                        log("VERIFIED on this GooglebookOS build: " + fp, true);
                        alert("Sleep Guard verified", "Sleep Guard checked itself on this GooglebookOS build and everything works.");
                    }
                }
            }

            if (lidEvent && !lid) {
                adbFastUntil = now + 120_000; // Wi-Fi reconnects after the lid opens: look more often for a while
                adbCheckAt = Math.min(adbCheckAt, now + 3000);
            }
            if (now >= adbCheckAt && !grabbing) { // never inside a wake hold (its release must not wait)
                adbCheckAt = now + (now < adbFastUntil ? 5000 : 30_000);
                int port = adbWifiPort();
                if (port > 0 && port != lastAdbPort) {
                    kickTries = 0;
                    kickApp(port, "Wireless debugging moved to port " + port);
                } else if (port > 0 && port == kickFailedPort && kickTries < 10) {
                    kickTries++;
                    kickApp(port, "Wireless debugging is on port " + port + ", try " + (kickTries + 1));
                }
                if (port >= 0) lastAdbPort = port;
            }
            prevLid = lid;

            synchronized (wake) {
                // Anything that arrived since the checks above is handled right away.
                if (lidClosed == prevLid && !lidResync && !lidReaderFailed) {
                    wake.wait(now < protectUntil && settleLock == null ? 10 : PASS_MS); // short passes during a wake hold
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        FMT.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
        try {
            Os.umask(077);
        } catch (Exception ignored) { }
        new java.io.File(DIR).mkdirs();
        if (args.length > 0 && args[0].equals("repair")) {
            // Run by the supervisor after every helper exit. Older versions held a lid-state override
            // that had to be undone; this version holds none, so there is nothing to repair.
            System.out.println("nothing to repair (this Sleep Guard version never changes the lid state)");
            System.exit(0);
        }
        if (args.length > 0 && args[0].equals("dumptest")) {
            // Proves the service-report reader can never block a service (run under `timeout`).
            SleepGuard g = new SleepGuard();
            long worst = 0;
            int ok = 0;
            for (int i = 0; i < 20; i++) {
                long t0 = SystemClock.uptimeMillis();
                String d = g.dump("power");
                long ms = SystemClock.uptimeMillis() - t0;
                worst = Math.max(worst, ms);
                if (d.contains("Wake Locks:")) ok++;
            }
            System.out.println("20 full power reports: " + ok + " complete, slowest " + worst + " ms");
            long t1 = SystemClock.uptimeMillis();
            String capped = g.dump("power", 3000, 1024);   // reader gives up after 1 KB (stops early)
            String timed = g.dump("power", 1, DUMP_MAX_BYTES); // reader gives up after 1 ms
            long t2 = SystemClock.uptimeMillis();
            System.out.println("early-stop tests: cap=" + (capped.isEmpty() ? "gave up as designed" : "UNEXPECTED")
                    + ", deadline=" + (timed.isEmpty() ? "gave up as designed" : "completed fast")
                    + " (" + (t2 - t1) + " ms)");
            long t3 = SystemClock.uptimeMillis();
            String after = g.dump("power");
            long t4 = SystemClock.uptimeMillis();
            System.out.println("power service right after the early stops: "
                    + (after.contains("Wake Locks:") ? "responds normally" : "NO RESPONSE") + " (" + (t4 - t3) + " ms)");
            String other = exec("dumpsys", "power");
            System.out.println("independent dumpsys power: " + (other.contains("Wake Locks:") ? "responds normally" : "NO RESPONSE"));
            return;
        }
        if (args.length > 0 && args[0].equals("locktest")) {
            // The sleep lock: taken, listed as active by the power service, the kernel suspend blocker
            // held, released, gone.
            SleepGuard g = new SleepGuard();
            IBinder o = g.acquireLock("SleepGuard:ordinary", PARTIAL_WAKE_LOCK);
            SystemClock.sleep(300);
            String od = g.dump("power");
            System.out.println("ordinary wakelock taken: " + (o != null) + ", power service: "
                    + (od.contains("SleepGuard:ordinary") ? od.contains("SleepGuard:ordinary' DISABLED") ? "listed, DISABLED" : "listed, active" : "NOT listed"));
            g.releaseLock(o);
            IBinder l = g.acquireLock("SleepGuard:settle", PARTIAL_SLEEP_WAKE_LOCK);
            SystemClock.sleep(300);
            String p = g.lockProblem("SleepGuard:settle");
            String d = g.dump("power");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("PowerManagerService\\.WakeLocks: ref count=(\\d+)").matcher(d);
            String blocker = m.find() ? m.group(1) : "?";
            java.util.regex.Matcher s = java.util.regex.Pattern.compile("mWakeLockSummary=0x[0-9a-f]+").matcher(d);
            System.out.println("sleep lock taken: " + (l != null) + ", power service: " + (p == null ? "listed, active" : p.isEmpty() ? "report unavailable" : p)
                    + ", kernel suspend blocker ref count=" + blocker + (s.find() ? ", " + s.group() : ""));
            g.releaseLock(l);
            SystemClock.sleep(300);
            d = g.dump("power");
            int i = d.indexOf("Wake Locks:");
            int j = i < 0 ? -1 : d.indexOf("Suspend Blockers:", i);
            m = java.util.regex.Pattern.compile("PowerManagerService\\.WakeLocks: ref count=(\\d+)").matcher(d);
            System.out.println("after release: " + (i < 0 || j < 0 ? "unknown" : d.substring(i, j).contains("SleepGuard:") ? "STILL HELD" : "not held")
                    + ", kernel suspend blocker ref count=" + (m.find() ? m.group(1) : "?"));
            return;
        }
        if (args.length > 0 && args[0].equals("waketest")) {
            // The wake-up call the safety net relies on (a no-op while the screen is on): it must be
            // accepted by GooglebookOS (permission DEVICE_POWER, four-argument signature).
            SleepGuard g = new SleepGuard();
            try {
                g.wakeUp.invoke(g.power, SystemClock.uptimeMillis(), WAKE_REASON_LID, "SleepGuard:waketest", "com.android.shell");
                System.out.println("wake-up call accepted; screen " + (g.interactive() ? "on" : "off"));
            } catch (Exception e) {
                System.out.println("wake-up call FAILED: " + e.getCause());
            }
            return;
        }
        if (args.length > 0 && args[0].equals("check")) {
            SleepGuard g = new SleepGuard();
            String dev = findLidDevice();
            String p;
            try {
                p = g.selfTest(dev, exec("cmd", "device_state", "print-states"));
            } catch (Retry r) {
                p = "unavailable right now (" + r.getMessage() + ")";
            }
            System.out.println("self-test: " + (p == null ? "OK" : "FAIL: " + p) + "; lid device=" + dev
                    + " base state=" + g.baseState() + " charger=" + g.chargerConnected()
                    + " wireless debugging port=" + g.adbWifiPort()
                    + " screen=" + (g.interactive() ? "on" : "off") + " key devices=" + keyDevices()
                    + " keep-awake=sleep lock with the lid closed or the ordinary wakelock with the lid open, wake safety net build=" + fingerprint()
                    + (fingerprint().equals(readFile(VERIFIED)) ? " (verified)" : " (not yet verified)"));
            return;
        }
        // One helper at a time: a lock file in the shell-only directory (no app can hold it).
        try {
            heldLock = new java.io.RandomAccessFile(DIR + "/helper.lock", "rw").getChannel().tryLock();
        } catch (IOException e) {
            heldLock = null;
        }
        if (heldLock == null) {
            System.out.println("another Sleep Guard helper is already running");
            System.exit(EXIT_OTHER_RUNNING); // the supervisor adopts the running one (not a failure)
        }
        echoStdout = false; // the log file has everything; stdout (helper-stdout.log) keeps only crashes
        // Startup guard: the calls into GooglebookOS's services during startup have no deadline of their
        // own, and the main loop's watchdog only starts afterwards. A startup that hangs ends here (the
        // supervisor tries again in 30 s). 3 minutes allows for a busy boot (the retries inside run()).
        background("startup-guard", () -> {
            SystemClock.sleep(180_000);
            if (!startupDone && !exiting) {
                exiting = true;
                log("startup did not finish within 3 minutes: giving up for now (retried later)", true);
                Runtime.getRuntime().halt(EXIT_RETRY);
            }
        });
        SleepGuard g = null;
        try {
            g = new SleepGuard();
        } catch (Retry e) {
            retryLater(e.getMessage());
        } catch (Throwable e) {
            standDown("a GooglebookOS interface it uses changed (" + e + ")", null);
        }
        try {
            g.run();
        } catch (Throwable e) {
            if (exiting) return;
            exiting = true;
            log("FATAL: " + e, true);
            System.exit(3);
        }
    }
}
