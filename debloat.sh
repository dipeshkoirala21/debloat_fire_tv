#!/usr/bin/env bash
# Debloats a Fire TV over adb: disables Amazon ad, tracking and promo services, blocks system
# updates, turns on ad-blocking DNS and speeds up animations. With --remove-apps it also
# uninstalls every third-party app except the ones in APPS (config.sh).
#
# Usage: ./debloat.sh <tv-ip[:port]> [--remove-apps] [--yes] [--no-dns] [--keep-updates]
# Everything here is undone by ./restore_firetv.sh (uninstalled apps must be reinstalled by hand).
set -uo pipefail
cd "$(dirname "$0")"

usage() { sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
[[ $# -ge 1 && $1 != -* ]] || usage
TV=$1; shift
REMOVE_APPS=0; ASSUME_YES=0; DNS=1; BLOCK_UPDATES=1
for arg in "$@"; do
  case $arg in
    --remove-apps) REMOVE_APPS=1 ;;
    --yes) ASSUME_YES=1 ;;
    --no-dns) DNS=0 ;;
    --keep-updates) BLOCK_UPDATES=0 ;;
    *) usage ;;
  esac
done

source ./config.sh
KEEP=("${APPS[@]}" com.custom.tvhome)

TV=$(tv_addr "$TV")
# </dev/null: adb would otherwise swallow the stdin of any loop it runs in.
sh_tv() { adb -s "$TV" shell "$@" </dev/null 2>&1 | tr -d '\r'; }

echo "Connecting to $TV ..."
adb connect "$TV" | grep -q "connected" || { echo "Can't connect to $TV. Is ADB debugging on?"; exit 1; }
if ! adb -s "$TV" shell true >/dev/null 2>&1; then
  echo "Accept the 'Allow USB debugging?' prompt on the TV (tick 'Always allow'), then run again."
  exit 1
fi
echo "Device: $(sh_tv getprop ro.product.model), $(sh_tv getprop ro.build.version.name)"

mkdir -p backup
[[ -f backup/packages_before.txt ]] || sh_tv pm list packages | sed 's/package://' | sort > backup/packages_before.txt

if (( REMOVE_APPS )); then
  remove=()
  while read -r p; do
    [[ -z $p || $p == com.amazon.* ]] && continue   # Amazon's own overlays show up as third-party
    keep=0; for k in "${KEEP[@]}"; do [[ $p == "$k" ]] && keep=1; done
    (( keep )) || remove+=("$p")
  done < <(sh_tv pm list packages -3 | sed 's/package://')
  if (( ${#remove[@]} )); then
    echo "Third-party apps to uninstall:"; printf '  %s\n' "${remove[@]}"
    if (( ! ASSUME_YES )); then
      read -r -p "Uninstall these ${#remove[@]} apps? [y/N] " ok
      [[ $ok == [yY]* ]] || { echo "Skipped app removal."; remove=(); }
    fi
    # (guarded: bash 3.2, the macOS default, errors on empty arrays under set -u)
    if (( ${#remove[@]} )); then
      for p in "${remove[@]}"; do printf '  uninstall %-40s %s\n' "$p" "$(sh_tv pm uninstall "$p")"; done
    fi
  else
    echo "No extra third-party apps to remove."
  fi
fi

echo "Disabling Amazon ad, tracking and promo services ..."
touch backup/disabled_packages.txt
for p in "${DISABLE[@]}"; do
  out=$(sh_tv pm disable-user --user 0 "$p")
  case $out in
    *"new state: disabled-user"*) echo "$p" >> backup/disabled_packages.txt; status=disabled ;;
    *"Unknown package"*) status="not on this TV" ;;
    *protected*) status="protected by Fire OS (DNS blocks its traffic)" ;;
    *) status="failed" ;;
  esac
  printf '  %-45s %s\n' "$p" "$status"
done
sort -u -o backup/disabled_packages.txt backup/disabled_packages.txt

if (( BLOCK_UPDATES )); then
  echo "Blocking Fire OS updates ..."
  out=$(sh_tv pm disable-user --user 0 "$UPDATE_NAG")
  [[ $out == *disabled-user* ]] && echo "$UPDATE_NAG" >> backup/disabled_packages.txt
  for p in "${UPDATE_PKGS[@]}"; do
    sh_tv pm suspend "$p" >/dev/null
    sh_tv cmd appops set "$p" RUN_ANY_IN_BACKGROUND ignore >/dev/null
    printf '  %-45s %s\n' "$p" "$(sh_tv dumpsys package "$p" | grep -o 'suspended=[a-z]*' | head -1)"
  done
  sort -u -o backup/disabled_packages.txt backup/disabled_packages.txt
fi

echo "Limiting ad tracking and speeding up animations ..."
sh_tv settings put secure limit_ad_tracking 1
sh_tv settings put secure amz_limit_ad_tracking 1
sh_tv settings put secure non_amz_limit_ad_tracking 1
sh_tv settings put secure usage_metrics_marketing_enabled 0
sh_tv settings put global window_animation_scale 0.5
sh_tv settings put global transition_animation_scale 0.5
sh_tv settings put global animator_duration_scale 1.0   # keeps the home screen's own animations smooth

if (( DNS )); then
  echo "Turning on ad-blocking DNS (AdGuard) ..."
  sh_tv settings put global private_dns_mode hostname
  sh_tv settings put global private_dns_specifier dns.adguard-dns.com
fi

echo "Done. Next: launcher/install.sh $TV  (then cleaner/start_cleaner.sh $TV)"
