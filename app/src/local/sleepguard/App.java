package local.sleepguard;

import android.app.Application;
import android.os.Looper;
import android.util.Log;

/**
 * Safety net: if an unexpected error ever happens on a background thread (including Android's own
 * callback threads), it is logged and that thread ends - the app itself keeps running. Two crashes
 * in a row would otherwise make Android mark the app a "bad process" and refuse to start it in the
 * background (no boot start, no health checks) until it is opened by hand.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler platform = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (thread == Looper.getMainLooper().getThread() && platform != null) {
                platform.uncaughtException(thread, error); // main thread: Android's normal handling
            } else {
                Log.e(Starter.TAG, "contained background error on " + thread.getName(), error);
            }
        });
    }
}
