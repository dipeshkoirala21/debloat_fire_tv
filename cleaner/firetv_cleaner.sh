#!/system/bin/sh
# Runs as the adb shell user. A launcher app cannot acquire this privilege itself.
# Retries interrupted logcat readers; reboot/power loss still requires start_cleaner.sh.

# Replaced from config.sh by start_cleaner.sh when it pushes this file.
APPS="com.netflix.ninja org.smarttube.stable com.amazon.firebat com.stremio.one com.amazon.firetv.youtube"

STATE_DIR=${CLEANER_STATE_DIR:-/data/local/tmp/tvhome_cleaner}
LOG_FILE=${CLEANER_LOG_FILE:-/data/local/tmp/firetv_cleaner.log}
LOGCAT_PID=
READER_PID=

# Core Amazon services, Android services and this launcher must never be force-stopped.
# These two Amazon packages are the streaming apps deliberately included in config.sh.
safe_package() {
  case "$1" in
    ''|*[!a-zA-Z0-9_.]*|.*|*..*|*.) return 1 ;;
    com.amazon.firebat|com.amazon.firetv.youtube) return 0 ;;
    android|com.android.*|com.google.android.gms|com.google.android.gsf|com.amazon.*|com.custom.tvhome) return 1 ;;
    *.*) return 0 ;;
    *) return 1 ;;
  esac
}

note() {
  # Keep diagnostics bounded even if logcat repeatedly fails for days.
  if [ -f "$LOG_FILE" ] && [ "$(wc -c < "$LOG_FILE")" -gt 32768 ]; then
    mv -f "$LOG_FILE" "$LOG_FILE.old"
  fi
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG_FILE"
}

shutdown() {
  trap - EXIT HUP INT TERM
  if [ -f "$STATE_DIR/logcat_pid" ]; then LOGCAT_PID=$(cat "$STATE_DIR/logcat_pid"); fi
  case "$LOGCAT_PID" in ""|*[!0-9]*) ;; *) kill "$LOGCAT_PID" 2>/dev/null ;; esac
  if [ -n "$READER_PID" ]; then kill "$READER_PID" 2>/dev/null; wait "$READER_PID" 2>/dev/null; fi
  rm -f "$STATE_DIR/logcat_pid" "$STATE_DIR/pid" "$STATE_DIR/last_request" "$STATE_DIR/last_command"
  rmdir "$STATE_DIR" 2>/dev/null
}

umask 077
if ! mkdir "$STATE_DIR" 2>/dev/null; then
  echo "Cleaner state already exists; use start_cleaner.sh to replace it." >&2
  exit 1
fi
trap shutdown EXIT
trap 'exit 0' HUP INT TERM
printf '%s\n' "$$" > "$STATE_DIR/pid"
for package in $APPS; do
  if ! safe_package "$package"; then
    note "Refusing unsafe or protected package: $package"
    exit 1
  fi
done

home_is_front() {
  # Fire OS versions use either field name. Never rely on a log line alone:
  # the user may have opened another app while a cleanup request was queued.
  front=$(dumpsys activity activities </dev/null 2>/dev/null \
    | grep -m1 -E 'topResumedActivity|mResumedActivity')
  case "$front" in
    *com.custom.tvhome/*) return 0 ;;
    *) return 1 ;;
  esac
}

reply() {
  request_id=$1
  result=$2
  count=$3
  # The file lets start_cleaner.sh verify a complete request/response, not just a PID.
  printf '%s %s %s\n' "$request_id" "$result" "$count" > "$STATE_DIR/last_request"
  am broadcast --user 0 --receiver-foreground -a com.custom.tvhome.CLEANER_RESULT \
    -p com.custom.tvhome --es request_id "$request_id" --es result "$result" \
    --ei closed "$count" </dev/null >/dev/null 2>&1
}

clean() {
  closed=0
  outcome=cleaned
  # Give the app the user left a moment to save its state.
  sleep 1.5
  if ! home_is_front; then
    outcome=skipped
    return
  fi
  for package in $APPS; do
    if ! home_is_front; then
      outcome=skipped
      return
    fi
    # Do not let a child command consume the next event from the pipe.
    if pidof "$package" </dev/null >/dev/null 2>&1; then
      if am force-stop --user 0 "$package" </dev/null >/dev/null 2>&1; then
        closed=$((closed + 1))
      else
        outcome=error
        note "Could not close $package."
      fi
    fi
  done
}

handle_line() {
  case "$1" in
    *'/TVHomeCleaner('*': '*)
      payload=${1#*: }
      command=${payload%% *}
      request=${payload#* }
      # Log messages are untrusted. Accept only a fixed verb and a 128-bit hex ID.
      [ "$payload" = "$command $request" ] || return
      [ "${#request}" -eq 32 ] || return
      case "$request" in *[!0-9a-fA-F]*) return ;; esac
      case "$command" in CLEAN|PING) ;; *) return ;; esac
      # A launcher operation probes readiness and then cleans using the same ID.
      # Only an identical verb + ID is a replay (for example after logcat restarts).
      [ "$command $request" != "$(cat "$STATE_DIR/last_command" 2>/dev/null)" ] || return
      printf '%s\n' "$command $request" > "$STATE_DIR/last_command"
      if [ "$command" = PING ]; then
        reply "$request" ready 0
      else
        clean
        reply "$request" "$outcome" "$closed"
      fi
      ;;
    *"START u0"*"cmp=com.custom.tvhome/.HomeActivity"*)
      clean
      ;;
  esac
}

note "Cleaner started."
while :; do
  # Fire OS SELinux disallows named pipes here. Use an anonymous pipe and keep
  # the reader in the background so TERM interrupts wait and can reap logcat.
  (
    logcat -T 1 -v brief ActivityTaskManager:I ActivityManager:I TVHomeCleaner:I '*:S' 2>/dev/null &
    child=$!
    printf '%s\n' "$child" > "$STATE_DIR/logcat_pid"
    wait "$child"
  ) | while IFS= read -r line; do handle_line "$line"; done &
  READER_PID=$!
  wait "$READER_PID" 2>/dev/null
  READER_PID=
  note "Log stream ended; reconnecting."
  sleep 1
done
