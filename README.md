# teekOS

An Apple TV–inspired home screen for Android TV / Google TV. Liquid Glass dock, a cinematic panel with
what your apps recommend, a compact Control Center with Now Playing, and a night-sky standby screen.
No root, no ads from the stock launcher's "For you" rows, and fully reversible: uninstall it and your
normal home screen is back.

Works on **Android TV / Google TV 8.0 and newer** (Sony, TCL, Philips, Xiaomi, OnePlus, Chromecast
with Google TV, Nvidia Shield…). It finds your TV's own launcher by itself, so the Home button works
whichever one your TV has. Developed and tested on a OnePlus TV (Google TV, Android 11).

What varies by TV:
- **Cinematic panel:** shows what your apps publish to Android's TV recommendations. Some TVs
  restrict this; teekOS then shows each app's own banner instead.
- **Backlight / brightness sliders:** only on TVs that keep picture settings the way MediaTek-based
  sets do (OnePlus, many TCL/Xiaomi). Elsewhere the Picture button opens the TV's own picture settings.
- **PlayStation tile:** appears only when a console announces itself over HDMI-CEC.
- **Fire TV** isn't supported (Amazon's launcher works differently).

## Install (no computer needed)

1. On the TV, install **Downloader** (by AFTVnews) from the Play Store.
2. Turn on developer options: Settings → System → About → click **Android TV OS build** 7 times.
3. Allow installs from Downloader: Settings → Apps → Security & restrictions → **Unknown sources** →
   Downloader → On.
4. Open Downloader and enter the link to `teekOS.apk` from the
   [latest release](../../releases/latest). Download, then **Install**.
5. Open **teekOS** from your apps.
6. Make the Home button open teekOS: Settings → Accessibility → **teekOS Home button** → On.
   (Google TV won't let other apps be the default home, so teekOS listens for the Home button
   instead. It reads no screen content.)

**Remote:** Home opens teekOS. **Hold Home** for the Control Center. The **gauge** button (top right, and in
the Control Center) cleans up: closes other apps and clears junk, keeping teekOS, the app you're in and
anything playing. For the full clean, keep **Network debugging** on in Developer options (the TV may ask
once to allow teekOS); without it the gauge just closes apps running in the background. On a tile, **hold OK** for options
(Move, Add to Dock, Hide).

## Optional extras (need ADB from a computer)

```bash
TV=<tv-ip>:5555 ./scripts/install.sh --now-playing   # Now Playing in the Control Center
TV=<tv-ip>:5555 ./scripts/install.sh --picture       # Backlight / brightness sliders
TV=<tv-ip>:5555 ./scripts/debloat.sh                 # Disable OEM extras listed in scripts/packages.debloat
```

Everything is undone with `TV=<tv-ip>:5555 ./scripts/rollback.sh`.

## Uninstall

Settings → Apps → teekOS → Uninstall. Google TV is the home screen again straight away; it is never
disabled or modified.

## Build from source

Needs JDK 17 and the Android SDK (build-tools 34.0.0, platform android-34). No Gradle.

```bash
cd launcher
SHARE=1 ./build.sh                 # teekOS-share.apk: no personal name, PS5 tile only if a console is detected
TEEKOS_NAME=Alex ./build.sh        # teekOS.apk greeting "Welcome back, Alex"
```

Signing uses `~/.android/teekos.keystore` (created on first build) with the password from
`TEEKOS_KS_PASS` or `~/.android/teekos.keystore.pass`.

## Credits

See [NOTICE.md](NOTICE.md) for fonts, logos and icons. Code under the [MIT licence](LICENSE).
