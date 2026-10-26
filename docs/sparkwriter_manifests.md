# SparkWriter Manifest Authoring

This is the concise author guide for the SparkWriter manifest format.

If this document and the runtime disagree, trust the Android v3.0 contract,
schema, and tests in this repository:

- `docs/SPARKPLUG_V3.md`
- `docs/sparkwriter_manifest_contract_v3_0.schema.json`
- `manifest-runtime/src/main/kotlin/net/metalstrapper/sparkwriter/manifest/ManifestRuntime.kt`

## What A Manifest Does

A SparkWriter manifest defines a customization layer that can:

- expose configuration fields in the UI
- arrange those fields into wizard pages
- render templates with the collected values
- run Android-supported local actions during image preparation or after USB write

Manifests are selected after the user chooses an installation Source.

## Minimal Manifest

A SparkPlug is two documents. The image document describes the base image and
how SparkWriter may change it:

```yaml
version: "3.0"
kind: sparkplug.image
metadata:
  id: example-image
  name: Example Image
source:
  id: example-image
  user_supplied: true
```

The workflow document describes what to ask and render, and names its image:

```yaml
version: "3.0"
kind: sparkplug.workflow
metadata:
  id: example-manifest
  name: Example Manifest
image: example-image
```

Required rules:

- `version` must be `"3.0"` in both documents; Android rejects every other version
- `kind` is `sparkplug.workflow` or `sparkplug.image`
- the workflow's `image` equals the image's `metadata.id`
- `metadata.id` is the stable identifier and `metadata.name` the display name

`source`, `outputs`, `installer`, `delivery`, and `boot_patch` belong in the
image. Everything else in this guide belongs in the workflow. A workflow fills
the image's slots with its `fill` map; see the delivery sections below.

Useful optional metadata:

- `metadata.version`
- `metadata.author`
- `metadata.description`
- `metadata.homepage`

SparkWriter never skips what it does not understand. A manifest that uses a key,
field type, action type, action phase, host event, or padding strategy outside
this contract fails to load with an error naming the unsupported item.

## Installation Model

SparkWriter currently supports two install paths:

1. HTTPS install with `spark://plugin/add?manifest=...`, pointing to a package
   root named `manifest.json`.
2. Local import of a `.zip` package selected with Android's system document
   picker. No storage permission or "all files access" is required. This path
   is for SparkPlug development: it is off by default and appears only after
   the user turns on "Allow local ZIP import" in Settings.

URL installs are snapshot installs. Reinstall to pick up remote changes. Both
paths stage and review a package before saving it; bundled SparkPlugs shipped in
the app bypass this import flow.

### Local ZIP Layout

A local package must be a standard, unencrypted ZIP with `manifest.json` at its
root. Documents and files referenced by that package root use relative paths;
every regular file in the ZIP must be declared. Wrapper directories, nested
archives, undeclared files, unsafe paths, and non-UTF-8 declared text are
rejected.

The compressed ZIP and the cumulative expanded package content are each limited
to 100 KiB. Password protection is not supported and should not be used to carry
secrets.

Local import validates package structure and the SparkPlug contract, but does
not require or verify package hashes. A hash stored beside content in the same
user-selected ZIP would not establish who produced it. Remote HTTPS packages
continue to require package-root and sidecar hashes.

### Import Review

Before a remote download, SparkWriter confirms the HTTPS connection and
certificate. That confirms the contacted host and protected transport, not the
publisher or safety of the package.

After ingestion, SparkWriter shows inspectable script content and highlights
notable behavior such as download-and-execute pipelines, package or repository
changes, remote-access enablement, startup execution, and credential use. These
findings help review; they are not a complete security analysis. The package is
saved only after the user confirms this second review.

## Source Image

The image document declares its install media with a single `source` object.
The older `presets` and `preset_feeds` keys are not part of the v3.0 contract
and fail the load.

Useful `source` properties:

- `id` and `name`
- `family` and `capabilities`, used by `ui_visibility`
- `url` and `sha256` for a downloadable, hash-verified image
- `acquire.kind`: `direct` (the default) or `torrent`; any other kind is
  refused when the image is acquired
