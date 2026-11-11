# Fire TV ad-free home screen

Tools for setting up an Amazon Fire TV over adb: debloat, block ads and updates, install a custom home screen, and free RAM. See README.md for what each part does and how users run it.

## Layout

- `config.sh`: shared lists sourced by the scripts. `APPS` is the user's apps; `DISABLE` is the Amazon packages debloat turns off.
- `debloat.sh <ip>`: disables `DISABLE`, suspends OTA, sets AdGuard Private DNS and animation scales. `--remove-apps` uninstalls third-party apps not in `APPS`. Records what it disabled in `backup/disabled_packages.txt`, which restore reads.
- `launcher/`: the home screen app (`com.custom.tvhome`), plain Java with no libraries. `build.sh` builds it without Gradle; `install.sh <ip>` builds, installs, grants permissions, enables the accessibility service and reboots.
  - `HomeView`: all UI, shared by the activity and the Home overlay so both draw identical frames.
  - `HomeButtonService`: accessibility service that takes over Home: logcat watcher, overlay, redirect.
  - `AppCatalog`: the app tiles. `Weather` and `WeatherIcon`: weather data and icons. `Backdrops`: pre-rendered backgrounds.
- `cleaner/`: `firetv_cleaner.sh` runs on the TV as the shell user and force-stops the apps when home opens. `start_cleaner.sh <ip>` fills in `APPS` from config.sh, pushes it and starts it.
- `restore_firetv.sh <ip>`: undoes everything.

## Working with a user's TV

- Ask for the TV's IP if it isn't given. Connect with `adb connect <ip>:5555`. The first connection needs the user to accept a prompt on the TV, so ask them to look at it.
- Check the model and OS first: `adb shell getprop ro.product.model` and `getprop ro.build.version.name`. The project was built and tested on AFTKM (Fire TV Stick 4K 2nd gen), Fire OS 8.1.
- Uninstalling apps is the user's call: show the list and confirm before running `--remove-apps`.
- Changing the app set means editing `config.sh` (`APPS`), `AppCatalog.java` (the tiles; the dock fits 5 apps + Settings), and the `<queries>` in `AndroidManifest.xml`.
- The scripts must run on macOS's bash 3.2: guard empty-array expansions under `set -u`, and give `adb shell` `</dev/null` inside `while read` loops.

## Fire OS facts (verified on device; they cost many rebuild cycles to find)

- **Never disable** `com.amazon.tv.launcher` (protected anyway), `com.amazon.vizzini` (Alexa voice on the remote), `com.amazon.tv.devicecontrol` (TV power/volume over CEC), `com.amazon.tv.livetv`, `com.amazon.venezia` (Appstore), or anything account- or DRM-related.
- Protected packages refuse `pm disable-user`; OTA can still be blocked with `pm suspend`.
- Fire OS never passes KEYCODE_HOME (or any key) to an accessibility key filter. Home is detected from logcat (`START ... cmp=com.amazon.tv.launcher/.ui.HomeActivity... from uid 0`, which needs READ_LOGS granted via `pm grant`), with TYPE_WINDOW_STATE_CHANGED as fallback. The START line is tagged ActivityTaskManager on Android 10+ and ActivityManager on Android 9 (Fire OS 7), so both tags are read.
- System Settings opens via a hop: `CTSDummySettingsHandler`, then Amazon's home for ~170 ms, then `MainSettingsActivity`. The service must not redirect during that hop; it logs "from uid <app>", not uid 0.
- **After `adb install -r`, the accessibility service gets no events until the TV reboots.** Always reboot before testing the Home button. `pm clear com.custom.tvhome` also removes it from `enabled_accessibility_services`.
- Apps can't free other apps' RAM: `killBackgroundProcesses` is silently ignored, and adbd drops connections from the TV itself (127.0.0.1 and its own IP). Only the shell user can, hence `cleaner/`. It dies on reboot or power loss but survives sleep.
- The GPU (PowerVR GE8300) janks on full-screen gradient drawables and live elevation shadows. Keep backdrops as pre-rendered bitmaps.
- Screen recording turns black while a DRM app (Netflix) is in front. Test transitions from SmartTube.

## Testing changes on the TV

- Rebuild and install with `launcher/install.sh <ip>`, then restart the cleaner after the reboot.
- Screenshot: `adb exec-out screencap -p > shot.png`. Taps don't work; navigate with `adb shell input keyevent KEYCODE_DPAD_*`, and read focus with `uiautomator dump`.
- Smoothness: `adb shell dumpsys gfxinfo com.custom.tvhome reset`, scroll the dock with D-pad key events, then read `Janky frames` and percentiles. Measure at least 3 minutes after boot; the first minutes are skewed by Fire OS startup load. Target: 0% janky.
- Home transition: `adb shell screenrecord`, press Home from SmartTube, and step through frames with ffmpeg.
