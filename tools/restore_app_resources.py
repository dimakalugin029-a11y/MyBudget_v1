#!/usr/bin/env python3
"""Copy app-owned resources from a JADX dump into an Android Gradle module.

JADX merges library R fields into C1788R and marks them with
"JADX INFO: Added by JADX". Original app fields come first in each inner class.
"""
from __future__ import annotations

import re
import shutil
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
R_JAVA = ROOT / "decompiled" / "sources" / "ru" / "mybudget" / "app" / "C1788R.java"
RES_SRC = ROOT / "decompiled" / "resources" / "res"
RES_DST = ROOT / "app" / "src" / "main" / "res"

CLASS_RE = re.compile(r"public static final class (\w+) \{")
FIELD_RE = re.compile(r"public static(?: final)? int (\w+) = ")
JADX_MARK = "JADX INFO: Added by JADX"

FILE_TYPES = {"layout", "drawable", "mipmap", "xml", "color", "anim", "animator", "menu", "font", "raw", "interpolator"}
VALUE_TYPES = {"string", "color", "dimen", "style", "plurals", "integer", "bool", "array"}
SKIP_VALUE_FILES = {"public.xml", "attrs.xml", "ids.xml"}


def parse_app_resources(r_java: Path) -> dict[str, set[str]]:
    text = r_java.read_text(encoding="utf-8")
    lines = text.splitlines()
    resources: dict[str, set[str]] = defaultdict(set)
    current: str | None = None
    collecting = False
    class_jadx_only = False

    for line in lines:
        class_match = CLASS_RE.search(line)
        if class_match:
            current = class_match.group(1)
            collecting = True
            class_jadx_only = False
            continue
        if current is None or not collecting:
            continue
        if JADX_MARK in line:
            if not resources[current]:
                class_jadx_only = True
            collecting = False
            continue
        field = FIELD_RE.search(line)
        if field and not class_jadx_only:
            resources[current].add(field.group(1))
    return {k: v for k, v in resources.items() if v}


def style_xml_name(java_name: str) -> str:
    return java_name.replace("_", ".")


def copy_file_resources(names_by_type: dict[str, set[str]]) -> int:
    copied = 0
    if RES_DST.exists():
        shutil.rmtree(RES_DST)
    RES_DST.mkdir(parents=True)

    wanted: dict[str, set[str]] = {}
    for res_type in FILE_TYPES:
        wanted[res_type] = set(names_by_type.get(res_type, set()))

    for src in RES_SRC.rglob("*"):
        if not src.is_file():
            continue
        rel = src.relative_to(RES_SRC)
        folder = rel.parts[0]
        kind = folder.split("-", 1)[0]
        if kind not in wanted:
            continue
        stem = src.stem
        if stem not in wanted[kind]:
            continue
        dest = RES_DST / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest)
        copied += 1
    return copied


def iter_values_xml() -> list[Path]:
    files: list[Path] = []
    for folder in RES_SRC.iterdir():
        if not folder.is_dir() or not folder.name.startswith("values"):
            continue
        for xml in folder.glob("*.xml"):
            if xml.name in SKIP_VALUE_FILES:
                continue
            files.append(xml)
    return files


def keep_resource(tag: str, name: str, names_by_type: dict[str, set[str]]) -> bool:
    if name is None:
        return False
    if tag == "style":
        java = name.replace(".", "_")
        return java in names_by_type.get("style", set()) or name in names_by_type.get("style", set())
    mapping = {
        "string": "string",
        "color": "color",
        "dimen": "dimen",
        "plurals": "plurals",
        "integer": "integer",
        "bool": "bool",
        "array": "array",
        "string-array": "array",
        "integer-array": "array",
        "typed-array": "array",
    }
    res_type = mapping.get(tag)
    if not res_type:
        return False
    return name in names_by_type.get(res_type, set())


def write_filtered_values(src: Path, dest: Path, names_by_type: dict[str, set[str]], skip_colors: set[str]) -> int:
    try:
        tree = ET.parse(src)
    except ET.ParseError as exc:
        print(f"skip unparsable {src}: {exc}")
        return 0
    root = tree.getroot()
    kept = []
    for child in list(root):
        tag = child.tag.split("}", 1)[-1]
        name = child.get("name")
        if tag == "color" and name in skip_colors:
            continue
        if keep_resource(tag, name or "", names_by_type):
            kept.append(child)
    if not kept:
        return 0
    new_root = ET.Element("resources")
    for child in kept:
        new_root.append(child)
    dest.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(new_root, space="    ")
    xml = ET.tostring(new_root, encoding="unicode")
    dest.write_text('<?xml version="1.0" encoding="utf-8"?>\n' + xml + "\n", encoding="utf-8")
    return len(kept)


def main() -> int:
    if not R_JAVA.exists() or not RES_SRC.exists():
        print("decompiled sources/resources not found", file=sys.stderr)
        return 1
    names = parse_app_resources(R_JAVA)
    print("app resource types:")
    for key in sorted(names):
        print(f"  {key}: {len(names[key])}")

    copied_files = copy_file_resources(names)
    skip_colors = {p.stem for p in RES_DST.glob("color*/*.xml")}
    value_items = 0
    value_files = 0
    for src in iter_values_xml():
        rel = src.relative_to(RES_SRC)
        dest = RES_DST / rel
        kept = write_filtered_values(src, dest, names, skip_colors)
        if kept:
            value_files += 1
            value_items += kept
    print(f"copied file resources: {copied_files}")
    print(f"filtered values files: {value_files} ({value_items} items)")
    print(f"output: {RES_DST}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
