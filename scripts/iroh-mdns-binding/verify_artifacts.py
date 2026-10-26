#!/usr/bin/env python3
"""Inspect self-consistent Kotlin/JNA and Android/JNI output; no publication."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


UNIT = Path(__file__).resolve().parents[2] / "android-compose/native/iroh-mdns-binding"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def confined(path: Path, root: Path) -> Path:
    resolved = path.resolve()
    if not resolved.is_relative_to(root.resolve()):
        raise SystemExit(f"Path escapes {root}: {path}")
    return resolved


def locations(source_arg: str, output_arg: str, unit: Path | None = None) -> tuple[Path, Path]:
    """Accept only the two fixed build directories, before any build or write."""
    unit = unit or UNIT
    expected = (unit.resolve() / "build/upstream", unit.resolve() / "build/artifacts")
    paths = []
    for raw, allowed in zip((source_arg, output_arg), expected, strict=True):
        argument = Path(raw)
        if argument.is_absolute() or ".." in argument.parts:
            raise SystemExit(f"Absolute or traversal path rejected: {raw}")
        actual = (Path.cwd() / argument).resolve()
        if actual != allowed:
            raise SystemExit(f"Expected path within pinned build root: {allowed}; got {raw}")
        # Construct destinations from our fixed roots, not the validated CLI spelling.
        paths.append(allowed)
    return paths[0], paths[1]


def single(folder: Path, pattern: str, source: Path) -> Path:
    confined(folder, source)
    matches = list(folder.glob(pattern))
    if len(matches) != 1:
        raise SystemExit(f"Expected one {pattern} in {folder}: {matches}")
    return confined(matches[0], source)


def read_source(source: Path, relative: str) -> bytes:
    return confined(source / relative, source).read_bytes()


def write_output(output: Path, name: str, data: bytes) -> None:
    if Path(name).name != name:
        raise SystemExit(f"Invalid artifact name: {name}")
    destination = confined(output / name, output)
    with destination.open("wb") as artifact_file:
        artifact_file.write(data)


def source_summary(source: Path, mode: str) -> dict:
    binding = "kotlin/lib/src/main/kotlin/computer/iroh/iroh_ffi.kt"
    if not confined(source / binding, source).is_file():
        raise SystemExit("Generated Kotlin binding missing")
    return {
        "mode": mode,
        "generated_kotlin_sha256": digest(read_source(source, binding)),
        "cargo_lock_sha256": digest(read_source(source, "Cargo.lock")),
        "natives": {},
    }


def verify_licenses(packaged: ZipFile, prefix: str) -> None:
    for name in ("LICENSE-APACHE", "LICENSE-MIT", "THIRD-PARTY-NOTICES.txt"):
        packaged.read(f"{prefix}/{name}")


def verify_desktop(source: Path, mode: str, output: Path, summary: dict) -> None:
    jar = single(source / "kotlin/lib/build/libs", "*.jar", source)
    native_name = "libiroh_ffi.so" if mode == "linux" else "iroh_ffi.dll"
    platform = "linux-x86-64" if mode == "linux" else "win32-x86-64"
    original = read_source(source, f"target/release/{native_name}")
    with ZipFile(jar) as packaged:
        embedded = packaged.read(f"{platform}/{native_name}")
        verify_licenses(packaged, "META-INF")
    if original != embedded:
        raise SystemExit("JAR native differs from built native")
    summary["natives"][platform] = digest(original)
    artifact = jar.read_bytes()
    summary["artifact_sha256"] = digest(artifact)
    write_output(output, "iroh.jar", artifact)


def verify_android(source: Path, output: Path, summary: dict) -> None:
    aar = single(source / "kotlin/android/build/outputs/aar", "*release.aar", source)
    with ZipFile(aar) as packaged:
        verify_licenses(packaged, "assets/licenses")
        for abi in ("armeabi-v7a", "arm64-v8a", "x86", "x86_64"):
            original = read_source(source, f"android-jniLibs/{abi}/libiroh_ffi.so")
            embedded = packaged.read(f"jni/{abi}/libiroh_ffi.so")
            if original != embedded:
                raise SystemExit(f"AAR native differs from built native: {abi}")
            summary["natives"][abi] = digest(original)
    artifact = aar.read_bytes()
    summary["artifact_sha256"] = digest(artifact)
    write_output(output, "iroh.aar", artifact)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source")
    parser.add_argument("mode", choices=("linux", "windows", "android"))
    parser.add_argument("output")
    parser.add_argument("--check-paths", action="store_true")
    args = parser.parse_args()
    source, output = locations(args.source, args.output)
    if args.check_paths:
        return
    output.mkdir(parents=True, exist_ok=True)
    confined(output, UNIT.resolve() / "build")
    summary = source_summary(source, args.mode)
    if args.mode in ("linux", "windows"):
        verify_desktop(source, args.mode, output, summary)
    else:
        verify_android(source, output, summary)
    write_output(output, "proof.json", (json.dumps(summary, indent=2, sort_keys=True) + "\n").encode())
    print(json.dumps(summary, sort_keys=True))


if __name__ == "__main__":
    main()
