#!/bin/zsh
# Installs/updates teekOS on the TV.
#   --enable-hook  turn on the Home-button hook (accessibility service) — the way teekOS becomes Home
#   --set-home     set-home-activity (has no effect on this TV: Google TV's Home priority wins)
# Google TV stays fully installed and enabled; rollback.sh switches back.
[[ -n "$TV" ]] || { echo "Set TV=<tv-ip>:5555 (your TV's address)"; exit 1; }; cd "${0:A:h}"
adb connect $TV >/dev/null
adb -s $TV install -r ../launcher/teekOS.apk || exit 1
echo "- $(date '+%F %T') installed/updated space.teek.tvos" >> ../CHANGELOG.md
# Cinematic Dashboard reads app recommendations from the TV provider (runtime permission).
adb -s $TV shell pm grant space.teek.tvos android.permission.READ_TV_LISTINGS 2>/dev/null
if [[ "$1" == "--set-home" ]]; then
  adb -s $TV shell cmd package set-home-activity space.teek.tvos/.HomeActivity
  echo "- $(date '+%F %T') set-home-activity space.teek.tvos/.HomeActivity (was launcherx)" >> ../CHANGELOG.md
fi
if [[ "$1" == "--picture" ]]; then
  # Control Center backlight/brightness sliders write the TV's picture_* global settings.
  adb -s $TV shell pm grant space.teek.tvos android.permission.WRITE_SECURE_SETTINGS
  echo "- $(date '+%F %T') pm grant space.teek.tvos WRITE_SECURE_SETTINGS (picture sliders)" >> ../CHANGELOG.md
fi
if [[ "$1" == "--now-playing" ]]; then
  # Lets the Control Center read active media sessions (title, art, playback) for Now Playing.
  adb -s $TV shell cmd notification allow_listener space.teek.tvos/space.teek.tvos.MediaListener
  echo "- $(date '+%F %T') notification listener allowed: space.teek.tvos/.MediaListener (Now Playing)" >> ../CHANGELOG.md
fi
if [[ "$1" == "--enable-hook" ]]; then
  # Our service is the only one on this TV (list was empty before teekOS: see backup/).
  adb -s $TV shell settings put secure enabled_accessibility_services space.teek.tvos/.HomeKeyService
  adb -s $TV shell settings put secure accessibility_enabled 1
  echo "- $(date '+%F %T') accessibility: enabled_accessibility_services (null) → space.teek.tvos/.HomeKeyService; accessibility_enabled 0 → 1" >> ../CHANGELOG.md
fi
adb -s $TV shell am start -n space.teek.tvos/.HomeActivity >/dev/null
