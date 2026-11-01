#!/system/bin/sh
# Runs on the Fire TV as the adb shell user (which, unlike apps, may force-stop other apps).
# Whenever the custom home screen opens, it force-stops the streaming apps so they don't sit in RAM.
# Fire OS ends this script on reboot; start it again with ./start_cleaner.sh from the Mac.

# Replaced from config.sh by start_cleaner.sh when it pushes this file.
APPS="com.netflix.ninja org.smarttube.stable com.amazon.firebat com.stremio.one com.amazon.firetv.youtube"

# Android 10+ logs app starts as ActivityTaskManager, Android 9 (Fire OS 7) as ActivityManager.
logcat -T 1 -v brief ActivityTaskManager:I ActivityManager:I '*:S' | while read -r line; do
  case "$line" in
    *"START u0"*"cmp=com.custom.tvhome/.HomeActivity"*) ;;
    *) continue ;;
  esac
  # Give the app the user left a moment to save its state.
  sleep 1.5
  # Only clean up if home is still in front (the user hasn't already opened another app).
  front=$(dumpsys activity activities | grep -m1 mResumedActivity)
  case "$front" in
    *com.custom.tvhome/*) ;;
    *) continue ;;
  esac
  for p in $APPS; do
    pidof "$p" >/dev/null && am force-stop "$p"
  done
done
