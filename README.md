# Sleep Guard

Sleep Guard protects a Lenovo Googlebook 15 from losing all power when the USB-C charger is unplugged or plugged in
while the lid is closed and the laptop is in deep sleep. Closing the lid still puts the laptop into normal deep sleep
right away, with the glow bar off, so battery life is not affected. Sleep Guard only steps in for a fraction of a
second each time the laptop wakes with the lid closed, and for 20 seconds when the charger has changed. It is made of
a small helper that runs on GooglebookOS with adb shell permissions (no root), an Android app that starts and repairs
the helper by itself, and a `sleep-guard` command for the Linux Terminal. It is a mitigation that wins a timing race,
not a firmware fix, and opening the lid before you unplug the charger avoids the problem completely.

## What it does

Sleep Guard protects my Googlebook from losing power when the charger is unplugged or plugged in while the lid is
closed and the laptop is in deep sleep. Closing the lid still puts the laptop into normal deep sleep, so battery life
is not affected.

It has three parts:

- **The helper** (`local.sleepguard.SleepGuard`) runs on GooglebookOS as the adb shell user, started with
  `app_process` straight from the installed app. It watches the lid, the charger and every wake from deep sleep, and
  it holds the laptop awake for a moment when that is needed. A small supervisor script (`device/start.sh`) keeps it
  running, restarts it if it exits, and puts the real lid state back after every exit.
- **The Sleep Guard app** starts the helper after every reboot, every app update and every Wi-Fi reconnect, checks it
  every 15 minutes, shows its status, and posts a notification when something needs attention. It reaches the
  helper's shell through Wireless debugging on the Googlebook itself, using Sleep Guard's own paired key.
- **The `sleep-guard` command** in the Linux Terminal pairs that key, builds and installs the app, and shows the
  status and the log.

Once it is set up, I never have to touch it.

## The problem

I would open the lid and find a black screen. Pressing a key started a full boot instead of showing the lock screen,
so any open work was lost. It happened after the laptop had slept on the charger and the charger was unplugged before
the lid was opened, although not every unplug caused it. On one occasion the charger was unplugged during sleep, the
lid was opened four seconds later, and the laptop was fine.

A bug report showed what had happened. The firmware log in it said `EC reset flags at last EC boot: sysjump | hard`,
the embedded controller (EC) had only been running for a few seconds, and the file system reported an unclean
shutdown. The EC is the chip that manages power, and it had cold-rebooted itself and cut all power while the laptop
was in deep sleep (S0ix). The EC firmware is based on the open-source ChromiumOS EC code, and that code sets the
"hard" flag when software orders a cold reboot, for example with the host's cold reboot command or the EC console's
`reboot hard`. A crash, a watchdog reset and a power failure each leave different flags. The EC's own log had also
recorded `Detected sleep hang! Waking host up!` right after an earlier charger change during sleep. That time, the
operating system went back to sleep within about 60 milliseconds, but the processor never reached deep sleep, so the
EC woke it up again.

Everything points to a race. With the lid closed, GooglebookOS's `DesktopPowerService` force-disables all wakelocks,
which is what makes lid-closed sleep so deep. When a charger change wakes the laptop, nothing is allowed to keep it
awake, so it falls back into deep sleep within about 60 milliseconds while the USB-C power controller is still
handling the change. Part of the EC firmware for this board is private, so I could never identify exactly which
component sent the cold reboot command. I checked and ruled out many other causes, including the Setup Wizard's
auto-shutdown, the lid settings, an orderly Android shutdown, the battery level, heat, the 80% charge limit and an EC
watchdog.

Turning off deep sleep while charging would have hidden the problem, but I wanted to keep deep sleep exactly as it
is.

## How I solved it

Sleep Guard gives the hardware a moment to finish before the laptop goes back to sleep. It does nothing during normal
lid-closed sleep. It only acts at the moments that matter.

### How GooglebookOS decides that the lid is closed

GooglebookOS describes the laptop with device states such as `CLOSED` (0) and `OPEN` (2). Apps and system services
receive a merged state: its identifier comes from the *committed* state, but its physical properties come from the
*base* state, which follows the lid switch. `DesktopPowerService` force-disables wakelocks when the merged state has
the physical "closed" property (number 101 on my build). The display system, on the other hand, turns the screen on
when the committed state changes to one that wakes the screen, such as `OPEN`.

