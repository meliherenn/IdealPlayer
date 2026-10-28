#!/usr/bin/env python3
"""Prepare hash-pinned native source trees; does not replace or approve shipped binaries."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile


ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party/native/sources.lock.json"


def checked_file(path, expected):
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != expected:
        raise ValueError(f"SHA-256 mismatch: {path.name}")
    return path


def inside(root, name):
    relative = Path(name)
    path = (root / relative).resolve()
    if relative.is_absolute() or not path.is_relative_to(root.resolve()) or path == root.resolve():
        raise ValueError("Source path escapes its directory")
    return path


def download(source, cache):
    archive = inside(cache, source["file"])
    if not archive.exists():
        if not source["url"].startswith("https://"):
            raise ValueError("Source downloads require HTTPS")
        archive.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=cache) as temporary:
            candidate = Path(temporary) / "download"
            subprocess.run([
                "curl", "--fail", "--location", "--silent", "--show-error", "--retry", "2",
                "--proto", "=https", "--proto-redir", "=https", source["url"], "--output", str(candidate),
            ], check=True)
            checked_file(candidate, source["sha256"])
            candidate.replace(archive)
    return checked_file(archive, source["sha256"])


def extract_source(archive, destination):
    """Extract one upstream root, preserving notices and rejecting unsafe tar entries."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() or destination.is_symlink():
        # Git archive includes empty submodule placeholders; never replace populated sources.
        if destination.is_symlink() or not destination.is_dir() or any(destination.iterdir()):
            raise ValueError("Source destination already contains data")
        destination.rmdir()
    with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
        staging = Path(temporary)
        with tarfile.open(archive) as bundle:
            members = bundle.getmembers()
            if len(members) > 200_000 or sum(member.size for member in members) > 4 * 1024**3:
                raise ValueError("Source archive exceeds extraction budget")
            bundle.extractall(staging, members=members, filter="data")
        children = list(staging.iterdir())
        if len(children) != 1 or children[0].is_symlink() or not children[0].is_dir():
            raise ValueError("Expected exactly one upstream source root")
        children[0].rename(destination)


def notice_inventory(out):
    """Record upstream notice files without claiming a complete linked-license review."""
    notices = []
    for path in sorted(out.rglob("*")):
        if path.is_symlink() or not path.is_file():
            continue
        name = path.name.upper()
        if any(name == prefix or name.startswith(prefix + ".") or name.startswith(prefix + "-")
               for prefix in ("LICENSE", "LICENCE", "COPYING", "COPYRIGHT", "NOTICE", "AUTHORS")):
            with path.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            notices.append({"path": path.relative_to(out).as_posix(), "sha256": digest})
    return notices


def apply_patch(patch, directory, log):
    subprocess.run([
        "patch", "-p1", "--batch", "--forward", "--fuzz=0", "-i", str(patch.resolve()),
    ], cwd=directory, stdout=log, stderr=subprocess.STDOUT, check=True)


def prepare(component, out, cache):
    lock_bytes = LOCK.read_bytes()
    lock = json.loads(lock_bytes)
    if lock["schema_version"] != 1:
        raise ValueError("Unsupported source lock")
    spec = lock[component]
    out.mkdir(parents=True, exist_ok=False)
    (out / "sources.lock.json").write_bytes(lock_bytes)
    cache.mkdir(parents=True, exist_ok=True)
    sources = []
    for source in spec["sources"]:
        archive = download(source, cache)
        if source["destination"] is not None:
            extract_source(archive, inside(out, source["destination"]))
        sources.append({**source, "cached_archive": str(archive)})
    if component == "vlc_candidate":
        vlc = out / "libvlcjni/vlc"
        with (out / "patches.log").open("w") as log:
            for patch in sorted((out / "libvlcjni/libvlc/patches").glob("*.patch")):
                apply_patch(patch, vlc, log)
            for patch in [spec["recipe_patch"], *spec.get("build_patches", [])]:
                directory = inside(out, patch.get("target", "libvlcjni/vlc"))
                apply_patch(checked_file(inside(LOCK.parent, patch["file"]), patch["sha256"]), directory, log)
        for patch in spec["security_patches"]:
            source = checked_file(inside(LOCK.parent, patch["file"]), patch["sha256"])
            shutil.copy2(source, inside(vlc / "contrib/src/ffmpeg", patch["recipe_name"]))
        tarballs = vlc / "contrib/tarballs"
        tarballs.mkdir(exist_ok=True)
        ffmpeg = next(source for source in sources if source["name"] == "ffmpeg")
        shutil.copy2(ffmpeg["cached_archive"], tarballs / ffmpeg["file"])
    report = {
        "status": "SOURCES_PREPARED_NOT_BINARY_VERIFIED", "component": component,
        "lock_sha256": hashlib.sha256(lock_bytes).hexdigest(), "sources": sources,
        "notice_inventory": notice_inventory(out),
        "limitations": ["No native compilation or binary replacement performed by this command",
                        "Notice inventory is filename-based, not a complete linked-license review",
                        "Archive trees lack Git metadata; restore exact revisions before native builds",
                        "VLC contrib sources and full distribution review still required"],
    }
    (out / "prepared-sources.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("component", choices=("jellyfin", "vlc_candidate"))
    parser.add_argument("--out", required=True, type=Path, help="New, non-existing output directory")
    parser.add_argument("--cache", type=Path, default=ROOT / "build/native-source-cache")
    args = parser.parse_args()
    report = prepare(args.component, args.out.resolve(), args.cache.resolve())
    print(f"{report['status']}: {args.out}")


if __name__ == "__main__":
    main()
