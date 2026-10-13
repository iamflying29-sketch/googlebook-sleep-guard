package local.sleepguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot or an update of this app: (re)start Sleep Guard. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Only the two system broadcasts (both protected - no app can send them); anything else
        // another app might send here explicitly is ignored.
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HealthJob.schedule(context, false, true);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // After an app update the running helper still has the old code: restart it.
            HealthJob.schedule(context, true, true);
        }
    }
}