- `user_supplied: true` when the user provides the image file
- `adaptation` describing whether the image already satisfies
  `installer.base_image_requires`

## Config Fields

Use `config_fields` to describe the values SparkWriter should collect.

Supported field types:

- `text`
- `password`
- `select`
- `multiline`
- `array`
- `info`

Common field properties:

- `id`
- `label`
- `type`
- `default`
- `required`
- `description`
- `placeholder`
- `options` for `select`
- `big` for wide `multiline` layout
- `advanced`
- `hidden`
- `disabled`
- `immutable`
- `standard_field`
- `storage`
- `bindings`
- `when` to show a field only when another field has a given value

Current behavior:

- required fields must be non-empty before the user can continue
- `select` options use `{ "value": ..., "label": ... }`
- `info` shows non-editable text using `default`
- `array` collects one item per line, accepts a list as `default`, and also
  exposes the value to templates as a TOML array named `<field_id>_toml`
- every `config_fields` entry is an object; string and single-key shorthand
  entries are rejected
- `description` is shown as helper text or tooltip, depending on widget type
- `advanced: true` lets the host place a field in an advanced section that is
  normally collapsed or visually deemphasized
- `hidden: true` keeps a field out of ordinary UI while still allowing defaults,
  host-provided values, and bindings to populate the workflow context
- `disabled: true` or `immutable: true` shows a value for review without
  allowing user edits; hosts still submit the field's default or host-provided
  value
- `standard_field` and `storage` are meaningful to the host UI for profile save/fill behavior
- `bindings` declare which manifest subsystem consumes a value, its semantic destination, and required encoding
- `fetch` lets a host offer to fetch a text value from an HTTPS endpoint into the field

Advanced and disabled are presentation hints. Hidden fields are omitted from
ordinary UI and cannot be targeted by host profiles or remote prefill buttons.
Nothing in a manifest can keep a value secret from the person writing the USB
drive: they hold the device and the media, so every value SparkWriter receives
is visible to them. Do not send a value through a manifest, host profile, or
prefill response unless the writer is allowed to see it.

### Field HTTPS Fetch

A field can declare an optional HTTPS text fetch source. This is intended for
public user-owned data such as SSH public keys:

```json
{
  "id": "ssh_keys",
  "label": "SSH Public Keys",
  "type": "multiline",
  "big": true,
  "standard_field": "user.ssh_public_keys",
  "fetch": {
    "url": "https://github.com/octocat.keys",
    "media_type": "text/plain",
    "max_bytes": 16384
  }
}
```

Fetch fields use:

- `url`: required absolute HTTPS URL
- `method`: optional; only `GET` is supported
- `media_type`: optional expected response type for review and future validation
- `max_bytes`: optional positive response-size limit

Current Android behavior:

- SparkWriter shows a Fetch button for editable fields that declare `fetch`
- fetched text replaces the current field value
- trailing line endings are trimmed
- non-HTTPS URLs and non-GET methods are rejected when the manifest loads
- fetch execution is user-triggered; manifests do not fetch remote values automatically

For GitHub SSH keys, publishers can point at
`https://github.com/<username>.keys` when the manifest is generated for a known
GitHub account.

### Host Profiles And Remote Prefill

SparkWriter v3.0 can prototype whole-form prefill through `form.host_profiles`
and `form.buttons`.

Host profiles are embedded presets:

```json
{
  "form": {
    "host_profiles": [
      {
        "id": "lab-one",
        "label": "Lab One",
        "fields": {
          "hostname": "lab-one",
          "enrollment_token": "secret-from-admin"
        },
        "immutable_fields": ["enrollment_token"]
      }
    ]
  }
}
```

Remote prefill buttons fetch HTTPS JSON and bind response paths to config fields:

```json
{
  "form": {
    "buttons": [
      {
        "id": "admin-prefill",
        "label": "Admin Prefill",
        "fetch": {"url": "https://prefill.example/hosts/lab-one"},
        "field_bindings": {
          "hostname": "host.network.hostname",
          "enrollment_token": "host.secrets.enrollment_token"
        },
        "immutable_fields": ["enrollment_token"]
      }
    ]
  }
}
```

