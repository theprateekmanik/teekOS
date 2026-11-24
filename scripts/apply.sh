#!/bin/zsh
# Re-applies the whole teekOS setup (after a rollback, OTA or factory reset with ADB on).
[[ -n "$TV" ]] || { echo "Set TV=<tv-ip>:5555 (your TV's address)"; exit 1; }; cd "${0:A:h}"
adb connect $TV >/dev/null
./debloat.sh
TV=$TV ./install.sh --enable-hook
# Cosmos standby: teekOS screensaver after 5 minutes idle (TV still powers off after its own sleep timer).
adb -s $TV shell settings put secure screensaver_components space.teek.tvos/.TeekDream
adb -s $TV shell settings put system screen_off_timeout 300000
echo "- $(date '+%F %T') screensaver_components → space.teek.tvos/.TeekDream; screen_off_timeout 600000 → 300000" >> ../CHANGELOG.md
