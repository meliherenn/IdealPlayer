#!/usr/bin/env python3
"""Freeze distributable Android sources and verified native inputs without committing Git.

Produces local review material, never publishes or approves licensing/release readiness.
Only an explicit source allowlist is copied. Signing keys, local configuration, device
evidence, build outputs and Git history are intentionally outside the source package.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile

ROOT_FILES = (
    "LICENSE", "gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts",
    "gradle.properties", "gradle.lockfile", "settings-gradle.lockfile", "version.properties",
    "app/build.gradle.kts", "app/proguard-rules.pro", "app/gradle.lockfile",
    "benchmark/build.gradle.kts", "benchmark/gradle.lockfile",
    "docs/VLC_T8211_RELEASE_2026-10-27.md", "docs/SOURCE_DISTRIBUTION.md",
    "docs/RELEASE_CANDIDATE_BUILD_2026-10-27.md", "docs/VLC_TLS_RELEASE_2026-10-28.md",
    "docs/NATIVE_LICENSE_REVIEW_2026-10-28.md",
)
SOURCE_DIRS = ("app/src", "app/schemas", "benchmark/src", "gradle", "third_party")
DENIED_PARTS = {"build", ".git", ".gradle", ".kotlin", "__pycache__", "keystores"}
DENIED_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".hprof", ".log"}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def permitted(relative):
    return (not any(p in DENIED_PARTS for p in relative.parts)
            and relative.suffix.lower() not in DENIED_SUFFIXES
            and relative.name not in {"local.properties", ".env"}
            and not relative.name.startswith(".env."))


def source_files(root):
    files = {root / name for name in ROOT_FILES if (root / name).is_file()}
    for name in SOURCE_DIRS:
        files.update(p for p in (root / name).rglob("*") if p.is_file() or p.is_symlink())
    # Native preparation and its pinned regression source are build inputs, not test results.
    files.update(root / name for name in ("scripts/prepare_native_sources.py", "scripts/native_hls_regression.c"))
    files.update((root / "scripts").glob("native_*_regression.c"))
    selected = []
    for path in sorted(files):
        relative = path.relative_to(root)
        if not permitted(relative):
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError(f"Unsafe source link: {relative}")
        if not path.is_file():
            raise ValueError(f"Missing source: {relative}")
        # Never export a populated signing secret through a Gradle property file.
        if path.suffix == ".properties":
            for line in path.read_text().splitlines():
                if not line.lstrip().startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    if value.strip() and any(word in key.upper() for word in ("PASSWORD", "SECRET", "TOKEN", "API_KEY", "RELEASE_KEY", "RELEASE_STORE")):
                        raise ValueError(f"Private configuration in source file: {relative}")
        selected.append(path)
    return selected


def copy_record(source, target, base):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    sha = digest(source)
    if digest(target) != sha:
        raise ValueError(f"Copy changed: {target.relative_to(base)}")
    return {"path": target.relative_to(base).as_posix(), "bytes": target.stat().st_size, "sha256": sha}


def prepare(root, native, out):
    root, native, out = root.resolve(), native.resolve(), out.resolve()
    if out.exists():
        raise ValueError("Output must be a new directory")
    files = source_files(root)
    manifest = json.loads((native / "manifest.json").read_text())
    native_files = []
    for entry in manifest["files"]:
        relative = Path(entry["path"])
        source = native / relative
        if relative.is_absolute() or ".." in relative.parts or source.is_symlink() or not source.resolve().is_relative_to(native):
            raise ValueError("Unsafe native input path")
        if not source.is_file() or digest(source) != entry["sha256"]:
            raise ValueError(f"Native input changed: {relative}")
        native_files.append((source, relative))
    out.mkdir(parents=True)
    records = [copy_record(p, out / "application" / p.relative_to(root), out) for p in files]
    records += [copy_record(p, out / "native-inputs" / rel, out) for p, rel in native_files]
    records.append(copy_record(native / "manifest.json", out / "native-inputs/upstream-staging-manifest.json", out))
    report = {
        "status": "PREPARED_NOT_PUBLISHED_OR_RELEASE_APPROVED",
        "scope": "Android application source snapshot and hash-verified native input staging",
        "application_files": len(files), "native_files": len(native_files),
        "exclusions": ["local.properties", "signing keys/passwords", "Git history", "device/account data", "generated build outputs"],
        "limitations": ["Not a legal clearance or a complete linked-source audit", "No remote CI or publication performed", "Private build configuration must be supplied locally; install with your own key"],
        "files": records,
    }
    (out / "manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def archive(source, destination):
    if destination.exists():
        raise ValueError("Archive already exists")
    # Inputs already include compressed archives/AARs. An uncompressed tar avoids a costly
    # recompression and keeps each verified source archive byte-for-byte intact.
    with tarfile.open(destination, "x") as tar:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                info = tar.gettarinfo(str(path), arcname=path.relative_to(source).as_posix())
                info.uid = info.gid = info.mtime = 0
                info.uname = info.gname = ""
                with path.open("rb") as stream:
                    tar.addfile(info, stream)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--native-staging", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    report = prepare(args.root, args.native_staging, args.out)
    print(f"Prepared {report['application_files']} application files and {report['native_files']} native inputs; no publication")


if __name__ == "__main__":
    main()
