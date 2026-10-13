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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Sleep Guard helper for the Lenovo Googlebook 15 (GooglebookOS). The laptop sleeps exactly as
 * designed; Sleep Guard only stops it from diving straight back into deep sleep while the USB-C
 * charger is changing.
 *
 * Background (2026-10-10 investigation): a charger plug/unplug while the laptop is in lid-closed
 * deep sleep collides with the USB-C power controller's work; the EC once logged "Detected sleep
 * hang!" and twice hard-reset (cutting all power). With the lid closed, GooglebookOS's
 * DesktopPowerService force-disables every wakelock, so after a charger wake the laptop re-enters
 * S0ix within ~60 ms while the USB-C controller is still busy.
 *
 * How GooglebookOS decides "lid closed": device-state callbacks get a MERGED state - identifier and
 * system properties from the committed state, PHYSICAL properties from the BASE state (lid switch,
 * displays). DesktopPowerService force-disables wakelocks on the physical "closed" property (101).
 * A base-state override of OPEN makes it re-enable them at once. GooglebookOS cancels such an
 * override by itself whenever the physical state changes to something else.
 *
 * Behaviour (chosen on 2026-10-10: stock lid-closed sleep, "as designed"):
 *  - Closing the lid: stock. Deep sleep at once (glow bar off), charger or not. No override.
 *  - Each wake from deep sleep with the lid closed (a charger change wakes the laptop): within ~5 ms
 *    Sleep Guard applies the lid-state override (wakelocks work again) and holds a wakelock for
 *    GRAB_MS while it checks the charger. Changed -> 20 s settle; otherwise straight back to sleep.
 *  - A charger change while awake: a 20 s settle (with the lid closed: also the lid-state override).
 *  - When a protection window ends: lid-state override released (-> stock CLOSED), then the locks.
 *  - Lid closed with an external display (DOCKED) or a tablet state: left to GooglebookOS.
 *
 * Safety / update-proofing:
 *  - The override and wakelocks belong to this process. If it dies GooglebookOS drops them - but it
 *    does NOT restore the real lid state for a dropped base override, so every start, and the
 *    supervisor after every exit ("repair"), puts the real lid state back.
 *  - Startup self-test of everything it relies on (incl. the physical properties of each state);
 *    a changed interface -> STAND DOWN, recorded for this GooglebookOS build, user alerted.
 *    Something only temporarily unavailable -> exit EXIT_RETRY instead.
 *  - Live checks: the override must be accepted (activation notice) and stay ours (cancellation
 *    notice); after it's active, a "lid closed" state means it failed; 1.5 s into each lid-closed
 *    protection window the power service must not be force-disabling wakelocks and our lock must be
 *    active. Failure -> stand down. The first success on a GooglebookOS build is announced.
 *  - Never calls System.exit while holding a lock; rate limits on wakes and override requests.
 *  - Watchdog: a stalled main loop releases everything and exits (the supervisor restarts it).
 *  - Reads of service reports never block a service (see dump()); every command has a time limit;
 *    service calls every 500 ms (every 100 ms for 3 s after a wake, lid or charger change); the
 *    5 ms wake detector only reads two clocks and runs only while the laptop can deep sleep.
 *  - Single instance (lock file in the shell-only directory). Never deletes anything; never
 *    injects input.
 */
public final class SleepGuard {
    static final int STATE_CLOSED = 0, STATE_OPEN = 2;
    static final int PROP_PHYSICAL_CLOSED = 101; // what DesktopPowerService checks (verified at startup)
    // A wake hold is kept very short (the EC lights the glow bar if the laptop stays awake longer;
    // stock wakes are a fraction of a second): the charger change showed up 87 ms after the wake in
    // a live test on 2026-10-10. Only a real charger change keeps the laptop awake (SETTLE_MS).
    static final long SETTLE_MS = 20_000, GRAB_MS = 300, CHECK_AFTER_MS = 150;
    static final long PASS_MS = 40, POLL_MS = 500, POLL_FAST_MS = 100, POLL_GRAB_MS = 25, FAST_MS = 3000, DETECT_MS = 5;
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

