# SparkPlug v2.2 Transitional Notes

SparkPlug v2.2 was an implementation bridge toward Android-safe SparkWriter
manifests. It changed how runtime work was described, but it also carried draft
surface that made the Android contract look broader than it really was.

New public manifests should target SparkPlug v2.3. Android keeps v2.2 parsing
for transitional compatibility with existing drafts and tests.

The preferred v2.2 package is simple: a workflow document declares generated
artifacts and host-owned delivery, while any installer-media preparation happens
before SparkWriter receives the base image.

Image adaptation, when needed, is performed before the write by the
publisher, user, desktop host, or MetalStrapper service.

The format must not encode a transport assumption such as "remote" or "local".
Android, desktop Linux, Crostini, and a MetalStrapper web service can all accept
the same package root and adapt execution to their platform.

## Package Root

Remote install still targets `manifest.json`.

```json
{
  "version": "2.2",
  "kind": "sparkplug.manifest",
  "metadata": {
    "manifest_root_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "id": "example-ubuntu",
    "name": "Example Ubuntu Autoinstall"
  },
  "documents": {
    "workflow": {
      "role": "workflow",
      "url": "sparkplug.yaml",
      "sha256": "..."
    },
    "ui": {
      "role": "ui",
      "url": "ui.yaml",
      "sha256": "..."
    }
  },
  "files": {
    "firstboot": {
      "url": "https://cdn.example.net/sparkwriter/firstboot.sh",
      "sha256": "...",
      "media_type": "text/x-shellscript"
    }
  }
}
```

Packages that want a capable desktop host or adapting service to produce the
base image may add one or more `subcontainer` role documents under `documents`.
These documents were exploratory adaptation roles, not the default SparkWriter
write path. Bootstrap/runtime orchestration that is meaningful only to
MetalStrapper belongs outside the Android runnable package root.

Compatibility rules:

- `manifest.json` is the package index and the only remote install target.
- `metadata.author` may also be supplied as `metadata.publisher`.
- `source.id` is the primary identity; `source.name` is optional and defaults to `source.id`.
- `acquire.kind` defaults to `direct` when omitted.
- `field_condition.field` may also be supplied as `field_condition.selector`.
- `field_condition.operator` may also be supplied as `field_condition.op`.
- `config_field.id` may also be supplied as `config_field.key`.
- `kernel_arg_patch` supports `remove_lines_containing` to filter configuration lines.
- Every referenced document and file is SHA-256 pinned.
- References may be relative package URLs or absolute HTTPS URLs. HTTP URLs are not
  blocked by this document alone; MetalStrapper enforces URL policy during
  bootstrap install and re-approval (blocked by default, with explicit warning/allow path when configured).
- Declaration documents are parsed only after their bytes match the package root.
- Arbitrary files do not become behavior by being present in the package.

New v2.2 rules:

- `metadata.manifest_root_sha256` is required and must be a lowercase
  SHA-256 of the canonicalized manifest.json object before `manifest_root_sha256`
  is added. If this value changes, SparkWriter must re-fetch documents and
  files for the same manifest ID before it can run them.
- Fixed byte-range delivery is also Android-safe when an adapted base image
  reserves deterministic placeholder ranges for per-write artifacts.
- Heavy runtime behavior is outside the default write path. It may be delegated
  through optional adaptation roles or omitted when the user supplies an
  already-adapted base image.

## Adapted Base Images

The simplest v2.2 flow is "bring an adapted base image." SparkWriter does not
need to know how that image was produced; it only needs to know the installer
scheme and which host-owned delivery primitive the image expects.

For example, a Proxmox package can require the user to start with a Proxmox ISO
that was already prepared outside SparkWriter:

- the ISO is already in Proxmox auto-install mode
- the installer is configured to fetch `answer.toml` from a labeled auxiliary
  partition
- the ISO optionally contains a generic first-boot handoff that reads additional
  payloads from that same partition
- host-specific answers, secrets, and scripts are not baked into the base ISO

This keeps SparkWriter's job small and portable. SparkWriter renders the
per-write artifacts, raw-writes the selected base image, appends or updates the
auxiliary partition, and records exactly what it wrote.

