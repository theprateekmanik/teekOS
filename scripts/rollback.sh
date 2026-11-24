#!/bin/zsh
# teekOS full rollback: restores the stock Google TV experience.
#   1. re-enables every debloated package, turns off the Home hook, restores the screensaver
#   2. makes Google TV (launcherx) the home again
#   3. uninstalls the teekOS launcher (pass --keep-launcher to skip)
[[ -n "$TV" ]] || { echo "Set TV=<tv-ip>:5555 (your TV's address)"; exit 1; }; cd "${0:A:h}"
adb connect $TV >/dev/null
grep -v '^#' packages.debloat | grep . | while read p; do
  echo "enable $p: $(adb -s $TV shell pm enable --user 0 $p </dev/null 2>&1)"
done
adb -s $TV shell pm revoke space.teek.tvos android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
adb -s $TV shell cmd notification disallow_listener space.teek.tvos/space.teek.tvos.MediaListener 2>/dev/null
adb -s $TV shell settings delete secure enabled_accessibility_services
adb -s $TV shell settings put secure accessibility_enabled 0
adb -s $TV shell settings delete secure screensaver_components
adb -s $TV shell settings put system screen_off_timeout 600000   # original (backup/20261023-1959)
adb -s $TV shell pm enable com.google.android.apps.tv.launcherx >/dev/null 2>&1
adb -s $TV shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
[[ "$1" == "--keep-launcher" ]] || adb -s $TV uninstall space.teek.tvos 2>/dev/null
adb -s $TV shell input keyevent KEYCODE_HOME
echo "- $(date '+%F %T') ROLLBACK run" >> ../CHANGELOG.md
echo "Rollback done. Google TV is home again."