    static synchronized void log(String s, boolean sync) {
        String line = FMT.format(new Date()) + " " + s + "\n";
        System.out.print(line);
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
        } catch (IOException ignored) { }
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
     * never while holding protLock or grabLock (the shutdown hook needs them).
     */
    static void standDown(String reason, SleepGuard g) {
        if (exiting) return;
        exiting = true;
        log("STANDING DOWN: " + reason, true);
        if (g != null) g.releaseAll("standing down");
        writeFile(STANDDOWN, "STANDING DOWN: " + reason + " | build=" + fingerprint() + "\n");
        if (g != null) g.heartbeat = SystemClock.uptimeMillis(); // the notification wait isn't a stall
        Thread a = alert("Sleep Guard stood down",
                "Sleep Guard switched itself off safely, and the laptop now behaves exactly like stock. Reason: " + reason
                        + ". After GooglebookOS updates this is expected if something it relies on changed. It will try "
                        + "again after the next update, or you can run 'sleep-guard start' in the Linux terminal.");
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
    final IBinder powerBinder, deviceStateBinder; // checked every pass: dead = system services restarted
    final Object power;            // IPowerManager
    final Method isInteractive, acquireWakeLock, releaseWakeLock, goToSleep;
    final Object dsm;              // DeviceStateManagerGlobal
    final Method registerStateCallback, requestBaseOverride, cancelBaseOverride, getSupportedStates;
    final Method requestState, cancelState;
    final Method getStateId, getConfiguration, getPhysicalProperties;
    final Method newRequestBuilder;
    final Object requestCallback;  // DeviceStateRequest.Callback: our request activated / cancelled
    final Object stateCallback;    // DeviceStateManager.DeviceStateCallback: merged state changes
    final java.util.concurrent.Executor direct = Runnable::run;
    final Object wake = new Object();
    final Object grabLock = new Object();
    final java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
    /** Physical property set of each supported state -> its identifier (to read what GooglebookOS sees). */
    final Map<Set<Integer>, Integer> physicalStates = new HashMap<>();
    int dockedId = -1;

    // Shared with the binder callback threads, the wake detector, the lid thread, the watchdog and
    // the shutdown hook.
    volatile boolean started;            // the main loop runs (before that, notices only record state)
    volatile Object baseRequest;         // our lid-state (base) request (a fresh object each time:
                                         // GooglebookOS matches requests by identity)
    volatile boolean baseActive, baseLost, baseArmed;
    // The committed-state hold of CLOSED: while the lid-state override makes the PHYSICAL state open
    // (so wakelocks work), this keeps the COMMITTED state closed - GooglebookOS's display service
    // wakes the screen only when the committed state gains "trigger wake" (OPEN has it), so the
    // screen and glow bar stay off. Dropped the instant the lid opens (by the lid reader itself).
    volatile Object holdRequest;
    volatile boolean holdActive, holdLost;
    volatile long baseSince;
    volatile String baseFailure;         // a lid-state request failed (handled by the main loop)
    volatile boolean closedDespiteOverride;
    volatile String closedReason = "";
    volatile int committedState = -1;    // identifier of the latest merged state
    volatile int physState = -1;         // the state GooglebookOS sees physically
    volatile long protectUntil = 0;      // uptime until which a protection window (grab/settle) runs
    volatile long fastUntil = 0;         // uptime until which the main loop polls fast
    volatile IBinder settleLock, wakeLock;
    volatile long settleUntil = 0;
    volatile boolean lidClosed;
    volatile long lidChangedAt = 0;
    volatile boolean lidReaderFailed;
    volatile long heartbeat = SystemClock.uptimeMillis();
    volatile boolean lidResync;          // the lid reader may have missed events (SYN_DROPPED / reopened)
    volatile String rateProblem;         // too many lid-state requests (handled by the main loop)

    /** ReflectiveOperationException etc. = an interface changed (stand down); Retry = not available yet. */
    SleepGuard() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        getService = sm.getMethod("getService", String.class);
        powerBinder = (IBinder) getService.invoke(null, "power");
        deviceStateBinder = (IBinder) getService.invoke(null, "device_state");
        if (powerBinder == null || deviceStateBinder == null) throw new Retry("GooglebookOS's system services aren't available yet");
        power = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, powerBinder);
        isInteractive = method(power.getClass(), "isInteractive", 0);
        acquireWakeLock = method(power.getClass(), "acquireWakeLock", 8);
        releaseWakeLock = method(power.getClass(), "releaseWakeLock", 2);
        goToSleep = method(power.getClass(), "goToSleep", 3);
        Class<?> g = Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal");
        dsm = g.getMethod("getInstance").invoke(null);
        if (dsm == null) throw new Retry("the device state service isn't available yet");
        registerStateCallback = method(g, "registerDeviceStateCallback", 2);
        requestState = method(g, "requestState", 3);          // the committed-state hold (flags 0)
        cancelState = method(g, "cancelStateRequest", 0);
        requestBaseOverride = method(g, "requestBaseStateOverride", 3);
        cancelBaseOverride = method(g, "cancelBaseStateOverride", 0);
        getSupportedStates = method(g, "getSupportedDeviceStates", 0);
        Class<?> dsClass = Class.forName("android.hardware.devicestate.DeviceState");
        getStateId = dsClass.getMethod("getIdentifier");
        getConfiguration = dsClass.getMethod("getConfiguration");
        getPhysicalProperties = Class.forName("android.hardware.devicestate.DeviceState$Configuration").getMethod("getPhysicalProperties");
        Class<?> rc = Class.forName("android.hardware.devicestate.DeviceStateRequest");
        newRequestBuilder = rc.getMethod("newBuilder", int.class);
        Class<?> cb = Class.forName("android.hardware.devicestate.DeviceStateRequest$Callback");
        requestCallback = java.lang.reflect.Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb}, (proxy, m, a) -> {
            Object r = a != null && a.length == 1 ? a[0] : null;
            switch (m.getName()) {
                case "onRequestActivated":
                    callbacks.incrementAndGet();
                    // Posted after the state the request caused: from now on "closed" is a real failure.
                    if (r != null && r == baseRequest) baseArmed = true;
                    return null;
                case "onRequestCanceled":
                    callbacks.incrementAndGet();
                    if (r != null && (r == baseRequest || r == holdRequest)) {
                        if (r == baseRequest) baseLost = true;
                        else holdLost = true;
                        synchronized (wake) { wake.notifyAll(); }
                    }
                    return null;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return r != null && proxy == r;
                case "toString": return "SleepGuard.requestCallback";
                default: return null;
            }
        });
        Class<?> scb = Class.forName("android.hardware.devicestate.DeviceStateManager$DeviceStateCallback");
        stateCallback = java.lang.reflect.Proxy.newProxyInstance(scb.getClassLoader(), new Class<?>[]{scb}, (proxy, m, a) -> {
            switch (m.getName()) {
                case "onDeviceStateChanged":
                    if (a != null && a.length == 1 && a[0] != null) onMergedState(a[0]);
                    return null;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return a != null && a.length == 1 && proxy == a[0];
                case "toString": return "SleepGuard.stateCallback";
                default: return null;
            }
        });
    }

    /** A merged state from GooglebookOS (binder thread; notices arrive in order). */
    void onMergedState(Object state) throws Exception {
        int id = (Integer) getStateId.invoke(state);
        int phys = physicalStateOf(state);
        int prev = physState;
        committedState = id;
        physState = phys;
        // After GooglebookOS confirmed our lid-state override (armed) and didn't cancel it, "lid
        // closed" means it doesn't work.
        if (phys == STATE_CLOSED && baseRequest != null && baseArmed && !baseLost) {
            closedReason = "GooglebookOS still sees the lid as closed although Sleep Guard's lid-state override was active";
            closedDespiteOverride = true;
        }
        if (started && !exiting && phys == STATE_CLOSED) {
            if (dockedId != -1 && prev == dockedId) {
                // External display (often a USB-C dock that also charges) unplugged with the lid closed.
                startGrab("external display unplugged with the lid closed");
            } else if (SystemClock.uptimeMillis() < protectUntil) {
                // The lid closed during a protection window: keep wakelocks working right away.
                protect("lid closed during a protection window");
            }
        }
        synchronized (wake) { wake.notifyAll(); }
    }

    int physicalStateOf(Object deviceState) {
        try {
            Set<?> props = (Set<?>) getPhysicalProperties.invoke(getConfiguration.invoke(deviceState));
            Set<Integer> key = new HashSet<>();
            for (Object o : props) key.add((Integer) o);
            Integer id = physicalStates.get(key);
            return id == null ? -1 : id;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Reads each supported state's physical properties. Returns a problem, or null. */
    String loadPhysicalStates() throws Retry {
        try {
            List<?> states = (List<?>) getSupportedStates.invoke(dsm);
            if (states == null || states.isEmpty()) throw new Retry("the device state list is unavailable right now");
            physicalStates.clear();
            Boolean closedHas = null, openHas = null;
            for (Object st : states) {
                int id = (Integer) getStateId.invoke(st);
                Set<Integer> key = new HashSet<>();
                for (Object o : (Set<?>) getPhysicalProperties.invoke(getConfiguration.invoke(st))) key.add((Integer) o);
                if (key.isEmpty() || physicalStates.containsKey(key)) return "GooglebookOS's physical state properties changed";
                physicalStates.put(key, id);
                if (id == STATE_CLOSED) closedHas = key.contains(PROP_PHYSICAL_CLOSED);
                if (id == STATE_OPEN) openHas = key.contains(PROP_PHYSICAL_CLOSED);
            }
            if (closedHas == null || openHas == null || !closedHas || openHas)
                return "GooglebookOS's physical lid property changed (" + PROP_PHYSICAL_CLOSED + ")";
            return null;
        } catch (Retry r) {
            throw r;
        } catch (Exception e) {
            if (!servicesAlive()) throw new Retry("GooglebookOS's system services restarted");
            return "the device state list can't be read (" + e + ")";
        }
    }

    Object newOpenRequest() throws Exception {
        return newRequest(STATE_OPEN);
    }

    Object newRequest(int state) throws Exception {
        Object b = newRequestBuilder.invoke(null, state);
        return b.getClass().getMethod("build").invoke(b);
    }

    static Method method(Class<?> c, String name, int argc) throws NoSuchMethodException {
        for (Method m : c.getMethods())
            if (m.getName().equals(name) && m.getParameterCount() == argc) return m;
        throw new NoSuchMethodException(c.getSimpleName() + "." + name + " with " + argc + " parameters");
    }

    boolean servicesAlive() {
        return powerBinder.isBinderAlive() && deviceStateBinder.isBinderAlive();
    }

    /**
     * Puts the real lid state back. GooglebookOS restores it when WE cancel a lid-state override,
     * but not when it drops one because its owner died - then it would keep seeing a closed lid as open.
     */
    boolean repairLidState() {
        try {
            cancelBaseOverride.invoke(dsm); // sets the base state to the lid's real state (no-op if already)
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static final long DUMP_DEADLINE_MS = 3000;
    static final int DUMP_MAX_BYTES = 1_000_000;

    /**
     * A service's dump text, built so it can NEVER block the service (design rule: a system service
     * must never be left waiting on this process):
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
     * Live check from the power service's report while one of our wakelocks is held: GooglebookOS
     * must not be force-disabling wakelocks, and ours must be listed and not DISABLED. Returns null
     * if fine, "" if the report is unavailable, else what's wrong.
     */
    String lockProblem(String tag) {
        String d = dump("power");
        if (d.isEmpty() || !d.contains("Wake Locks:")) return ""; // never act on a missing report
        if (d.contains("mGlobalForceDisableWakelocks=true")) return "GooglebookOS is force-disabling all keep-awake locks";
        for (String line : d.split("\n")) {
            if (line.contains("'" + tag + "'")) return line.contains("DISABLED") ? "Sleep Guard's keep-awake lock is disabled by GooglebookOS" : null;
        }
        return "Sleep Guard's keep-awake lock is not listed by the power service";
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
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Wireless debugging's current port from GooglebookOS's adb report (~5 KB): 0 = off, -1 = unknown.
     * After Wi-Fi drops in deep sleep GooglebookOS restarts it by itself on a NEW port without
     * changing any setting (seen twice in live tests on 2026-10-11), so nothing tells the Sleep Guard app.
     */
    int adbWifiPort() {
        String d = dump("adb");
        if (!d.contains("adb_wifi")) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("tls_port=(\\d+)").matcher(d);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Asks the Sleep Guard app to run a check now (it finds the new port and publishes it). */
    static void kickApp(String why) {
        background("kick-app", () -> {
            String user = exec("am", "get-current-user").trim();
            if (!user.matches("\\d+")) user = "10";
            exec("am", "broadcast", "--user", user, "-n", "local.sleepguard/.ControlReceiver", "-a", "local.sleepguard.KICK");
            log("asked the Sleep Guard app to publish the new address (" + why + ")", false);
        });
    }

    /** The physical (base) state from the device state report (synchronous); -1 = unknown. */
    int baseState() {
        return stateFrom(dump("device_state"), "mBaseState");
    }

    static final String UNKNOWN = "?";

    /** Identifier of the DOCKED state (lid closed + external display), -1 if this build has none. */
    static int dockedStateId(String printStates) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("identifier=(\\d+), name='DOCKED'").matcher(printStates);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
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
        if (stateFrom(ds, "mCommittedState") == -1 || stateFrom(ds, "mBaseState") == -1)
            return "device state report format changed";
        return loadPhysicalStates();
    }

    // ---------------------------------------------------------------- actions
    // Protection = the committed-state hold of CLOSED (screen and glow bar stay off) + the lid-state
    // (base) override of OPEN (GooglebookOS's power service then keeps wakelocks working). Both are
    // changed only together, under protLock, in a fixed order: on = hold, then override; off =
    // override, then hold; lid opened = hold, then override (the committed state follows the open
    // lid at once). Nothing logs between the two steps. Never exits the process while holding the
    // lock - failures are flagged for the main loop.

    final Object protLock = new Object();
    final ArrayDeque<Long> baseRequestTimes = new ArrayDeque<>(); // realtime, under protLock
    volatile String releaseFailure;      // a release failed: exit so GooglebookOS drops everything (+ repair)

    private void holdOnLocked() throws Exception {
        if (holdActive) return;
        Object req = newRequest(STATE_CLOSED);
        holdLost = false;
        holdRequest = req;
        requestState.invoke(dsm, req, direct, requestCallback);
        holdActive = true;
    }

    private void holdOffLocked() throws Exception {
        if (!holdActive) return;
        boolean alreadyGone = holdLost; // replaced by someone else: cancelling would cancel THEIR request
        holdRequest = null;
        holdLost = false;
        holdActive = false;
        if (!alreadyGone) cancelState.invoke(dsm);
    }

    private void baseOnLocked() throws Exception {
        if (baseActive) return;
        long real = SystemClock.elapsedRealtime();
        while (!baseRequestTimes.isEmpty() && real - baseRequestTimes.peekFirst() > 3_600_000) baseRequestTimes.pollFirst();
        if (baseRequestTimes.size() >= 1000) { // one every 3.6 s for an hour: something is looping
            rateProblem = "too many lid-state override requests (" + baseRequestTimes.size() + " in an hour)";
            return;
        }
        baseRequestTimes.addLast(real);
        Object req = newOpenRequest();
        baseLost = false;
        baseArmed = false;
        baseSince = SystemClock.uptimeMillis();
        baseRequest = req;
        requestBaseOverride.invoke(dsm, req, direct, requestCallback);
        baseActive = true;
    }

    private void baseOffLocked() throws Exception {
        if (!baseActive) return;
        boolean alreadyGone = baseLost;
        baseRequest = null;
        baseLost = false;
        baseActive = false;
        if (!alreadyGone) cancelBaseOverride.invoke(dsm); // also restores the real lid state
    }

    /**
     * Lid closed: keep wakelocks working without any visible change. Only while GooglebookOS has the
     * lid physically closed AND has already committed CLOSED: holding a state that is already CLOSED
     * changes nothing on screen (at a lid opening the committed state turns OPEN first - holding CLOSED
     * then made GooglebookOS put the just-woken laptop back to sleep in a live test on 2026-10-10).
     */
    boolean protect(String why) {
        String failure = null;
        boolean did;
        synchronized (protLock) {
            if (exiting) return false;
            if (holdActive && baseActive) return true;
            if (!holdActive && (!lidClosed || physState != STATE_CLOSED || committedState != STATE_CLOSED)) return false;
            try {
                holdOnLocked();
                if (!lidClosed) { // the lid opened meanwhile: undo at once
                    holdOffLocked();
                    return false;
                }
                baseOnLocked();
            } catch (Exception e) {
                failure = "protection was refused (" + e.getCause() + ")";
            }
            did = holdActive && baseActive;
        }
        if (failure != null) {
            baseFailure = failure;
            log("protect FAILED: " + failure, true);
            synchronized (wake) { wake.notifyAll(); }
            return false;
        }
        if (did) log("protecting (screen held off, wakelocks working): " + why, false);
        return did;
    }

    /** Hand back to stock: the override first (wakelocks off again), then the hold (no visible change). */
    void unprotect(String why) {
        String failure = null;
        boolean had;
        synchronized (protLock) {
            had = holdActive || baseActive;
            try {
                baseOffLocked();
            } catch (Exception e) {
                failure = "the lid-state override could not be released (" + e.getCause() + ")"; // keep the hold
            }
            if (failure == null) {
                try {
                    holdOffLocked();
                } catch (Exception e) {
                    failure = "the committed-state hold could not be released (" + e.getCause() + ")";
                }
            }
        }
        if (failure != null) flagReleaseFailure(failure);
        else if (had) log("protection released: " + why, false);
    }

    /** The lid opened: the hold first (the committed state follows the open lid), then the override. */
    void releaseForLidOpen() {
        String failure = null;
        boolean had;
        synchronized (protLock) {
            had = holdActive || baseActive;
            try {
                holdOffLocked();
            } catch (Exception e) {
                failure = "the committed-state hold could not be released at a lid opening (" + e.getCause() + ")";
            }
            try {
                baseOffLocked();
            } catch (Exception e) {
                if (failure == null) failure = "the lid-state override could not be released at a lid opening (" + e.getCause() + ")";
            }
        }
        if (failure != null) flagReleaseFailure(failure);
        else if (had) log("protection released: lid opened", false);
    }

    void flagReleaseFailure(String failure) {
        releaseFailure = failure;
        log("RELEASE FAILED: " + failure, true);
        synchronized (wake) { wake.notifyAll(); }
    }

    // Wake holds: never skip one; keep them short when there are many (an awake-time budget, real time).
    static final long HOLD_BUDGET_WINDOW_MS = 15 * 60_000, HOLD_BUDGET_MS = 15_000, GRAB_SHORT_MS = 150;
    final ArrayDeque<long[]> holdHistory = new ArrayDeque<>(); // {realtime start, length ms}, under grabLock
    final java.util.concurrent.atomic.AtomicLong protectEnd = new java.util.concurrent.atomic.AtomicLong();

    /** Protection runs at least until this uptime (never shortened by a concurrent caller). */
    void extendProtection(long untilUptime) {
        protectUntil = protectEnd.accumulateAndGet(untilUptime, Math::max);
    }

    /**
     * The laptop woke from deep sleep with the lid closed (or a dock was unplugged): keep it awake
     * briefly so a charger change can be noticed. From the wake detector / a notice; never exits.
     * Order: the keep-awake lock first (it waits, disabled, until the override re-enables wakelocks),
     * then the protection; logs only after.
     */
    void startGrab(String why) {
        if (exiting || !lidClosed || physState != STATE_CLOSED) return; // e.g. the wake was the lid opening
        long len;
        long real = SystemClock.elapsedRealtime();
        synchronized (grabLock) {
            long used = 0;
            while (!holdHistory.isEmpty() && real - holdHistory.peekFirst()[0] > HOLD_BUDGET_WINDOW_MS) holdHistory.pollFirst();
            for (long[] h : holdHistory) used += h[1];
            len = used >= HOLD_BUDGET_MS ? GRAB_SHORT_MS : GRAB_MS; // many wakes: still check, just shorter
            holdHistory.addLast(new long[]{real, len});
            if (wakeLock == null) wakeLock = acquireLock("SleepGuard:wake");
        }
        long now = SystemClock.uptimeMillis();
        extendProtection(now + len);
        fastUntil = Math.max(fastUntil, now + FAST_MS);
        protect(why);
        synchronized (wake) { wake.notifyAll(); }
    }

    /** A partial wakelock (keeps the CPU awake) without a display; null if it couldn't be taken. */
    IBinder acquireLock(String tag) {
        try {
            IBinder b = new Binder();
            // acquireWakeLock(IBinder lock, int flags, String tag, String packageName, WorkSource ws,
            //                 String historyTag, int displayId, IWakeLockCallback callback)
            acquireWakeLock.invoke(power, b, 1 /* PARTIAL_WAKE_LOCK */, tag, "com.android.shell", null, null, -1, null);
            return b;
        } catch (Exception e) {
            log("wakelock " + tag + " FAILED: " + e.getCause(), true);
            return null;
        }
    }

    void releaseLock(IBinder b) {
        if (b == null) return;
        try {
            releaseWakeLock.invoke(power, b, 0);
        } catch (Exception e) {
            log("wakelock release FAILED: " + e.getCause(), true);
        }
    }

    void releaseWake() {
        IBinder b;
        synchronized (grabLock) {
            b = wakeLock;
            wakeLock = null;
        }
        releaseLock(b);
    }

    void releaseSettle() {
        IBinder b = settleLock;
        if (b == null) return;
        settleLock = null;
        releaseLock(b);
        log("settle done, wakelock released", true);
    }

    /** Everything handed back: lid state first, then the locks (stand-down, exit). */
    void releaseAll(String why) {
        protectEnd.set(0);
        protectUntil = 0;
        if (!lidClosed) releaseForLidOpen(); // lid open: the committed state follows the lid first
        else unprotect(why);
        releaseWake();
        releaseSettle();
    }

    void sleepNow(String why) {
        try {
            goToSleep.invoke(power, SystemClock.uptimeMillis(), 3 /* GO_TO_SLEEP_REASON_LID_SWITCH */, 0);
            log("turned the screen off and sent the laptop back to sleep (" + why + ")", true);
        } catch (Exception e) {
            log("goToSleep FAILED: " + e.getCause(), true);
            if (!servicesAlive()) retryLater("GooglebookOS's system services restarted");
        }
    }

    /** Screen on? For other threads: no side effects (true if unknown = "don't hold"). */
    boolean interactiveNow() {
        try {
            return (Boolean) isInteractive.invoke(power);
        } catch (Exception e) {
            return true;
        }
    }

    boolean interactive() {
        try {
            return (Boolean) isInteractive.invoke(power);
        } catch (Exception e) {
            if (!servicesAlive()) retryLater("GooglebookOS's system services restarted");
            return true;
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
        String out = exec("/system/bin/getevent", "-lp");
        if (!out.contains("add device")) return UNKNOWN;
        java.util.regex.Pattern add = java.util.regex.Pattern.compile("add device \\d+: (/dev/input/event\\d+)");
        String dev = null;
        for (String line : out.split("\n")) {
            java.util.regex.Matcher m = add.matcher(line);
            if (m.find()) dev = m.group(1);
            else if (dev != null && line.trim().equals("name:     \"Lid Switch\"")) return dev;
        }
        return null;
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
                        // Lid opened: drop the protection right here, so the screen comes on normally.
                        if (!lidClosed) releaseForLidOpen();
                        log("lid " + (lidClosed ? "closed" : "opened"), true);
                        synchronized (wake) { wake.notifyAll(); }
                    }
                }
            } catch (IOException e) {
                log("lid reader error: " + e + " (retrying)", false);
                lidResync = true; // whatever happened while it was closed is re-read
                if (++failures >= 5) {
                    lidReaderFailed = true; // the main loop stands down
                    synchronized (wake) { wake.notifyAll(); }
                    return;
                }
                SystemClock.sleep(2000);
            }
        }
    }

    /**
     * Notices a wake from deep sleep within ~5 ms (it only reads two clocks), while the laptop is
     * in GooglebookOS's lid-closed state - otherwise it checks every 100 ms.
     */
    void wakeDetector() {
        long last = sleepOffset();
        while (!exiting) {
            // 5 ms whenever the lid is closed (also while protecting: a deep sleep can start right
            // after a window ends, and the rest of a longer sleep would run only after the next wake).
            SystemClock.sleep(started && lidClosed ? DETECT_MS : 100);
            long off = sleepOffset();
            if (off - last > 20 && started && !exiting && lidClosed && physState == STATE_CLOSED && !baseActive) {
                SystemClock.sleep(15); // a lid opening shows up within this (our lid reader, GooglebookOS, screen on)
                if (lidClosed && physState == STATE_CLOSED && !baseActive && !interactiveNow()) {
                    startGrab("woke from deep sleep with the lid closed");
                }
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

        // A helper that died during a protection window may have left GooglebookOS seeing the lid open.
        if (!repairLidState()) {
            if (!servicesAlive()) retryLater("GooglebookOS's system services restarted");
            standDown("the real lid state couldn't be restored", null);
        }
        // The starting point from the report (synchronous, already reflects the repair).
        int base = -1;
        for (int i = 0; i < 5 && base == -1; i++) {
            base = baseState();
            if (base == -1) SystemClock.sleep(1000);
        }
        if (base == -1) retryLater("the lid state is unknown right now");
        Thread t = new Thread(() -> lidReader(lidDev), "lid");
        t.setDaemon(true);
        t.start();
        registerStateCallback.invoke(dsm, stateCallback, direct); // replays a (possibly older) state
        for (int i = 0; i < 20 && physState != base; i++) SystemClock.sleep(50); // let notices catch up
        if (lidChangedAt == 0) lidClosed = base == STATE_CLOSED || base == dockedId;
        Boolean c = chargerConnected();
        if (c == null) retryLater("the battery report is unavailable right now");
        boolean charger = c;
        log("start: build " + (verifiedBuild ? "verified" : "NOT yet verified (will verify at the first lid-closed protection)")
                + ", lid=" + (lidClosed ? "closed" : "open") + ", state seen by GooglebookOS=" + base
                + ", charger=" + charger + ", dev=" + lidDev, true);

        Thread wd = new Thread(() -> {
            while (true) {
                SystemClock.sleep(10_000);
                // uptimeMillis doesn't advance in deep sleep, so sleep never looks like a stall.
                if (SystemClock.uptimeMillis() - heartbeat > 60_000) {
                    // Exit for sure within 3 s even if a call below blocks (the system also drops our
                    // override and wakelocks when this process dies; the supervisor repairs the lid state).
                    background("halt", () -> { SystemClock.sleep(3000); Runtime.getRuntime().halt(EXIT_WATCHDOG); });
                    log("WATCHDOG: the main loop stalled, so the helper releases everything and exits", true);
                    exiting = true;
                    protectUntil = 0;
                    try { cancelBaseOverride.invoke(dsm); } catch (Exception ignored) { } // restores the real lid state
                    if (holdRequest != null && !holdLost) try { cancelState.invoke(dsm); } catch (Exception ignored) { }
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
        Thread det = new Thread(this::wakeDetector, "wake-detector");
        det.setDaemon(true);
        det.start();

        boolean prevCharger = charger;
        boolean prevLid = lidClosed;
        boolean screenOn = interactive();
        long lastSleepRequest = 0, screenOnSince = 0;
        long lastPollAt = SystemClock.uptimeMillis(), chargerUnknownSince = 0;
        long windowCheckAt = 0;     // live check pending in the current lid-closed protection window
        int checkTries = 0;
        boolean checkFailedOnce = false;
        long lastOffset = sleepOffset();
        int lastAdbPort = adbWifiPort();           // Wireless debugging's port (see adbWifiPort)
        long adbCheckAt = SystemClock.uptimeMillis() + 30_000, adbFastUntil = 0;
        ArrayDeque<Long> sleepTimes = new ArrayDeque<>();
        ArrayDeque<Long> lostTimes = new ArrayDeque<>();
        while (true) {
            if (exiting) { // shutting down (e.g. SIGTERM): never act again
                SystemClock.sleep(1000);
                continue;
            }
            long now = SystemClock.uptimeMillis();
            heartbeat = now;
            if (!servicesAlive()) retryLater("GooglebookOS's system services restarted");
            long offset = sleepOffset();
            if (offset - lastOffset > 50) fastUntil = Math.max(fastUntil, now + FAST_MS); // just woke up
            lastOffset = offset;
            final boolean lid = lidClosed;              // one snapshot per pass
            final boolean lidEvent = lid != prevLid;
            if (lidEvent) fastUntil = Math.max(fastUntil, now + FAST_MS);
            final boolean fast = now < fastUntil;
            final boolean grabbing = now < protectUntil && settleLock == null && wakeLock != null;
            if (now - lastPollAt >= (grabbing ? POLL_GRAB_MS : fast ? POLL_FAST_MS : POLL_MS)) {
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
                screenOn = interactive();
                now = SystemClock.uptimeMillis();
            }

            if (lidReaderFailed) standDown("the lid switch can't be read any more", this);
            if (baseFailure != null) {
                if (!servicesAlive()) retryLater("GooglebookOS's system services restarted");
                standDown(baseFailure, this);
            }
            if (rateProblem != null) standDown(rateProblem, this);
            if (releaseFailure != null) retryLater(releaseFailure + ", so the helper restarts (GooglebookOS drops everything, and the lid state is repaired)");
            if (closedDespiteOverride) standDown(closedReason, this);
            if ((baseLost && baseActive) || (holdLost && holdActive)) {
                // GooglebookOS dropped part of our protection - normally because the physical state
                // changed (e.g. an external display was connected). Hand the rest back too; it's renewed
                // only when GooglebookOS next reports the lid closed (the new state may be DOCKED).
                boolean wasHold = holdLost;
                unprotect(wasHold ? "the committed-state hold was replaced" : "GooglebookOS dropped the lid-state override");
                long real = SystemClock.elapsedRealtime();
                while (!lostTimes.isEmpty() && real - lostTimes.peekFirst() > 3_600_000) lostTimes.pollFirst();
                lostTimes.addLast(real);
                if (lostTimes.size() > 12) standDown("GooglebookOS keeps dropping Sleep Guard's protection", this);
            }
            if (lidResync && !baseActive) {
                // Events may have been lost: GooglebookOS's own (unoverridden) lid state is the truth.
                lidResync = false;
                int b = physState;
                if (b != -1) {
                    boolean closed = b == STATE_CLOSED || b == dockedId;
                    if (closed != lidClosed) {
                        lidChangedAt = SystemClock.uptimeMillis();
                        lidClosed = closed;
                        log("lid state re-read: " + (closed ? "closed" : "open"), true);
                        if (!closed) releaseForLidOpen();
                    }
                }
            }

            // What GooglebookOS sees: lid closed (stock), OPEN, or something to leave alone (DOCKED, TABLET).
            final int phys = physState;
            final boolean handsOff = phys != -1 && phys != STATE_OPEN && phys != STATE_CLOSED;

            if (charger != prevCharger) {
                // Protection first: with the lid closed the lid-state override (what makes wakelocks
                // work), then the settle lock; logs after.
                extendProtection(now + SETTLE_MS);
                fastUntil = Math.max(fastUntil, now + FAST_MS);
                if (lid && phys == STATE_CLOSED) protect("charger change with the lid closed");
                if (settleLock == null) settleLock = acquireLock("SleepGuard:settle");
                if (settleLock == null) {
                    if (!servicesAlive()) retryLater("GooglebookOS's system services restarted");
                    standDown("the keep-awake lock for the 20-second settle was refused", this);
                }
                settleUntil = now + SETTLE_MS;
                log("charger " + (charger ? "CONNECTED" : "DISCONNECTED") + " (lid " + (lid ? "closed" : "open")
                        + ", screen " + (screenOn ? "on" : "off") + "): keeping the laptop awake " + (SETTLE_MS / 1000) + " s", true);
                prevCharger = charger;
            }

            final boolean protecting = now < protectUntil;
            if (protecting && lid && phys == STATE_CLOSED && !baseActive) {
                // E.g. a notice came before the window: GooglebookOS sees the lid closed - keep wakelocks working.
                protect("lid closed during a protection window");
            }
            if ((baseActive || holdActive) && (!protecting || handsOff) && lid) {
                unprotect(handsOff ? "external display / tablet: GooglebookOS handles it"
                        : "protection window over: normal lid-closed sleep");
            }
            if (!lid && (baseActive || holdActive)) releaseForLidOpen();
            // Locks only after the lid state is handed back.
            if (!protecting && wakeLock != null) releaseWake();
            if (lidEvent && !lid && wakeLock != null) releaseWake();
            if (settleLock != null && now >= settleUntil) releaseSettle();

            // Live check once per lid-closed protection window: GooglebookOS must keep wakelocks working.
            if (baseActive && baseArmed && windowCheckAt == 0 && (settleLock != null || !verifiedBuild)) {
                windowCheckAt = Math.max(now, baseSince + CHECK_AFTER_MS);
                checkTries = 0;
                checkFailedOnce = false;
            }
            if (!baseActive) windowCheckAt = 0;
            if (windowCheckAt != 0 && windowCheckAt != -1 && now >= windowCheckAt) {
                String tag = settleLock != null ? "SleepGuard:settle" : wakeLock != null ? "SleepGuard:wake" : null;
                String p = tag == null ? null : lockProblem(tag);
                if (tag == null) {
                    windowCheckAt = -1; // nothing held to check (window ending)
                } else if (p != null && p.isEmpty()) {
                    windowCheckAt = ++checkTries >= 3 ? -1 : now + 500; // report unavailable: retry
                } else if (p != null && !checkFailedOnce) {
                    checkFailedOnce = true; // e.g. GooglebookOS still re-enabling: look again shortly
                    windowCheckAt = now + 400;
                    extendProtection(now + 600);
                } else if (p != null) {
                    standDown(p + " with the lid closed (the protection can't work)", this);
                } else {
                    windowCheckAt = -1; // done for this window
                    log("live check OK: lid closed, GooglebookOS keeps wakelocks working", true);
                    if (!verifiedBuild) {
                        verifiedBuild = true;
                        writeFile(VERIFIED, fp + "\n");
                        log("VERIFIED on this GooglebookOS build: " + fp, true);
                        alert("Sleep Guard verified", "Sleep Guard checked itself on this GooglebookOS build and everything works.");
                    }
                }
            }

            // During a lid-closed protection window the screen must stay off. At a lid open the screen
            // wakes a moment before our lid reader sees it, so only a screen that has been on for
            // 0.5 s with the lid STILL closed (live value) is turned off again.
            if (!(lid && screenOn && baseActive)) screenOnSince = 0;
            else if (screenOnSince == 0) screenOnSince = now;
            if (baseActive && lid && screenOn && screenOnSince != 0 && now - screenOnSince >= 500 && lidClosed
                    && now - lidChangedAt > 1000 && now - lastSleepRequest > 1500) {
                long real = SystemClock.elapsedRealtime();
                while (!sleepTimes.isEmpty() && real - sleepTimes.peekFirst() > 60_000) sleepTimes.pollFirst();
                if (sleepTimes.size() >= 6) standDown("the screen kept turning on with the lid closed", this);
                sleepNow("screen came on with the lid closed");
                lastSleepRequest = now;
                sleepTimes.addLast(real);
                screenOn = interactive();
            }
            if (lidEvent && !lid) {
                adbFastUntil = now + 120_000; // Wi-Fi reconnects after the lid opens: look more often for a while
                adbCheckAt = Math.min(adbCheckAt, now + 3000);
            }
            if (now >= adbCheckAt) {
                adbCheckAt = now + (now < adbFastUntil ? 5000 : 30_000);
                int port = adbWifiPort();
                if (port > 0 && port != lastAdbPort) kickApp("Wireless debugging moved to port " + port);
                if (port >= 0) lastAdbPort = port;
            }
            prevLid = lid;

            synchronized (wake) {
                // Anything that arrived since the checks above is handled right away.
                if (lidClosed == prevLid && !(baseLost && baseActive) && !(holdLost && holdActive) && baseFailure == null
                        && rateProblem == null && releaseFailure == null && !lidResync
                        && !closedDespiteOverride && !lidReaderFailed) {
                    wake.wait(now < protectUntil && settleLock == null ? 10 : PASS_MS); // short passes during a wake hold
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        FMT.setTimeZone(TimeZone.getDefault()); // log times in the device's own time zone
        try {
            Os.umask(077);
        } catch (Exception ignored) { }
        new java.io.File(DIR).mkdirs();
        if (args.length > 0 && args[0].equals("repair")) {
            // Run by the supervisor after every helper exit: puts the real lid state back.
            SleepGuard g = new SleepGuard();
            boolean ok = g.repairLidState();
            System.out.println(ok ? "lid state repaired" : "lid state repair FAILED");
            System.exit(ok ? 0 : 1);
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
            // A keep-awake lock: taken, listed as active (not disabled) by the power service, released.
            SleepGuard g = new SleepGuard();
            IBinder l = g.acquireLock("SleepGuard:settle");
            SystemClock.sleep(300);
            String p = g.lockProblem("SleepGuard:settle");
            System.out.println("lock taken: " + (l != null) + ", power service: " + (p == null ? "listed, active" : p.isEmpty() ? "report unavailable" : p));
            g.releaseLock(l);
            SystemClock.sleep(300);
            String d = g.dump("power");
            int i = d.indexOf("Wake Locks:");
            int j = i < 0 ? -1 : d.indexOf("Suspend Blockers:", i);
            System.out.println("after release: " + (i < 0 || j < 0 ? "unknown" : d.substring(i, j).contains("SleepGuard:") ? "STILL HELD" : "not held"));
            return;
        }
        if (args.length > 0 && args[0].equals("basetest")) {
            // Lid open: a lid-state override of OPEN changes nothing visible. Proves the physical states
            // are understood, the request is accepted (activation notice), a replacement by another
            // requester is reported, a re-request works, no false alarms - and exits WITHOUT
            // cancelling, so the caller can confirm "repair" afterwards.
            SleepGuard g = new SleepGuard();
            String p = g.loadPhysicalStates();
            System.out.println("physical states: " + (p == null ? "understood " + g.physicalStates : "PROBLEM: " + p));
            g.registerStateCallback.invoke(g.dsm, g.stateCallback, g.direct);
            SystemClock.sleep(200);
            System.out.println("merged state: id=" + g.committedState + ", seen physically as " + g.physState);
            if (g.physState != STATE_OPEN) { System.out.println("the lid is not open, and this test only runs with the lid open"); return; }
            synchronized (g.protLock) { g.baseOnLocked(); } // the raw override (protect() never acts with the lid open)
            long t0 = SystemClock.uptimeMillis();
            while (!g.baseArmed && SystemClock.uptimeMillis() - t0 < 3000) SystemClock.sleep(10);
            System.out.println("lid-state override accepted (activation notice): " + g.baseArmed + " (" + (SystemClock.uptimeMillis() - t0) + " ms)");
            exec("cmd", "device_state", "base-state", String.valueOf(STATE_OPEN)); // another requester
            t0 = SystemClock.uptimeMillis();
            while (!g.baseLost && SystemClock.uptimeMillis() - t0 < 3000) SystemClock.sleep(10);
            System.out.println("replacement reported: " + g.baseLost);
            exec("cmd", "device_state", "base-state", "reset");
            g.baseActive = false;
            g.baseRequest = null;
            synchronized (g.protLock) { g.baseOnLocked(); }
            t0 = SystemClock.uptimeMillis();
            while (!g.baseArmed && SystemClock.uptimeMillis() - t0 < 3000) SystemClock.sleep(10);
            System.out.println("re-request accepted: " + g.baseArmed + "; false alarms: "
                    + (g.closedDespiteOverride ? "YES (bug): " + g.closedReason : "none") + "; failures: " + g.baseFailure);
            System.out.println("power service force-disabling wakelocks: " + g.dump("power").contains("mGlobalForceDisableWakelocks=true"));
            System.out.println("exiting without cancelling (the system must drop it now)");
            Runtime.getRuntime().halt(0);
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
                    + " base state=" + g.baseState() + " physical states=" + g.physicalStates + " charger=" + g.chargerConnected()
                    + " wireless debugging port=" + g.adbWifiPort()
                    + " screen=" + (g.interactive() ? "on" : "off") + " build=" + fingerprint()
                    + (fingerprint().equals(readFile(VERIFIED)) ? " (verified)" : " (not yet verified)"));
            return;
        }
        // One helper at a time: a lock file in the shell-only directory (no app can hold it).
        java.nio.channels.FileLock lock;
        try {
            lock = new java.io.RandomAccessFile(DIR + "/helper.lock", "rw").getChannel().tryLock();
        } catch (IOException e) {
            lock = null;
        }
        if (lock == null) {
            System.out.println("another Sleep Guard helper is already running");
            System.exit(EXIT_OTHER_RUNNING); // the supervisor adopts the running one (not a failure)
        }
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
