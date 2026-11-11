#!/bin/sh
# Recreates MainActivity N times by changing the display density, and prints the
# Activities / Views counts from `dumpsys meminfo` after each recreation, after 60 s
# idle, and after `am send-trim-memory RUNNING_CRITICAL`.
#
# Start the app first, in the mode you want to measure. The script waits until the
# app's buttons are on screen before the first change, so a slow start (a debuggable
# build takes several seconds) does not skip the first recreation.
#
# Usage, from the repository root: ReproducerApp/scripts/recreate.sh [recreations=10] [settle_seconds=10]
# Set ANDROID_SERIAL to pick a device when more than one is connected.
set -eu
N=${1:-10}
SETTLE=${2:-10}
PKG=com.reproducerapp
UI_DUMP=/data/local/tmp/mvcp-repro-ui.xml

fail() {
  echo "recreate.sh: $*" >&2
  exit 1
}

adb shell pidof $PKG >/dev/null 2>&1 || fail "$PKG is not running. Start it first."

# Alternate between the current density and one 15% higher, so the first change is
# always a real change, whatever the device's current density is.
WM=$(adb shell wm density | tr -d '\r')
PHYSICAL=$(echo "$WM" | sed -n 's/Physical density: //p')
OVERRIDE=$(echo "$WM" | sed -n 's/Override density: //p')
[ -n "$PHYSICAL" ] || fail "could not read the display density: $WM"
CURRENT=${OVERRIDE:-$PHYSICAL}
OTHER=$(( (CURRENT * 115 + 50) / 100 ))

restore() {
  if [ -n "$OVERRIDE" ]; then adb shell wm density "$OVERRIDE" >/dev/null; else adb shell wm density reset >/dev/null; fi
}
trap restore EXIT

# Prints "Views=<n> Activities=<n>". Retries a failed read; exits if it still fails.
count() {
  tries=0
  while :; do
    out=$(adb shell dumpsys meminfo $PKG | tr -d '\r' |
      sed -nE 's/.* Views: +([0-9]+).*/Views=\1/p; s/.*Activities: +([0-9]+).*/Activities=\1/p' |
      tr '\n' ' ')
    case "$out" in *Activities=*) echo "$out"; return 0 ;; esac
    tries=$((tries + 1))
    [ $tries -lt 3 ] || fail "empty reading from 'dumpsys meminfo $PKG' (3 tries). Is the app still running?"
    sleep 2
  done
}

# Prints "<label>: Views=<n> Activities=<n>", or stops the script if the reading failed.
report() {
  c=$(count) || exit 1
  echo "$1: $c"
}

# Waits until the app's UI (rendered by JS) is on screen.
wait_for_app() {
  waited=0
  until adb shell "uiautomator dump $UI_DUMP >/dev/null 2>&1 && grep -qi 'remount hidden scrollview' $UI_DUMP"; do
    waited=$((waited + 2))
    [ $waited -le 60 ] || fail "the app's buttons did not appear within 60 s"
    sleep 2
  done
}

wait_for_app
echo "density: $CURRENT <-> $OTHER"
report start
i=1
while [ $i -le $N ]; do
  if [ $((i % 2)) -eq 1 ]; then adb shell wm density $OTHER; else adb shell wm density $CURRENT; fi
  sleep $SETTLE
  wait_for_app
  report "recreation $i"
  i=$((i + 1))
done
if [ $((N % 2)) -eq 1 ]; then
  adb shell wm density $CURRENT
  echo "(density set back: one more recreation)"
fi
echo "waiting 60 s..."
sleep 60
report "after 60 s idle"
adb shell am send-trim-memory $PKG RUNNING_CRITICAL
sleep 10
report "after trim"