Sleep Guard uses both halves of that design through the device state interfaces that the adb shell user is allowed
to call:

- A **base-state override of `OPEN`** makes `DesktopPowerService` see the lid as open for power purposes, so
  wakelocks work again.
- A **committed-state hold of `CLOSED`** keeps the committed state closed at the same time, so the screen and the
  glow bar stay off.

### Step by step

1. **Noticing every wake.** While the lid is closed, the helper compares two clocks every 5 milliseconds. One of them
   counts time spent in deep sleep and the other does not, so when the difference jumps by more than 20 milliseconds,
   the laptop has just woken from deep sleep. The helper waits another 15 milliseconds and checks again that the lid
   is still closed, that GooglebookOS still sees it as closed, and that the screen is off. A lid opening shows up
   within that time and is left alone.
2. **The wake hold.** The helper takes its own keep-awake lock (`SleepGuard:wake`), then applies the committed-state
   hold of `CLOSED` and the base-state override of `OPEN`, always in that order and only while GooglebookOS reports
   the lid as closed in both the physical and the committed state. The hold lasts 0.3 seconds. During the hold the
   helper reads the charger state from the battery service every 25 milliseconds. Holds are never skipped, but once
   they add up to 15 seconds within 15 minutes, each new hold is shortened to 0.15 seconds.
3. **The settle.** When the charger state changes, at any time, the helper takes a keep-awake lock for the settle
   (`SleepGuard:settle`) for 20 seconds. With the lid closed it also keeps the hold and the override on for those 20
   seconds, so the USB-C controller can finish before the laptop is allowed back into deep sleep.
4. **Handing back to stock.** When the window ends, the helper releases the override first (GooglebookOS sees the
   real, closed lid again and force-disables wakelocks), then the committed-state hold, and only then its keep-awake
   locks. The laptop goes back into normal deep sleep.
5. **Opening the lid.** The helper reads the lid switch input device directly. When the lid opens, it drops the hold
   first and then the override, so the screen comes on normally.
6. **Everything else.** If the lid closes during a protection window, the protection is applied at once. If the
   screen comes on with the lid closed during a protection window and stays on for half a second, the helper puts it
   back to sleep. With an external display connected (the `DOCKED` state) or in a tablet state, Sleep Guard does not
   apply its lid-state protection and leaves sleep to GooglebookOS. Unplugging an external display with the lid
   closed does start a wake hold, because a USB-C dock often also charges the laptop.

An earlier version kept the laptop awake whenever it was charging with the lid closed, and another used 2-second
holds. The first kept the glow bar lit, and the second made it blink every time the laptop woke, because the EC
lights the glow bar whenever the laptop stays awake. That is why the hold is now only 0.3 seconds.

### Results

I proved it live on October 10, 2026. An unplug during deep sleep was caught 87 milliseconds after the wake, a
plug-in was caught 18 milliseconds after the wake, and the laptop came through the following night without losing
power.

### Safety and self-checks

I treated safety as seriously as the fix. An early version once stalled the power service for about two minutes,
because it asked for the power service's report into a pipe and only read the pipe after the call returned. The
helper is now built so that it can never block a system service:

- Service reports are requested with the one-way `IBinder.dumpAsync`, read while the service writes, with a
  3-second deadline and a 1 MB cap. On a deadline, the cap or any error, the helper closes its end of the pipe, so the
  service gets an error instead of waiting.
- Every command the helper runs has a 10-second time limit and an output cap, and its output is read while it runs.
- A watchdog releases everything and exits if the main loop stalls for 60 seconds (time in deep sleep does not
  count), and the exit is forced within 3 seconds even if a call hangs.
- Only one helper can run at a time (a lock file), and the supervisor runs only one start or stop at a time (`flock`).
- The override and the keep-awake locks belong to the helper's process, so GooglebookOS drops them if it dies. But
  GooglebookOS does not put the real lid state back when it drops an override for a dead process, so every helper
  start, and the supervisor after every helper exit, runs a **repair** that restores the real lid state.
- At every start the helper runs a **self-test** of everything it relies on: the `CLOSED` and `OPEN` states, the lid
  switch input device, the format of the battery and device state reports, and the physical properties of every
  state (`CLOSED` must have the "closed" property and `OPEN` must not). If something has changed, it
  **stands down**: it releases everything, records the reason for this GooglebookOS build, and posts a
  notification. The laptop then behaves exactly like stock. Something that is only unavailable for the moment, for
  example right after boot, makes it retry later instead.
