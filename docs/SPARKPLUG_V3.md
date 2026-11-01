# SparkPlug v3.0 Android Contract

SparkPlug v3.0 is the public Android-facing contract for SparkWriter. A
SparkPlug is everything SparkWriter needs to write a customized USB drive,
without pretending to be a desktop Linux build environment:

- verified base image selection
- form/config collection
- template and artifact rendering
- raw USB image write and readback verification
- bounded boot patching, including fixed-size text replacement
- bounded fixed byte-range patching
- one declared auxiliary `vfat` payload path
- declared host-event callbacks such as `write_ready` and `write_complete`

A SparkPlug has two documents:

- **Workflow** (`kind: sparkplug.workflow`): what to ask and what to render.
  Fields, wizard, actions, templates, artifacts, host events, and a `fill` map
  that puts artifacts into the image's slots.
- **Image** (`kind: sparkplug.image`): the base image and how SparkWriter may
  change it. Source, installer, slots (`delivery`), boot patches, and outputs.

An image document is hand-written for a stock image (for example Ubuntu or
Debian straight from upstream). For a custom image built on a Linux host, the
build (a MetalStrap, see `METALSTRAP.md`) generates it. One image can serve
many workflows, each naming it in `image:`.

v3.0 is the only version Android accepts. A package root, workflow, or image
document with any other `version` is rejected. v2.3 single-document manifests
are not accepted.

## Strict Loading

Android never skips what it does not understand. A manifest fails to load, with
an error naming the item, when it contains:

- a key this contract does not define, at any level
- an unknown config field type, action type, action phase, or host event
- an unknown `ui_visibility` selector or `source.acquire.kind`
- an unknown fixed byte-range placeholder encoding or padding strategy

A future contract version can therefore add behavior without older apps
silently ignoring it.

## Package Root

A package root is a `manifest.json`. Android accepts one in two ways:

