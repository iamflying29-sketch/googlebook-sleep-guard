package local.sleepguard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Keeps the helper alive: a persisted periodic job (Android restores it after reboots by itself)
 * plus one-off runs right after boot / app update / a request from the shell.
 */
public class HealthJob extends JobService {
    // RESTART has its own id so a later plain check can never replace (cancel) a pending restart.
    static final int PERIODIC = 1, NOW = 2, SETTINGS_CHANGED = 3, RESTART = 4, SETTINGS_CHANGED_B = 5, NETWORK_BACK = 6;

    /** Jobs currently running in this process (re-scheduling a running job's id would cancel it). */
    static final java.util.Set<Integer> RUNNING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * A check was requested while one was already running (e.g. Wireless debugging moved to yet
     * another port during the check): run once more when it finishes, so the newest port is published.
     */
    static final java.util.Set<Integer> RERUN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Wakes this app the moment Wireless debugging, USB debugging or Developer options are switched,
     * so a helper killed by that is restarted within seconds. Content-triggered jobs fire once, so
     * this is re-armed every time.
     */
    static void armSettingsTrigger(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(SETTINGS_CHANGED) != null || js.getPendingJob(SETTINGS_CHANGED_B) != null) return;
        armSettingsTrigger(c, SETTINGS_CHANGED);
    }

    /**
     * A trigger job arms the other of the two ids (re-scheduling its own, running id would cancel
     * it) - unless that one is still pending or running; whichever finishes last re-arms.
     */
    private static void armOtherTrigger(Context c, int self) {
        int other = self == SETTINGS_CHANGED ? SETTINGS_CHANGED_B : SETTINGS_CHANGED;
        if (c.getSystemService(JobScheduler.class).getPendingJob(other) == null) armSettingsTrigger(c, other);
    }

    private static void armSettingsTrigger(Context c, int id) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        JobInfo.Builder b = new JobInfo.Builder(id, new ComponentName(c, HealthJob.class));
        for (String key : new String[]{"adb_wifi_enabled", android.provider.Settings.Global.ADB_ENABLED,
                android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED}) {
            b.addTriggerContentUri(new JobInfo.TriggerContentUri(android.provider.Settings.Global.getUriFor(key), 0));
        }
        js.schedule(b.setTriggerContentUpdateDelay(500).setTriggerContentMaxDelay(2000).build());
    }

    /** Wireless debugging needs Wi-Fi: a check that runs as soon as Wi-Fi is back (Wi-Fi only). */
    static void armNetworkTrigger(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (RUNNING.contains(NETWORK_BACK) || js.getPendingJob(NETWORK_BACK) != null) return;
        android.net.NetworkRequest wifi = new android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build();
        js.schedule(new JobInfo.Builder(NETWORK_BACK, new ComponentName(c, HealthJob.class))
                .setRequiredNetwork(wifi).build());
    }

    /**
     * restart: also restart the helper (new app version). fresh: a new request (boot, setup,
     * the Linux script) - it replaces a check waiting in retry back-off and starts a new retry series.
     */
    static void schedule(Context c, boolean restart, boolean fresh) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        ComponentName cn = new ComponentName(c, HealthJob.class);
        armSettingsTrigger(c);
        if (js.getPendingJob(PERIODIC) == null) {
            js.schedule(new JobInfo.Builder(PERIODIC, cn).setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
        }
        int id = restart ? RESTART : NOW;
        // A running check already does the work (and re-scheduling its id would cancel it); it runs
        // once more afterwards, in case what it found is already outdated.
        if (RUNNING.contains(id)) {
            RERUN.add(id);
            return;
        }
        if (!fresh && js.getPendingJob(id) != null) return;
        if (fresh) Starter.prefs(c).edit().putInt("startFails", 0).apply();
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("restart", restart);
        js.schedule(new JobInfo.Builder(id, cn).setOverrideDeadline(0).setExtras(extras)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int id = params.getJobId();
        RUNNING.add(id);
        new Thread(() -> {
            boolean retry = false;
            Starter.Result r = null;
            try {
                boolean trigger = id == SETTINGS_CHANGED || id == SETTINGS_CHANGED_B;
                if (trigger) {
                    armOtherTrigger(this, id);
                    // The app's own switch-on of Wireless debugging fires this trigger too: nothing to do then.
                    long ownWrite = System.currentTimeMillis() - Starter.prefs(this).getLong("wifiAssertAt", 0);
                    if (Starter.adbWifi(this) == 1 && ownWrite >= 0 && ownWrite < 20_000) {
                        Log.i(Starter.TAG, "health job " + id + ": triggered by Sleep Guard's own setting write, skipped");
                        return;
                    }
                    android.os.SystemClock.sleep(3000); // let adbd settle after the switch
                }
                if (id == NETWORK_BACK) android.os.SystemClock.sleep(3000); // let Wi-Fi finish connecting
                // An automatic restart (new app version) never overrides the user's Stop.
                boolean restart = params.getExtras().getBoolean("restart", false);
                do {
                    // A check requested while this one ran (the port moved again) is done right here, in the
                    // same job: scheduling it after jobFinished could be lost (the finished job may still be
                    // listed for a moment, and the request may arrive just as this thread ends).
                    r = restart ? Starter.run(this, Starter.Action.RESTART, true) : Starter.check(this);
                    Log.i(Starter.TAG, "health job " + id + ": " + r.summary);
                } while ((id == NOW || id == RESTART) && RERUN.remove(id));
                // Failed one-off starts (e.g. Wi-Fi not up yet after boot) retry with back-off, at most
                // 6 times in a row; after that the 15-minute periodic check keeps trying, quietly.
                if (r.ok) {
                    Starter.prefs(this).edit().putInt("startFails", 0).apply();
                } else if ((id == NOW || id == RESTART) && Starter.provisioned(this)) {
                    int fails = Starter.prefs(this).getInt("startFails", 0) + 1;
                    Starter.prefs(this).edit().putInt("startFails", fails).apply();
                    retry = fails <= 6;
                }
                if (trigger) armOtherTrigger(this, id);
                else armSettingsTrigger(this);
            } finally {
                RUNNING.remove(id);
                jobFinished(params, retry);
                // Wi-Fi dropped again during the Wi-Fi job itself: the trigger could not be re-armed while
                // this job counted as running, so arm it now.
                if (id == NETWORK_BACK && r != null && r.summary.startsWith("Waiting for Wi-Fi")) armNetworkTrigger(this);
            }
        }, "health-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
