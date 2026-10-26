#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKDIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
IPXE_REPO="${IPXE_REPO:-$WORKDIR/../iPXE}"
SCRIPT_FILE="${SCRIPT_FILE:-$WORKDIR/tools/generic-ipxe-placeholder.ipxe}"
OUTPUT_FILE="${OUTPUT_FILE:-$WORKDIR/sparkwriter-ipxe-dual-protocol.iso}"

if [[ ! -f "$SCRIPT_FILE" ]]; then
  echo "Missing iPXE script: $SCRIPT_FILE" >&2
  exit 1
fi

if [[ ! -d "$IPXE_REPO" ]]; then
  echo "Missing iPXE checkout at $IPXE_REPO" >&2
  echo "Set IPXE_REPO to an alternate path before running." >&2
  exit 1
fi

echo "Building $IPXE_REPO/src/bin/ipxe.lkrn ..."
(
  cd "$IPXE_REPO"
  DEBUG= make bin/ipxe.lkrn
)

echo "Packing ISO with generic iPXE script ..."
DEBUG= "$IPXE_REPO/util/genfsimg" -o "$OUTPUT_FILE" -s "$SCRIPT_FILE" "$IPXE_REPO/src/bin/ipxe.lkrn"

echo "Wrote $OUTPUT_FILE"
ls -lh "$OUTPUT_FILE"
