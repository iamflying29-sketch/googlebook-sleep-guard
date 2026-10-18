#!/system/bin/sh
# Sleep Guard - on-device supervisor (runs as the adb shell user). Installed into
# /data/local/tmp/sleepguard by the Sleep Guard app (at boot / every health check, through its OWN
# paired adb key) or by the sleep-guard script in the Linux terminal.
#   sh start.sh start    start (idempotent); clears a user stop and a stand-down
#   sh start.sh stop     stop helper + supervisor; stays stopped until "start"
#   sh start.sh ensure   automatic start (app checks): does nothing if the user stopped it
#   sh start.sh restart  automatic restart (new app version): does nothing if stopped
#   sh start.sh status   status (first line "running" / "NOT running"; "STANDING DOWN: ..." if so)
# The helper's code is taken from the installed Sleep Guard app (APK), falling back to a pushed dex.
# Never deletes files: state is kept by overwriting small files.
D=/data/local/tmp/sleepguard
PKG=local.sleepguard
umask 077
mkdir -p $D
chmod 700 $D 2>/dev/null

slog() { echo "$(date '+%m-%d %H:%M:%S') supervisor: $*" >> $D/sleepguard.log; }
# The installed Sleep Guard app's APK (the helper's code); empty if the package manager has no answer
# right now. The helper is never run from the old pushed dex: that would silently run outdated code.
classpath() {
    APK=$(timeout 10 pm path --user current $PKG 2>/dev/null | head -1 | cut -d: -f2)
    [ -z "$APK" ] && APK=$(timeout 10 pm path $PKG 2>/dev/null | head -1 | cut -d: -f2)
    if [ -n "$APK" ] && [ -r "$APK" ]; then echo "$APK"; fi
}
# Puts the real lid state back: GooglebookOS keeps a dead helper's lid-state override's effect
# (it would keep seeing a closed lid as open). Harmless when nothing needs repairing. Never while a
# helper is alive (it would cancel that helper's protection). The APK path is cached only when found.
CP=""
repair() {
    [ -n "$(pids_of "$HLP")" ] && return 0
    [ -n "$CP" ] && [ -r "$CP" ] || CP=$(classpath)
    RCP=$CP
    [ -n "$RCP" ] || RCP=$D/sleepguard.dex   # repair only (cancel our override): the old code does the same
    CLASSPATH=$RCP timeout -k 5 20 app_process / local.sleepguard.SleepGuard repair >> $D/helper-stdout.log 2>&1 ||
        slog "lid state repair FAILED"
}
alive() { [ -f "$1" ] && p=$(cat "$1") && [ -n "$p" ] && grep -q "$2" /proc/$p/cmdline 2>/dev/null; }
supervisor_alive() { alive $D/supervisor.pid supervise; }
helper_alive() { alive $D/helper.pid local.sleepguard.SleepGuard; }
control() { cat $D/control 2>/dev/null; }
standing_down() {
    s=$(cat $D/standdown 2>/dev/null)
    case "$s" in "STANDING DOWN:"*"build=$(getprop ro.build.fingerprint)") return 0 ;; esac
    return 1
}

SUP="sh $D/start.sh supervise"
HLP="app_process / local.sleepguard.SleepGuard"
# Our processes, found by their exact command line (pid files can be stale after a race).
pids_of() { ps -A -o PID,ARGS 2>/dev/null | sed 1d | while read -r p a; do [ "$a" = "$1" ] && echo "$p"; done; }

stop_all() {
    [ -n "$(pids_of "$SUP")$(pids_of "$HLP")" ] && slog "stopping (sh start.sh $1)"
    echo "" > $D/supervisor.pid                       # a supervisor that is still alive exits on seeing this
    for p in $(pids_of "$SUP"); do kill $p 2>/dev/null; done
    for p in $(pids_of "$HLP"); do kill $p 2>/dev/null; done   # the system drops a dead helper's override + wakelocks
    n=0
    while [ -n "$(pids_of "$HLP")$(pids_of "$SUP")" ] && [ $n -lt 10 ]; do sleep 1; n=$((n + 1)); done
    for p in $(pids_of "$HLP") $(pids_of "$SUP"); do kill -9 $p 2>/dev/null; done
    echo "" > $D/helper.pid
    repair
}

# One control action (start/ensure/restart/stop) at a time, from any caller (app, boot, terminal).
# mksh makes fds opened with "exec" close-on-exec, so the lock fd is a redirection of this subshell
# (inherited by flock). toybox flock has no timeout option: retry non-blocking for up to ~30 s.
# The supervisor is started with fd 9 closed, so it never keeps holding the lock.
locked() {
    (
        n=0
        until flock -n 9; do
            n=$((n + 1)); [ $n -ge 30 ] && { echo "another start/stop is still in progress"; exit 0; }
            sleep 1
        done
        "$@"
    ) 9>>$D/start.lock
}

