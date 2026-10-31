#!/usr/bin/env bash
# Boot an assembled Proxmox ISO in QEMU/KVM (UEFI), in two phases:
#   install : boot the ISO onto a blank disk; QEMU exits when the installer reboots.
#   install-usb : like install, but boots a USB stick image written by the app
#                 (tools/emulator-test), attached as USB mass storage. The stick is opened
#                 with snapshot=on, so the image file is never changed.
#   run     : boot the installed disk (no ISO) with the first-boot hook and web UI reachable.
#
# Usage: run-vm.sh <install|run> <work-dir> [assembled.iso]
#        run-vm.sh install-usb <work-dir> <stick.img>
# Environment: VM_RAM_MB (default 4096), VM_CPUS (2), VM_DISK_GB (32), VM_DISPLAY (none|gtk)
# The VM uses QEMU user-mode networking (isolated from the LAN): guest 10.0.2.15 by DHCP,
# host ports 8006 -> 8006 (web UI) and 2222 -> 22 (ssh). A monitor socket is at <work-dir>/monitor.sock.
set -euo pipefail
phase=${1:?usage: run-vm.sh <install|install-usb|run> <work-dir> [assembled.iso|stick.img]}
work=${2:?work dir}; iso=${3:-$work/assembled.iso}
mkdir -p "$work"
disk=$work/disk.qcow2; vars=$work/OVMF_VARS.fd
code=/usr/share/OVMF/OVMF_CODE_4M.fd; template=/usr/share/OVMF/OVMF_VARS_4M.fd
[ -e "$disk" ] || qemu-img create -f qcow2 "$disk" "${VM_DISK_GB:-32}G" >/dev/null
[ -e "$vars" ] || cp "$template" "$vars"
args=(
  -machine q35,accel=kvm -cpu host -smp "${VM_CPUS:-2}" -m "${VM_RAM_MB:-4096}"
  -drive if=pflash,format=raw,readonly=on,file="$code"
  -drive if=pflash,format=raw,file="$vars"
  -device ich9-ahci,id=ahci -drive id=hd0,if=none,file="$disk",format=qcow2 -device ide-hd,drive=hd0,bus=ahci.0
  -netdev user,id=n0,hostfwd=tcp::8006-:8006,hostfwd=tcp::2222-:22 -device virtio-net-pci,netdev=n0
  -device virtio-vga -display "${VM_DISPLAY:-none}"
  -monitor unix:"$work/monitor.sock",server,nowait
  -serial file:"$work/serial.log"
)
if [ "$phase" = install ]; then
  args+=(-drive if=none,id=cd0,file="$iso",media=cdrom,readonly=on -device ide-cd,drive=cd0,bus=ahci.1 -boot order=d -no-reboot)
elif [ "$phase" = install-usb ]; then
  [ -f "$iso" ] || { echo "stick image not found: $iso" >&2; exit 2; }
  args+=(-device qemu-xhci,id=xhci -drive if=none,id=stick,file="$iso",format=raw,snapshot=on
    -device usb-storage,bus=xhci.0,drive=stick,bootindex=0 -no-reboot)
elif [ "$phase" != run ]; then echo "phase must be install or run" >&2; exit 2; fi
exec qemu-system-x86_64 "${args[@]}"
