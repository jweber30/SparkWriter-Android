# SparkWriter Netboot Path

This document records the Android-friendly netboot path backed by the
MetalStrapper Mailroom rendezvous endpoint.

The core idea is small: SparkWriter writes a tiny iPXE seed image to USB. That
seed image does DHCP and chains to Mailroom. Mailroom returns the current iPXE
payload for a short-lived label. Everything after that belongs to
MetalStrapper: installer selection, generated installer answers, secrets,
orchestration, and lifecycle tracking.

Ownership split for this flow:

- SparkWriter owns the generic tiny seed ISO artifact (`sparkwriter-ipxe-dual-protocol.iso`)
  and any reusable placeholder text in this repo.
- MetalStrapper owns:
  - generation of the netboot workflow
  - generation of `manifest.json`
  - publishing of config fields and runtime hashes
  - creation and PUT of rendezvous payload when running orchestrated netboot
  - lifecycle orchestration
- The only meaningful delivery-mode difference is `write_ready`: if MetalStrapper
  runs orchestrated flow, Android never PUTs to Mailroom; if not orchestrated,
  SparkWriter PUTs during `write_ready` using the workflow template payload.

## Current Seed Shape

The prototype seed inspected with:

```bash
7z l sparkwriter-ipxe-dual-protocol.iso
```

is about 910 KiB and contains:

- `boot.catalog`
- `autoexec.ipxe`
- `ipxe.lkrn`
- `isolinux.bin`
- `isolinux.cfg`
- `ldlinux.c32`
- `[BOOT]/Boot-NoEmul.img`

`isolinux.cfg` boots `ipxe.lkrn` with `autoexec.ipxe` as the initrd:

```text
SAY SparkWriter iPXE boot image
TIMEOUT 10
DEFAULT ipxe.lkrn
LABEL ipxe.lkrn
 KERNEL ipxe.lkrn
 APPEND initrd=autoexec.ipxe
```

The patched `autoexec.ipxe` is intentionally tiny:

```ipxe
#!ipxe
dhcp
chain --autofree http://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot
chain --autofree https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot
```

The replacement URL points at a Mailroom artifact inside a rendezvous session.
For the boot script artifact, the canonical path is
`/netboot/v1/rendezvous/<rendezvous-id>/boot`. Writers publish anonymously
under source-address and global capacity limits.

When a flow patches or emits an iPXE script that chains to
`/netboot/v1/rendezvous/`, SparkPlug `source.capabilities` must include
`netboot-rendezvous` so SparkWriter can explicitly allow Mailroom netboot
participation.

## Mailroom Contract

The netboot path lives in `../MetalStrapperWeb/mailroom` and is deployed at:

```text
https://mailroom.metalstrapper.net
```

Routes:

```text
PUT /netboot/v1/rendezvous/<43-char-rendezvous-id>/<artifact-label>
GET /netboot/v1/rendezvous/<43-char-rendezvous-id>/<artifact-label>
```

Current behavior:

- In `MAILROOM_MODE=public`, PUT requires HTTPS.
- PUT publication is anonymous and rate-limited by source address.
- PUT content types: `text/plain`, `application/x-ipxe`, or
  `application/octet-stream`
- PUT payload limit: 16 KiB
- GET response content type: `text/plain; charset=utf-8`
- Storage key namespace: `netboot:<rendezvous-id>:<artifact-label>`
- Artifact labels are child keys such as `boot`, `answer`, and `firstboot`
- Boot-time GET requires only the rendezvous URL; no writer-only proof material is
  required
- TTL: 3600 seconds from successful PUT
- Duplicate live label: `409 Conflict`
- Missing label on GET: `404 Not Found`
- Burst-rate responses are `429 Too Many Requests` and daily quota exhaustion
  returns `503 Daily Capacity Exhausted`.
- Rate limits and daily quota are shared with GLINT Mailroom
- Responses use `Cache-Control: no-store`
- Cloudflare KV visibility can lag across locations, so callers should tolerate
  short propagation delays

Example stored iPXE payload:

```ipxe
#!ipxe
dhcp
set base https://boot-assets.example/proxmox
kernel ${base}/linux26 initrd=initrd.img auto=true url=https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/answer
initrd ${base}/initrd.img
boot
```