Current Android behavior:

- host profiles and remote buttons appear on pages where they target visible fields
- hidden field targets are rejected when the manifest loads
- remote buttons are user-triggered; a manifest cannot fetch prefill data automatically
- remote responses must be JSON objects and bindings use simple dot paths
- scalar JSON values are converted to strings; `null` becomes an empty string
- prefilled fields show their source label in editor and review
- fields listed in `immutable_fields` become read-only once prefilled; their
  values stay visible

### Field Bindings

A field can define both how SparkWriter collects a value and how the manifest uses it:

```json
{
  "id": "timezone",
  "label": "Timezone",
  "type": "text",
  "required": true,
  "default": "UTC",
  "bindings": [
    {
      "consumer": "template",
      "target": "proxmox_answer",
      "destination": "global.timezone",
      "encoding": "toml-string"
    }
  ]
}
```

Each binding contains:

- `consumer`: currently `template`, `action_condition`, or `return_delivery`
- `target`: the named template or delivery target
- optional `destination`: the semantic location within that consumer
- `encoding`: `raw`, `toml-string`, `toml-array`, `toml-fragment`, `shell-argument`, or `secret`

Bindings make field intent available for validation, review, and future structured rendering. The current execution pipeline still uses templates and actions, so a template binding should correspond to an actual field reference in the named template.

## Wizard Pages

`wizard.pages` lets a manifest split its `config_fields` into multiple steps.

Each page has:

- `id`
- `title`
- optional `description`
- `fields`: ordered field IDs from `config_fields`

Example:

```json
{
  "config_fields": [
    { "id": "hostname", "label": "Hostname", "type": "text", "required": true },
    { "id": "email", "label": "Email", "type": "text" },
    { "id": "ssh_keys", "label": "SSH Keys", "type": "multiline", "big": true }
  ],
  "wizard": {
    "pages": [
      {
        "id": "identity",
        "title": "Identity",
        "description": "Basic machine settings.",
        "fields": ["hostname", "email"]
      },
      {
        "id": "access",
        "title": "Access",
        "fields": ["ssh_keys"]
      }
    ]
  }
}
```

Validation rules enforced at load time:

- `wizard` must be an object
- `wizard.pages` must be an array
- each page must be an object with a unique `id` and an explicit, nonblank `title`
- each listed field must exist in `config_fields`
- a field may appear on at most one wizard page
- when pages are declared, every non-hidden `config_field` must appear on exactly one page

Current host behavior:

- pages are shown in manifest order
- a page's fields are shown in the listed order
- the Continue button stays disabled until required fields on that page are filled
- the last page button changes to Review
- if no pages are declared, SparkWriter creates a single implicit page named `<Manifest Name> Configuration` containing every non-hidden field
- declaring any page opts the publisher into owning the complete visible field layout; SparkWriter does not append a fallback page
- field labels remain optional; when omitted, SparkWriter derives a human-readable label from the field ID

### Profile Save And Auto-Fill

SparkWriter can offer to reuse saved profile values on a page-by-page basis.

To participate, a field needs:

- a recognized `standard_field`
- `storage.scope` set to `profile`, or `storage.persist` set to `true`

Known `standard_field` values:

- `user.name`
- `user.username`
- `user.email`
- `user.ssh_public_keys`
- `network.suffix`
- `network.wifi.ssid`
- `apt.proxy`
- `locale.timezone`
- `locale.keyboard`

Current behavior:

- when a wizard page has saved profile matches, SparkWriter offers a Profile Fill button with default-checked fields
- values are matched by `standard_field`, not by the manifest field ID
- `password` fields are never saved to or filled from a profile
- SparkWriter saves non-empty values when the user advances through wizard pages and when starting a flash/save flow

## Visibility Rules

Use `ui_visibility.when` to control when a manifest is offered for the current Source.

Supported selectors:

- `source_family`
- `source_id`
- `source_capabilities`

A condition on any other selector fails the load.

## Templates

`templates` is a mapping from template name to one of:

- inline string
- array of lines

