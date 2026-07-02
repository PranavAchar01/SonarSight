#!/usr/bin/env bash
# One-time prep of the Galaxy A23 5G (SM-A236U1) as a dedicated SixthSense edge hub.
# No root required (US Samsung bootloader is locked — ADB-level only).
#
#   scripts/prepare_a23.sh            # settings tweaks + report
#   scripts/prepare_a23.sh --debloat  # also uninstall bloat for user 0 (reversible)
#   scripts/prepare_a23.sh --restore  # reinstall everything the debloat removed
#
# Debloat uses `pm uninstall --user 0`, which keeps the APK on the system
# partition; --restore brings any package back with `cmd package install-existing`.
# com.facebook.* is deliberately NOT touched — the Meta AI app (glasses pairing)
# lives in that namespace and shares services.

set -u
cd "$(dirname "$0")/.." || exit 1
source scripts/adb_common.sh

# Conservative bloat list for SM-A236U1 (unlocked, so little carrier junk).
# Everything here is unrelated to camera, BLE, networking, TTS, or Meta AI.
BLOAT=(
  com.samsung.android.bixby.agent          # Bixby assistant
  com.samsung.android.bixby.wakeup
  com.samsung.android.bixbyvision.framework
  com.samsung.android.app.spage            # Samsung Free feed
  com.samsung.android.app.tips
  com.samsung.android.arzone               # AR Zone
  com.samsung.android.ardrawing
  com.samsung.android.game.gamehome        # Game Launcher
  com.samsung.android.game.gametools
  com.samsung.android.tvplus               # Samsung TV Plus
  com.samsung.android.kidsinstaller        # Samsung Kids
  com.samsung.android.app.appsedge
  com.samsung.sree                         # Global Goals
  com.microsoft.skydrive                   # OneDrive
  com.microsoft.office.officehubrow        # Office
  com.microsoft.appmanager                 # Link to Windows
  com.linkedin.android
  com.netflix.partner.activation
  com.samsung.android.spay                 # Samsung Pay/Wallet
  com.samsung.android.spayfw
)

do_debloat() {
  echo "== Debloating (reversible, user 0 only) =="
  for pkg in "${BLOAT[@]}"; do
    if ss_adb shell pm list packages --user 0 "$pkg" | grep -q "$pkg"; then
      echo "  removing $pkg"
      ss_adb shell pm uninstall --user 0 "$pkg" >/dev/null 2>&1 || echo "    (skip: not removable)"
    fi
  done
}

do_restore() {
  echo "== Restoring debloated packages =="
  for pkg in "${BLOAT[@]}"; do
    ss_adb shell cmd package install-existing "$pkg" >/dev/null 2>&1 && echo "  restored $pkg"
  done
}

do_settings() {
  echo "== Runtime settings =="
  # Kill animation jank and keep the device demo-ready
  ss_adb shell settings put global window_animation_scale 0
  ss_adb shell settings put global transition_animation_scale 0
  ss_adb shell settings put global animator_duration_scale 0
  ss_adb shell settings put global stay_on_while_plugged_in 7   # never sleep on power
  ss_adb shell settings put system screen_off_timeout 1800000   # 30 min on battery
  # Stop the OS from strangling our background pipeline
  ss_adb shell settings put global adaptive_battery_management_enabled 0
  ss_adb shell settings put global app_standby_enabled 0
  # Doze exemptions: our app + Meta AI companion (glasses session survives pocket time)
  ss_adb shell dumpsys deviceidle whitelist +"$SS_PACKAGE" 2>/dev/null
  ss_adb shell dumpsys deviceidle whitelist +com.facebook.stella 2>/dev/null
  echo "  done"
}

do_compile() {
  echo "== AOT-compiling installed SixthSense build (if present) =="
  ss_adb shell cmd package compile -m speed -f "$SS_PACKAGE" 2>/dev/null \
    || echo "  ($SS_PACKAGE not installed yet — rerun after first install)"
}

report() {
  echo "== Device report =="
  ss_adb shell getprop ro.product.model
  ss_adb shell getprop ro.build.version.release
  ss_adb shell "grep MemTotal /proc/meminfo; grep MemAvailable /proc/meminfo"
  echo
  echo "Manual One UI steps ADB cannot do (Settings app, ~3 min):"
  echo "  1. Battery > Background usage limits > Never sleeping apps: add SixthSense + Meta AI"
  echo "  2. Device care: turn OFF Auto optimization / auto-restart"
  echo "  3. Device care > Memory > RAM Plus: set to minimum (or off) and reboot"
  echo "  4. Play Store > Settings: disable auto-update apps (until after July 9)"
  echo "  5. Remove any case for demo runs — the SD695 throttles on heat"
}

case "${1:-}" in
  --debloat) do_debloat; do_settings; do_compile; report ;;
  --restore) do_restore ;;
  *)         do_settings; do_compile; report ;;
esac
