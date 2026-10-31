# Emulator matrix and the virtual USB stick

`emu.sh` boots the test emulators headless and can attach a disk image file to one as a
USB mass-storage stick. Android then lists it in `UsbManager` like a real OTG drive,
so `AndroidUsbHostBlockWriter` runs its real path against it: permission, SCSI probe,
WRITE(10), READ(10) verify and SYNCHRONIZE CACHE over Android bulk transfers.
Afterwards you can check the image file on the host.

## Devices

| AVD | Image | Why |
|---|---|---|
| `test28go` | `android-28;google_apis` | the floor (minSdk 28), as an Android Go phone: `ro.config.low_ram=true`, 480x854 at 240 dpi, 2 cores |
| `test35` | `android-35;google_apis` | Android 15; no AppFunctions platform |
| `test36` | `android-36;google_apis` | Android 16; AppFunctions registered but the stock image never indexes them |
| `test37` | `android-canary-20261007;google_apis_ps16k` | Android 17; runs `AppFunctionsPlatformTest` |

`test28go` is a stand-in for a Go phone, not an exact match. The emulator raises API 28
x86_64 to 1536 MB of RAM whatever the AVD asks for, so memory is not as tight as a real
1 GB Go phone. Low-RAM mode still applies: `ActivityManager.isLowRamDevice()` is true,
along with the system's low-RAM behaviour.

```
tools/emulator-test/emu.sh create-go test28go
tools/emulator-test/emu.sh prepare test28go --low-ram
```

## Virtual stick

Emulator images leave out the `android.hardware.usb.host` feature, so apps see no USB
devices. `prepare` adds it once per AVD (root, remount, one permissions file); after
that the AVD must always start with `-writable-system`, which `emu.sh start` does.

```
tools/emulator-test/emu.sh prepare test35            # once
tools/emulator-test/emu.sh start test35 /tmp/stick.img   # creates a 256 MB sparse image
./gradlew :usb-writer:connectedDebugAndroidTest
tools/emulator-test/emu.sh check-stick /tmp/stick.img
tools/emulator-test/emu.sh stop
```

`VirtualUsbStickTest` only writes to a device named `QEMU USB HARDDRIVE` and skips
when there isn't one, so it is safe on a phone with a real drive plugged in. The system
permission dialog is accepted with UiAutomator.

Things to know:

- **Disk space.** On API 31+, `remount` puts an overlay scratch image of several GB
  in the AVD's data (`test36.avd` went from 2.0 GB to 5.9 GB). API 28 has no scratch
  image. The emulator also refuses to boot when the host is low on space.
- **USB 2.0 on Android 9.** The API 28 kernel has EHCI but no xHCI. `emu.sh` picks EHCI
  below API 30 and xHCI above, which matches what phones mostly negotiate over OTG anyway.
- **Rebooting** a writable-system emulator from inside sometimes crashes QEMU, so
  `prepare` ends with a cold stop.

## Write a SparkPlug, then boot it

`ProxmoxStickWriteTest` does what the Write button does for the bundled Proxmox
SparkPlug: same plan building and boot-patch template, same `UsbImageWriteWorker`
(write, readback verify, fixed-byte-range patch). Field values match
`AssembleProxmoxIsoHarness`'s DHCP case. `tools/vm-test/run-vm.sh install-usb` then boots
the stick in QEMU/UEFI as USB mass storage, not as a CD.

```
emu.sh start test35 ~/vmtest/stick/stick.img        # STICK_BYTES=4G for Proxmox
./gradlew :app:installDebug :app:installDebugAndroidTest
adb push metalstrapper-proxmox-ve-9.2-1.iso /data/local/tmp/base.iso
adb shell am instrument -w -e class net.metalstrapper.sparkwriter.ProxmoxStickWriteTest \
    -e baseIso /data/local/tmp/base.iso \
    net.metalstrapper.sparkwriter.test/androidx.test.runner.AndroidJUnitRunner
adb pull /storage/emulated/0/Android/data/net.metalstrapper.sparkwriter/files/proxmox-stick/. ~/vmtest/usb/
emu.sh stop
tools/vm-test/run-vm.sh install-usb ~/vmtest/usb ~/vmtest/stick/stick.img
tools/vm-test/run-vm.sh run ~/vmtest/usb             # web UI on https://localhost:8006
```

Push the ISO to `/data/local/tmp`. Pushing into the app's external files directory
makes it owned by `shell`, and the app cannot read it there. Use API 35+ for this test:
emulated USB 2.0 on API 28 runs at about 1.5 MB/s, about 40 minutes for this image.

October 2026, API 35: write and verify of 1.7 GB took about 5 minutes. Compared with the
host-assembled ISO, the stick differs only in one 102-byte stretch inside `answer.toml`
(the generated root password hash). GRUB on the stick defaulted to the automated
install, which finished unattended in 4.5 minutes. The installed node came up as
`pve-test` with Proxmox VE 9.2.2. Root login worked with the password generated on the
phone, and the first-boot script had enabled the community repositories and disabled
the enterprise ones.

## Results, October 2026

| | 28 Go | 35 | 36 | 37 |
|---|---|---|---|---|
| `VirtualUsbStickTest` (3 tests) | pass, host hash matches | pass, host hash matches | not run yet (host disk full) | not run yet |
| `DraftHandoffTest` (4 tests) | pass | pass (earlier commit) | pass (earlier commit) | pass (earlier commit) |

The first API 28 run found `URLEncoder.encode(String, Charset)` and
`URLDecoder.decode(String, Charset)`, both API 33+, in the draft link and
`spark://plugin/add` parsing. On Android 9–12 these crashed or silently dropped every
draft value. They now use the `"UTF-8"` overloads. `manifest-runtime` is a JVM module,
so Android lint does not check its API levels; only a run on an old device catches this.

## Not covered

- Unplugging mid-write and devices that stall or return short transfers. QEMU can
  detach the stick (`device_del` on the monitor) but that is not scripted yet.
- The static-address and VLAN variants of the boot test.
- On the boot test VM the install disk is AHCI (`sda`) and the stick enumerates after it.
  On hardware where the stick could come first, `pve_disk_list = ["sda"]` would point at
  the stick.