A template can also live in a separate package file. Declare it under `files`
in `manifest.json` with the key or role `template:<name>`; its text replaces any
inline template of the same name. `{ "file": ... }` and `{ "asset": ... }`
template objects are not supported.

Template rendering uses SparkWriter's restricted template engine.

That means:

- `{{field_name}}` substitutes a value
- `{% if field_name %}`, `{% else %}`, and `{% endif %}` support truthiness
  checks; each tag must be on a line of its own
- comparisons, loops, filters, and arbitrary Jinja expressions are not supported
- an undefined value in `{{ }}` fails the render; in `{% if %}` it is false
- template sidecars must stay relative to the manifest and may not escape its directory

Current template context includes:

- config field defaults and collected UI values
- action outputs from the current phase
- host-generated `Spark.mail.*` variables, described below

Hyphenated field IDs are also aliased for Jinja use. For example, `root-password` can be referenced as `{{ root_password }}`.

### Debian Preseed Packages And Late Commands

For Debian-style preseed flows, keep package installation and bootstrap commands
inside the preseed template. SparkWriter renders `/preseed.cfg`, delivers it on
the declared auxiliary partition, and patches boot arguments such as
`auto file=/preseed.cfg`; the Debian installer owns `pkgsel` and `late_command`
execution.

Useful prototype lines:

```text
d-i mirror/http/proxy string http://apt-proxy:3142
d-i pkgsel/include string curl ca-certificates
d-i preseed/late_command string in-target sh -c 'curl -fsSL https://tailscale.com/install.sh | sh && tailscale up --auth-key={{tailscale_auth_key}} --hostname={{tailscale_hostname}}'
```

The corresponding manifest should deliver that rendered artifact as
`/preseed.cfg` and use structured `boot_patch.kernel_arg_patches` to ensure
`auto` and `file=/preseed.cfg` are present on the selected installer entries.

`boot_patch.text_patches` can make ordered UTF-8 text replacements before
kernel argument patches run. Use `kind: replace_once` with a unique `find`
string and `replace` string. When multiple text and kernel argument patches
target the same ISO file, SparkWriter applies them to one in-memory copy and
then verifies the final file still fits in the ISO file's allocated sectors.
This allows one patch to grow as long as other patches or existing padding keep
the final boot config within its storage allocation.

`boot_patch.placeholder_patches` may use `encoding: ascii` (the compatibility
default) or `encoding: utf-8`. Placeholder capacity is measured in encoded
bytes, not Unicode characters. SparkWriter preserves the surrounding file size
and space-pads any unused bytes, so manifest authors must reserve enough bytes
for the longest expected UTF-8 value and ensure the target file format accepts
UTF-8.

## Actions

Actions live under:

- `actions.on_iso_ready`
- `actions.on_write_ready`
- `actions.on_write_complete`
- `actions.on_workflow_started`

Currently supported action types:

- `render_template`
- `generate_ephemeral_password`
- `hash_password`
- `create_artifact`

Notes:

- `on_iso_ready` runs during ISO processing
- `on_write_ready`, `on_write_complete`, and `on_workflow_started` run before
  the matching host event is delivered
- an action `type` or phase not listed above fails the load
- `when` conditions can skip an action
- `output_var` stores an action result for later actions in the same phase
- generic commands, runtime jobs, local OCI/container execution, and asset
  runners are outside the Android v3.0 contract

`generate_ephemeral_password` may opt in to user copy/display by setting
`user_secret: true`. Add `user_secret_label` when the generated value should be
shown with a friendlier name:

```yaml
- id: generate-root-password
  type: generate_ephemeral_password
  output_var: generated_root_password
  user_secret: true
  user_secret_label: Root password
```

Only SparkWriter-generated secret values should be marked this way. Do not mark
user-provided values such as SSH public keys or internal rendezvous tokens as
user secrets.

`hash_password` converts a variable containing a plain password into an
installer-ready password hash. It currently supports SHA-512 crypt:

```yaml
- id: hash-root-password
  type: hash_password
  input_var: generated_root_password
  output_var: generated_root_password_hash
  algorithm: sha512_crypt
```

For exact per-action fields, use the schema and runtime as the source of truth.

