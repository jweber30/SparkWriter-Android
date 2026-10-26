#!/usr/bin/env bash
set -euo pipefail

SOURCE_ISO="${SOURCE_ISO:-$HOME/debian-13.5.0-amd64-netinst.iso}"
MAILROOM_ORIGIN="${MAILROOM_ORIGIN:-https://mailroom.metalstrapper.net}"
WORK_DIR="${WORK_DIR:-$(pwd)/tools/.debian-https-preseed-test}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_DIR="$WORK_DIR/$RUN_ID"
OUTPUT_ISO="${OUTPUT_ISO:-$RUN_DIR/debian-13.5.0-amd64-netinst-https-preseed.iso}"
AUTHORIZED_KEYS_URL="${AUTHORIZED_KEYS_URL:-}"
GITHUB_USER="${GITHUB_USER:-}"
PRESEED_URL="${PRESEED_URL:-}"
TEST_USERNAME="${TEST_USERNAME:-debian}"
TEST_PASSWORD="${TEST_PASSWORD:-SparkWriterQemuTest123!}"
TEST_PASSWORD_HASH="${TEST_PASSWORD_HASH:-}"

info() {
  printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"
}

fail() {
  printf '[%s] ERROR: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

require_command curl
require_command python3
require_command xorriso
require_command 7z

[ -f "$SOURCE_ISO" ] || fail "SOURCE_ISO not found: $SOURCE_ISO"

mkdir -p "$RUN_DIR"

if [ -z "$AUTHORIZED_KEYS_URL" ] && [ -n "$GITHUB_USER" ]; then
  AUTHORIZED_KEYS_URL="https://github.com/${GITHUB_USER}.keys"
fi

if [ -z "$TEST_PASSWORD_HASH" ]; then
  require_command openssl
  TEST_PASSWORD_HASH="$(openssl passwd -6 -salt SparkHttpsTest "$TEST_PASSWORD")"
fi

preseed_path="$RUN_DIR/preseed.cfg"
mailroom_json="$RUN_DIR/mailroom.json"
grub_dir="$RUN_DIR/grub"
patched_grub="$RUN_DIR/grub.cfg"

cat >"$preseed_path" <<EOF
# SparkWriter HTTPS preseed smoke test.
# Intentionally does not preseed partitioning. It should stop at disk selection.
d-i debian-installer/locale string en_US.UTF-8
d-i keyboard-configuration/xkb-keymap select us
d-i netcfg/choose_interface select auto
d-i netcfg/get_hostname string https-smoke
d-i netcfg/get_domain string local
d-i mirror/country string manual
d-i mirror/http/hostname string deb.debian.org
d-i mirror/http/directory string /debian
d-i mirror/http/proxy string
d-i clock-setup/utc boolean true
d-i time/zone string UTC
d-i passwd/root-login boolean false
d-i passwd/make-user boolean true
d-i passwd/user-fullname string Debian HTTPS Smoke
d-i passwd/username string $TEST_USERNAME
d-i passwd/user-password-crypted password $TEST_PASSWORD_HASH
d-i partman/early_command string echo SPARKWRITER_HTTPS_PRESEED_REACHED_DISK_DETECT >/dev/tty2; logger SPARKWRITER_HTTPS_PRESEED_REACHED_DISK_DETECT; list-devices disk >/dev/tty2
EOF

if [ -n "$AUTHORIZED_KEYS_URL" ]; then
  cat >>"$preseed_path" <<EOF
d-i anna/choose_modules string network-console
d-i network-console/authorized_keys_url string $AUTHORIZED_KEYS_URL
d-i network-console/start note
EOF
fi

if [ -z "$PRESEED_URL" ]; then
  info "Mining and registering Mailroom client token"
  python3 - "$RUN_DIR" "$MAILROOM_ORIGIN" <<'PY'
import base64
import hashlib
import json
import os
import sys
from pathlib import Path

run_dir = Path(sys.argv[1])
origin = sys.argv[2].rstrip("/")

def b64u(byte_count):
    return base64.urlsafe_b64encode(os.urandom(byte_count)).decode("ascii").rstrip("=")

client_id = b64u(32)
rendezvous_id = b64u(32)
domain = b"netboot-client-work-v1\0"
origin_bytes = origin.encode("utf-8")
client_bytes = client_id.encode("utf-8")

for _ in range(25_000_000):
    nonce = os.urandom(8)
    digest = hashlib.sha256(domain + origin_bytes + b"\0" + client_bytes + b"\0" + nonce).digest()
    if int.from_bytes(digest, "big") >> (256 - 20) == 0:
        token = "v1:" + base64.urlsafe_b64encode(nonce).decode("ascii").rstrip("=")
        break
else:
    raise SystemExit("failed to mine Mailroom client token")

preseed_url = f"{origin}/netboot/v1/rendezvous/{rendezvous_id}/preseed"
(run_dir / "mailroom.json").write_text(
    json.dumps(
        {
            "client_id": client_id,
            "token": token,
            "rendezvous_id": rendezvous_id,
            "preseed_url": preseed_url,
        },
        indent=2,
    ),
    encoding="utf-8",
)
print(preseed_url)
PY
  PRESEED_URL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["preseed_url"])' "$mailroom_json")"
  client_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["client_id"])' "$mailroom_json")"
  token="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$mailroom_json")"

  info "Registering Mailroom token"
  curl -fsS \
    -X POST "$MAILROOM_ORIGIN/netboot/v1/clients/$client_id/tokens" \
    -H 'Content-Type: application/json' \
    --data-binary "{\"token\":\"$token\"}" \
    -o "$RUN_DIR/token-response.json"

  info "Publishing HTTPS preseed"
  curl -fsS \
    -X PUT "$PRESEED_URL" \
    -H "X-Netboot-Client-Id: $client_id" \
    -H 'Content-Type: text/plain' \
    --data-binary "@$preseed_path" \
    -o "$RUN_DIR/put-response.txt"
