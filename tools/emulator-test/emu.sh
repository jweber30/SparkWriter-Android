#!/usr/bin/env bash
# Emulators for SparkWriter device tests, with an optional virtual USB stick.
#
#   emu.sh create-go <avd>               Android 9 (API 28) low-RAM phone, 480x854
#   emu.sh prepare <avd> [--low-ram]     one time: declare USB host (and Go low-RAM mode)
#   emu.sh start <avd> [stick.img]       boot headless; with a stick, attach it over USB
#   emu.sh check-stick <stick.img>       compare the stick image with the last test write
#   emu.sh stop
#
# See README.md in this directory.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
HERE="$(cd "$(dirname "$0")" && pwd)"
STICK_BYTES="${STICK_BYTES:-256M}"
LOG_DIR="${TMPDIR:-/tmp}"

die() { echo "emu.sh: $*" >&2; exit 1; }

api_of() {
    local sysdir
    sysdir="$(sed -n 's/^image\.sysdir\.1 *= *//p' "$AVD_HOME/$1.avd/config.ini")"
    [ -n "$sysdir" ] || die "no AVD named $1"
    # Canary images report e.g. 37.2.
    sed -n 's/^AndroidVersion\.ApiLevel=\([0-9]*\).*/\1/p' "$SDK/$sysdir/source.properties"
}

