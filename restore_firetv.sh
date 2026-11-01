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
sh_tv "pkill -f '[f]iretv_cleaner.sh'; rm -f /data/local/tmp/firetv_cleaner.sh"
adb -s "$TV" uninstall com.custom.tvhome
for p in "${APPS[@]}"; do
  sh_tv cmd appops set "$p" RUN_ANY_IN_BACKGROUND allow
done
# Re-enable what debloat.sh disabled on this TV; without its record, re-enable the whole list
# (enabling a package that isn't disabled is harmless).
if [[ -f backup/disabled_packages.txt ]]; then
  while read -r p; do [[ -n $p ]] && sh_tv pm enable "$p"; done < backup/disabled_packages.txt
else
  echo "backup/disabled_packages.txt not found; re-enabling every package debloat.sh can disable."
  for p in "${DISABLE[@]}"; do sh_tv pm enable "$p" 2>/dev/null; done
fi
sh_tv pm enable "$UPDATE_NAG"
for p in "${UPDATE_PKGS[@]}"; do
  sh_tv pm unsuspend $p
  sh_tv cmd appops reset $p
done
sh_tv settings delete global private_dns_specifier
sh_tv settings put global private_dns_mode off
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  sh_tv settings put global $k 1.0
done
echo "Restored. Reboot the TV to finish: adb -s $TV reboot"
