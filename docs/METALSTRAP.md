# MetalStrap and SparkPlug

**MetalStrap:** the material that has to be generated on a cloud or other
Linux host. It prepares a bootable image with reserved configuration regions
and produces the metadata needed to modify them safely.

**SparkPlug:** the material SparkWriter needs to do its job. It defines form
fields and what to do with them, and fills the image's configuration regions.

## What is implemented (SparkPlug v3.0)

A SparkPlug is two documents (see `SPARKPLUG_V3.md`):

- **Workflow** (`sparkplug.workflow`): fields, wizard, actions, templates,
  artifacts, host events, and `fill`, which maps each image slot to an artifact.
- **Image** (`sparkplug.image`): source, installer, slots (`delivery` patches
  by id and auxiliary files by path), boot patches, outputs. Slots never name
  artifacts.

The image document is the hand-off point:

- **Stock images** (Ubuntu, Debian from upstream): the image document is
  hand-written. SparkWriter applies its fixed-size boot-config text
  replacements and auxiliary partition on the device. No MetalStrap is needed.
- **Custom images** (the Proxmox generic ISO): a MetalStrap build on Linux
  makes the image and generates its image document, including slot offsets.
  See "Proxmox MetalStrap build" below.

One image document can serve many workflows. The bundled Proxmox SparkPlug uses
`metalstrapper-proxmox-generic.image.yaml`.

## Goals

1. **Android does only what it can do completely.** On the phone: verify image
   hashes, collect form input, render templates, apply bounded fixed-size text
   replacements, write bytes at known offsets, append one auxiliary `vfat`
   partition, and read back. Anything that needs a Linux environment
   (rebuilding an ISO, resizing partitions, mounting filesystems) is MetalStrap
   work.
2. **Offsets are build output.** For custom images, byte offsets, lengths, and
   placeholder hashes come from the MetalStrap build. Nobody types them by hand.
3. **Named slots are the contract.** The image publishes named slots with a
   media type, a maximum length, and a padding rule. The workflow fills slots
   by name and never sees an offset.
4. **One image, many workflows.** A workflow names exactly one image. An image
   can serve any number of workflows.
5. **Image documents are pure data.** No form fields, templates, or logic. If an
   image seems to need a form field, the split is in the wrong place.
6. **Everything the installer needs stays on the drive.** The default path
   writes configuration into local slots. Nothing generated on the phone, such
   as password hashes, keys, or answer files, has to be uploaded somewhere for
   the installer to fetch.
7. **Provenance comes from public builds.** MetalStraps and SparkPlugs are
   built and released from public repositories. A MetalStrap build attests the
   image and its image document together (GitHub artifact attestations). Each
   release points to an exact public commit and workflow.

## Proxmox MetalStrap build

- **Recipe:** `metalstrap/proxmox-generic/` holds `metalstrap.json`
  (slot ids, ISO paths, sizes), the two placeholder files, and the image
  document template `image.yaml.in`.
- **Tool:** `tools/metalstrap.py` (Python standard library plus `xorriso`).
  `slots` pads each placeholder with `#` to its slot size. `image-doc` finds
  each slot file in the built ISO, requires one contiguous extent whose bytes
  are exactly the padded placeholder, requires the slot marker to appear only
  once in the whole ISO, and fills the template with the ISO hash, size, URL,
  and offsets.
- **Placeholders:** `answer.toml` is a complete, valid answer file so
  `prepare-iso` accepts it, followed by `# METALSTRAPPER_PATCH_SLOT answer_toml v1`.
  `proxmox-first-boot` is a no-op script with its own marker.
- **Workflow:** `.github/workflows/build-proxmox-metalstrap.yml`, run by hand.
  It downloads the Proxmox ISO and checks it against Proxmox's `SHA256SUMS`
  (and an optional pinned hash). It runs
  `prepare-iso --fetch-from iso --answer-file … --on-first-boot …` and writes
  the image document. It attests both files when the repository is public,
  and creates a draft release. A second job checks the image document against
  every SparkPlug that uses it (`GeneratedImageDocumentTest`). A third job
  publishes the release.
- **Using a build:** copy the release's image document over
  `app/bundled/images/` and `examples/images/`.

Checked against the published reference ISO
(`fd438f02…`, Proxmox VE 9.2-1): the padded placeholders match its slot bytes
exactly, and `image-doc` reproduces the hand-maintained image document apart
from the added header, version, and description.

## Next steps

- **Release downloads need a public repository.** Assets of a private
  repository need GitHub authentication, so SparkWriter and phone browsers
  cannot fetch them until the repository is public. Attestations also need a
  public repository.