```yaml
version: "2.2"
metadata:
  manifest_root_sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  id: proxmox-seed
  name: Proxmox Seed

source:
  id: proxmox-ve-adapted
  name: Proxmox VE adapted auto-install ISO
  family: proxmox
  media_type: application/x-iso9660-image
  sha256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
  user_supplied: true
  adaptation:
    status: preadapted
    satisfies:
      - auto_install_mode
      - answer_fetch.partition
      - firstboot_handoff.partition
    human_requirements:
      - Use a Proxmox ISO prepared with Proxmox auto-install enabled.
      - The ISO must fetch answer.toml from partition label PROXMOX-AIS.
      - The ISO must contain a first-boot handoff when firstboot.sh is delivered.

installer:
  scheme: proxmox-auto-install
  accepts_delivery:
    - auxiliary_partition
  base_image_requires:
    - auto_install_mode
    - answer_fetch.partition
    - firstboot_handoff.partition
  auxiliary_partition:
    label: PROXMOX-AIS
    filesystem: vfat
    required_files:
      - /answer.toml

artifacts:
  answer_toml:
    media_type: application/toml
    template: templates/answer.toml
  firstboot_script:
    media_type: text/x-shellscript
    template: templates/firstboot.sh
    optional: true

delivery:
  mode: auxiliary_partition
  id: proxmox-answer-seed
  after: write_base_image
  partition:
    label: PROXMOX-AIS
    filesystem: vfat
    size_mb: 64
    placement: preallocated_in_base_image
    geometry:
      base_image_sha256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
      partition_table: gpt
      partition_number: 3
      start_lba: 1048576
      sector_size: 512
      byte_offset: 536870912
      byte_length: 67108864
  files:
    /answer.toml:
      from: artifacts.answer_toml
      media_type: application/toml
    /firstboot.sh:
      from: artifacts.firstboot_script
      media_type: text/x-shellscript
      optional: true
```

`source.adaptation.satisfies` is the machine-readable contract. It must cover
every flag in `installer.base_image_requires` for the selected source or adapted
base image. `source.adaptation.human_requirements` is UI text for requirements
that cannot be proven by generic image inspection.

Hosts should make these requirements explicit in the UI. If a selected base image
is not declared as adapted for the installer scheme, SparkWriter should not
attempt to repair it with generic ISO mutation. The user can either provide a
proper adapted image or use a package path that delegates image preparation to a
desktop host or adapting service.

Known `source.adaptation.status` values:

- `preadapted`: the package expects the user or publisher to supply an image
  that already satisfies the declared installer requirements.
- `produced`: the image is expected to come from an optional adaptation role or
  adapting service before SparkWriter writes it.
- `none`: the source image has no declared adaptation; it is only valid when the
  installer scheme does not require image adaptation for the selected delivery
  mode.

## Fixed Byte-Range Delivery

An adapted base image may reserve exact byte ranges for SparkWriter to overwrite
after the raw image write. This avoids auxiliary partition discovery entirely:
SparkWriter does not need a partition label, GUID, mount point, or filesystem
driver. It verifies the selected base image hash, verifies each placeholder
range, writes rendered artifacts at absolute byte offsets, and pads the unused
space with content-compatible bytes.

For Proxmox, this can be used with a base ISO that is already prepared for
automatic installation and already contains placeholder files where Proxmox will
look for its answer file and first-boot executable.

```yaml
version: "2.2"
metadata:
  manifest_root_sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  id: proxmox-inline-seed
  name: Proxmox Inline Seed

source:
  id: proxmox-ve-adapted-inline
  name: Proxmox VE adapted auto-install ISO
  family: proxmox
  media_type: application/x-iso9660-image
  sha256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
  user_supplied: true
  adaptation:
    status: preadapted
    satisfies:
      - auto_install_mode
      - answer_fetch.iso_placeholder
      - firstboot_handoff.iso_placeholder
    human_requirements:
      - Use a Proxmox ISO prepared with Proxmox auto-install enabled.
      - The ISO must contain reserved placeholder ranges for answer.toml and firstboot.sh.

installer:
  scheme: proxmox-auto-install
  accepts_delivery:
    - fixed_byte_ranges
  base_image_requires:
    - auto_install_mode
    - answer_fetch.iso_placeholder
    - firstboot_handoff.iso_placeholder

artifacts:
  answer_toml:
    media_type: application/toml
    template: templates/answer.toml
  firstboot_script:
    media_type: text/x-shellscript
    template: templates/firstboot.sh

delivery:
  mode: fixed_byte_ranges
  id: proxmox-inline-seed
  after: write_base_image
  base_image_sha256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
  patches:
    - id: answer_toml
      from: artifacts.answer_toml
      media_type: application/toml
      logical_name: answer.toml
      byte_offset: 123456789
      byte_length: 16384
      placeholder:
        encoding: zeroes
        sha256: eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
      padding:
        strategy: toml-comment
    - id: firstboot_script
      from: artifacts.firstboot_script
      media_type: text/x-shellscript
      logical_name: firstboot.sh
      byte_offset: 123473173
      byte_length: 65536
      placeholder:
        encoding: zeroes
        sha256: ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
      padding:
        strategy: shell-comment
```