do_start() {   # $1 = start (the user's) | ensure (automatic)
    [ "$1" = ensure ] && [ "$(control)" = stop ] && { echo "stopped by user"; return; }
    if [ "$1" = start ]; then
        was_down=0; standing_down && was_down=1
        echo run > $D/control
        echo none > $D/standdown     # the user's start also retries after a stand-down
        [ $was_down = 1 ] && stop_all "$1" # replace a supervisor napping after the stand-down: retry now
    fi
    if supervisor_alive; then echo "already running"; return; fi
    setsid sh $D/start.sh supervise < /dev/null > /dev/null 2>&1 9>&- &
    sleep 1
    if supervisor_alive; then echo "started"; else echo "failed to start"; fi
}
do_restart() {
    [ "$(control)" = stop ] && { echo "stopped by user"; return; }
    echo none > $D/standdown     # a new app version may fix what the helper stood down for: try again
    stop_all restart
    do_start ensure
}
do_stop() {
    echo stop > $D/control
    stop_all stop
    echo "stopped"
}

case "$1" in
    start|ensure) locked do_start "$1" ;;
    restart)      locked do_restart ;;
    stop)         locked do_stop ;;
    status)
        if supervisor_alive && helper_alive; then echo "running"
        elif supervisor_alive && standing_down; then echo "running (supervisor only)"
        else echo "NOT running"; fi
        standing_down && cat $D/standdown
        [ "$(control)" = stop ] && echo "stopped by user"
        echo "build: $(getprop ro.build.fingerprint)$( [ "$(cat $D/verified 2>/dev/null)" = "$(getprop ro.build.fingerprint)" ] && echo ' (verified)' || echo ' (not yet verified)')"
        echo "--- log ---"
        tail -n "${2:-12}" $D/sleepguard.log 2>/dev/null
        ;;
    supervise)
        echo $$ > $D/supervisor.pid
        fails=0; retries=0
        while [ "$(control)" != stop ]; do
            [ "$(cat $D/supervisor.pid 2>/dev/null)" = "$$" ] || exit 0   # another supervisor took over
            if standing_down; then sleep 600; continue; fi              # retried after an update or 'start'
            # A helper left running by an earlier supervisor (only one can run): watch it instead.
            orphan=$(pids_of "$HLP" | head -1)
            if [ -n "$orphan" ]; then
                echo "$orphan" > $D/helper.pid
                slog "watching the helper that is already running (pid $orphan)"
                while [ -d /proc/$orphan ] && [ "$(control)" != stop ] && [ "$(cat $D/supervisor.pid 2>/dev/null)" = "$$" ]; do
                    sleep 1
                done
                if [ ! -d /proc/$orphan ]; then
                    slog "the watched helper (pid $orphan) exited"
                    repair   # it exited (maybe killed): put the real lid state back
                fi
                continue
            fi
            CP=$(classpath)
            if [ -z "$CP" ]; then
                # The package manager has no answer (busy boot, system services restarting): wait, never
                # run outdated code instead.
                slog "the Sleep Guard app's code is not available right now; trying again in 30 s"
                sleep 30
                continue
            fi
            # Keep the logs small (the older half is kept as .1).
            for f in sleepguard.log helper-stdout.log; do
                if [ -f $D/$f ] && [ "$(stat -c %s $D/$f)" -gt 2000000 ]; then mv $D/$f $D/$f.1; fi
            done
            t0=$(date +%s)
            CLASSPATH=$CP app_process / local.sleepguard.SleepGuard >> $D/helper-stdout.log 2>&1 &
            echo $! > $D/helper.pid
            wait $!
            code=$?
            echo "" > $D/helper.pid
            [ "$(control)" = stop ] && { repair; break; }
            slog "helper exited ($code)"
            ran=$(( $(date +%s) - t0 ))
            if [ $code = 7 ]; then sleep 30; continue; fi  # another helper runs (no repair: it holds the protection); adopt it next round
            repair
            [ $ran -ge 300 ] && retries=0               # it ran for a while: these are not failures in a row
            if [ $code = 6 ]; then
                # 6 = something only temporarily unavailable (busy boot, system services restarting):
                # retry every 30 s; after ~20 tries in a row only once an hour (never give up for good:
                # the condition is temporary by definition).
                retries=$((retries + 1))
                if [ $retries -ge 20 ]; then
                    slog "what Sleep Guard needs has been unavailable for a long time; trying again every hour"
                    sleep 3600
                    retries=10
                else
                    sleep 30
                fi
                continue
            fi
            if [ $ran -lt 30 ]; then fails=$((fails + 1)); else fails=0; fi
            if [ $fails -ge 10 ]; then
                # Keeps failing right after starting: stop retrying until the next update or 'start'
                # (the Sleep Guard app shows it).
                echo "STANDING DOWN: the helper keeps stopping right after it starts (exit code $code) | build=$(getprop ro.build.fingerprint)" > $D/standdown
                fails=0
                continue
            fi
            if [ $fails -ge 5 ]; then sleep 60; else sleep 2; fi
        done
        echo "" > $D/supervisor.pid
        ;;
    *)
        echo "usage: start.sh start|stop|ensure|restart|status"
        ;;
esac