- **Named inputs for boot patches.** Image boot patches can already use
  workflow variables (for example a Mailroom URL in Debian kernel arguments).
  Declaring them as named inputs in the image would make that dependency
  checkable at load time.
- **Matching fields.** `installer.accepts_delivery`, `base_image_requires`, and
  `source.capabilities` / `adaptation` existed to match a manifest to an image.
  With the workflow naming its image, some of them may be removable.
- **Precomputed patches for stock images.** Optionally, a build could
  precompute stock-image text replacements against the exact image hash, so the
  phone applies byte patches instead of finding text. Not required for v3.0.

## Findings so far

- **GitHub release downloads redirect off-domain.** `github.com/…/releases/download/…`
  returns `302` to `release-assets.githubusercontent.com` with a signed,
  short-lived query string. The app refuses all redirects today. Allowing this
  one hop is a narrow change: exact source pattern, exact target host, HTTPS
  only. Integrity still comes from the SHA-256 pins.
- **GitHub release assets are flat** (no folders) and capped at 2 GiB each.
- **Debian 13.5.0 netinst works with today's on-device patching.** The
  `examples/debian-https-preseed.sparkplug.yaml` find text matches the real ISO
  exactly once. BIOS boots through isolinux; UEFI goes `efi.img` →
  `/boot/grub/x86_64-efi/grub.cfg` → `/boot/grub/grub.cfg`, so patching the
  ISO9660 `grub.cfg` covers both. The edit has to fit the file's sector
  padding: `grub.cfg` is 6115 bytes with 29 bytes free. It only fits because
  the patch also deletes lines.
- **That Debian example depends on Mailroom.** The preseed (including the user
  password hash) is uploaded and the installer fetches it by URL. It is
  disabled in release builds. A Debian image document should put the preseed in a
  local slot and boot with `file=/cdrom/<slot file>` instead.

## Open questions

- **Pinning across packages.** Inside one package the image document is pinned
  by `manifest.json`. If images are later published on their own, a workflow
  would pin one by SHA-256 (strict) or by release identity plus version.
- **Where does a per-write preseed live on a stock Debian image?** See
  "Debian preseed placement" below.
- **Images over 2 GiB** cannot be single GitHub release assets.

## Debian preseed placement

Goal: the user downloads the real ISO from Debian, and SparkWriter adds only the
host-specific data.

Text replacement can stay on the phone (v3.0 keeps fixed-size text
replacement for stock images). Alternatively, because the stock ISO is
identified by its SHA-256, a build could precompute every boot config edit
against that exact image, so the phone applies byte patches instead of
finding text. Either way the preseed itself still needs a place on the drive.

What we checked in the Debian 13.5.0 amd64 netinst installer (`/install.amd/initrd.gz`):

- **`/hd-media` does not exist there.** It belongs to Debian's separate
  hd-media installer (its own kernel and initrd, not on the netinst ISO), which
  finds an ISO *file* on a USB filesystem and mounts that filesystem at
  `/hd-media`. The netinst initrd ships `cdrom-detect`, not `iso-scan`, so
  `preseed/file=/hd-media/preseed.cfg` fails on a drive written from the
  netinst ISO.
- **The `floppy://` preseed fetch** (`mountmedia floppy`) only scans floppy
  devices, not USB partitions.
- **The generic media scan** (`mountmedia`) mounts the first device that
  mounts, starting with whole disks, so it would mount the ISO itself.
- **`file-preseed` only runs when `preseed/file` is set,** and it runs
  `preseed/early_command` after loading that file.

Candidates, none tested yet:

1. **Reuse a file extent on the stock ISO.** The MetalStrap points
   `preseed/file=/cdrom/<path>` at a file the automated text install never
   reads, writes the preseed into that file's space, and updates its directory
   length. Everything else is precomputed. Debian's `md5sum.txt` will not match
   that file; the installer only checks it when asked.
2. **hd-media layout.** Build the USB drive from unmodified Debian files: the
   hd-media kernel and initrd (pinned through Debian's signed checksums), the
   netinst ISO stored as a file, and `preseed.cfg` on a FAT filesystem. The
   MetalStrap could precompute the whole FAT layout so the phone writes fixed
   bytes, streams the ISO to a known offset, and fills the preseed slot. The
   open problem is UEFI and Secure Boot: the drive needs a signed bootloader.
3. **Auxiliary partition plus `preseed/early_command` on the kernel command
   line** to mount it. Unverified: depends on whether a command-line
   `early_command` runs before anything needs the preseed, and on kernel
   command-line length and quoting.

Next step: test candidate 1 in QEMU (BIOS and UEFI) against the stock 13.5.0
ISO.
