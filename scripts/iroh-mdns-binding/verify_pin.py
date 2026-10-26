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
PEER_PATCH = UNIT / "patch/iroh-mdns-peer-lookup-rust-1.91.patch"


def fail(message: str) -> None:
    raise SystemExit(message)


def verify_patch() -> None:
    actual = hashlib.sha256(PATCH.read_bytes()).hexdigest()
    if actual != MANIFEST["patch_sha256"]:
        fail(f"Patch checksum mismatch: {actual}")
    test_sha = hashlib.sha256(NATIVE_TEST.read_bytes()).hexdigest()
    if test_sha != MANIFEST["native_test_sha256"]:
        fail(f"Native test checksum mismatch: {test_sha}")
    peer_patch_sha = hashlib.sha256(PEER_PATCH.read_bytes()).hexdigest()
    if peer_patch_sha != MANIFEST["peer_lookup"]["compat_patch_sha256"]:
        fail(f"Peer compatibility patch checksum mismatch: {peer_patch_sha}")


def required_packages() -> list[tuple[str, str]]:
    return [
        ("iroh-ffi", MANIFEST["upstream"]["version"]),
        ("iroh", MANIFEST["rust_iroh"]),
        ("iroh-mdns-peer-lookup", MANIFEST["peer_lookup"]["version"]),
        ("hick", MANIFEST["hick"]["version"]),
    ]


def verify_dependencies(packages: dict) -> None:
    for key in required_packages():
        if key not in packages:
            fail(f"Pinned dependency missing: {key}")
    peer = packages[("iroh-mdns-peer-lookup", MANIFEST["peer_lookup"]["version"])]
    if peer.get("source") is not None:
        fail("Peer lookup must resolve from the verified local copy")


def verify_peer_copy(source: Path, packages: dict) -> None:
    peer = packages[("iroh-mdns-peer-lookup", MANIFEST["peer_lookup"]["version"])]
    vendor = source / "vendor/iroh-mdns-peer-lookup"
    if Path(peer["manifest_path"]).resolve() != (vendor / "Cargo.toml").resolve():
        fail("Peer lookup resolved outside the verified local copy")
    for relative, expected in MANIFEST["peer_lookup"]["vendored_files_sha256"].items():
        actual = hashlib.sha256((vendor / relative).read_bytes()).hexdigest()
        if actual != expected:
            fail(f"Vendored peer lookup changed: {relative}: {actual}")


def verify_licenses(packages: dict) -> None:
    for name, pin in (
        ("iroh-mdns-peer-lookup", "peer_lookup"),
        ("hick", "hick"),
        ("iroh-ffi", "upstream"),
    ):
        package = packages[(name, MANIFEST[pin]["version"])]
        if package.get("license") != MANIFEST[pin]["license"]:
            fail(f"{name} license changed: {package.get('license')}")


def verify_source(source: Path) -> None:
    metadata = json.loads((source / "metadata.json").read_text())
    packages = {(item["name"], item["version"]): item for item in metadata["packages"]}
    verify_dependencies(packages)
    verify_peer_copy(source, packages)
    verify_licenses(packages)
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
