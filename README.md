# SparkWriter Droid

Android framework for SparkWriter's manifest-driven USB provisioning flow.

This project ports the SparkWriter concept into Android-native modules:

- `app`: Jetpack Compose workflow UI and `spark://plugin/add` deep-link entrypoint.
- `core-model`: Android-neutral SparkWriter domain contracts.
- `manifest-runtime`: SparkPlug v3.0 contract parsing (workflow + image documents) with strict rejection of unknown keys and values, install URL validation, sidecar hash checks, and Android support checks.
- `usb-writer`: SparkWriter-owned USB block writer interface plus an Android USB Host adapter.
- `storage`: Room/DataStore persistence for manifests, receipts, and profile fields.
- `shared-test-fixtures`: sample manifest data adapted from the Python project.

## Current State

The framework is intentionally usable but conservative:

- The app renders a source catalog, dynamic config form, USB target confirmation, and write handoff state.
- Android installs accept HTTPS SparkPlug package roots named `manifest.json`
  and, when "Allow local ZIP import" is turned on in Settings, local `.zip`
  packages selected with the system document picker.
- SparkPlug v3.0 is the public Android-facing contract and the only version accepted. A SparkPlug is a workflow document plus an image document.
- Package documents and files are SHA-256 pinned by `manifest.json` and fetched only from the package's own origin.
- Local ZIP packages are structurally inspected but are not publisher-verified;
  the user's explicit selection and two-stage review are the trust decision.
- Workflow document support covers the Android-safe subset and rejects shell/OCI/runtime actions at load.
- The USB writer exposes the final `UsbBlockWriter` contract, write safety guards, and Android USB Host mass-storage transport.
- `AndroidUsbHostBlockWriter` enumerates USB mass-storage devices, requests host permission, probes capacity, writes image blocks, and verifies readback through Android bulk transfers.

## SparkPlug Contract

- [SparkPlug v3.0 Android Contract](docs/SPARKPLUG_V3.md)
- [MetalStrap and SparkPlug design notes](docs/METALSTRAP.md)
- [Manifest Authoring Guide](docs/sparkwriter_manifests.md)

Machine-readable v3.0 manifest contract lives in JSON at:

- [SparkWriter Manifest Contract v3.0 JSON Schema](docs/sparkwriter_manifest_contract_v3_0.schema.json)

To export that schema as YAML for review/authoring:

```bash
python3 tools/json_to_yaml.py docs/sparkwriter_manifest_contract_v3_0.schema.json --output docs/sparkwriter_manifest_contract_v3_0.schema.yaml
```

## Build

This directory includes a Gradle wrapper. Run:

```bash
./gradlew test
./gradlew assembleDebug
```

## Hardware Test Checklist

- Test at least two Android USB OTG hosts.
- Test at least three USB drives from different vendors/sizes.
- Verify permission denial, unplug during write, checksum mismatch, and too-small-device refusal.
- Enable raw streaming only after full-image readback verification succeeds on real hardware.

## License

SparkWriter is released under the [MIT License](LICENSE).

The MetalStrap GitHub workflow publishes Proxmox VE ISOs with reserved slots.
Those ISOs are built from upstream Proxmox VE releases and keep their own
licenses (Proxmox VE is AGPL-3.0; bundled Debian packages carry their own
terms). The MIT License covers this repository's code, not the contents of
those ISOs.
