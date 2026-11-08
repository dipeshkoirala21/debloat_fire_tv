#!/usr/bin/env bash
# Starts the shell-privileged RAM cleaner. Run after reboot or power loss.
# Log reader failures recover automatically, and the home screen can request a clean.
# Usage: ./start_cleaner.sh <tv-ip[:port]>
set -euo pipefail
cd "$(dirname "$0")"
source ../config.sh
[[ $# -eq 1 ]] || { echo "Usage: $0 <tv-ip[:port]>" >&2; exit 1; }
TV=$(tv_addr "$1")

adb connect "$TV" >/dev/null
connected=0
for attempt in 1 2 3; do
  if adb -s "$TV" shell true </dev/null >/dev/null 2>&1; then connected=1; break; fi
  sleep 2
  adb connect "$TV" >/dev/null 2>&1 || true
done
[[ "$connected" -eq 1 ]] || { echo "TV shell unavailable. Check the connection and any debugging prompt on the TV." >&2; exit 1; }

# Validate before inserting packages into shell source. Never interpolate commands from config.
app_list=""
if [[ ${APPS[@]+present} ]]; then
  for package in "${APPS[@]}"; do
    case "$package" in
      ''|*[!a-zA-Z0-9_.]*|.*|*..*|*.)
        echo "Invalid cleaner package in config.sh: $package" >&2; exit 1 ;;
      com.amazon.firebat|com.amazon.firetv.youtube) ;;
      android|com.android.*|com.google.android.gms|com.google.android.gsf|com.amazon.*|com.custom.tvhome)
        echo "Refusing to clean protected package: $package" >&2; exit 1 ;;
      *.*) ;;
      *) echo "Invalid cleaner package in config.sh: $package" >&2; exit 1 ;;
    esac
    app_list="${app_list}${app_list:+ }${package}"
  done
fi

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
sed "s|^APPS=.*|APPS=\"$app_list\"|" firetv_cleaner.sh > "$tmp"

# Stop the supervised copy first; its trap also stops its logcat child. The exact
# path fallback removes older copies without matching unrelated cleaner processes.
adb -s "$TV" shell '
  supervisor=/data/local/tmp/tvhome_cleaner_supervisor
  if [ -f "$supervisor/pid" ]; then
    manager=$(cat "$supervisor/pid")
    case "$manager" in ""|*[!0-9]*) manager= ;; esac
    if [ -n "$manager" ] && tr "\000" " " 2>/dev/null < "/proc/$manager/cmdline" | grep -q "sh /data/local/tmp/firetv_cleaner_supervisor[.]sh"; then
      kill "$manager"
      count=0
      while kill -0 "$manager" 2>/dev/null && [ "$count" -lt 12 ]; do sleep 1; count=$((count + 1)); done
      if kill -0 "$manager" 2>/dev/null; then echo "Supervisor did not stop." >&2; exit 1; fi
    fi
  fi
  rm -f "$supervisor/events" "$supervisor/pid" "$supervisor/worker_pid" "$supervisor/logcat_pid" "$supervisor/request" "$supervisor/request.tmp" "$supervisor/ready"
  rmdir "$supervisor" 2>/dev/null || true
  state=/data/local/tmp/tvhome_cleaner
  if [ -f "$state/pid" ]; then
    pid=$(cat "$state/pid")
    case "$pid" in ""|*[!0-9]*) pid= ;; esac
    if [ -n "$pid" ] && tr "\000" " " 2>/dev/null < "/proc/$pid/cmdline" | grep -Eq "(^|/)sh /data/local/tmp/firetv_cleaner[.]sh *$"; then
      kill "$pid" 2>/dev/null
      count=0
      while kill -0 "$pid" 2>/dev/null && [ "$count" -lt 5 ]; do
        sleep 1
        count=$((count + 1))
      done
      if kill -0 "$pid" 2>/dev/null; then
        echo "Previous cleaner did not stop; leaving it intact." >&2
        exit 1
      fi
    fi
  fi
  old_pids=$(pgrep -f "(^|/)sh /data/local/tmp/firetv_cleaner[.]sh$" 2>/dev/null || true)
  for old_pid in $old_pids; do
    # Older scripts used a pipeline without a trap. Stop their direct children,
    # including logcat, before stopping the shells identified by the exact path.
    ps -A -o PID,PPID | while read -r child parent; do
      if [ "$parent" = "$old_pid" ]; then kill "$child" 2>/dev/null || true; fi
    done
    kill "$old_pid" 2>/dev/null || true
  done
  count=0
  while pgrep -f "(^|/)sh /data/local/tmp/firetv_cleaner[.]sh$" >/dev/null && [ "$count" -lt 5 ]; do
    sleep 1
    count=$((count + 1))
  done
  if pgrep -f "(^|/)sh /data/local/tmp/firetv_cleaner[.]sh$" >/dev/null; then
    echo "Previous cleaner is still running; no replacement started." >&2
    exit 1
  fi
  rm -f "$state/events" "$state/pid" "$state/last_request" "$state/last_command" "$state/logcat_pid"
  rmdir "$state" 2>/dev/null || true
' </dev/null
adb -s "$TV" push "$tmp" /data/local/tmp/firetv_cleaner.sh >/dev/null
adb -s "$TV" push firetv_cleaner_supervisor.sh /data/local/tmp/firetv_cleaner_supervisor.sh >/dev/null
adb -s "$TV" shell 'setsid nohup sh /data/local/tmp/firetv_cleaner_supervisor.sh >/dev/null 2>&1 </dev/null &' </dev/null

# A live shell can still have a dead log reader. Verify that a real PING is handled.
request_id=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  adb -s "$TV" shell "log -p i -t TVHomeCleaner 'PING $request_id'" </dev/null
  sleep 1
  response=$(adb -s "$TV" shell cat /data/local/tmp/tvhome_cleaner/last_request </dev/null 2>/dev/null | tr -d '\r' || true)
  if [[ "$response" == "$request_id ready 0" ]]; then
    # The worker can answer before its supervisor finishes starting. Wait for
    # both, otherwise setup could report success just before the controller exits.
    if adb -s "$TV" shell '
      state=/data/local/tmp/tvhome_cleaner_supervisor
      ready=$(cat "$state/ready" 2>/dev/null)
      worker=$(cat "$state/worker_pid" 2>/dev/null)
      manager=$(cat "$state/pid" 2>/dev/null)
      case "$ready:$worker:$manager" in *[!0-9:]*) exit 1 ;; esac
      [ -n "$ready" ] && [ "$ready" = "$worker" ] && [ -n "$manager" ] \
        && kill -0 "$worker" && kill -0 "$manager"
    ' </dev/null >/dev/null 2>&1; then
      echo "RAM cleaner and supervisor are responding. Restart worker is ready in More Apps."
      exit 0
    fi
  fi
done
echo "RAM cleaner did not respond. Diagnostics: adb -s $TV shell cat /data/local/tmp/firetv_cleaner.log" >&2
exit 1
