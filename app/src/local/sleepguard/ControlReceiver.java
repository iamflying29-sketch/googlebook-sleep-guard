package local.sleepguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import java.io.FileOutputStream;

/**
 * Control channel for the Linux-side script and the on-device helper. Guarded by
 * android.permission.DUMP in the manifest, so only the adb shell / system can send to it - no
 * other app can install keys, trigger starts or post alerts.
 *   PROVE:     nonce proof that an adb connection reached the real shell (see Starter.proveShell).
 *   PROVISION: stores Sleep Guard's own paired adb key (PKCS#12, base64) in private storage.
 *   CONFIG:    stopped flag / USB-debugging consent / device challenge for the Linux script.
 *   KICK:      runs a start/health check now (what a reboot would do).
 *   ALERT:     the helper reports something the user should know (e.g. it stood down after an
 *              update changed what it relies on); shown as a notification.
 */
public class ControlReceiver extends BroadcastReceiver {
    static final java.util.Set<String> PROVEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("local.sleepguard.PROVE".equals(action)) {
            String nonce = intent.getStringExtra("nonce");
            if (nonce != null && nonce.matches("[0-9a-f]{32}")) {
                if (PROVEN.size() > 50) PROVEN.clear();
                PROVEN.add(nonce);
            }
            setResultData("ok");
        } else if ("local.sleepguard.PROVISION".equals(action)) {
            String p12 = intent.getStringExtra("p12");
            String pass = intent.getStringExtra("pass");
            if (p12 == null || pass == null) {
                setResultData("missing extras");
                return;
            }
            try {
                // Validate and write to a temporary file first, so a malformed request can't replace a working key.
                byte[] key = Base64.decode(p12, Base64.DEFAULT);
                java.security.KeyStore.getInstance("PKCS12").load(new java.io.ByteArrayInputStream(key), pass.toCharArray());
                java.io.File tmp = new java.io.File(context.getFilesDir(), "adb.p12.new");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(key);
                }
                if (!tmp.renameTo(Starter.keyFile(context))) throw new java.io.IOException("rename failed");
                Starter.prefs(context).edit().putString("p12pass", pass).remove("port").apply();
                setResultData("provisioned");
                Log.i(Starter.TAG, "adb key provisioned");
            } catch (Exception e) {
                setResultData("error: " + e);
                return;
            }
            HealthJob.schedule(context, false, true);
        } else if ("local.sleepguard.CONFIG".equals(action)) {
            if (intent.hasExtra("stopped")) {
                Starter.prefs(context).edit().putBoolean("stopped", intent.getBooleanExtra("stopped", false)).commit();
            }
            if (intent.hasExtra("usb_debugging")) {
                Starter.prefs(context).edit().putBoolean("keepUsbDebugging", intent.getBooleanExtra("usb_debugging", false)).apply();
            }
            String challenge = intent.getStringExtra("challenge");
            String proof = challenge != null && challenge.matches("[0-9a-f]{32}") ? " proof=" + Starter.keyMac(context, "challenge:" + challenge) : "";
            setResultData("keepUsbDebugging=" + Starter.prefs(context).getBoolean("keepUsbDebugging", false)
                    + " stopped=" + Starter.prefs(context).getBoolean("stopped", false) + proof);
        } else if ("local.sleepguard.KICK".equals(action)) {
            // The helper saw Wireless debugging's current port: try it first (no discovery needed).
            int port = intent.getIntExtra("port", 0);
            if (port > 0 && port < 65536) Starter.prefs(context).edit().putInt("port", port).apply();
            HealthJob.schedule(context, intent.getBooleanExtra("restart", false), true);
            setResultData("kicked");
        } else if ("local.sleepguard.ALERT".equals(action)) {
            String title = intent.getStringExtra("title");
            String text = intent.getStringExtra("text");
            if (text != null) {
                if (text.length() > 600) text = text.substring(0, 600);
                if (title == null || title.length() > 80) title = "Sleep Guard";
                Starter.alert(context, title, text);
            }
            setResultData("ok");
        }
    }
}
