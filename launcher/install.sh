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
adb -s "$TV" shell settings put secure enabled_accessibility_services com.custom.tvhome/com.custom.tvhome.HomeButtonService
adb -s "$TV" shell settings put secure accessibility_enabled 1
# Background-restrict the streaming apps: their services stop once they leave the screen.
for p in "${APPS[@]}"; do
  adb -s "$TV" shell cmd appops set "$p" RUN_ANY_IN_BACKGROUND ignore 2>/dev/null || true
done
# Compile ahead of time so animations are smooth from the first boot.
adb -s "$TV" shell cmd package compile -m speed -f com.custom.tvhome >/dev/null
# Fire OS only re-attaches the Home service reliably after a reboot.
adb -s "$TV" reboot
echo "Installed. The TV is rebooting into the new home screen."
echo "When it is back up (about a minute), run: cleaner/start_cleaner.sh $TV"
