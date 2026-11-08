#!/system/bin/sh
# A small shell-privileged controller owns the automatic worker. The TV's Restart
# worker button replaces that child; neither process can survive a power loss.
STATE=/data/local/tmp/tvhome_cleaner_supervisor
WORKER=/data/local/tmp/firetv_cleaner.sh
WORKER_STATE=/data/local/tmp/tvhome_cleaner
worker_pid=
reader_pid=
last_request=
umask 077

stop_worker() {
  rm -f "$STATE/ready"
  if [ -n "$worker_pid" ]; then
    # Each worker has its own session. Reap its readers too, even after SIGKILL
    # removed the parent before its shutdown trap could run.
    kill -TERM -- "-$worker_pid" 2>/dev/null || kill "$worker_pid" 2>/dev/null || true
    # The worker traps TERM, reaps its logcat, and removes its own private state.
    n=0
    while kill -0 "$worker_pid" 2>/dev/null && [ "$n" -lt 5 ]; do
      sleep 1
      n=$((n + 1))
    done
    if kill -0 "$worker_pid" 2>/dev/null; then return 1; fi
    wait "$worker_pid" 2>/dev/null || true
  fi
  worker_pid=
}

start_worker() {
  # This state belongs only to our dead child; never reuse a live worker's files.
  rm -f "$WORKER_STATE/events" "$WORKER_STATE/pid" "$WORKER_STATE/last_request" "$WORKER_STATE/last_command" "$WORKER_STATE/logcat_pid"
  rmdir "$WORKER_STATE" 2>/dev/null || true
  setsid sh "$WORKER" </dev/null >/dev/null 2>&1 &
  # Fire OS setsid may fork: $! can be a short-lived launcher, not the worker.
  # The worker writes its own shell PID after acquiring its state directory.
  worker_pid=
  n=0
  while [ "$n" -lt 6 ]; do
    sleep 1
    n=$((n + 1))
    [ -f "$WORKER_STATE/pid" ] || continue
    IFS= read -r worker_pid < "$WORKER_STATE/pid"
    case "$worker_pid" in ""|*[!0-9]*) worker_pid=; continue ;; esac
    kill -0 "$worker_pid" 2>/dev/null || continue
    printf '%s\n' "$worker_pid" > "$STATE/worker_pid"
    ping_id=$(printf '%032x' "$worker_pid")
    log -p i -t TVHomeCleaner "PING $ping_id"
    sleep 1
    response=$(cat "$WORKER_STATE/last_request" 2>/dev/null)
    # Setup may probe concurrently. Any ready response from this fresh worker
    # proves its reader is alive; requiring our ID races the installer probe.
    case "$response" in
      *" ready 0") printf '%s\n' "$worker_pid" > "$STATE/ready"; return 0 ;;
    esac
  done
  return 1
}

reply() {
  am broadcast --user 0 --receiver-foreground -a com.custom.tvhome.CLEANER_RESULT \
    -p com.custom.tvhome --es request_id "$1" --es result "$2" \
    --ei worker_pid "${worker_pid:-0}" </dev/null >/dev/null 2>&1
}

shutdown() {
  trap - EXIT HUP INT TERM
  if [ -f "$STATE/logcat_pid" ]; then
    log_pid=$(cat "$STATE/logcat_pid")
    case "$log_pid" in ""|*[!0-9]*) ;; *) kill "$log_pid" 2>/dev/null ;; esac
  fi
  if [ -n "$reader_pid" ]; then kill "$reader_pid" 2>/dev/null; wait "$reader_pid" 2>/dev/null; fi
  stop_worker
  rm -f "$STATE/pid" "$STATE/worker_pid" "$STATE/logcat_pid" "$STATE/request" "$STATE/request.tmp" "$STATE/ready"
  rmdir "$STATE" 2>/dev/null
}

mkdir "$STATE" 2>/dev/null || exit 1
trap shutdown EXIT
trap 'exit 0' HUP INT TERM
printf '%s\n' "$$" > "$STATE/pid"
start_worker || exit 1

# Only the parent controls worker lifetime. The log reader posts fixed request IDs
# through an atomically replaced file; no arbitrary command strings are executed.
(
  while :; do
    (
      logcat -T 1 -v brief TVHomeCleaner:I '*:S' 2>/dev/null &
      child=$!
      printf '%s\n' "$child" > "$STATE/logcat_pid"
      wait "$child"
    ) | while IFS= read -r line; do
      case "$line" in
        *'/TVHomeCleaner('*': RESTART '*)
          request=${line##*: RESTART }
          case "$request" in ''|*[!0-9a-fA-F]*) continue ;; esac
          [ "${#request}" -eq 32 ] || continue
          printf '%s\n' "$request" > "$STATE/request.tmp"
          mv -f "$STATE/request.tmp" "$STATE/request"
          ;;
      esac
    done
    sleep 1
  done
) &
reader_pid=$!
while :; do
  request=
  if [ -f "$STATE/request" ]; then IFS= read -r request < "$STATE/request"; fi
  if [ -n "$request" ] && [ "$request" != "$last_request" ]; then
    last_request=$request
    if stop_worker && start_worker; then reply "$request" restarted
    else reply "$request" error; fi
  fi
  if [ -z "$worker_pid" ] || ! kill -0 "$worker_pid" 2>/dev/null; then
    stop_worker || true
    start_worker || sleep 2
  fi
  sleep 1
done
