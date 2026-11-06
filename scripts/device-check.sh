#!/usr/bin/env bash
set -euo pipefail
mkdir -p evidence
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell appwidget grantbind --package dev.codex.usage --user 0
./gradlew :app:connectedDebugAndroidTest
for frame in dashboard widget-small widget-medium widget-large; do
  adb exec-out run-as dev.codex.usage cat files/"$frame.png" > "evidence/$frame.png"
  test -s "evidence/$frame.png"
done
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce
adb reboot
adb wait-for-device
until adb shell getprop sys.boot_completed | tr -d '\r' | grep -qx 1; do sleep 2; done
adb shell input keyevent 82
adb shell am start -W -n dev.codex.usage/.MainActivity | grep -q 'Status: ok'