Patch rules:

- `delivery.base_image_sha256` must match `source.sha256`.
- Patch ranges are absolute byte offsets from the start of the raw image/device.
- Patch ranges must not overlap.
- SparkWriter must verify the placeholder range before writing it.
- SparkWriter must reject artifacts larger than their reserved range.
- Text artifacts should use comment or whitespace padding rather than NUL
  padding unless the consuming installer explicitly tolerates embedded NUL
  bytes.
- The adapting service is responsible for placing placeholders where the
  installer will actually read them. SparkWriter only performs verified byte
  replacement.

### Proxmox `answer.toml` Artifact

For Proxmox auto-install workflows, `artifacts.answer_toml` should render only
the Proxmox installer answer file. It must not carry SparkPlug delivery metadata,
MetalStrapper bootstrap metadata, or first-boot orchestration fields unless the
Proxmox installer explicitly documents those fields.

The package template should cover the installer answers that are host-specific
at write time:

```toml
[global]
keyboard = "{{ pve_keyboard }}"
country = "{{ pve_country }}"
fqdn = "{{ pve_fqdn }}"
mailto = "{{ pve_mailto }}"
timezone = "{{ pve_timezone }}"
root-password = "{{ pve_root_password }}"
root-ssh-keys = {{ pve_root_ssh_keys_toml }}
reboot-mode = "{{ pve_reboot_mode }}"

[network]
source = "{{ pve_network_source }}"
# Static networking fields are rendered only when the workflow collects them:
# cidr, gateway, dns, and filter.ID_NET_NAME_MAC.

[disk-setup]
filesystem = "{{ pve_disk_filesystem }}"
disk-list = {{ pve_disk_list_toml }}

[first-boot]
source = "from-iso"
ordering = "fully-up"
```

The `[first-boot]` table is only the Proxmox installer trigger for a separate
script. The actual first-boot payload remains the `firstboot_script` artifact.
If the adapted ISO needs a first-boot hook, the adapter must place that hook or
placeholder where the base image will execute it, and `delivery.patches` must
bind the rendered `artifacts.firstboot_script` bytes to that placeholder range.
SparkWriter should not smuggle the first-boot script body or MetalStrapper
delivery metadata into `answer.toml`.

When SparkWriter publishes the first-boot script as mail-backed auxiliary data,
the answer template should point Proxmox at the host-provided runtime URL:

```toml
[first-boot]
source = "from-url"
url = "{{Spark.mail.aux_data.firstboot_url}}"
ordering = "fully-up"
```

Packages should bind fields to `artifacts.answer_toml` with explicit encodings:

- installer strings, such as `global.fqdn` and `network.cidr`, use
  `toml-string`
- sensitive installer strings, such as `global.root-password`, use `secret`
- array values, such as `global.root-ssh-keys` and `disk-setup.disk-list`, use
  `toml-array`

MetalStrapper-generated answer files should produce the same semantic shape as
the package template. The generator may choose a structured renderer instead of
text templating, but it should still emit only Proxmox-supported answer keys and
let SparkPlug delivery describe placeholders, patches, and first-boot sidecars.

## Netboot Seed Images

See [Netboot Path](NETBOOT_PATH.md) for the operational Mailroom route and
Android write flow.

The preferred Android netboot path is an even smaller version of the same v2.2
contract: SparkWriter writes a tiny bootable seed image whose only job is to
start iPXE and rendezvous with MetalStrapper. MetalStrapper owns the dynamic
boot menu, installer selection, secrets, and per-host orchestration after that
rendezvous. Android owns only package verification, optional bounded boot-script
patching, raw USB write, readback verification, and receipts.