wait_for_boot() {
    for _ in $(seq 1 120); do
        [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && return 0
        pgrep -f "qemu-system|emulator/emulator" >/dev/null || die "emulator exited; see $LOG_DIR/emu-*.log"
        sleep 3
    done
    die "device did not finish booting"
}

emulator_running() { pgrep -f "qemu-system.*-avd $1( |$)" >/dev/null; }

start() {
    local avd="$1" stick="${2:-}" usb=()
    emulator_running "$avd" && die "$avd is already running"
    if [ -n "$stick" ]; then
        [ -f "$stick" ] || truncate -s "$STICK_BYTES" "$stick"
        stick="$(realpath "$stick")"
        # Android 9's emulator kernel has EHCI (USB 2.0) but no xHCI.
        local bus=xhci controller=qemu-xhci
        if [ "$(api_of "$avd")" -lt 30 ]; then bus=ehci controller=usb-ehci; fi
        usb=(-qemu -device "$controller,id=$bus"
            -blockdev "driver=raw,node-name=usbstick,file.driver=file,file.filename=$stick"
            -device "usb-storage,bus=$bus.0,drive=usbstick")
    fi
    local log="$LOG_DIR/emu-$avd.log"
    # -writable-system keeps the changes made by `prepare`.
    setsid nohup "$EMULATOR" -avd "$avd" -no-window -no-audio -no-snapshot -no-boot-anim \
        -gpu swiftshader_indirect -writable-system "${usb[@]}" >"$log" 2>&1 &
    echo "Booting $avd (log: $log)"
    wait_for_boot
    "$ADB" shell settings put global window_animation_scale 0
    "$ADB" shell settings put global transition_animation_scale 0
    "$ADB" shell settings put global animator_duration_scale 0
    echo "$avd ready: API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')," \
        "low_ram=$("$ADB" shell getprop ro.config.low_ram | tr -d '\r')"
    if [ -n "$stick" ]; then
        "$ADB" shell dumpsys usb | grep -q "QEMU USB HARDDRIVE" \
            && echo "Virtual stick visible to apps: $stick" \
            || echo "Stick attached, but apps cannot see it yet: run 'emu.sh prepare $avd' once"
    fi
}

stop() {
    "$ADB" emu kill >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do pgrep -f qemu-system >/dev/null || return 0; sleep 1; done
}

remount() {
    "$ADB" root >/dev/null; sleep 2; "$ADB" wait-for-device
    local out
    out="$("$ADB" remount 2>&1 || true)"
    if grep -qi reboot <<<"$out"; then
        # First remount disables verity, which needs a reboot.
        "$ADB" reboot || true
        sleep 5
        wait_for_boot
        "$ADB" root >/dev/null; sleep 2; "$ADB" wait-for-device
        out="$("$ADB" remount 2>&1)"
    fi
    grep -qi "succeeded" <<<"$out" || die "remount failed: $out"
}

prepare() {
    local avd="$1" low_ram="${2:-}"
    stop
    start "$avd"
    if [ "$(api_of "$avd")" -ge 31 ]; then
        echo "Note: on API 31+ remount adds an overlay scratch image of several GB to $avd's data."
    fi
    remount
    # Emulator images leave out android.hardware.usb.host, so UsbManager lists no devices.
    "$ADB" push "$HERE/android.hardware.usb.host.xml" /system/etc/permissions/ >/dev/null
    if [ "$low_ram" = "--low-ram" ]; then
        "$ADB" shell 'grep -q "^ro.config.low_ram=" /system/build.prop ||
            printf "\n# SparkWriter: Android Go low-RAM mode\nro.config.low_ram=true\n" >> /system/build.prop'
    fi
    "$ADB" shell sync
    # Rebooting a writable-system emulator can crash it; a cold start is reliable.
    stop
    echo "Prepared $avd. Start it with: emu.sh start $avd [stick.img]"
}

create_go() {
    local avd="$1" dir="$AVD_HOME/$1.avd"
    [ -d "$dir" ] && die "$avd already exists"
    "$SDK/cmdline-tools/latest/bin/sdkmanager" "system-images;android-28;google_apis;x86_64"
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$avd" \
        -k "system-images;android-28;google_apis;x86_64" -d "Nexus S"
    python3 - "$dir/config.ini" <<'EOF'
import sys
path = sys.argv[1]
kv = {}
for line in open(path).read().splitlines():
    if "=" in line:
        key, value = line.split("=", 1)
        kv[key.strip()] = value.strip()
kv.update({
    # The emulator raises API 28 x86_64 to 1536 MB whatever is asked; low_ram mode does the rest.
    "hw.ramSize": "1024", "vm.heapSize": "128", "hw.cpu.ncore": "2",
    "hw.lcd.width": "480", "hw.lcd.height": "854", "hw.lcd.density": "240",
    "hw.keyboard": "yes", "hw.gpu.enabled": "yes", "hw.gpu.mode": "swiftshader_indirect",
    "disk.dataPartition.size": "2G", "sdcard.size": "128 MB",
})
for key in ("skin.name", "skin.path", "skin.dynamic"):
    kv.pop(key, None)
open(path, "w").write("".join(f"{k}={v}\n" for k, v in kv.items()))
EOF
    # The emulator refuses to start unless 1.2x a 6 GB data partition is free on the host.
    # A sparse pre-made image skips that check and only uses what is written.
    truncate -s 2G "$dir/userdata-qemu.img"
    "$SDK/emulator/bin64/mkfs.ext4" -q -L data "$dir/userdata-qemu.img"
    echo "Created $avd. Next: emu.sh prepare $avd --low-ram"
}

check_stick() {
    local stick="$1" line sha bytes actual
    line="$("$ADB" logcat -d -s VirtualUsbStickTest | grep STICK_IMAGE | tail -1)"
    [ -n "$line" ] || die "no STICK_IMAGE line in logcat; run VirtualUsbStickTest first"
    sha="$(sed -n 's/.*sha256=\([0-9a-f]*\).*/\1/p' <<<"$line")"
    bytes="$(sed -n 's/.*bytes=\([0-9]*\).*/\1/p' <<<"$line")"
    actual="$(head -c "$bytes" "$stick" | sha256sum | cut -d' ' -f1)"
    if [ "$actual" = "$sha" ]; then
        echo "OK: first $bytes bytes of $stick match what the test wrote ($sha)"
    else
        die "MISMATCH: test wrote $sha, $stick has $actual"
    fi
}

case "${1:-}" in
    create-go) create_go "${2:?avd name}" ;;
    prepare) prepare "${2:?avd name}" "${3:-}" ;;
    start) start "${2:?avd name}" "${3:-}" ;;
    stop) stop ;;
    check-stick) check_stick "${2:?stick image}" ;;
    *) sed -n '2,10p' "$0"; exit 2 ;;
esac
