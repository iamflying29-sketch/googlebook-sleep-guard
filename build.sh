#!/bin/bash
# Builds sleepguard.dex (runs on the Googlebook via app_process as the adb shell user).
# Uses a fresh timestamped build directory (never deletes anything).
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/tools/android-sdk}
JAR=$SDK/platforms/android-35/android.jar
OUT=build/$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT/classes"
javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d "$OUT/classes" src/local/sleepguard/*.java
"$SDK/build-tools/35.0.0/d8" --min-api 33 --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
cp "$OUT/classes.dex" sleepguard.dex
echo "Built $(pwd)/sleepguard.dex"