The current patched seed prototype at
`/tmp/sparkwriter-ipxe-initrd-patched.iso` is about 910 KiB and contains only:

- `boot.catalog`
- `boot.ipxe`
- `ipxe.lkrn`
- `isolinux.bin`
- `isolinux.cfg`
- `ldlinux.c32`
- `[BOOT]/Boot-NoEmul.img`

Its `isolinux.cfg` boots `ipxe.lkrn` with `initrd=boot.ipxe`. The patched
`boot.ipxe` shape is:

```ipxe
#!ipxe
dhcp
chain http://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot
```

This is the shape SparkWriter manifests should prefer when the target workflow
can be network-booted. The Android-visible package describes a small,
hash-pinned boot seed and the rendezvous contract. It does not describe Proxmox
answer rendering, ISO mutation, local command execution, or bootstrap
orchestration as Android work.

There are two valid publication modes.

### Service-Rendered Seed

MetalStrapper may render and host a per-profile or per-host seed ISO before
SparkWriter installs the package. In that case SparkWriter downloads and writes
the already-patched seed image exactly as it would any other base image.

```yaml
version: "2.2"
metadata:
  manifest_root_sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
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
    human_requirements:
      - The target machine must be able to boot USB media.
      - The target network must allow DHCP and outbound HTTP or HTTPS to the rendezvous endpoint.

installer:
  scheme: ipxe-rendezvous
  accepts_delivery: []

artifacts: {}
presets: []
config_fields: []
wizard:
  pages: []
```

In this mode there is no `delivery` block. The seed image is already complete.
The package root still hash-binds the workflow and seed image, so Android can
verify every byte it writes.

The generated package root should use the same shape MetalStrapperWeb uses for
other SparkWriter artifacts: `manifest.json` pins the workflow document and the
seed ISO file, and Android installs only from that package root.

```json
{
  "version": "2.2",
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

### Android-Patched Generic Seed

When the seed image is generic, Android may patch only the bounded iPXE entry
point. This is still not a general ISO editing model. The patch must name exact
candidate files and replace either a whole iPXE script or a fixed ASCII
placeholder. SparkWriter must refuse ambiguous matches, oversized replacements,
unsupported filesystems, and non-iPXE script bodies.

```yaml
version: "2.2"
metadata:
  manifest_root_sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  id: metalstrapper-netboot-seed-patched-on-android
  name: MetalStrapper Netboot Seed

source:
  id: metalstrapper-ipxe-seed-generic
  name: Generic MetalStrapper iPXE seed
  family: netboot
  version: "1"
  media_type: application/x-iso9660-image
  url: https://mailroom.metalstrapper.net/netboot/v1/seeds/generic.iso
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
  script_patches:
    - kind: netboot_xyz_autoexec
      candidate_paths:
        - /boot.ipxe
        - /autoexec.ipxe
      esp_image_path: /esp.img
      esp_script_path: /autoexec.ipxe
      script: |
        #!ipxe
        dhcp
        chain --autofree https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot
```

For images where the iPXE binary embeds a placeholder URL, publishers may use a
bounded placeholder patch instead:

```yaml
boot_patch:
  filesystem: iso9660
  placeholder_patches:
    - candidate_paths:
        - /ipxe.lkrn
      placeholder: http://mailroom.invalid/placeholder/0000000000000000000000000000000000000000000000000000000000000000
      replacement: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot
      encoding: ascii
      padding: space
```

The service-rendered seed is the cleaner default because the package root can
pin the finished ISO. Android-side patching is useful when publishing one
generic seed artifact and injecting only a short-lived rendezvous URL at write
time.

For netboot, SparkWriter may also publish
the iPXE payload to Mailroom before starting the USB write. The SparkPlug must
declare `netboot-rendezvous`, patch or script-chain the seed to
`/netboot/v1/rendezvous/<rendezvous-id>/boot`, and declare a required `write_ready`
return delivery:

```yaml
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

SparkWriter generates one 43-character base64url rendezvous id per write
preparation and exposes it to boot patches and templates as `rendezvous_id`,
`netboot_rendezvous_id`, `netboot_rendezvous_label`, and hyphenated aliases.
Artifact URLs add child labels such as `/boot`, `/answer`, and `/firstboot`.
The `write_ready` publish runs before the USB writer is enqueued; required
delivery failure blocks the write.