## Auxiliary Partition Delivery

Android SparkWriter clients cannot ask the Android USB Host API for partition
labels, GPT GUIDs, mounted volumes, or filesystem identities. For auxiliary
partition delivery, SparkWriter supports two Android-safe placements.

`preallocated_in_base_image` uses image-pinned geometry published by the adapting
service:

```yaml
source:
  # In this workflow, source is the MetalStrapper base ISO, not the distro
  # vendor source ISO consumed by MetalStrapper's adapter.
  sha256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc

delivery:
  mode: auxiliary_partition
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
```

`geometry.base_image_sha256` must match the verified `source.sha256`.
`byte_offset` must equal `start_lba * sector_size`. SparkWriter must fail before
writing delivery files when geometry is missing, hash-mismatched, or internally
inconsistent.

`append_after_image` lets SparkWriter derive geometry after the raw image write
by inspecting and extending the target device GPT. This placement is FAT32/VFAT
only and is intended for installers such as Ubuntu NoCloud `CIDATA`.

```yaml
delivery:
  mode: auxiliary_partition
  partition:
    label: CIDATA
    filesystem: vfat
    size_mb: 64
    placement: append_after_image
  files:
    /user-data:
      media_type: text/cloud-config
    /meta-data:
      media_type: text/plain
```

Each file path is a slot. The workflow names what goes in it:

```yaml
fill:
  /user-data: user-data
  /meta-data: meta-data
```

For `append_after_image`, `geometry` is omitted. SparkWriter appends one GPT
partition after the existing image partitions, labels the GPT partition and FAT32
volume with `delivery.partition.label`, and fails if a partition with that label
already exists.

## Fixed Byte-Range Delivery

Some adapted images do not need SparkWriter to discover, create, or mount an
auxiliary filesystem at all. The adapting service can reserve all-zero or
otherwise deterministic placeholder byte ranges inside the base image and
publish a patch map for SparkWriter to overwrite after the raw image write.

This mode is intended for Android-safe writes where the base image already
contains the installer hooks. SparkWriter only needs random-access writes to
known offsets on the target device.

```yaml
source:
  sha256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
  adaptation:
    status: preadapted
    satisfies:
      - auto_install_mode
      - answer_fetch.iso_placeholder
      - firstboot_handoff.iso_placeholder

installer:
  scheme: proxmox-auto-install
  accepts_delivery:
    - fixed_byte_ranges
  base_image_requires:
    - auto_install_mode
    - answer_fetch.iso_placeholder
    - firstboot_handoff.iso_placeholder

delivery:
  mode: fixed_byte_ranges
  id: proxmox-inline-seed
  after: write_base_image
  base_image_sha256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
  patches:
    - id: answer_toml
      media_type: application/toml
      byte_offset: 123456789
      byte_length: 16384
      placeholder:
        encoding: zeroes
        sha256: eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
      padding:
        strategy: toml-comment
    - id: firstboot_script
      media_type: text/x-shellscript
      byte_offset: 123473173
      byte_length: 65536
      placeholder:
        encoding: zeroes
        sha256: ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
      padding:
        strategy: shell-comment
```

Each patch `id` is a slot. The workflow fills them:

```yaml
fill:
  answer_toml: answer_toml
  firstboot_script: firstboot_script
```

Rules:

- `delivery.base_image_sha256` must match the verified `source.sha256`.
- Every patch must declare `byte_offset`, `byte_length`, `from`, and a
  placeholder hash or placeholder encoding that SparkWriter can verify before
  writing.
- SparkWriter must fail before writing a patch when the current target bytes do
  not match the declared placeholder.
- SparkWriter must fail when rendered artifact bytes exceed `byte_length`.
- SparkWriter must pad the remaining range using the declared
  content-compatible padding strategy, not arbitrary binary data.
- Patch ranges must not overlap.
- Patch offsets are absolute byte offsets from the start of the raw image/device
  after the base image has been written.

When `placeholder.sha256` is present, SparkWriter verifies only that hash and
ignores `placeholder.encoding`. Otherwise the known `placeholder.encoding`
values are:

- `zeroes`: every byte in the range is `0x00`.
- `metalstrapper-patch-slot`: the range holds a MetalStrapper patch-slot marker
  whose label matches the patch.

Any other encoding fails the load.

Known `padding.strategy` values:

- `zeroes`: fill remaining bytes with `0x00`; use only when the consumer
  tolerates NUL padding.
- `space`: fill remaining bytes with spaces.
- `toml-comment` and `shell-comment`: end the artifact with a newline if
  needed, then fill remaining bytes with `#`.

An unknown strategy fails the load. A patch with a text media type must declare
a padding strategy, or the write is refused; other patches default to `zeroes`.

For text artifacts such as `answer.toml` and `firstboot.sh`, manifests should
prefer comment or whitespace padding so the consumer never reads embedded NUL
bytes. The adapting service is responsible for creating placeholder ranges in a
location that the installer will read, such as an ISO-embedded answer file or a
first-boot hook path.

### Proxmox Answer Templates

When a manifest renders a Proxmox `answer.toml`, keep that template limited to
the Proxmox automated installer answer contract. Do not add SparkPlug or
MetalStrapper delivery fields to the TOML file. Delivery belongs under
`delivery`; first-boot script bodies belong in separate artifacts such as
`artifacts.firstboot_script`. The Proxmox `[first-boot]` table may still be
rendered in `answer.toml` when it is needed to tell the installer to execute the
separate first-boot artifact.

Useful Proxmox answer fields for the package template are:

- `global.keyboard`
- `global.country`
- `global.fqdn`
- `global.mailto`
- `global.timezone`
- `global.root-password`
- `global.root-ssh-keys`
- `global.reboot-mode`
- `network.source`
- `network.cidr`, `network.gateway`, and `network.dns` for static addressing
- `network.filter.ID_NET_NAME_MAC` when the install needs to pin a NIC
- `disk-setup.filesystem`
- `disk-setup.disk-list`
- `first-boot.source`
- `first-boot.ordering`

For Mailroom-backed first-boot handoff, do not hard-code a rendezvous URL. Ask
SparkWriter for a write-scoped URL with the host-provided `Spark.mail.aux_data` variables:

```toml
[first-boot]
source = "from-url"
url = "{{Spark.mail.aux_data.firstboot_url}}"
ordering = "fully-up"
```

SparkWriter generates the URL and matching label during write preparation, then
uses the same values for template rendering, boot patch replacement, and required
`write_ready` delivery.

Prefer field bindings that say how each collected value is encoded:
`toml-string` for ordinary TOML strings, `secret` for sensitive strings, and
`toml-array` for arrays such as SSH keys and disk lists. This gives hosts enough
information to move away from text substitution later without changing the
manifest's user-facing field model.

### Spark Mail Auxiliary Data

`Spark.mail.aux_data` is the manifest namespace for short-lived data that
SparkWriter can publish to a mail/rendezvous service before the USB write begins.
The current implementation creates Mailroom rendezvous URLs and exposes both the
URL and label forms:

- `{{Spark.mail.aux_data.answer_url}}`
- `{{Spark.mail.aux_data.answer_label}}`
- `{{Spark.mail.aux_data.firstboot_url}}`
- `{{Spark.mail.aux_data.firstboot_label}}`

Use `answer_url` when an adapted installer ISO must fetch `answer.toml`; the
generated Mailroom rendezvous id is shared by the session, while the URL ends
with the artifact label such as `/answer` for that payload. Mailroom stores
netboot artifacts internally as `netboot:<rendezvous-id>:<artifact-label>`. Use
`firstboot_url` inside the Proxmox `[first-boot]` table when the first-boot
script is delivered as a separate Mailroom `/firstboot` payload.

Example:

```yaml
templates:
  proxmox_answer_standard:
    - "[first-boot]"
    - source = "from-url"
    - url = "{{Spark.mail.aux_data.firstboot_url}}"
    - ordering = "fully-up"

host_events:
  - event: write_ready
    return_delivery:
      url: "{{Spark.mail.aux_data.answer_url}}"
      method: PUT
      delivery_mode: required
      content_var: proxmox_answer_toml
      media_type: application/toml
  - event: write_ready
    return_delivery:
      url: "{{Spark.mail.aux_data.firstboot_url}}"
      method: PUT
      delivery_mode: required
      content_var: firstboot_script
      media_type: text/x-shellscript
```