- On a GooglebookOS build it has not seen before, it **verifies itself live** during the first lid-closed protection
  window: the power service's report must show that wakelocks are not force-disabled and that Sleep Guard's lock is
  active. After that it repeats the check once in every lid-closed charger settle. Two failures in a row make it stand
  down. The first success on a build is recorded and announced with a "Sleep Guard verified" notification.
- Further live checks: the override must be accepted and must stay Sleep Guard's own, GooglebookOS must not keep
  reporting the lid as physically closed while the override is active, and there are rate limits on override requests,
  lost protections and screen re-sleeps. Any of these failing makes it stand down.

### Keeping the connection alive

After the Wi-Fi drops in deep sleep, GooglebookOS restarts Wireless debugging by itself on a new port without
changing any setting, so nothing tells the app. The helper therefore reads the current Wireless debugging port from
the adb service's small report every 30 seconds, and every 5 seconds for two minutes after the lid opens. When the
port changes, it asks the app to run a check. The app finds the new port, starts the helper if needed, and publishes
the address in a small signed file, so the `sleep-guard` command in the Linux Terminal can reconnect by itself.

## Requirements

- **Device:** Lenovo Googlebook 15 (x86-64 Intel). Other Googlebooks may differ. The self-test and the live check
  decide whether Sleep Guard can act, and it stands down instead of guessing.
- **GooglebookOS build:** I built and tested it on build CL3B.260622.271.R1 (based on Android 17). Later builds are
  checked automatically as described above.
- **Developer options and Wireless debugging** turned on, and the Googlebook connected to **Wi-Fi**. Wireless
  debugging is needed to start the helper. The helper keeps running when the Wi-Fi drops.
- **No root.** The helper runs with the permissions of the adb shell user.
- **In the Linux Terminal:** `adb`, `openssl`, `python3`, a JDK (`javac` and `keytool`), and the Android SDK with
  `build-tools;35.0.0` and `platforms;android-35` in `~/tools/android-sdk`. The
  [Getting started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md)
  explains how to install these. If your SDK is somewhere else, set `ANDROID_SDK` to its folder.
- **Permissions the app receives** during installation: `WRITE_SECURE_SETTINGS` (to switch Wireless debugging back
  on), `POST_NOTIFICATIONS` and `ACCESS_LOCAL_NETWORK`. The installer also exempts the app from battery optimization,
  allows it to run in the background, and stops Android from removing its permissions when it is unused.

## Installation

First follow sections 1 to 4 and section 6 of the
[Getting started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md):
Developer options, the Linux Terminal, adb and the basic tools, Wireless debugging, and the Android SDK build tools.
Sleep Guard pairs its own key in step 4 below, separately from the pairing in the guide.

1. **Install `openssl`** in the Linux Terminal if it is not there yet:

   ```bash
   sudo apt install openssl
   ```

2. **Get the source code:**

   ```bash
   git clone https://github.com/iamflying29-sketch/googlebook-sleep-guard.git ~/googlebook-sleep-guard
   ```

3. **Put the `sleep-guard` command on your PATH** with a symbolic link. The command finds the rest of the source
   code through that link.

   ```bash
   mkdir -p ~/.local/bin
   ln -s ~/googlebook-sleep-guard/bin/sleep-guard ~/.local/bin/sleep-guard
   export PATH="$HOME/.local/bin:$PATH"
   ```

   Debian adds `~/.local/bin` to your PATH at login when the folder exists, so the `export` line is only needed in
   the current window. If you copy the script instead of linking it, set `SLEEP_GUARD_REPO` to the source folder.