For installer sidecars rather than standalone iPXE netboot payloads, manifests
should use SparkWriter's mail-backed auxiliary data namespace. SparkWriter
generates these variables only when a boot patch, template, or `write_ready`
delivery references them:

- `{{Spark.mail.aux_data.answer_url}}`
- `{{Spark.mail.aux_data.answer_label}}`
- `{{Spark.mail.aux_data.firstboot_url}}`
- `{{Spark.mail.aux_data.firstboot_label}}`

Use `answer_url` for the adapted installer seed URL and `firstboot_url` inside
the Proxmox `[first-boot]` table. The older `mailroom_*` and
`metalstrapper.mailroom.*` names are compatibility aliases; new packages should
prefer `Spark.mail.aux_data.*`.

### MetalStrapper Ownership

MetalStrapperWeb already has the ownership split that netboot should follow:

- the approved bootstrap package owns package/UI inputs and any sidecar files
- MetalStrapper owns local deployment context, approved commit metadata,
  return-delivery endpoints, host SSH keys, GLINT tickets, and rendezvous tokens
- the generated `manifest.json` is the Android install target
- the generated workflow is the SparkWriter-facing form and write contract
- files in `manifest.json.files` are hash-bound package artifacts or
  MetalStrapper-produced artifacts, not ambient behavior

For orchestrated netboot, the rendezvous endpoint becomes the only boot-time
handoff in the seed. Everything after
`chain .../netboot/v1/rendezvous/<rendezvous-id>/boot` belongs to the MetalStrapper service:
menu construction, installer asset selection, answer generation, secret
release, logging, and lifecycle state. For standalone netboot, SparkWriter may
publish a short iPXE payload to the same endpoint during `write_ready`.

## Optional Adaptation Roles

A subcontainer document describes a bounded job role, its inputs, outputs, and
required host capabilities. It does not require the host to run an actual local
container. Subcontainers are for packages that want a desktop host or adapting
service to produce an adapted base image instead of requiring the user to bring
one.

SparkWriter implementations may ignore local adaptation roles entirely and still
support the v2.2 happy path. SparkWriter may understand adaptation roles enough to validate, display, delegate, or reject them. It does not need to execute them. 
Android implementations should treat these roles as
service-delegation opportunities only; they should not expose a local command
runner or local OCI builder to satisfy them.

```yaml
version: "2.2"
kind: sparkplug.subcontainer
id: ubuntu-base-image
purpose: base_image_prepare

requires:
  capabilities:
    - iso.read
    - iso.write
    - filesystem.iso9660.modify

inputs:
  source_image:
    kind: source_image
    media_type: application/x-iso9660-image
  artifacts:
    user-data:
      kind: generated_artifact
      media_type: text/cloud-config
    meta-data:
      kind: generated_artifact
      media_type: text/plain

outputs:
  base_image:
    kind: base_image
    media_type: application/x-iso9660-image
    role: android_writable_base

command_contract:
  image: package.files.ubuntu-builder
  argv:
    - prepare-ubuntu-image
    - --source
    - "{{inputs.source_image}}"
    - --user-data
    - "{{inputs.artifacts.user-data}}"
    - --meta-data
    - "{{inputs.artifacts.meta-data}}"
    - --output
    - "{{outputs.base_image}}"
  network: false
```

Host interpretation:

- A Linux host may run the contract in a local OCI/subprocess sandbox.
- Android may hand the contract to a trusted adapting service and consume only
  the hash-described output.
- A web service may run the contract server-side and publish a derived package
  artifact.
- A host that cannot satisfy the capability set must show the role as
  unavailable, not partially execute it.

An adaptation role that produces a `base_image` output must also declare the
installer requirements satisfied by that output:

```yaml
outputs:
  base_image:
    kind: base_image
    media_type: application/x-iso9660-image
    role: android_writable_base
    adaptation:
      status: produced
      satisfies:
        - auto_install_mode
        - seed_fetch.partition
```

## Auxiliary Partition Delivery

v2.2 introduces a host-owned delivery primitive for data that can be placed
outside the base ISO/image. This is the preferred Android-safe customization path.

