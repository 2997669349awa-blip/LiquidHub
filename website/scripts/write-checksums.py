#!/usr/bin/env python3
"""Write checksums.json for APKs present in a directory.

Usage: write-checksums.py <dir>
Output: <dir>/checksums.json => {"<apk name>": {"sha256": "...", "size": 123}}
"""
import hashlib
import json
import pathlib
import sys


def sha256_of(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    entries = {}
    for apk in sorted(root.glob("liquidhub-*.apk")):
        entries[apk.name] = {
            "sha256": sha256_of(apk),
            "size": apk.stat().st_size,
        }
    (root / "checksums.json").write_text(json.dumps(entries, indent=2) + "\n")
    print(f"wrote {len(entries)} entries to {root / 'checksums.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
