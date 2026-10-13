#!/bin/bash
# Builds SleepGuard.apk: the Sleep Guard app plus the helper (local.sleepguard.SleepGuard), which the
# app starts on the device with "app_process" straight from this APK.
# Never deletes anything: every build goes into a fresh build/<timestamp> directory.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/tools/android-sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
HELPER=$(cd .. && pwd)                       # the repository root (helper source and device/start.sh)
CONF=${SLEEP_GUARD_CONF:-$HOME/.config/sleep-guard}
KS=$CONF/app-signing.jks
VERSION_CODE=${VERSION_CODE:-$(( $(date +%s) / 60 ))}   # always increasing (no downgrade rejections)
VERSION_NAME=${VERSION_NAME:-1.$VERSION_CODE}

mkdir -p "$CONF" && chmod 700 "$CONF"
# Keystore password: KS_PASS from the environment if set, otherwise a random password that is
# created once and kept in $CONF/app-signing.pass (never in this repository).
if [ -z "${KS_PASS:-}" ]; then
    if [ ! -f "$CONF/app-signing.pass" ]; then
        (umask 077; head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$CONF/app-signing.pass")
    fi
    KS_PASS=$(cat "$CONF/app-signing.pass")
fi
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias sleepguard \
        -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Sleep Guard (local build)" >/dev/null 2>&1
    chmod 600 "$KS"
fi

B=build/$(date +%Y%m%d-%H%M%S)
mkdir -p "$B/classes" "$B/gen" assets
cp "$HELPER/device/start.sh" assets/start.sh
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml -A assets \
    --min-sdk-version 34 --target-sdk-version 35 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$B/gen" "$B/res.zip"
javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d "$B/classes" \
    "$B/gen/local/sleepguard/R.java" src/local/sleepguard/*.java "$HELPER"/src/local/sleepguard/SleepGuard.java
"$BT/d8" --min-api 34 --lib "$JAR" --output "$B" $(find "$B/classes" -name '*.class')
python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
    "$B/unsigned.apk" "$B/classes.dex"
"$BT/zipalign" -p -f 4 "$B/unsigned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out SleepGuard.apk "$B/aligned.apk"
"$BT/apksigner" verify SleepGuard.apk
cp "$B/classes.dex" "$HELPER/sleepguard.dex"   # same helper code as a fallback for start.sh
echo "Built $(pwd)/SleepGuard.apk (version $VERSION_NAME)"