```yaml
delivery:
  mode: auxiliary_partition
  id: installer-seed
  after: write_base_image
  partition:
    label: CIDATA
    filesystem: vfat
    size_mb: 64
    placement: preallocated_in_base_image
    geometry:
      base_image_sha256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
      partition_table: gpt
      partition_number: 3
      start_lba: 1048576
      sector_size: 512
      byte_offset: 536870912
      byte_length: 67108864
  files:
    /user-data:
      from: artifacts.user-data
      media_type: text/cloud-config
    /meta-data:
      from: artifacts.meta-data
      media_type: text/plain
```

Each `delivery.files.*.from` value references a generated artifact from the
workflow document. The artifact model is intentionally host-neutral: SparkWriter
renders artifacts from the UI/configuration data, verifies their declared media
types where possible, and then copies the rendered bytes into the delivery
primitive.

Example artifact declaration:

```yaml
artifacts:
  user-data:
    media_type: text/cloud-config
    template: templates/user-data.yaml
  meta-data:
    media_type: text/plain
    template: templates/meta-data.txt
```

The workflow document must pair this with an installer scheme:

```yaml
installer:
  scheme: ubuntu-nocloud
  accepts_delivery:
    - auxiliary_partition
  auxiliary_partition:
    label: CIDATA
    filesystem: vfat
    required_files:
      - /user-data
      - /meta-data
```

Hosts must not assume every installer can use an auxiliary partition. The
installer scheme declares whether the delivery mode is meaningful and how files
must be named.

For installers such as Proxmox auto-install, auxiliary partition delivery is only
sufficient when the base image has already been prepared to fetch from that
partition. SparkWriter owns the partition bytes, not the installer's boot-time
discovery behavior.

Auxiliary partition write semantics:

- v2.2 supports one auxiliary partition per write.
- `delivery.partition.label` and `installer.auxiliary_partition.label` must
  match.
- `delivery.partition.filesystem` and `installer.auxiliary_partition.filesystem`
  must match.
- Android-compatible auxiliary partition delivery supports
  `delivery.partition.placement: preallocated_in_base_image` and
  `delivery.partition.placement: append_after_image`.
- For `preallocated_in_base_image`, MetalStrapper or the adapting service is
  responsible for creating that partition in the base image partition table
  before SparkWriter receives the image.
- For `preallocated_in_base_image`, `delivery.partition.geometry` is required
  before SparkWriter may write auxiliary partition data on Android.
- `delivery.partition.geometry.base_image_sha256` must exactly match
  `source.sha256` and the verified base image hash.
- SparkWriter writes only within the declared
  `delivery.partition.geometry.byte_offset` and `byte_length` range.
- `byte_offset` must equal `start_lba * sector_size`; `byte_length` must be at
  least `delivery.partition.size_mb` MiB.
- For `append_after_image`, `delivery.partition.geometry` is omitted. SparkWriter
  appends one FAT32/VFAT GPT partition after the existing image partitions,
  labels the GPT partition and FAT volume with `delivery.partition.label`, and
  fails if that partition label already exists.
- SparkWriter may raw-read the partition table for sanity checks, but it must not
  depend on Android USB Host APIs exposing partition identity, labels, GUIDs, or
  mounted volumes.
- SparkWriter must fail before writing delivery files when geometry is missing,
  hash-mismatched, out of range for the verified base image, or internally
  inconsistent.
- Required installer files must be present after optional files are filtered.
- Delivery file paths are absolute paths within the auxiliary partition and must
  not contain `..` path traversal.

SparkWriter records a receipt containing at least:

- package ID and package-root hash
- selected base image ID, size, and hash when known
- target device identity observed before the write
- auxiliary partition label, filesystem, size, base-image-pinned geometry, and
  byte range
- delivered file paths, media types, sizes, and hashes
- adaptation status used for the selected base image
- write and verification result

## Android Adaptation Model

SparkWriter for Android should treat v2.2 packages as follows:

1. Verify `manifest.json`.
2. Fetch and hash-check package documents/files.
3. Render UI and generated artifacts locally.
4. Match the selected source or adapted base image against the installer scheme
   requirements.
5. If the image is declared `preadapted`, require its
   `adaptation.satisfies` flags to cover `installer.base_image_requires`.
6. If adaptation is required but not already satisfied, ask MetalStrapper or
   another trusted adapting service to satisfy an optional adaptation role, or
   mark that package path unavailable.
7. Download and verify any adapted `base_image` output when an adapting service
   is used.
