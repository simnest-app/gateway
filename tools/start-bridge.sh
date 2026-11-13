#!/bin/sh
# Starts the gateway's shell audio bridge on the phone over adb (stage 4 step 3b; 3c moves this into the app).
# It runs as the shell user, takes commands only from the gateway app's uid, and stops when the phone reboots.
# Usage: tools/start-bridge.sh [adb serial]
set -eu
ADB="${ADB:-adb}"
[ $# -gt 0 ] && ADB="$ADB -s $1"
PKG=app.simnest.gateway
APK=$($ADB shell pm path $PKG | sed -n 's/^package://p' | head -1 | tr -d '\r')
LIB=$(dirname "$APK")/lib/arm64
# BRIDGE_APK runs the bridge from a newer build pushed to the phone, without reinstalling the app
# (MIUI takes back the answer-calls permission on every reinstall). WebRTC's native library still comes from the app.
if [ -n "${BRIDGE_APK:-}" ]; then
  $ADB push "$BRIDGE_APK" /data/local/tmp/cloudsim-bridge.apk >/dev/null
  APK=/data/local/tmp/cloudsim-bridge.apk
fi
UID_=$($ADB shell pm list packages -U $PKG | sed -n "s/^package:$PKG uid://p" | tr -d '\r')
[ -n "$APK" ] && [ -n "$UID_" ] || { echo "gateway app not installed" >&2; exit 1; }
$ADB shell "pkill -f dev.cloudsim.gateway.shell.[B]ridgeMain; true"  # the bracket keeps pkill from matching this shell itself
$ADB shell "CLASSPATH=$APK nohup app_process /system/bin dev.cloudsim.gateway.shell.BridgeMain $UID_ $LIB /sdcard/Android/data/$PKG/files/bridge-token > /data/local/tmp/cloudsim-bridge.log 2>&1 &"
# The uplink is tried for 3 s after the start ("tx warmed" or "tx warm failed"): wait for that line.
sleep 5
$ADB shell cat /data/local/tmp/cloudsim-bridge.log