The older `mailroom_*` and `metalstrapper.mailroom.*` variable names are no
longer generated; a template that references them fails to render.

### Compact Mailroom Artifacts

Use compact Mailroom artifacts when the installer needs a very short URL. A
manifest declares the compact payloads it needs, then references generated
`Spark.mail.compact.*` variables from boot patches and `host_events`.

```yaml
mailroom:
  compact_artifacts:
    - id: preseed
      read_protocol: https

boot_patch:
  kernel_arg_patches:
    - ensure_args:
        - url={{Spark.mail.compact.preseed_url}}

host_events:
  - event: write_ready
    return_delivery:
      url: "{{Spark.mail.compact.preseed_write_url}}"
      method: POST
      delivery_mode: required
      template: preseed
      media_type: text/plain
```

For each declared compact artifact, SparkWriter generates:

- `{{Spark.mail.compact.<id>_artifact_id}}`
- `{{Spark.mail.compact.<id>_url}}`
- `{{Spark.mail.compact.<id>_write_url}}`

Compact read URLs default to `http://wri7e.net/<artifact-id>`. Set
`read_protocol: https` to use `https://wri7e.net/<artifact-id>` when the target
preboot environment supports HTTPS.

`<id>` must be a short safe artifact id. SparkWriter requires a matching
`write_ready` delivery with `method: POST`, `delivery_mode: required`, and a body
source for each declared compact artifact.

## Host Event Delivery

`host_events` subscriptions can deliver workflow events through a configured
`return_delivery` endpoint. Android delivers `write_ready`, `write_complete`,
and `workflow.started`; a subscription to any other event fails the load. Each
subscription carries its endpoint in a `return_delivery` object with a `url`. `delivery_mode: required` means the workflow stops
when event delivery fails; `best_effort` reports the failure without stopping
the workflow. Retry policy is not part of the SparkPlug manifest contract.
MetalStrapper may enforce retries by responding to subsequent workflow events,
while SparkWriter only gates the workflow on event delivery.

A return delivery endpoint may be on any host: organizations commonly provision
across several domains. The endpoint is trusted because the manifest that
declares it is hash-pinned by `manifest.json`, the same as every other part of
the package. SparkWriter shows each endpoint, method, and body source on the
review step before a write.

Return delivery endpoints must use HTTPS. SparkWriter rejects manifests that
declare any other scheme, refuses to deliver to a rendered URL that is not
HTTPS, and does not follow redirects from HTTPS to HTTP. The same applies to
`mailroom.compact_artifacts[].write_base_url`.

The endpoint URL is resolved from the SparkWriter machine. A URL such as
`https://localhost:3001/api/returns/sparkwriter` works only when MetalStrapper is
listening on port `3001` on that same machine. For remote SparkWriter clients,
generate the manifest with a MetalStrapper URL reachable from those clients.

## Trust And Distribution

Current install-time trust behavior:

- local ZIP import is off until enabled in Settings; ZIPs then require explicit
  selection, structural inspection, behavior review, and final confirmation,
  and are not publisher-verified
- HTTPS package roots require certificate confirmation, hash validation,
  behavior review, and final confirmation
- documents and files fetched for a remote package must be on the same origin
  (scheme, host, and port) as its `manifest.json`
- loopback/local-development HTTP origins remain a development exception
- other plain HTTP origins are blocked

GitHub publisher identity and signature verification are not implemented yet.
GitHub-hosted packages currently follow the same HTTPS and hash-validation path
as other remote packages.

## A Good Authoring Pattern

For most manifests, keep the structure simple:

1. Declare a small set of `config_fields`
2. Group them into a few `wizard.pages`
3. Render one or more templates
4. Materialize artifacts with Android-supported local actions
5. Use delivery modes only for byte ranges or declared auxiliary `vfat` payloads

That shape matches the current UI and is the easiest path to a stable manifest.