else
  info "Using provided PRESEED_URL; skipping Mailroom publish"
fi

if [ "${SKIP_PRESEED_VERIFY:-0}" = "1" ]; then
  info "Skipping HTTPS preseed GET verification"
else
  info "Verifying HTTPS preseed GET"
  curl -fsS "$PRESEED_URL" -o "$RUN_DIR/preseed-fetched.cfg"
fi

info "Extracting GRUB config"
mkdir -p "$grub_dir"
7z x -y -o"$grub_dir" "$SOURCE_ISO" boot/grub/grub.cfg >/dev/null

info "Patching UEFI GRUB menu"
python3 - "$grub_dir/boot/grub/grub.cfg" "$patched_grub" "$PRESEED_URL" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
preseed_url = sys.argv[3]
text = source.read_text(encoding="utf-8")

entry = f"""set default=0
set timeout=8

menuentry 'SparkWriter HTTPS preseed smoke test' {{
    set background_color=black
    linux    /install.amd/vmlinuz auto=true priority=critical interface=auto url={preseed_url} --- quiet
    initrd   /install.amd/initrd.gz
}}

"""

marker = "menuentry "
index = text.find(marker)
if index < 0:
    raise SystemExit("could not find first GRUB menuentry")
target.write_text(text[:index] + entry + text[index:], encoding="utf-8")
PY

info "Rebuilding ISO"
mkdir -p "$(dirname "$OUTPUT_ISO")"
xorriso \
  -indev "$SOURCE_ISO" \
  -outdev "$OUTPUT_ISO" \
  -map "$patched_grub" /boot/grub/grub.cfg \
  -boot_image any replay \
  >/dev/null

info "Done"
printf 'Output ISO: %s\n' "$OUTPUT_ISO"
printf 'Preseed URL: %s\n' "$PRESEED_URL"
printf 'Run dir: %s\n' "$RUN_DIR"
if [ -n "$AUTHORIZED_KEYS_URL" ]; then
  printf 'SSH keys URL: %s\n' "$AUTHORIZED_KEYS_URL"
fi
printf 'Test user: %s\n' "$TEST_USERNAME"
printf 'Test password: %s\n' "$TEST_PASSWORD"
printf '\nMailroom TTL is short; write/boot this ISO soon.\n'
