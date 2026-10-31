#!/usr/bin/env python3
"""Update gh-pages updates.json for a release channel.

Usage: update-manifest.py --pages <dir> --version <ver> --channel stable|preview
Keeps the other channel untouched; computes sizes from APKs present in <dir>.
"""
import argparse
import datetime
import json
import pathlib


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pages", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--channel", required=True, choices=["stable", "preview"])
    parser.add_argument("--notes", default="")
    args = parser.parse_args()

    pages = pathlib.Path(args.pages)
    updates_path = pages / "updates.json"
    data = {}
    if updates_path.exists():
        try:
            data = json.loads(updates_path.read_text())
        except Exception:
            data = {}

    notes = pathlib.Path(args.notes) if args.notes else None
    if notes and notes.exists():
        changelog = notes.read_text().strip()
    else:
        changelog = f"LiquidHub {args.version}"

    def apk_rank(path: pathlib.Path) -> int:
        name = path.name
        if "universal" in name:
            return 0
        if "arm64" in name:
            return 1
        return 2

    apks = sorted(
        (p for p in pages.glob("*.apk") if p.name.startswith(f"liquidhub-{args.version}-")),
        key=apk_rank,
    )
    downloads = []
    for path in apks:
        size_mb = path.stat().st_size / 1024 / 1024
        downloads.append({
            "name": path.name,
            "url": f"https://2997669349awa-blip.github.io/LiquidHub/{path.name}",
            "size": f"约 {size_mb:.0f} MB",
        })
    if not downloads:
        raise SystemExit(f"no APK found for version {args.version} in {pages}")

    def version_key(value: str):
        parts = []
        for token in value.replace("-", ".").split("."):
            parts.append(int(token) if token.isdigit() else token)
        return parts

    if args.channel == "stable":
        preview = data.get("preview")
        if preview and version_key(preview.get("version", "0")) <= version_key(args.version):
            # 正式版已经追平或超过旧预览版，预览通道应显示“暂无”
            data["preview"] = None

    data[args.channel] = {
        "version": args.version,
        "publishedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "changelog": changelog,
        "downloads": downloads,
    }
    data.setdefault("stable", None)
    data.setdefault("preview", None)
    # 清理旧版扁平字段，避免旧客户端读取到过期版本
    for legacy in ("version", "publishedAt", "changelog", "downloads"):
        data.pop(legacy, None)
    updates_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    print(updates_path.read_text())


if __name__ == "__main__":
    main()
