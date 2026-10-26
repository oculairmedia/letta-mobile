#!/usr/bin/env python3
"""Inspect self-consistent Kotlin/JNA and Android/JNI output; no publication."""
import hashlib
import json
from pathlib import Path
import sys
from zipfile import ZipFile


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def single(folder: Path, pattern: str) -> Path:
    matches = list(folder.glob(pattern))
    if len(matches) != 1:
        raise SystemExit(f"Expected one {pattern} in {folder}: {matches}")
    return matches[0]


def main() -> None:
    source, mode, output = map(Path, sys.argv[1:4])
    mode = str(mode)
    output.mkdir(parents=True, exist_ok=True)
    binding = source / "kotlin/lib/src/main/kotlin/computer/iroh/iroh_ffi.kt"
    if not binding.is_file():
        raise SystemExit("Generated Kotlin binding missing")
    summary = {
        "mode": mode,
        "generated_kotlin_sha256": digest(binding.read_bytes()),
        "cargo_lock_sha256": digest((source / "Cargo.lock").read_bytes()),
        "natives": {},
    }
    if mode in ("linux", "windows"):
        jar = single(source / "kotlin/lib/build/libs", "*.jar")
        native_name = "libiroh_ffi.so" if mode == "linux" else "iroh_ffi.dll"
        platform = "linux-x86-64" if mode == "linux" else "win32-x86-64"
        original = (source / "target/release" / native_name).read_bytes()
        with ZipFile(jar) as packaged:
            embedded = packaged.read(f"{platform}/{native_name}")
            for name in ("LICENSE-APACHE", "LICENSE-MIT", "THIRD-PARTY-NOTICES.txt"):
                packaged.read(f"META-INF/{name}")
        if original != embedded:
            raise SystemExit("JAR native differs from built native")
        summary["natives"][platform] = digest(original)
        summary["artifact_sha256"] = digest(jar.read_bytes())
        (output / jar.name).write_bytes(jar.read_bytes())
    elif mode == "android":
        aar = single(source / "kotlin/android/build/outputs/aar", "*release.aar")
        with ZipFile(aar) as packaged:
            for name in ("LICENSE-APACHE", "LICENSE-MIT", "THIRD-PARTY-NOTICES.txt"):
                packaged.read(f"assets/licenses/{name}")
            for abi in ("armeabi-v7a", "arm64-v8a", "x86", "x86_64"):
                original = (source / f"android-jniLibs/{abi}/libiroh_ffi.so").read_bytes()
                embedded = packaged.read(f"jni/{abi}/libiroh_ffi.so")
                if original != embedded:
                    raise SystemExit(f"AAR native differs from built native: {abi}")
                summary["natives"][abi] = digest(original)
        summary["artifact_sha256"] = digest(aar.read_bytes())
        (output / aar.name).write_bytes(aar.read_bytes())
    else:
        raise SystemExit(f"Unknown mode: {mode}")
    (output / "proof.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    print(json.dumps(summary, sort_keys=True))


if __name__ == "__main__":
    main()
