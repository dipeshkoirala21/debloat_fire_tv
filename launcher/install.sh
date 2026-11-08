#!/usr/bin/env bash
# Builds and installs the custom home screen on a Fire TV, then reboots it.
# Usage: ./install.sh <tv-ip[:port]>
set -euo pipefail
cd "$(dirname "$0")"
source ../config.sh
[[ $# -ge 1 ]] || { echo "Usage: $0 <tv-ip[:port]>" >&2; exit 1; }
TV=$(tv_addr "$1")

adb connect "$TV"
adb -s "$TV" shell true >/dev/null 2>&1 \
  || { echo "Accept the 'Allow USB debugging?' prompt on the TV, then run again." >&2; exit 1; }
./build.sh
adb -s "$TV" install -r out/tvhome.apk
# Lets the service see Home presses the instant they happen (no flash of Amazon's home).
adb -s "$TV" shell pm grant com.custom.tvhome android.permission.READ_LOGS
service=com.custom.tvhome/com.custom.tvhome.HomeButtonService
enabled=$(adb -s "$TV" shell settings get secure enabled_accessibility_services | tr -d '\r')
[[ "$enabled" == null ]] && enabled=""
case ":$enabled:" in
  *":$service:"*) ;;
  *) adb -s "$TV" shell settings put secure enabled_accessibility_services "${enabled:+$enabled:}$service" ;;
esac
adb -s "$TV" shell settings put secure accessibility_enabled 1
# Background-restrict the streaming apps: their services stop once they leave the screen.
if [[ ${APPS[@]+present} ]]; then
  for p in "${APPS[@]}"; do
    adb -s "$TV" shell cmd appops set "$p" RUN_ANY_IN_BACKGROUND ignore 2>/dev/null || true
  done
fi
# Compile ahead of time so animations are smooth from the first boot.
adb -s "$TV" shell cmd package compile -m speed -f com.custom.tvhome >/dev/null
# Fire OS only re-attaches the Home service reliably after a reboot.
adb -s "$TV" reboot
echo "Installed. The TV is rebooting into the new home screen."
echo "More Apps is available below Settings: select Settings, then press Down."
echo "Waiting for the TV to finish booting, then starting the RAM worker…"
# Do not leave a successful APK update with a dead worker. The reboot always ends it.
sleep 5
for attempt in {1..60}; do
  adb connect "$TV" >/dev/null 2>&1 || true
  boot=$(adb -s "$TV" shell getprop sys.boot_completed </dev/null 2>/dev/null | tr -d '\r' || true)
  if [[ "$boot" == 1 ]]; then
    if ../cleaner/start_cleaner.sh "$TV"; then
      echo "Installation complete. Home and automatic RAM cleanup are ready."
      exit 0
    fi
    echo "The TV is still settling after boot; retrying worker startup…"
  fi
  sleep 3
done
echo "The TV did not reconnect. After it boots, run: cleaner/start_cleaner.sh $TV" >&2
exit 1
