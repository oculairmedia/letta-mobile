#!/usr/bin/env python3
"""Require matching generated Kotlin and Cargo graph on every native target."""
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
proofs = {}
for mode in ("linux", "windows", "android"):
    proof = root / mode / "proof.json"
    proofs[mode] = json.loads(proof.read_text())
    if proofs[mode]["mode"] != mode:
        raise SystemExit(f"Wrong proof mode: {proof}")
for field in ("generated_kotlin_sha256", "cargo_lock_sha256"):
    values = {mode: proof[field] for mode, proof in proofs.items()}
    if len(set(values.values())) != 1:
        raise SystemExit(f"Native artifact parity failed for {field}: {values}")
if set(proofs["android"]["natives"]) != {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}:
    raise SystemExit("Android ABI matrix incomplete")
if set(proofs["linux"]["natives"]) != {"linux-x86-64"}:
    raise SystemExit("Linux ABI proof missing")
if set(proofs["windows"]["natives"]) != {"win32-x86-64"}:
    raise SystemExit("Windows ABI proof missing")
print("Generated Kotlin and Cargo lock match Linux, Windows, and all Android ABIs")
