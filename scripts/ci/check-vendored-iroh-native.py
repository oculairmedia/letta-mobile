#!/usr/bin/env python3
"""Refuse a vendored Android libiroh_ffi.so that predates the pinned iroh version.

The Android app does not load libiroh_ffi.so from the computer.iroh:iroh-android artifact for
arm64-v8a: it packages the 16 KB-realigned copy vendored under
android-compose/app/src/rootDebug/jniLibs/ (Google Play rejects the artifact's 4 KB alignment).
Bumping `iroh` in gradle/libs.versions.toml moves the Kotlin bindings but not that copy, so the
bindings can call a native symbol the shipped library lacks: UniffiLib then fails to load and
every Iroh dial fails on the device while JVM CI (which loads the desktop library) stays green.

This check reads, without Gradle:
  * the pinned `iroh` version from android-compose/gradle/libs.versions.toml;
  * every `uniffi_iroh_ffi_*` symbol name the vendored arm64 .so exports (a byte scan of the
    file, the same strings the dynamic linker resolves by name);
  * a recorded manifest (android-compose/app/native-manifests/iroh-native.json; outside jniLibs/,
    whose every file the 16 KB realign and ELF sanity tasks treat as a .so) naming the
    iroh version the vendored .so was taken from and the symbols that version's bindings need.

It fails when the pinned version differs from the manifest's, or when any symbol the manifest
requires is missing from the vendored file. Re-vendoring writes a new manifest with
`--write-manifest` from the iroh-android AAR the build resolved.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "android-compose"
VERSIONS = COMPOSE / "gradle" / "libs.versions.toml"
VENDORED = COMPOSE / "app" / "src" / "rootDebug" / "jniLibs" / "arm64-v8a" / "libiroh_ffi.so"
MANIFEST = COMPOSE / "app" / "native-manifests" / "iroh-native.json"
SYMBOL = re.compile(rb"uniffi_iroh_ffi_[A-Za-z0-9_]+")
PIN = re.compile(r'^iroh\s*=\s*"([^"]+)"', re.MULTILINE)


def pinned_version(versions: Path) -> str:
    match = PIN.search(versions.read_text(encoding="utf-8"))
    if not match:
        raise SystemExit(f"check-vendored-iroh-native: no `iroh = \"...\"` pin in {versions}")
    return match.group(1)


def exported_symbols(blob: bytes) -> set[str]:
    return {m.decode("ascii") for m in SYMBOL.findall(blob)}


def required_symbols(manifest: dict) -> set[str]:
    return set(manifest.get("requiredSymbols", []))


def problems(pin: str, manifest: dict, vendored: set[str]) -> list[str]:
    found = []
    if manifest.get("irohVersion") != pin:
        found.append(
            f"libs.versions.toml pins iroh {pin} but the vendored arm64 libiroh_ffi.so was taken "
            f"from iroh {manifest.get('irohVersion')!r}: re-vendor it from iroh-android {pin}"
        )
    missing = sorted(required_symbols(manifest) - vendored)
    if missing:
        found.append(
            f"the vendored arm64 libiroh_ffi.so lacks {len(missing)} symbol(s) the iroh "
            f"{manifest.get('irohVersion')} bindings call, e.g. {', '.join(missing[:3])}"
        )
    return found


def write_manifest(aar: Path, pin: str) -> None:
    with zipfile.ZipFile(aar) as archive:
        symbols = exported_symbols(archive.read("jni/arm64-v8a/libiroh_ffi.so"))
    MANIFEST.write_text(
        json.dumps({"irohVersion": pin, "requiredSymbols": sorted(symbols)}, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"check-vendored-iroh-native: wrote {MANIFEST.relative_to(ROOT)} ({len(symbols)} symbols, iroh {pin})")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--write-manifest", metavar="AAR", type=Path,
                        help="record the symbols of this iroh-android AAR as the requirement")
    args = parser.parse_args(argv)
    pin = pinned_version(VERSIONS)
    if args.write_manifest:
        write_manifest(args.write_manifest, pin)
        return 0
    if not MANIFEST.is_file():
        print(f"check-vendored-iroh-native: missing {MANIFEST.relative_to(ROOT)}", file=sys.stderr)
        return 1
    found = problems(pin, json.loads(MANIFEST.read_text(encoding="utf-8")), exported_symbols(VENDORED.read_bytes()))
    for problem in found:
        print(f"check-vendored-iroh-native: {problem}", file=sys.stderr)
    if found:
        return 1
    print(f"check-vendored-iroh-native: ok (iroh {pin}, vendored arm64 .so exports every required symbol)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
