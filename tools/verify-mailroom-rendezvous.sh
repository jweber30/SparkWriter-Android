#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: verify-mailroom-rendezvous.sh [--source /dev/sda|/path/to/image.iso]

Reads auto-installer-mode.toml from the source image, extracts the Mailroom
rendezvous URL, and checks whether it (and sibling labels) are currently
available.
USAGE
  exit 2
}

SOURCE="${1:-/dev/sda}"
if [[ "$#" -gt 0 && "$1" == "--source" ]]; then
  if [[ "${2:-}" == "" ]]; then
    usage
  fi
  SOURCE="$2"
fi

if ! command -v 7z >/dev/null 2>&1; then
  echo "Missing dependency: 7z" >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "Missing dependency: curl" >&2
  exit 1
fi

if [[ ! -e "$SOURCE" ]]; then
  echo "Source does not exist: $SOURCE" >&2
  exit 1
fi

extract_url() {
  7z x "$SOURCE" auto-installer-mode.toml -so \
  | awk -F'"' '/^[[:space:]]*url[[:space:]]*=/ {print $2; exit}'
}

RANDOMIZER_URL="$(extract_url | tr -d '\r')"
if [[ -z "$RANDOMIZER_URL" ]]; then
  echo "No url found in auto-installer-mode.toml on $SOURCE" >&2
  exit 1
fi

check_url() {
  local label="$1"
  local url="$2"
  local code
  code="$(curl -sS -o /tmp/sparkwriter-rendezvous-check.body -w '%{http_code}' "$url" || echo 000)"
  rm -f /tmp/sparkwriter-rendezvous-check.body
  case "$code" in
    200)
      echo "  ✅ $label -> $url [200]"
      return 0
      ;;
    000)
      echo "  ⚠️  $label -> $url [network error or DNS]"
      return 1
      ;;
    *)
      echo "  ❌ $label -> $url [$code]"
      return 1
      ;;
  esac
}

BASE_URL="${RANDOMIZER_URL%/*}"
CANDIDATES=("$RANDOMIZER_URL" "$BASE_URL/boot" "$BASE_URL/answer" "$BASE_URL/firstboot")

# Preserve order while removing duplicates.
UNIQUE=()
declare -A seen
for candidate in "${CANDIDATES[@]}"; do
  if [[ -z "${seen[$candidate]-}" ]]; then
    UNIQUE+=("$candidate")
    seen[$candidate]=1
  fi
done

echo "Extracted rendezvous URL: $RANDOMIZER_URL"
echo
echo "Probing rendezvous labels:"

FAILED=0
for candidate in "${UNIQUE[@]}"; do
  if [[ "$candidate" != "${candidate#*/netboot/v1/rendezvous/}" ]]; then
    label="${candidate##*/}"
  else
    label="(unknown)"
  fi
  if check_url "$label" "$candidate"; then
    if [[ "$candidate" == "$RANDOMIZER_URL" ]]; then
      echo
      echo "This is expected for your current ISO manifest."
    fi
  else
    FAILED=1
  fi
  echo

done

if (( FAILED == 1 )); then
  echo "Tip: if the exact URL is 404, the write-ready PUT likely did not publish the artifact for that label." >&2
  echo "Re-run write or ensure required write_ready delivery succeeds." >&2
  exit 1
fi

echo "Rendezvous checks passed."
