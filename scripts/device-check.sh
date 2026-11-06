#!/usr/bin/env bash
set -euo pipefail
mkdir -p evidence
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell appwidget grantbind --package dev.codex.usage --user 0

# Running the runner directly keeps the app and its private captures installed.
instrument() {
  local class_name="$1" report="$2"
  shift 2
  adb shell am instrument -w -r -e class "$class_name" "$@" \
    dev.codex.usage.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
  grep -Eq 'OK \([0-9]+ tests?\)' "$report"
}
instrument dev.codex.usage.DeviceTest evidence/device-tests.txt
for frame in dashboard widget-small widget-medium widget-large; do
  adb exec-out run-as dev.codex.usage cat files/"$frame.png" > "evidence/$frame.png"
done
python3 - <<'PY'
from pathlib import Path
import struct
for path in Path("evidence").glob("*.png"):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"Invalid screenshot: {path}"
    assert min(struct.unpack(">II", data[16:24])) > 100
PY

instrument dev.codex.usage.RebootTest evidence/before-reboot.txt -e phase before
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle enable deep
adb shell dumpsys deviceidle force-idle deep
adb shell dumpsys deviceidle get deep | tr -d '\r' | grep -qx IDLE
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb reboot
adb wait-for-device
until adb shell getprop sys.boot_completed | tr -d '\r' | grep -qx 1; do sleep 2; done
adb shell wm dismiss-keyguard
adb shell am start -W -n dev.codex.usage/.MainActivity | tee evidence/relaunch.txt
grep -q 'Status: ok' evidence/relaunch.txt
instrument dev.codex.usage.RebootTest evidence/after-reboot.txt -e phase after
