package local.sleepguard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Status screen with Check / start, Restart, Stop. */
public class MainActivity extends Activity {
    private TextView summary, status;
    private Button[] buttons;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Sleep Guard");
        title.setTextSize(22);
        root.addView(title);
        TextView about = new TextView(this);
        about.setText("Sleep Guard keeps deep sleep, but it gives the USB-C charging chip time to finish whenever the charger "
                + "is plugged in or unplugged while the laptop sleeps, so the laptop no longer turns itself off. It starts by "
                + "itself after every reboot, checks itself every 15 minutes, and stands down safely (the laptop then behaves "
                + "normally) if a GooglebookOS update changes anything it relies on.");
        about.setPadding(0, pad / 2, 0, pad);
        root.addView(about);
        summary = new TextView(this);
        summary.setTextSize(16);
        root.addView(summary);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, pad, 0, pad);
        buttons = new Button[]{button("Check / start", Starter.Action.ENSURE), button("Restart", Starter.Action.RESTART),
                button("Stop", Starter.Action.STOP)};
        for (Button bt : buttons) row.addView(bt);
        root.addView(row);
        Button dev = new Button(this);
        dev.setText("Wireless debugging settings");
        dev.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
        root.addView(dev);
        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(12);
        status.setPadding(0, pad, 0, 0);
        status.setTextIsSelectable(true);
        root.addView(status);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        if (!Starter.provisioned(this)) {
            summary.setText("Not set up yet. Run 'sleep-guard pair' in the Linux terminal.");
        } else {
            showLast();
            new Thread(() -> {
                Starter.check(this);
                runOnUiThread(this::showLast);
            }, "ui-check").start();
        }
    }

    private Button button(String label, Starter.Action action) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setOnClickListener(v -> perform(action));
        return bt;
    }

    private void showLast() {
        long t = Starter.prefs(this).getLong("lastCheck", 0);
        summary.setText((t == 0 ? "" : "Last check " + DateUtils.getRelativeTimeSpanString(t) + ": ")
                + Starter.prefs(this).getString("lastSummary", ""));
        status.setText(Starter.prefs(this).getString("lastStatus", ""));
    }

    private void perform(Starter.Action action) {
        for (Button bt : buttons) bt.setEnabled(false);
        summary.setText("Working…");
        new Thread(() -> {
            Starter.run(this, action);
            runOnUiThread(() -> {
                showLast();
                for (Button bt : buttons) bt.setEnabled(true);
            });
        }, "ui-action").start();
    }
}