- **Remote install** (always on): a `spark://plugin/add?url=<manifest.json URL>`
  link. See [Remote Install](#remote-install).
- **Local ZIP import** (development only, off by default): a ZIP picked from
  the device. See [Local ZIP Import](#local-zip-import-development).

```json
{
  "version": "3.0",
  "kind": "sparkplug.manifest",
  "metadata": {
    "manifest_root_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "id": "example-ubuntu",
    "name": "Example Ubuntu Autoinstall"
  },
  "documents": {
    "workflow": {
      "role": "workflow",
      "url": "ubuntu-autoinstall.sparkplug.yaml",
      "sha256": "..."
    },
    "image": {
      "role": "image",
      "url": "ubuntu-26.04-server-autoinstall.image.yaml",
      "sha256": "..."
    }
  },
  "files": {
    "template:firstboot_script": {
      "url": "firstboot.sh",
      "sha256": "...",
      "media_type": "text/x-shellscript"
    }
  }
}
```

Rules:

- Remote package roots require `metadata.manifest_root_sha256`: the lowercase
  SHA-256 of the UTF-8 canonical JSON of `manifest.json` with that one key
  removed. Canonical JSON here means object keys sorted, no insignificant
  whitespace, and array order preserved.
- Every remotely referenced document and file is SHA-256 pinned.
- Sidecar references may be relative package paths or absolute URLs. Android
  fetches a sidecar only from the same origin (scheme, host, and port) as
  `manifest.json`.
- Arbitrary package files do not become behavior by being present. Android
  fetches and verifies only the two documents and `template:<name>` files.
- Package roots contain exactly two documents: one with `role: workflow` and
  one with `role: image`. Any other role, including `ui` and `runtime`, is
  rejected.
- Each document or file reference has exactly one of `url` or `inline`. An
  inline reference is `{"encoding": "utf-8", "text": "..."}`; no other encoding
  is accepted. A package root that inlines everything is a complete package in
  one file.
- All package content is UTF-8 text. Invalid UTF-8 fails the load.
- Importing a package whose `metadata.id` matches an installed or bundled
  manifest replaces it.

SparkWriter shows inspectable scripts and notable behavior (download-and-run,
`eval`, package or repository changes, remote access, credential use, network
endpoints in scripts) before committing either a local or remote import. These
findings are pattern matches to help a person review, not a sandbox.

### Remote Install

- The link opens a source check, then downloads the package root and its
  sidecars after the user approves.
- Allowed origins are HTTPS, or plain HTTP to local development hosts only:
  `localhost`, `127.0.0.1`, `::1`, the emulator hosts `10.0.2.2` and
  `10.0.3.2`, the ChromeOS Linux container range `100.115.92.0/24`, and
  `*.linux.test`. This HTTP allowance is in release builds today.
- Over plain HTTP, `manifest_root_sha256` only proves the files are consistent
  with each other. Whoever can change the traffic can change the hashes too.
- Redirects are not followed. The package root and all sidecars share one
  100 KiB download budget.

### Local ZIP Import (development)

Turn on **Settings → Allow local ZIP import**. A **Local ZIP** button then
appears on the SparkPlugs screen and opens the system file picker.

- The selected file is copied to app cache, capped at 100 KiB, and the copy is
  deleted after inspection. Nothing is extracted to disk.
- Decompression uses the platform `java.util.zip.ZipFile` (system zlib).
  SparkWriter does not ship its own decompressor.
- Entries must be stored or deflated. At most 1024 entries. Nested archives
  (`.zip`, `.jar`, `.apk`), duplicate paths, absolute paths, backslashes, `.`
  and `..` segments are rejected.
- The 100 KiB expanded limit counts the bytes actually inflated, not the sizes
  the archive claims.
- Every file in the archive must be declared in `manifest.json`, and every
  declared `url` must be a relative path present in the archive.
- `sha256` values are optional and are not publisher verification. A local ZIP
  is only as trustworthy as the person who made it.

## Workflow and Image Documents

Minimum image document:

```yaml
version: "3.0"
kind: sparkplug.image
metadata:
  id: base-image
  name: Base Image
source:
  id: base-image
  url: https://example.com/base.iso
```

Minimum workflow document:

```yaml
version: "3.0"
kind: sparkplug.workflow
metadata:
  id: example
  name: Example
image: base-image
```

The workflow's `image` must equal the image's `metadata.id`.

| Workflow keys | Image keys |
|---|---|
| `metadata`, `image`, `fill` | `metadata` (no `manifest_root_sha256`) |
| `config_fields`, `wizard`, `form`, `ui_visibility` | `source`, `outputs` |
| `actions`, `templates`, `artifacts` | `installer` |
| `host_events`, `mailroom` | `delivery`, `boot_patch` |

A key in the wrong document fails the load like any unknown key.

### Slots and `fill`

An image's slots are its `delivery.patches` entries (named by `id`) and its
`delivery.files` entries (named by path). Slots describe where bytes go; they
never name an artifact. The workflow says what goes in each slot:

```yaml
# image
delivery:
  mode: auxiliary_partition
  files:
    /user-data:
      media_type: text/cloud-config
    /meta-data:
      media_type: text/plain

# workflow
fill:
  /user-data: user-data
  /meta-data: meta-data
```

Loading fails when `fill` names a slot the image does not declare, names an
artifact the workflow does not declare (in `artifacts` or as a
`create_artifact` output), or leaves a slot unfilled that is not
`optional: true`.

### Direct writes and delivery

Images may omit `delivery`. Android writes the selected, verified image as-is,
optionally applying supported boot patches and return delivery hooks.

Images with `delivery` must also declare an `installer`, because the installer
scheme owns whether a delivery mode makes sense.

### Bundled SparkPlugs

Bundled workflows live at the root of `app/bundled/`, and their images in
`app/bundled/images/<image id>.image.yaml`. Examples and docs use the same
layout.

## Supported Local Actions

Android v3.0 supports only local, deterministic action types:

- `generate_ephemeral_password`
- `hash_password`
- `render_template`
- `create_artifact`

Unsupported build/job semantics are not part of v3.0. A manifest that uses
`command`, `command_contract`, `run_asset`, local OCI builders, subcontainer
roles, or runtime job documents is rejected.

## Templates

Templates substitute `{{ name }}` placeholders line by line, with
`{% if name %}` / `{% else %}` / `{% endif %}` blocks. By default a value is
inserted as raw text.

A placeholder may pipe its value through filters: `{{ name | filter }}`.

- `shell_quote` emits a POSIX single-quoted literal (`'` becomes `'\''`).
  Values may not contain NUL bytes.
- `toml_string` emits a TOML basic string with `"`, `\` and control
  characters escaped.
- `mac_hex` turns a MAC address such as `00:11:22:AA:BB:CC` into 12 lowercase
  hex digits (`001122aabbcc`), the form udev uses in `ID_NET_NAME_MAC`. It
  accepts `:`, `-` or `.` separators and fails the render on anything that
  is not a MAC address. It does not quote, so it may sit inside a quoted string.

The quoting filters emit the surrounding quotes themselves, so write
`name={{ name | shell_quote }}` and `key = {{ name | toml_string }}`, never
`'{{ name | shell_quote }}'`. Android rejects at load time a malformed
placeholder, an unknown filter, or a quoting filter wrapped in quotes.

Use a quoting filter for every free-text value placed in a script or a config
file. Raw substitution is only right for values the template expects to be
pre-formatted, such as a TOML array.

## Config Field Limits

`max_length` (a positive integer) caps `text`, `password`, `multiline`, and
`array` fields, counted in UTF-16 characters. The editor refuses input over the
limit and the wizard will not continue while a value is over it. Rendering checks
again, because prefilled or saved values bypass the editor. A `default` longer
than `max_length` fails the load.

Set a limit on any field rendered into a fixed-size patch slot, so the rendered
artifact fits its `byte_length`.

`pattern` is a regular expression that a non-empty `text`, `password`,
`multiline`, or `array` value must match in full. An empty value is left to
`required`. The editor marks a value that does not match, the wizard will not
continue while one is on screen, and rendering checks again. A field the wizard
hides is not checked. Put an example of the expected format in `description`,
since the error points there. An invalid regular expression, or a non-empty
`default` that does not match, fails the load.

A `when` condition holds only while the field it names is itself shown. A field
nested under a choice that is hidden stays hidden too.

## Display Text Translations

Display text keys (field `label`, `description`, `placeholder`, option `label`,
page `title` and `description`, button and host profile `label` and
`description`, and `user_secret_label`) take a plain string or a map of BCP 47
language tag to text. A map may also hold `ref`, naming one of SparkWriter's
shared strings. Any other map key, an empty map, blank text, or an unknown `ref`
fails the load. `metadata.language` (default `en`) is the language of plain
strings. See [Translating Display Text](sparkwriter_manifests.md#translating-display-text).

## Delivery Modes

`delivery` remains a single strategy per manifest.

### `fixed_byte_ranges`

Use this when an adapted base image reserves exact byte ranges for per-write
artifacts. Android verifies the selected image hash, verifies placeholders when
declared, writes rendered bytes at declared offsets, pads the reserved range,
and verifies readback.

This does not resize files or edit filesystems. It is an offset patch primitive.

### `auxiliary_partition`

Use this when installer seed data belongs in a declared `vfat` payload.

Supported placements:

- `preallocated_in_base_image`: Android validates declared GPT geometry and
  writes within that existing byte range.
- `append_after_image`: Android may append one GPT basic-data partition after
  the existing image content, create a `vfat` image for it, and write that range.

`append_after_image` is intentionally narrow. It is not general partition
editing: Android does not move, resize, repair, or mount arbitrary partitions.

## Optional Sections

Optional workflow sections, when their dependencies are present:

- `config_fields`, `wizard`, and `ui_visibility` for form UX
- `artifacts` and `templates` for generated files
- `host_events`, each with a `return_delivery` endpoint, for callbacks
- `mailroom` for compact/rendezvous artifact handoff

Optional image sections:

- `boot_patch` for bounded ISO boot config, script, or placeholder patches.
  Boot patch text may use the workflow's variables (for example a Mailroom URL).
- `outputs` for USB/ISO write preferences

## Network Trust

- Package sidecars are fetched only from the origin of `manifest.json`.
- Nothing in a manifest hides a value from the person writing the USB drive.

### Initial Release: External Services Off

The initial Android release disables every manifest feature that makes the
phone, or the machine being installed, contact a service the manifest names:

- `host_events` / `return_delivery` callbacks
- `mailroom`, and any template or boot patch that references a Mailroom host
- config field `fetch`
- `form.buttons` remote prefill

Such manifests still load and can be inspected, but they are marked unavailable
with a "Disabled in this release" reason and cannot write. This applies the
same way to remote installs, local ZIP imports, template files attached from a
package, and manifests installed by an earlier build. The app also refuses
these requests at the network call itself. Local host profiles are unaffected.

Development builds can opt in with `-Psparkwriter.externalServices=true`.

What this switch does **not** cover:

- Remote install itself (fetching `manifest.json` and its sidecars).
- Network calls made by scripts the manifest writes to the drive, such as a
  first-boot `curl`. These are flagged at import as "Script network endpoint",
  not blocked.
- Mailroom references are detected by a fixed list of hostnames and paths. A
  script that reaches Mailroom another way is not detected.

### When External Services Are Enabled

These rules apply only to builds with `-Psparkwriter.externalServices=true`.

- `return_delivery`, field `fetch`, and prefill endpoints must use HTTPS and may
  be on any host.
- For a remote HTTPS install they are trusted because the package root
  hash-pins the manifest that declares them. A local ZIP has no such pin: its
  endpoints are whatever the ZIP's author wrote.
- `return_delivery` bodies are rendered from manifest variables and can include
  generated passwords, password hashes, keys, and other rendered artifacts.
- Each return endpoint's event, method, URL, body source, and media type are
  shown on the review step before a write.
- Mailroom compact artifacts may be read back over plain HTTP
  (`http://wri7e.net/<id>`), so their content is visible on the network.

## Android Non-Goals

Android SparkWriter does not provide:

- generic shell command execution
- local OCI/container/subcontainer execution
- desktop ISO mutation with external tools
- arbitrary partition editing
- mounting unknown filesystems
- network-enabled build scripts from a manifest
- unpinned sidecar behavior

Those tasks must happen before Android receives the image or be handled by an
external service that returns a hash-described artifact.

## Removed From Earlier Drafts

v2.3 and v2.2 manifests are not accepted. In v3.0 the single workflow document
was split into workflow and image documents, and delivery slots no longer take
`artifact_id` or `from` (use the workflow's `fill`). These draft forms are also
gone and now fail to load:

- `ui` and `runtime` package documents
- `boot_patch.line_match`, `append_args`, and top-level `candidate_paths`
- `return_delivery` `endpoint`, `endpoint_url`, and `endpoint_id` spellings, and
  the top-level `return_delivery.endpoints` list
- string and single-key shorthand `config_fields` entries
- the `obfuscated` field flag
- `catalog`, field `validation`, and form `review_before_submit`,
  `review_required`, and `run_on_start`
- the `mailroom_*` and `metalstrapper.mailroom.*` template variables, and the
  `answer_template` and `firstboot_template` package file roles