4. **Pair Sleep Guard's own key.** On the Googlebook, open **Settings > System > Developer options > Wireless
   debugging**. Write down the address and port shown under **IP address & Port** on that screen. Then tap **Pair
   device with pairing code** and keep the dialog open. In the Linux Terminal, run:

   ```bash
   sleep-guard pair 123456 192.0.2.10:37000 192.0.2.10:41000
   ```

   Replace `123456` with the code in the dialog, `192.0.2.10:37000` with the address and port in the dialog, and
   `192.0.2.10:41000` with the address and port from the main Wireless debugging screen. (192.0.2.10 is a reserved
   example address.) If you leave out the last address, connect afterwards with
   `sleep-guard connect 192.0.2.10:41000`. If you run `sleep-guard pair` with no arguments, it asks for the code and
   the pairing address.

   **Keep the pairing: turn on "Disable adb authorization timeout"** on the same Developer options screen and leave
   it on. Without it, Android silently forgets every Wireless debugging pairing that has not connected for 7 days.
   On 2026-10-17 that removed Sleep Guard's key (and Shadow Trackpad Fix's) after exactly 7 days, Sleep Guard stopped
   working, and nothing had warned me. Since that day the `sleep-guard` command switches the timeout off by itself
   whenever its connection works (one short `settings put global adb_allowed_connection_time 0`), and the pairing
   step above is the way back if a pairing has already been lost.

   The first time, this creates a new adb key in `~/.config/sleep-guard/adbkey` (its file carries the label `SleepGuard@googlebook`; on the Googlebook the pairing shows the Linux Terminal's own name, for example `droid@debian`, like every pairing made from the Terminal).
   Then it pairs that key and keeps it for Sleep Guard only. Sleep Guard's adb server runs on its own port (5039)
   with its own home folder, so it never touches your normal adb connection or any other project's key. The pairing
   is listed under **Wireless debugging > Paired devices**.

5. **Build and install the app:**

   ```bash
   sleep-guard install-app
   ```

   This builds the app with `app/build.sh`, installs it for your Android user, grants the permissions listed under
   Requirements, sends the paired key to the app through the adb shell, and asks the app to start the helper. The
   first build creates a signing keystore with a random password in `~/.config/sleep-guard`. If Android has not yet
   allowed the new app to start in the background, the command opens the app once and then switches back to the
   Terminal. It ends with `Sleep Guard installed; the app (re)starts the helper now and after every reboot.`

6. **Check that it runs:**

   ```bash
   sleep-guard status
   sleep-guard check
   ```

   `status` should start with `running`. `check` should report `self-test: OK`.

7. **Let it verify itself.** On a new GooglebookOS build, `status` shows `(not yet verified)`. Close the lid for a
   minute or two and open it again. When the live check has passed, `sleep-guard log` shows `VERIFIED on this
   GooglebookOS build` and the app posts a "Sleep Guard verified" notification. If it has not appeared yet, close the
   lid for a little longer. From then on Sleep Guard starts by itself after every reboot.

## Using it

Day to day there is nothing to do. Close the lid, plug in or unplug the charger whenever you like, and open the lid.
Opening the lid before you unplug the charger is still the safest habit.

### The Sleep Guard app

Open **Sleep Guard** from the launcher. It shows the result of the last check and the helper's status, and it runs a
fresh check when it opens.

| Button | What it does |
| --- | --- |
| **Check / start** | Makes sure the helper runs. After a Stop or a stand-down it turns Sleep Guard back on. |
| **Restart** | Stops the helper and the supervisor and starts them again in one command. |
| **Stop** | Stops the helper and the supervisor and puts the real lid state back. Sleep Guard stays stopped, also across reboots, until you press **Check / start** or run `sleep-guard start`. |
| **Wireless debugging settings** | Opens Developer options. |

The app posts these notifications:

- **Sleep Guard needs attention**, with a short description, when a check fails. The same message is repeated at most
  once a day.
- **Sleep Guard stood down**, with the reason, when the helper has switched itself off safely.
- **Sleep Guard verified**, once per GooglebookOS build, when the live check has passed.

The app also switches Wireless debugging back on when it is off and the Googlebook is on Wi-Fi, because it needs
Wireless debugging to start the helper. If you switch Wireless debugging off by hand, the app switches it back on
at its next check, at most once every 10 minutes. To keep Wireless debugging off, press **Stop** first. A stopped
Sleep Guard leaves Wireless debugging alone.

### The `sleep-guard` command

| Command | What it does |
| --- | --- |
| `sleep-guard pair [CODE IP:PAIRPORT [IP:PORT]]` | Pairs Sleep Guard's own adb key, and connects if you give the connection address. |
| `sleep-guard connect [IP:PORT]` | Connects to Wireless debugging. Without an address it uses the address the app published. |
| `sleep-guard install-app [--force]` | Builds, installs or updates the app, grants its permissions, gives it the key and restarts the helper. `--force` skips the check that the app on the device is the one holding your key. |
| `sleep-guard status [N]` | Shows whether Sleep Guard is running, any stand-down, the build and whether it is verified, and the last N log lines (12 by default). This is also what `sleep-guard` with no command does. |
| `sleep-guard log [N]` | Shows the last N lines of the helper's log (60 by default). |
| `sleep-guard check` | Runs the helper's self-test once and prints the lid device, the lid state GooglebookOS sees, the charger, the Wireless debugging port, the screen state and the build. |
| `sleep-guard start` | Turns Sleep Guard on. It also retries right away after a stand-down. |
| `sleep-guard stop` | Stops Sleep Guard until the next `start`, also across reboots. |
| `sleep-guard usb-debugging on` or `off` | Allows the app to keep USB debugging switched on, which keeps the helper alive if Wireless debugging drops. It is off by default. |

The command reconnects by itself when it can. The app writes the current Wireless debugging address, signed with a
secret derived from Sleep Guard's key, to `Documents/Sleep Guard/adb-port.txt` on the Googlebook. On my Googlebook
the Linux Terminal sees that folder as `/mnt/shared/Documents/Sleep Guard`. If it appears somewhere else on yours,
set `SLEEP_GUARD_PORT_DIR` to that folder. The command only trusts a file whose signature matches your key.

These environment variables are optional:

| Variable | Default | Meaning |
| --- | --- | --- |
| `SLEEP_GUARD_CONF` | `~/.config/sleep-guard` | Sleep Guard's adb key, its adb home folder and the app signing keystore |
| `SLEEP_GUARD_REPO` | the folder above the script | The source code, used by `install-app`, `start` and `stop` |
| `SLEEP_GUARD_PORT_DIR` | `/mnt/shared/Documents/Sleep Guard` | Where the published address file appears |
| `ANDROID_SDK` | `~/tools/android-sdk` | The Android SDK used by the build scripts |
| `KS_PASS` | a random password in `~/.config/sleep-guard/app-signing.pass` | The signing keystore password used by `app/build.sh` |

### Reading the log

The helper writes to `/data/local/tmp/sleepguard/sleepguard.log` on the Googlebook, in the device's own time zone.
Some typical lines:

| Log line | Meaning |
| --- | --- |
| `start: build verified, lid=closed, ...` | The helper started and passed its self-test. |
| `protecting (screen held off, wakelocks working): woke from deep sleep with the lid closed` | A wake hold started. |
| `charger DISCONNECTED (lid closed, screen off): keeping the laptop awake 20 s` | A charger change was caught and the settle started. |
| `protection released: protection window over: normal lid-closed sleep` | The laptop was handed back to stock deep sleep. |
| `settle done, wakelock released` | The 20-second settle ended. |
| `live check OK: lid closed, GooglebookOS keeps wakelocks working` | The live check passed. |
| `asked the Sleep Guard app to publish the new address (...)` | Wireless debugging moved to a new port. |
| `STANDING DOWN: ...` | Sleep Guard switched itself off safely, with the reason. |
| `supervisor: helper exited (N)` | The helper exited with code N and the supervisor restarts it. |

Exit codes: 3 is an unexpected error, 4 the watchdog, 5 a stand-down, 6 "try again later", and 7 means another
helper is already running (the supervisor then watches that one instead).

### Developer diagnostics

The helper has a few bounded test modes. Run them with your normal adb connection from the Getting started guide,
always under `timeout`:

```bash
adb shell 'CLASSPATH=$(pm path --user current local.sleepguard | head -1 | cut -d: -f2) timeout 60 app_process / local.sleepguard.SleepGuard dumptest'
```

| Mode | What it does |
| --- | --- |
| `check` | The self-test and current readings (the same as `sleep-guard check`). |
| `dumptest` | Reads the power service's report 20 times, then stops reading early on purpose (after 1 KB and after 1 millisecond) and confirms that the power service still answers normally. |
| `locktest` | Takes a keep-awake lock, confirms that the power service lists it as active, releases it and confirms that it is gone. |
| `repair` | Puts the real lid state back. The supervisor runs this after every helper exit. |
| `basetest` | Development test of the lid-state override. It only runs with the lid open, and it exits without cancelling its override on purpose, so run `repair` right after it. |

## Updating, and what happens after app or GooglebookOS updates

**Updating Sleep Guard.** Get the new code and reinstall:

```bash
cd ~/googlebook-sleep-guard
git pull
sleep-guard install-app
```

Every build gets a higher version number, and the app is updated in place. After the update, Android tells the app
that it was replaced, and the app restarts the helper so that it runs the new code, unless you stopped Sleep Guard.
The update must be signed with the same keystore, so keep `~/.config/sleep-guard/app-signing.jks` and
`app-signing.pass`. If you lose them, uninstall the app first and then install it again.

If you uninstall and reinstall the app, or clear its data, the app no longer holds your key. The installer then
refuses to send the key until you confirm with `sleep-guard install-app --force`.

**After a GooglebookOS update.** The laptop restarts and the app starts the helper as usual. The helper runs its
self-test. Because the new build has not been verified yet, it checks itself live during the first lid-closed
protection window and posts "Sleep Guard verified" when that passes. If the self-test or the live check fails, it
stands down, the laptop behaves exactly like stock, and the app tells you why. A stand-down is recorded for that
GooglebookOS build only. Sleep Guard tries again by itself after the next GooglebookOS update, or right away when you
run `sleep-guard start` or press **Check / start**.

## Uninstalling

1. **Stop Sleep Guard.** This stops the helper and the supervisor and puts the real lid state back:

   ```bash
   sleep-guard stop
   ```

2. **Uninstall the app**, either from the launcher or with your normal adb connection:

   ```bash
   adb uninstall local.sleepguard
   ```

3. **Remove the helper's folder** on the Googlebook (the supervisor script, logs and state files):

   ```bash
   adb shell rm -r /data/local/tmp/sleepguard
   ```

4. **Remove the pairing.** On the Googlebook, open **Wireless debugging > Paired devices**, select Sleep Guard's
   entry (it shows the Terminal's name, for example `droid@debian`; the key file is labeled `SleepGuard@googlebook`) and tap **Forget**.

5. **Stop Sleep Guard's adb server and remove the files in the Linux Terminal.** The configuration folder holds the
   adb key and the signing keystore.

   ```bash
   ANDROID_ADB_SERVER_PORT=5039 adb kill-server
   rm ~/.local/bin/sleep-guard
   rm -r ~/.config/sleep-guard
   rm -r ~/googlebook-sleep-guard
   ```

6. **Remove the published address file** by deleting the `Sleep Guard` folder in **Documents** with the Files app.

## Troubleshooting

**`sleep-guard` says "Sleep Guard is not connected to the Googlebook."**
Make sure Wi-Fi and Wireless debugging are on, then connect with the address and port shown on the Wireless
debugging screen: `sleep-guard connect 192.0.2.10:41000`. Normally the command reconnects by itself from the file the
app publishes. Right after a reboot, the app may need a minute before it has published the new address.

**The app says "Not set up yet".**
The app does not have Sleep Guard's key yet. Run `sleep-guard pair` (if you have not paired yet) and then
`sleep-guard install-app`.

**The app says "Wireless debugging no longer accepts Sleep Guard's key".**
Sleep Guard's pairing was probably removed (Android does that by itself after 7 days without a connection unless "Disable adb authorization timeout" is on). Pair again with `sleep-guard pair`. The same key is used
again, so the app works again as soon as the pairing exists. If it still fails, run `sleep-guard install-app`.

**The app says "Could not reach Wireless debugging" or "Waiting for Wi-Fi".**
Wireless debugging needs Wi-Fi. "Waiting for Wi-Fi" is not an error: GooglebookOS turns Wireless debugging off while
the Wi-Fi is down, for example in deep sleep, and the helper keeps running meanwhile. The app checks again as soon as
the Wi-Fi is back.

**The app says "Wireless debugging is off and could not be switched on".**
The app is missing the `WRITE_SECURE_SETTINGS` permission. Run `sleep-guard install-app` again to grant it, or switch
Wireless debugging on by hand.

**The app says "Something on this Googlebook imitates Wireless debugging".**
A local port answered like Wireless debugging but could not prove that it is Android's adb service, so the app sent
nothing to it. Check which apps you have installed recently.

**`install-app` says "The device could not prove it is your Googlebook with the Sleep Guard app".**
This is expected after you reinstalled the app or cleared its data, because the app no longer holds your key. Run
`sleep-guard install-app --force`. If you did neither, make sure you are connected to your own Googlebook.

**Sleep Guard stood down.**
`sleep-guard status` shows a `STANDING DOWN:` line with the reason, and `sleep-guard log` shows what led to it. The
laptop now behaves exactly like stock. If the reason is a GooglebookOS change, Sleep Guard tries again after the next
update. To try again now, run `sleep-guard start`. The supervisor also stands down if the helper keeps stopping right
after it starts, or if what it needs stays unavailable for a long time.

**`status` says "running (supervisor only)".**
The helper has stood down for this build and the supervisor is waiting. See the previous entry.

**The laptop stays awake with the lid closed.**
A 20-second settle after a charger change is expected. If it stays awake much longer, GooglebookOS may still be
treating the closed lid as open. Run `sleep-guard stop` and then `sleep-guard start`. Both put the real lid state
back.

**The laptop still lost power during sleep.**
Run `sleep-guard status` to confirm that Sleep Guard was running and verified, and look for `charger` lines around
that time with `sleep-guard log 500`. Right after the laptop boots again, save a bug report with
`adb bugreport` and look for `EC reset flags at last EC boot` and `EC uptime` in its firmware log. Until then,
opening the lid before unplugging the charger avoids the problem.

**"another start/stop is still in progress".**
Two start or stop requests overlapped. Wait half a minute and try again.

## Safety and privacy notes

- **No root and no system changes.** The helper runs as the adb shell user. It uses the device state and power
  interfaces that the shell user is allowed to call, and it never modifies GooglebookOS itself.
- **It never blocks a system service.** See [Safety and self-checks](#safety-and-self-checks): one-way report
  requests with a deadline, a size cap and a closed pipe on timeout, time limits on every command, and a watchdog.
- **It never deletes files.** The supervisor and the helper keep their state by overwriting small files in
  `/data/local/tmp/sleepguard`. The logs are kept small by renaming a log over 2 MB to a `.1` file, which replaces
  the previous `.1` file.
- **It never injects input.** When the screen comes on with the lid closed during a protection window, it asks the
  power service to sleep, the same way closing the lid does.
- **Its own key and its own adb server.** Sleep Guard uses its own paired adb key and its own adb server port (5039),
  separate from your normal adb connection and from every other project. The keys stay in `~/.config/sleep-guard`
  and in the app's private storage, and they are never part of this repository.
- **The app accepts control commands only from the adb shell or the system.** Its control receiver requires the
  `DUMP` permission, which ordinary apps cannot get. Before it sends anything over a Wireless debugging connection,
  the app makes that connection prove it reaches Android's adb service. Before the `sleep-guard` command sends the key
  again, the app must prove that it already holds it.
- **The app only connects to the Googlebook itself.** It talks to Wireless debugging on `127.0.0.1`, and it uses
  local network service discovery only to find the Googlebook's own Wireless debugging port. Apart from those
  discovery queries, it does not use the network.
- **The app only runs fixed commands.** It installs the bundled supervisor script, runs that script's start, stop,
  restart, ensure and status commands, and sends one broadcast to itself that proves the connection.
- **Wireless debugging stays on.** While Sleep Guard runs, the app switches Wireless debugging back on whenever the
  Googlebook is on Wi-Fi. A new computer still needs a pairing code from your screen to connect, but you should only
  pair computers you own. USB debugging is only kept on if you allow it with `sleep-guard usb-debugging on`.
- **The published address file** in `Documents/Sleep Guard` contains the Googlebook's Wi-Fi address, the Wireless
  debugging port and a signature. Apps that can read your Documents folder can read it.

## Limitations and known issues

- **It is a mitigation, not a fix.** The underlying problem is in firmware that I cannot change, and I never found
  out exactly which component sends the cold reboot command. Sleep Guard removes the 60-millisecond race that the
  evidence points to, but it cannot promise that nothing else can trigger it.
- **It wins a timing race.** A wake is noticed within about 5 milliseconds, confirmed 15 milliseconds later, and held
  for 0.3 seconds. If GooglebookOS reports a charger change only after the hold has ended, Sleep Guard sees it at the
  next wake. I proved both an unplug and a plug-in live, but I cannot test every timing.
- **Opening the lid before unplugging the charger is always the safest choice.** The failure only ever happened during
  lid-closed deep sleep.
- **Tested on one laptop and one GooglebookOS build.** Other Googlebooks and later builds are checked by the self-test
  and the live check, and Sleep Guard stands down when something differs.
- **Each lid-closed wake lasts a little longer.** GooglebookOS wakes the laptop regularly during deep sleep, and each
  of those wakes lasts about 0.3 seconds longer than stock, or 0.15 seconds when there are many. The battery cost is
  small but not zero.
- **During a lid-closed settle, GooglebookOS treats the lid as open for power purposes.** For those 20 seconds other
  apps' wakelocks work too. The screen stays off, but the glow bar can light up, because the EC lights it while the
  laptop is awake.
- **External displays and tablet states are left to GooglebookOS.** Sleep Guard does not protect the `DOCKED` state.
  It does start a wake hold when an external display is unplugged with the lid closed.
- **It needs Wireless debugging to start.** After a reboot, the helper starts only once the Googlebook is on Wi-Fi
  and Wireless debugging is on.
- **A killed helper can leave the lid state overridden for a while.** If the helper and its supervisor are both killed
  during a protection window, GooglebookOS can keep treating the closed lid as open until Sleep Guard starts again and
  repairs it. The app restarts it at its next check.
- **The Android user is detected automatically.** The helper asks GooglebookOS for the current user when it sends a
  notification through the app, and falls back to user 10 (my account's user ID on my Googlebook) only if that
  question fails. You can change that fallback in `alert()` and `kickApp()` in `SleepGuard.java`.

## Files in this repository

| Path | What it is |
| --- | --- |
| `src/local/sleepguard/SleepGuard.java` | The helper: wake detection, wake holds, settles, lid reader, self-test, live checks, watchdog and test modes |
| `device/start.sh` | The on-device supervisor: start, stop, ensure, restart, status, repair after every exit, retries and stand-downs |
| `build.sh` | Builds `sleepguard.dex`, the helper on its own. Optional: `start.sh` only uses it when the app is not installed and you have pushed it to `/data/local/tmp/sleepguard/` yourself |
| `app/AndroidManifest.xml` | The app's manifest and permissions |
| `app/src/local/sleepguard/Starter.java` | Connects to Wireless debugging with Sleep Guard's key, runs the supervisor, publishes the address, posts notifications |
| `app/src/local/sleepguard/AdbClient.java` | A minimal adb client for Wireless debugging (TLS) that runs one shell command |
| `app/src/local/sleepguard/HealthJob.java` | Scheduled checks: every 15 minutes, after boot, after updates, after debugging settings change and when Wi-Fi returns |
| `app/src/local/sleepguard/ControlReceiver.java` | Control commands from the adb shell and the helper (prove, provision, config, kick, alert) |
| `app/src/local/sleepguard/BootReceiver.java` | Starts Sleep Guard after a reboot and restarts the helper after an app update |
| `app/src/local/sleepguard/MainActivity.java` | The status screen and its buttons |
| `app/src/local/sleepguard/App.java` | Keeps an unexpected background error from crashing the app |
| `app/res/` | The app name and the moon icons |
| `app/build.sh` | Builds and signs `SleepGuard.apk` with the helper inside, and copies `device/start.sh` into `app/assets/` |
| `bin/sleep-guard` | The Linux Terminal command |
| `LICENSE` | The MIT License |

Build outputs (`build/`, `app/build/`, `app/assets/`, `*.apk`, `*.idsig`, `*.dex`) and keys are created locally and
are not part of the repository.

## Related projects

- [Googlebook Projects](https://github.com/iamflying29-sketch/googlebook-projects): the overview of all my Googlebook
  projects and the [Getting started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md).
- [Shadow Trackpad Fix](https://github.com/iamflying29-sketch/googlebook-shadow-trackpad-fix): another helper that
  runs with adb shell permissions and is kept alive by its own app. It uses its own key, completely separate from
  Sleep Guard's.

## Disclaimer and license

This is an independent, unofficial project. It is not affiliated with, endorsed by, or sponsored by Google, Lenovo
or any other company named here. All product names belong to their owners. Released under the MIT License.
Copyright (c) 2026 Thomas Liviakis.
