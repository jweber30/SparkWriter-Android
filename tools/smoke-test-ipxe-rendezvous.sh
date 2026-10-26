#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_ISO="${SOURCE_ISO:-$REPO_ROOT/sparkwriter-ipxe-dual-protocol.iso}"
QEMU_TIMEOUT_SECONDS="${QEMU_TIMEOUT_SECONDS:-45}"
LABEL="${LABEL:-SMOKETEST1111111111111111111111111111111111}"
EXPECTED_PATH="/netboot/v1/rendezvous/$LABEL"
HTTP_PLACEHOLDER="http://mailroom.metalstrapper.net/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required command: $command_name" >&2
    exit 1
  fi
}

require_command python3
require_command qemu-system-x86_64
require_command timeout

if [ ! -f "$SOURCE_ISO" ]; then
  echo "Generic iPXE ISO not found: $SOURCE_ISO" >&2
  exit 1
fi

if ! [[ "$LABEL" =~ ^[A-Za-z0-9_-]{43}$ ]]; then
  echo "LABEL must be exactly 43 base64url-safe characters" >&2
  exit 1
fi

tmpdir="$(mktemp -d)"
server_log="$tmpdir/server.log"
qemu_log="$tmpdir/qemu.log"
port_file="$tmpdir/port"
hit_file="$tmpdir/hit"
patched_iso="$tmpdir/sparkwriter-ipxe-rendezvous-smoke.iso"
server_pid=""

cleanup() {
  if [ -n "$server_pid" ] && kill -0 "$server_pid" >/dev/null 2>&1; then
    kill "$server_pid" >/dev/null 2>&1 || true
    wait "$server_pid" >/dev/null 2>&1 || true
  fi
  rm -rf "$tmpdir"
}
trap cleanup EXIT

cp "$SOURCE_ISO" "$patched_iso"

python3 - "$patched_iso" "$HTTP_PLACEHOLDER" "http://10.0.2.2:PORT$EXPECTED_PATH" <<'PY'
import pathlib
import sys

iso_path = pathlib.Path(sys.argv[1])
placeholder = sys.argv[2].encode("ascii")
replacement_template = sys.argv[3]

data = iso_path.read_bytes()
matches = data.count(placeholder)
if matches != 1:
    raise SystemExit(f"Expected exactly one HTTP placeholder, found {matches}")

if replacement_template.count(":PORT/") != 1:
    raise SystemExit("Internal smoke-test error: replacement URL template must contain :PORT/")

# Leave the port as a temporary four-byte marker. The server chooses its real
# port after binding, then a second patch rewrites the whole URL slot with
# space padding so the ISO size remains unchanged even for five-digit ports.
replacement_bytes = replacement_template.encode("ascii")
if len(replacement_bytes) > len(placeholder):
    raise SystemExit("Replacement URL exceeds placeholder length")

patched = data.replace(placeholder, replacement_bytes + (b" " * (len(placeholder) - len(replacement_bytes))), 1)
iso_path.write_bytes(patched)
PY

python3 - "$EXPECTED_PATH" "$port_file" "$hit_file" >"$server_log" 2>&1 <<'PY' &
import http.server
import pathlib
import socketserver
import sys

expected_path = sys.argv[1]
port_file = pathlib.Path(sys.argv[2])
hit_file = pathlib.Path(sys.argv[3])

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        print(f"GET {self.path}", flush=True)
        if self.path == expected_path:
            hit_file.write_text(self.path)
            payload = b"#!ipxe\necho METALSTRAPPER_RENDEZVOUS_SMOKE_OK\nshell\n"
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ipxe")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        self.send_response(404)
        self.end_headers()

    def log_message(self, format, *args):
        return

with socketserver.TCPServer(("127.0.0.1", 0), Handler) as server:
    port_file.write_text(str(server.server_address[1]))
    server.serve_forever()
PY
server_pid="$!"

for _ in $(seq 1 100); do
  if [ -s "$port_file" ]; then
    break
  fi
  sleep 0.05
done

if [ ! -s "$port_file" ]; then
  echo "Rendezvous smoke server failed to start" >&2
  cat "$server_log" >&2 || true
  exit 1
fi

port="$(cat "$port_file")"
replacement_url="http://10.0.2.2:$port$EXPECTED_PATH"

python3 - "$patched_iso" "$HTTP_PLACEHOLDER" "http://10.0.2.2:PORT$EXPECTED_PATH" "$replacement_url" <<'PY'
import pathlib
import sys

iso_path = pathlib.Path(sys.argv[1])
original_placeholder = sys.argv[2].encode("ascii")
marker_url = sys.argv[3].encode("ascii")
replacement_url = sys.argv[4].encode("ascii")
data = iso_path.read_bytes()
matches = data.count(marker_url)
if matches != 1:
    raise SystemExit(f"Expected exactly one temporary port marker URL, found {matches}")
if len(replacement_url) > len(original_placeholder):
    raise SystemExit("Replacement URL exceeds placeholder slot")

slot = replacement_url + (b" " * (len(original_placeholder) - len(replacement_url)))
patched = data.replace(marker_url + (b" " * (len(original_placeholder) - len(marker_url))), slot, 1)
if len(patched) != len(data):
    raise SystemExit("ISO patch changed file size")
iso_path.write_bytes(patched)
PY

echo "Booting generic iPXE ISO with rendezvous URL: $replacement_url"

set +e
timeout "$QEMU_TIMEOUT_SECONDS"s qemu-system-x86_64 \
  -m 512 \
  -cdrom "$patched_iso" \
  -boot d \
  -netdev user,id=n0 \
  -device e1000,netdev=n0 \
  -nographic \
  -serial mon:stdio \
  >"$qemu_log" 2>&1
qemu_status="$?"
set -e

if [ -f "$hit_file" ]; then
  echo "iPXE rendezvous smoke test passed: observed GET $EXPECTED_PATH"
  exit 0
fi

echo "iPXE rendezvous smoke test failed: no GET $EXPECTED_PATH observed" >&2
echo "--- server log ---" >&2
cat "$server_log" >&2 || true
echo "--- qemu log ---" >&2
cat "$qemu_log" >&2 || true
echo "--- qemu exit status: $qemu_status ---" >&2
exit 1
