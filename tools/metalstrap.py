#!/usr/bin/env python3
"""MetalStrap build helper: placeholder slots in, SparkPlug image document out.

A MetalStrap recipe directory holds `metalstrap.json`, one placeholder file per slot,
and an image document template. The build runs on a Linux host:

  1. `slots`     pads each placeholder file to its slot size.
  2. (the build puts those files into the image, for example with
     proxmox-auto-install-assistant prepare-iso)
  3. `image-doc` finds each slot file in the built ISO, checks the bytes there are
     exactly the padded placeholder, and writes the image document with the ISO's
     hash, size, URL, and slot offsets.

Standard library only. Needs `xorriso` on PATH for `image-doc`.
"""

import argparse
import hashlib
import json
import mmap
import re
import subprocess
import sys
from pathlib import Path
from string import Template

ISO_BLOCK = 2048
GITHUB_ASSET_LIMIT = 2 * 1024 * 1024 * 1024


def load_recipe(recipe_dir: Path) -> dict:
    recipe = json.loads((recipe_dir / "metalstrap.json").read_text())
    pad = recipe.get("padding", "#")
    if len(pad.encode()) != 1:
        sys.exit("metalstrap.json padding must be a single byte")
    return recipe


def padded_slot(recipe_dir: Path, recipe: dict, slot: dict) -> bytes:
    body = (recipe_dir / slot["file"]).read_bytes()
    size = int(slot["size"])
    marker = f"METALSTRAPPER_PATCH_SLOT {slot['id']} v".encode()
    if marker not in body:
        sys.exit(f"{slot['file']} must contain the marker '{marker.decode()}<n>'")
    if len(body) > size:
        sys.exit(f"{slot['file']} is {len(body)} bytes, larger than its {size}-byte slot")
    return body + recipe.get("padding", "#").encode() * (size - len(body))


def cmd_slots(args: argparse.Namespace) -> None:
    recipe = load_recipe(args.recipe)
    args.out.mkdir(parents=True, exist_ok=True)
    for slot in recipe["slots"]:
        target = args.out / slot["file"]
        target.write_bytes(padded_slot(args.recipe, recipe, slot))
        print(f"{target}: {slot['size']} bytes")


def iso_extent(iso: Path, iso_path: str) -> tuple[int, int]:
    """Start block and size of a file in the ISO, which must be one contiguous extent."""
    result = subprocess.run(
        ["xorriso", "-indev", str(iso), "-find", iso_path, "-exec", "report_lba", "--"],
        check=True, capture_output=True, text=True,
    )
    pattern = re.compile(r"^File data lba:\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*)'\s*$")
    extents = [m for m in map(pattern.match, (result.stdout + result.stderr).splitlines()) if m and m.group(5) == iso_path]
    if len(extents) != 1:
        sys.exit(f"{iso_path}: expected one contiguous extent in the ISO, found {len(extents)}")
    _, lba, _, size, _ = extents[0].groups()
    return int(lba), int(size)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def count(data: mmap.mmap, needle: bytes) -> int:
    found, start = 0, 0
    while (index := data.find(needle, start)) != -1:
        found, start = found + 1, index + 1
    return found


def cmd_image_doc(args: argparse.Namespace) -> None:
    recipe = load_recipe(args.recipe)
    iso_size = args.iso.stat().st_size
    if iso_size >= GITHUB_ASSET_LIMIT:
        print(f"warning: {args.iso} is {iso_size} bytes, over GitHub's 2 GiB release asset limit", file=sys.stderr)

    values = {
        "iso_url": args.url,
        "iso_sha256": sha256_file(args.iso),
        "iso_size": str(iso_size),
        "image_version": args.version,
        "image_description": json.dumps(args.description),
    }
    with args.iso.open("rb") as handle, mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ) as data:
        for slot in recipe["slots"]:
            expected = padded_slot(args.recipe, recipe, slot)
            lba, size = iso_extent(args.iso, slot["iso_path"])
            offset = lba * ISO_BLOCK
            if size != len(expected):
                sys.exit(f"{slot['iso_path']}: {size} bytes in the ISO, slot is {len(expected)}")
            if data[offset:offset + size] != expected:
                sys.exit(f"{slot['iso_path']}: bytes at offset {offset} are not the padded placeholder")
            marker = f"METALSTRAPPER_PATCH_SLOT {slot['id']} v".encode()
            copies = count(data, marker)
            if copies != 1:
                sys.exit(f"{slot['id']}: marker appears {copies} times in the ISO; the installer could read another copy")
            values[f"{slot['id']}_offset"] = str(offset)
            values[f"{slot['id']}_length"] = str(size)
            print(f"{slot['id']}: {slot['iso_path']} at byte {offset}, {size} bytes", file=sys.stderr)

    template = (args.recipe / recipe["image_template"]).read_text()
    template = "".join(line for line in template.splitlines(keepends=True) if not line.startswith("# ")).lstrip()
    header = f"# Generated by tools/metalstrap.py from metalstrap/{args.recipe.name}. Do not edit by hand.\n"
    document = header + Template(template).substitute(values)
    args.out.write_text(document)
    print(f"wrote {args.out}", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    slots = sub.add_parser("slots", help="pad placeholder files to their slot sizes")
    slots.add_argument("recipe", type=Path, help="MetalStrap recipe directory")
    slots.add_argument("--out", type=Path, required=True, help="directory for the padded files")
    slots.set_defaults(func=cmd_slots)

    doc = sub.add_parser("image-doc", help="write the image document for a built ISO")
    doc.add_argument("recipe", type=Path, help="MetalStrap recipe directory")
    doc.add_argument("--iso", type=Path, required=True, help="the built ISO")
    doc.add_argument("--url", required=True, help="where the ISO will be downloaded from")
    doc.add_argument("--version", required=True, help="image document version, e.g. 9.2-1-r12")
    doc.add_argument("--description", default="", help="image document description")
    doc.add_argument("--out", type=Path, required=True, help="image document to write")
    doc.set_defaults(func=cmd_image_doc)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
