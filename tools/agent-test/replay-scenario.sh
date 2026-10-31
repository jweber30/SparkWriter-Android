#!/usr/bin/env bash
# Send a scenario's expected values to SparkWriter on a device, as if an
# assistant had understood the prompt perfectly. Useful for eyeballing where a
# draft lands, and as the "known good" side when checking a real assistant.
#
#   tools/agent-test/replay-scenario.sh tools/agent-test/scenarios/proxmox-static-vlan.properties
set -euo pipefail
[ $# -eq 1 ] || { sed -n '2,7p' "$0"; exit 2; }
here=$(cd "$(dirname "$0")" && pwd)
mapfile -t args < <(python3 - "$1" <<'PY'
import sys
props, key = {}, None
for raw in open(sys.argv[1], encoding="utf-8"):
    line = raw.rstrip("\n")
    if key is not None:                     # continuation of the previous line
        props[key] += line.strip().rstrip("\\")
        if not line.endswith("\\"): key = None
        continue
    if not line.strip() or line.lstrip().startswith("#"): continue
    k, _, v = line.partition("=")
    props[k.strip()] = v.rstrip("\\")
    key = k.strip() if line.endswith("\\") else None
print(" ".join(props.get("prompt", "").split()))
print(props.get("lands", "?"))
print(props["plug"])
for k, v in props.items():
    if k.startswith("expect."): print(f"{k[7:]}={v}")
PY
)
echo "prompt: ${args[0]}"
echo "expect to land on: ${args[1]}"
"$here/send-draft.sh" "${args[@]:2}"
