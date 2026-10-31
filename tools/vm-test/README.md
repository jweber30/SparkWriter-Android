# VM test: assemble a Proxmox ISO headlessly and boot it

Exercises the app's real code (workflow rendering, then `AssembledImageWriter`) without the UI,
then boots the result under QEMU/KVM + UEFI.

1. Download the base ISO from the release named in the image document and check its SHA-256
   (the harness refuses a base ISO that does not match the pin).
2. Assemble. Needs the Android SDK (`ANDROID_HOME`) and JDK 21:

   ```
   SPARKWRITER_BASE_ISO=/path/metalstrapper-proxmox-ve-9.2-1.iso \
   SPARKWRITER_OUT_DIR=/path/out SPARKWRITER_NETWORK=dhcp \
   ./gradlew :app:testDebugUnitTest --tests '*AssembleProxmoxIsoHarness*'
   ```

   `SPARKWRITER_NETWORK=static` uses 10.0.2.50/24 (QEMU user-mode network). For anything else
   (VLANs, MAC selection, other disks) point `SPARKWRITER_FIELDS_FILE` at a `.properties` file of
   `field_id=value` overrides. Output: `assembled.iso`, `answer.toml`, `first-boot.sh`,
   `root-password.txt` (throwaway, mode 0600). Without `SPARKWRITER_BASE_ISO` the test is skipped.
3. Boot: `tools/vm-test/run-vm.sh install <out>` (exits when the installer reboots), then
   `tools/vm-test/run-vm.sh run <out>`. Web UI at https://localhost:8006, ssh on port 2222.
   `tools/vm-test/screenshot.py <out> shot.png` captures the screen.

Not covered: the static path has been rendered but not booted yet. Note QEMU user networking
only matches `from-answer` if the guest NIC is addressed as 10.0.2.50.
