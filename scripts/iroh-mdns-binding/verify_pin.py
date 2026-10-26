#!/usr/bin/env python3
"""Reject source, dependency, or patch drift before any native build."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
UNIT = ROOT / "android-compose/native/iroh-mdns-binding"
MANIFEST = json.loads((UNIT / "source-pin/manifest.json").read_text())
PATCH = UNIT / "patch/iroh-ffi-v1.1.0-mdns.patch"
NATIVE_TEST = UNIT / "tests/native_mdns.rs"


def fail(message: str) -> None:
    raise SystemExit(message)


def verify_patch() -> None:
    actual = hashlib.sha256(PATCH.read_bytes()).hexdigest()
    if actual != MANIFEST["patch_sha256"]:
        fail(f"Patch checksum mismatch: {actual}")
    test_sha = hashlib.sha256(NATIVE_TEST.read_bytes()).hexdigest()
    if test_sha != MANIFEST["native_test_sha256"]:
        fail(f"Native test checksum mismatch: {test_sha}")


def verify_source(source: Path) -> None:
    metadata = json.loads((source / "metadata.json").read_text())
    packages = {(item["name"], item["version"]): item for item in metadata["packages"]}
    required = [
        ("iroh-ffi", MANIFEST["upstream"]["version"]),
        ("iroh", MANIFEST["rust_iroh"]),
        ("iroh-mdns-peer-lookup", MANIFEST["peer_lookup"]["version"]),
        ("hick", MANIFEST["hick"]["version"]),
    ]
    for key in required:
        if key not in packages:
            fail(f"Pinned dependency missing: {key}")
    peer = packages[("iroh-mdns-peer-lookup", MANIFEST["peer_lookup"]["version"])]
    if MANIFEST["peer_lookup"]["commit"] not in peer.get("source", ""):
        fail("Peer lookup source SHA differs from approved pin")
    if peer.get("license") != MANIFEST["peer_lookup"]["license"]:
        fail(f"Peer lookup license changed: {peer.get('license')}")
    if packages[("hick", MANIFEST["hick"]["version"])].get("license") != MANIFEST["hick"]["license"]:
        fail("hick license changed")
    if packages[("iroh-ffi", MANIFEST["upstream"]["version"])].get("license") != MANIFEST["upstream"]["license"]:
        fail("iroh-ffi license changed")
    lock = (source / "Cargo.lock").read_text()
    if "checksum = " not in lock:
        fail("Cargo.lock is missing registry checksums")
    print("Pinned source, Rust Iroh, peer lookup, hick, and licenses verified")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", nargs="?", type=Path)
    parser.add_argument("--patch-only", action="store_true")
    args = parser.parse_args()
    verify_patch()
    if not args.patch_only:
        if args.source is None:
            parser.error("source directory required")
        verify_source(args.source)


if __name__ == "__main__":
    main()