8. Raw-write the base image to USB.
9. Verify that auxiliary partition geometry is present and pinned to the verified
   base image hash.
10. Apply host-owned auxiliary partition delivery to the declared byte range when
   the installer scheme supports it.
11. Record the base image hash, auxiliary partition manifest, and receipt.

Android should not expose a general command runner. It should only execute
host-owned primitives:

- raw image write
- raw/readback verification
- bounded ISO9660 boot-script or placeholder patching for declared netboot seed
  images
- supported filesystem creation, starting with `vfat`
- copy generated/hash-pinned files into the auxiliary partition
- publish declared mail auxiliary sidecars during `write_ready`

For netboot seed packages, Android should use a shorter flow:

1. Verify `manifest.json`.
2. Fetch and hash-check the workflow and seed ISO.
3. Generate rendezvous ids when the boot patch, templates, or `write_ready`
   deliveries request `netboot_rendezvous_id`, `netboot_rendezvous_label`, or
   `Spark.mail.aux_data.*`.
4. If `host_events.write_ready.return_delivery` is present, render and deliver
   it before writing. Mailroom netboot publishes use `method: PUT`,
   `media_type: application/x-ipxe`, and a URL containing the generated
   rendezvous id plus an artifact label such as `/boot`.
5. If `boot_patch` is present, apply only the declared iPXE script or placeholder
   patch and refuse ambiguous or oversized replacements.
6. Raw-write the seed image to USB.
7. Verify readback.
8. Record the seed image hash, any applied boot patch metadata, target device,
   and receipt.

Netboot seed workflows do not need `delivery`; the seed image itself is the
write artifact. If `delivery` is present, it must still satisfy the normal
installer/delivery rules.

## Capability Vocabulary

Initial host capabilities:

- `usb.raw_write`
- `usb.raw_verify`
- `iso9660.boot_patch`
- `partition.preallocated_geometry`
- `filesystem.vfat.create`
- `filesystem.vfat.write`
- `filesystem.iso9660.modify`
- `iso.read`
- `iso.write`
- `adaptation.local`
- `adaptation.service`

Android is expected to support:

- `usb.raw_write`
- `usb.raw_verify`
- `iso9660.boot_patch`, for bounded netboot script and placeholder patches
- `partition.preallocated_geometry`
- `filesystem.vfat.create`, after implementation
- `filesystem.vfat.write`, after implementation
- `adaptation.service`

Android is not expected to support:

- `adaptation.local`
- generic shell execution
- arbitrary mounted filesystem mutation
- local OCI builders

## Validation Rules

v2.2 package validation should reject:

- package roots that are not `version: "2.2"`
- package roots that are not `kind: "sparkplug.manifest"`
- package roots missing a valid `metadata.manifest_root_sha256`
- document or file references without SHA-256 pins
- HTTP package/document/file URLs are blocked by default by MetalStrapper bootstrap
  install and re-approval policy; explicit configuration may allow them with a
  warning.
- more than one workflow document
- auxiliary partition delivery without an installer scheme that accepts it
- auxiliary partition delivery for a source image that is neither declared as
  already adapted nor produced by a compatible adaptation role or adapting
  service
- installer scheme requirements that are not acknowledged by the source image or
  the adaptation output
- adaptation roles whose outputs are not declared
- adaptation role outputs consumed by Android without a verified hash
- `source.adaptation.satisfies` values that do not cover
  `installer.base_image_requires` for the selected source
- mismatched auxiliary partition labels or filesystems between `installer` and
  `delivery`
- auxiliary partition delivery with missing geometry for Android USB writes
- auxiliary partition geometry whose `base_image_sha256` does not match the
  selected source image hash
- auxiliary partition geometry whose `byte_offset` does not equal
  `start_lba * sector_size`
- delivery file paths that are relative or contain path traversal
- more than one auxiliary partition delivery in a single workflow
- netboot `boot_patch.script_patches` whose script does not start with `#!ipxe`
- netboot `boot_patch.script_patches` without exact ISO candidate paths and, for
  hybrid media, an exact embedded ESP script path
- netboot `boot_patch.placeholder_patches` whose replacement is longer than the
  declared placeholder or whose placeholder is missing or ambiguous
- Mailroom netboot boot patches without `source.capabilities:
  [netboot-rendezvous]`
- `return_delivery.method` values other than `POST` or `PUT`

## Open Questions

- Add here and notify user
