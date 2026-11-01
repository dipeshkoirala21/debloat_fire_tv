#!/usr/bin/env bash
# Starts the RAM cleaner on the Fire TV. Run again after the TV reboots or loses power
# (turning the TV off with the remote only puts the stick to sleep; the cleaner keeps running).
# Usage: ./start_cleaner.sh <tv-ip[:port]>
set -euo pipefail
cd "$(dirname "$0")"
source ../config.sh
[[ $# -ge 1 ]] || { echo "Usage: $0 <tv-ip[:port]>" >&2; exit 1; }
TV=$(tv_addr "$1")

adb connect "$TV" >/dev/null
# Push the script with the app list from config.sh filled in.
tmp=$(mktemp)
sed "s|^APPS=.*|APPS=\"${APPS[*]}\"|" firetv_cleaner.sh > "$tmp"
adb -s "$TV" push "$tmp" /data/local/tmp/firetv_cleaner.sh >/dev/null
rm -f "$tmp"
# Stop any copy already running, then start detached so it outlives this adb session.
# ([f] keeps the pattern from matching this command's own shell.)
adb -s "$TV" shell "pkill -f '[f]iretv_cleaner.sh'; true"
adb -s "$TV" shell 'setsid nohup sh /data/local/tmp/firetv_cleaner.sh >/dev/null 2>&1 < /dev/null &'
sleep 1
if adb -s "$TV" shell "pgrep -f '[f]iretv_cleaner.sh'" >/dev/null; then
  echo "RAM cleaner running on the TV."
else
  echo "RAM cleaner failed to start." >&2
  exit 1
fi
