#!/usr/bin/env bash
# Undoes everything: removes the custom home screen and RAM cleaner, re-enables the packages
# debloat.sh disabled, re-allows system updates, and resets DNS and animations.
# Apps uninstalled with --remove-apps must be reinstalled from the Appstore.
# Usage: ./restore_firetv.sh <tv-ip[:port]>
set -uo pipefail
cd "$(dirname "$0")"
source ./config.sh
[[ $# -ge 1 ]] || { echo "Usage: $0 <tv-ip[:port]>" >&2; exit 1; }
TV=$(tv_addr "$1")
# </dev/null: adb would otherwise swallow the stdin of the loop reading the package list.
sh_tv() { adb -s "$TV" shell "$@" </dev/null; }

adb connect "$TV"
sh_tv settings put secure enabled_accessibility_services '""'
sh_tv settings put secure accessibility_enabled 0
cleaner_stopped=1
sh_tv '
  supervisor=/data/local/tmp/tvhome_cleaner_supervisor
  if [ -f "$supervisor/pid" ]; then
    manager=$(cat "$supervisor/pid")
    case "$manager" in ""|*[!0-9]*) manager= ;; esac
    if [ -n "$manager" ] && tr "\000" " " < "/proc/$manager/cmdline" 2>/dev/null | grep -q "sh /data/local/tmp/firetv_cleaner_supervisor[.]sh"; then
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
      # The supervised cleaner owns its logcat child and reaps it on SIGTERM.
      kill "$pid" 2>/dev/null
      count=0
      while kill -0 "$pid" 2>/dev/null && [ "$count" -lt 5 ]; do
        sleep 1
        count=$((count + 1))
      done
      if kill -0 "$pid" 2>/dev/null; then
        echo "Cleaner did not stop; leaving its files intact." >&2
        exit 1
      fi
    fi
  fi
  # Old versions used a pipeline without a trap. Match only this script path,
  # and stop their child readers too; never kill every logcat process on the TV.
  old_pids=$(pgrep -f "(^|/)sh /data/local/tmp/firetv_cleaner[.]sh$" 2>/dev/null || true)
  for old_pid in $old_pids; do
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
    echo "Cleaner is still running; leaving its files intact." >&2
    exit 1
  fi
  rm -f "$state/events" "$state/pid" "$state/last_request" "$state/last_command" "$state/logcat_pid"
  rmdir "$state" 2>/dev/null || true
  rm -f /data/local/tmp/firetv_cleaner.sh /data/local/tmp/firetv_cleaner_supervisor.sh /data/local/tmp/firetv_cleaner.log /data/local/tmp/firetv_cleaner.log.old
' || cleaner_stopped=0
adb -s "$TV" uninstall com.custom.tvhome
if [[ ${APPS[@]+present} ]]; then
  for p in "${APPS[@]}"; do
    sh_tv cmd appops set "$p" RUN_ANY_IN_BACKGROUND allow
  done
fi
# Re-enable what debloat.sh disabled on this TV; without its record, re-enable the whole list
# (enabling a package that isn't disabled is harmless).
if [[ -f backup/disabled_packages.txt ]]; then
  while read -r p; do [[ -n $p ]] && sh_tv pm enable "$p"; done < backup/disabled_packages.txt
else
  echo "backup/disabled_packages.txt not found; re-enabling every package debloat.sh can disable."
  if [[ ${DISABLE[@]+present} ]]; then
    for p in "${DISABLE[@]}"; do sh_tv pm enable "$p" 2>/dev/null; done
  fi
fi
sh_tv pm enable "$UPDATE_NAG"
if [[ ${UPDATE_PKGS[@]+present} ]]; then
  for p in "${UPDATE_PKGS[@]}"; do
    sh_tv pm unsuspend $p
    sh_tv cmd appops reset $p
  done
fi
sh_tv settings delete global private_dns_specifier
sh_tv settings put global private_dns_mode off
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  sh_tv settings put global $k 1.0
done
if [[ $cleaner_stopped -eq 0 ]]; then
  echo "Settings restored, but the cleaner could not be removed. Reboot, then run restore again." >&2
  exit 1
fi
echo "Restored. Reboot the TV to finish: adb -s $TV reboot"