Mailroom stores opaque text. It does not understand installers, secrets, host
state, USB writer state, rendezvous ids, or artifact contents.

## End-to-End Path

1. MetalStrapper generates a fresh netboot rendezvous id.
2. MetalStrapper PUTs the iPXE payload to
   `https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot`.
3. MetalStrapper generates a SparkPlug v2.3 package root at `manifest.json`.
4. SparkWriter Android installs only from that `manifest.json`.
5. SparkWriter fetches and hash-checks the workflow and seed ISO.
6. SparkWriter either writes a service-rendered seed ISO or patches a generic
   seed ISO with the rendezvous URL.
7. SparkWriter raw-writes the seed image to USB and verifies readback.
8. The target machine boots USB, iPXE does DHCP, and iPXE GETs the Mailroom
   rendezvous payload.
9. The returned iPXE payload continues the install path.
10. SparkWriter records a receipt for the seed image, patch metadata if any,
    target device, and verification result.

In orchestrated flows, Android should not PUT netboot payloads to Mailroom;
MetalStrapper owns the rendezvous payload before SparkWriter writes media. In
standalone netboot mode, SparkWriter may publish the declared iPXE payload
during `write_ready` before starting the USB write. SparkWriter generates
`Spark.mail.aux_data.rendezvous_id` and anonymously publishes the declared
artifact before rendering those Mailroom URLs.

The public MetalStrapper Mailroom is provided for demonstrations and testing,
has no SLA, and must not be treated as durable recovery storage. Production
users should deploy Mailroom in their own Cloudflare account.

## Manifest Shape

The Android-facing package should follow the MetalStrapperWeb shape:

- `manifest.json` is the install target
- `documents.workflow` points to the generated workflow YAML
- `files` hash-binds the tiny seed ISO as a package artifact
- the workflow `source.family` is `netboot`
- the workflow has no `delivery` block unless a future installer path truly
  needs one

Package root:

```json
{
  "version": "2.3",
  "kind": "sparkplug.manifest",
  "metadata": {
    "manifest_root_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "id": "metalstrapper-netboot-seed",
    "name": "MetalStrapper Netboot Seed"
  },
  "documents": {
    "workflow": {
      "role": "workflow",
      "url": "sparkplug.yaml?hostname=pve1-lab.srv.home.arpa",
      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "media_type": "application/yaml"
    }
  },
  "files": {
    "sparkwriter-ipxe-seed.iso": {
      "role": "base_image",
      "url": "https://mailroom.metalstrapper.net/netboot/v1/seeds/<seed-id>.iso",
      "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
      "media_type": "application/x-iso9660-image",
      "size": 931840
    }
  }
}
```

Workflow for a service-rendered seed:

```yaml
version: "2.3"
metadata:
  id: metalstrapper-netboot-seed
  name: MetalStrapper Netboot Seed

source:
  id: metalstrapper-ipxe-seed
  name: MetalStrapper iPXE rendezvous seed
  family: netboot
  version: "1"
  media_type: application/x-iso9660-image
  url: https://mailroom.metalstrapper.net/netboot/v1/seeds/<seed-id>.iso
  sha256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
  acquire:
    kind: direct
    url: https://mailroom.metalstrapper.net/netboot/v1/seeds/<seed-id>.iso
    artifact: sparkwriter-ipxe-seed.iso
  capabilities:
    - ipxe
    - netboot-rendezvous
  outputs:
    usb: true
    iso: true
  adaptation:
    status: preadapted
    satisfies:
      - ipxe_boot
      - rendezvous_script

installer:
  scheme: ipxe-rendezvous
  accepts_delivery: []

config_fields: []
wizard:
  pages: []
```

Workflow for a generic seed patched on Android:

