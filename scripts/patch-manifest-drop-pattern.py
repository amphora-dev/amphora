#!/usr/bin/env python3
"""Patch content_manifest.json: add fonts.tzst, drop pattern + layers.

Usage:
  python3 scripts/patch-manifest-drop-pattern.py \\
      /path/to/content_manifest.json \\
      [--fonts /path/to/fonts.tzst] \\
      [--url https://github.com/amphora-dev/imagefs/releases/download/pattern/fonts.tzst]

Writes the file in place and prints a short summary.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

DROP = frozenset({"container_pattern_common.tzst", "layers.tzst"})
DEFAULT_FONTS_URL = (
    "https://github.com/amphora-dev/imagefs/releases/download/"
    "pattern/fonts-windows-7dc95c80.tzst"
)


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument(
        "--fonts",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "build/runtime-assets/fonts.tzst",
    )
    parser.add_argument("--url", default=DEFAULT_FONTS_URL)
    args = parser.parse_args()

    if not args.manifest.is_file():
        print(f"error: manifest not found: {args.manifest}", file=sys.stderr)
        return 1
    if not args.fonts.is_file():
        print(f"error: fonts package not found: {args.fonts}", file=sys.stderr)
        print("run: ./scripts/build-fonts-tzst.sh", file=sys.stderr)
        return 1

    fonts_sha = sha256_of(args.fonts)
    fonts_size = args.fonts.stat().st_size
    fonts_entry = {
        "assetPath": "fonts.tzst",
        "sha256": fonts_sha,
        "remoteUrl": args.url,
        "size": fonts_size,
    }

    data = json.loads(args.manifest.read_text(encoding="utf-8"))
    assets = data.get("runtimeAssets")
    if not isinstance(assets, list):
        print("error: runtimeAssets[] missing", file=sys.stderr)
        return 1

    kept: list[dict] = []
    dropped: list[str] = []
    had_fonts = False
    for entry in assets:
        path = entry.get("assetPath")
        if path in DROP:
            dropped.append(path)
            continue
        if path == "fonts.tzst":
            kept.append(fonts_entry)
            had_fonts = True
            continue
        kept.append(entry)

    if not had_fonts:
        # Insert near the top (after wrapper if present) so cold-path assets stay grouped.
        insert_at = 0
        for i, entry in enumerate(kept):
            if entry.get("assetPath") == "graphics_driver/wrapper.tzst":
                insert_at = i + 1
                break
        kept.insert(insert_at, fonts_entry)

    data["runtimeAssets"] = kept
    args.manifest.write_text(
        json.dumps(data, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print(f"wrote {args.manifest}")
    print(f"  fonts.tzst sha256={fonts_sha} size={fonts_size}")
    print(f"  fonts.tzst url={args.url}")
    print(f"  dropped={dropped or ['(none already absent)']}")
    print(f"  runtimeAssets count: {len(assets)} -> {len(kept)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
