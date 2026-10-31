#!/usr/bin/env bash
# Send a config draft to SparkWriter on a connected device or emulator, the way
# another app would. The app opens the SparkPlug's wizard on the first page that
# still needs input, or on Review.
#
#   tools/agent-test/send-draft.sh <plug-id> field=value [field=value ...]
#   tools/agent-test/send-draft.sh proxmox-autoinstall pve_fqdn=pve-lab.home.arpa \
#       pve_network_source=from-answer pve_network_cidr=10.0.0.5/24
#
# Set ANDROID_SERIAL to pick a device when more than one is connected.
set -euo pipefail
[ $# -ge 1 ] || { sed -n '2,10p' "$0"; exit 2; }
plug=$1; shift
adb=${ADB:-adb}
command -v "$adb" >/dev/null || adb="${ANDROID_HOME:?set ANDROID_HOME or ADB}/platform-tools/adb"

enc() { python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$1"; }
uri="spark://draft?plug=$(enc "$plug")"
for pair in "$@"; do
  [[ $pair == *=* ]] || { echo "not field=value: $pair" >&2; exit 2; }
  uri+="&$(enc "f.${pair%%=*}")=$(enc "${pair#*=}")"
done
echo "$uri"
# The remote shell re-parses the command line, so the URI travels single-quoted.
# enc() leaves no quote characters in it.
"$adb" shell "am start -W -a android.intent.action.VIEW -d '$uri' net.metalstrapper.sparkwriter"
