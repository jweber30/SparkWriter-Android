#!/usr/bin/env python3

import argparse
import json
import sys
from pathlib import Path


def dump_scalar(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    return json.dumps(value, ensure_ascii=False)


def dump_yaml_node(node, indent, *, indent_width):
    spacer = " " * indent
    if isinstance(node, dict):
        if not node:
            return [f"{spacer}{{}}"]
        lines = []
        for key, value in node.items():
            key_text = json.dumps(str(key), ensure_ascii=False)
            if isinstance(value, (dict, list)):
                if isinstance(value, dict) and not value:
                    lines.append(f"{spacer}{key_text}: {{}}")
                elif isinstance(value, list) and not value:
                    lines.append(f"{spacer}{key_text}: []")
                else:
                    lines.append(f"{spacer}{key_text}:")
                    lines.extend(dump_yaml_node(value, indent + indent_width, indent_width=indent_width))
            else:
                lines.append(f"{spacer}{key_text}: {dump_scalar(value)}")
        return lines

    if isinstance(node, list):
        if not node:
            return [f"{spacer}[]"]
        lines = []
        for item in node:
            if isinstance(item, (dict, list)):
                if isinstance(item, dict) and not item:
                    lines.append(f"{spacer}- {{}}")
                elif isinstance(item, list) and not item:
                    lines.append(f"{spacer}- []")
                else:
                    lines.append(f"{spacer}-")
                    lines.extend(dump_yaml_node(item, indent + 2, indent_width=indent_width))
            else:
                lines.append(f"{spacer}- {dump_scalar(item)}")
        return lines

    return [f"{spacer}{dump_scalar(node)}"]


def json_to_yaml(json_text, indent_width):
    data = json.loads(json_text)
    lines = dump_yaml_node(data, 0, indent_width=indent_width)
    return "\n".join(lines) + "\n"


def parse_args():
    parser = argparse.ArgumentParser(description="Convert JSON input to YAML output.")
    parser.add_argument("input", nargs="?", default="-", help="JSON file path, or '-' for stdin")
    parser.add_argument("--output", "-o", help="Write YAML to this file. Defaults to stdout.")
    parser.add_argument(
        "--in-place",
        action="store_true",
        help="Write output next to input path using .yaml extension",
    )
    parser.add_argument(
        "--indent",
        type=int,
        default=2,
        help="Indent width for nested YAML blocks (default: 2)",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    if args.input == "-":
        source = sys.stdin.read()
    else:
        source = Path(args.input).read_text(encoding="utf-8")

    yaml_text = json_to_yaml(source, indent_width=args.indent)

    if args.in_place and args.input == "-":
        raise SystemExit("--in-place requires a file path input")

    output_path = args.output
    if args.in_place:
        input_path = Path(args.input)
        output_path = str(input_path.with_suffix(".yaml") if input_path.suffix else Path(f"{input_path}.yaml"))

    if output_path:
        Path(output_path).write_text(yaml_text, encoding="utf-8")
    else:
        sys.stdout.write(yaml_text)


if __name__ == "__main__":
    main()