```yaml
version: "2.3"
metadata:
  id: metalstrapper-netboot-seed-patched-on-android
  name: MetalStrapper Netboot Seed

source:
  id: metalstrapper-ipxe-seed-generic
  name: Generic MetalStrapper iPXE seed
  family: netboot
  version: "1"
  media_type: application/x-iso9660-image
  url: https://mailroom.metalstrapper.net/netboot/v1/seeds/generic-dual-protocol.iso
  sha256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
  capabilities:
    - ipxe
    - patchable-boot-script
    - netboot-rendezvous
  outputs:
    usb: true
    iso: true
  adaptation:
    status: patchable_on_import
    satisfies:
      - ipxe_boot

installer:
  scheme: ipxe-rendezvous
  accepts_delivery: []

boot_patch:
  filesystem: iso9660
  placeholder_patches:
    - candidate_paths:
        - /autoexec.ipxe
        - /boot.ipxe
      placeholder: http://mailroom.metalstrapper.net/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
      replacement: http://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot
      encoding: ascii
      padding: space
    - candidate_paths:
        - /autoexec.ipxe
        - /boot.ipxe
      placeholder: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
      replacement: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot
      encoding: ascii
      padding: space

templates:
  ipxe_payload: |
    #!ipxe
    dhcp
    chain --autofree https://boot.netboot.xyz

host_events:
  - event: write_ready
    return_delivery:
      url: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/{{netboot_rendezvous_id}}/boot
      method: PUT
      delivery_mode: required
      template: ipxe_payload
      media_type: application/x-ipxe
```

The service-rendered seed is the default path because `manifest.json` can pin the
final ISO. Android-side patching is for one generic seed artifact where only the
HTTP/HTTPS rendezvous URLs change. In standalone netboot mode, SparkWriter may
also publish the short iPXE payload to Mailroom during `write_ready` before the
USB write begins. It generates `netboot_rendezvous_id` before rendering the
`write_ready` URL.

## Ownership Boundaries

SparkWriter Android owns:

- package root verification
- workflow and seed ISO hash checks
- bounded `boot_patch` application when declared
- optional standalone `write_ready` Mailroom PUT for declared netboot
  rendezvous SparkPlugs
- raw USB write
- readback verification
- receipts

MetalStrapper owns:

- orchestrated netboot label generation
- orchestrated Mailroom PUTs
- orchestrated iPXE payload generation
- installer asset URLs
- answer/preseed/autoinstall payloads
- secret release
- lifecycle and return-delivery state

Mailroom owns:

- short-lived opaque text storage
- label uniqueness during TTL
- global and controller write quotas
- GET/PUT rate limiting
- no-store responses

## Implementation Notes

- Prefer HTTPS in generated iPXE scripts when firmware and iPXE build support
  it. The seed prototype currently demonstrates the minimal chain shape.
- Keep iPXE payloads under 16 KiB. Larger installer data should be hosted as
  separate hashable assets or stored under additional labels.
- Treat the Mailroom label as a bearer capability during its TTL.
- Do not put long-lived secrets in the iPXE script. Use the script to reach the
  next controlled stage.
- Record the exact seed SHA-256 and boot patch metadata in the SparkWriter
  receipt. Keep rendezvous labels local, and redact labels and payload bodies
  from shared logs.

## Dual-Protocol Generic ISO

The checked-in dual-protocol SparkPlug examples were retired because their
published seed ISO URL no longer resolved and their manifest hashes were stale.
Keep the local tooling as a way to build and smoke-test a fresh seed before
publishing a new package:

- `tools/generic-ipxe-placeholder.ipxe`
- `tools/build-generic-netboot-ipxe.sh`
- `tools/smoke-test-ipxe-rendezvous.sh`

To publish a new netboot SparkPlug, first rebuild or replace the seed ISO, run
the QEMU smoke test against that exact ISO, then generate a fresh manifest and
workflow with matching `source.sha256`, `documents.workflow.sha256`, and package
root hashes. Do not check in or publish package metadata that points at a seed
URL until the URL is live and hash-pinned.

To rebuild the ISO from the local `../iPXE` checkout:

```bash
IPXE_REPO=/path/to/iPXE OUTPUT_FILE=sparkwriter-ipxe-dual-protocol.iso \
  ./tools/build-generic-netboot-ipxe.sh
```

The script:

- builds `ipxe.lkrn` from `../iPXE`,
- uses `generic-ipxe-placeholder.ipxe` as the seed script, and
- emits `sparkwriter-ipxe-dual-protocol.iso` containing dual protocol chain lines.

The smoke test patches one rendezvous placeholder in the generated ISO, boots it
with QEMU, and passes only after the guest performs the expected HTTP GET.
