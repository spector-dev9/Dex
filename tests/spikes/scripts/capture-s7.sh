#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'Usage: %s <application-id> <output-directory>\n' "$0" >&2
  exit 2
fi

application_id=$1
output_dir=$2
mkdir -p "$output_dir"

adb shell getprop ro.build.fingerprint > "$output_dir/build-fingerprint.txt"
adb shell dumpsys power > "$output_dir/dumpsys-power.txt"
adb shell dumpsys deviceidle > "$output_dir/dumpsys-deviceidle.txt"
adb shell dumpsys battery > "$output_dir/dumpsys-battery.txt"
adb shell dumpsys activity services "$application_id" > "$output_dir/dumpsys-activity-services.txt"
adb shell dumpsys activity exit-info "$application_id" > "$output_dir/dumpsys-exit-info.txt"
adb logcat -d -v threadtime | grep -Eia 'am_kill|lmkd|lowmemorykiller|FATAL EXCEPTION|Fatal signal|Killing .*opencode|Killing .*spike' > "$output_dir/logcat-kills-crashes.txt" || true