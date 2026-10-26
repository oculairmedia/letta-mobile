#!/usr/bin/env bash
# CI-only native build from the prepared, pinned source. Never edits app deps.
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
mode="${1:?expected linux, windows, or android}"
source_dir=$(realpath "${2:?prepared upstream path required}")
artifact_dir="${3:?artifact output path required}"
mkdir -p "$artifact_dir"
artifact_dir=$(realpath "$artifact_dir")
cd "$source_dir"

# Keep upstream dual-license texts and the peer lookup attribution inside
# every review artifact, including the Android AAR.
for dest in kotlin/lib/src/main/resources/META-INF \
            kotlin/android/src/main/assets/licenses; do
  install -D -m644 LICENSE-APACHE "$dest/LICENSE-APACHE"
  install -D -m644 LICENSE-MIT "$dest/LICENSE-MIT"
  cat > "$dest/THIRD-PARTY-NOTICES.txt" <<'NOTICE'
iroh-mdns-peer-lookup 0.1.2
Source: https://codeberg.org/nfnitloop/sned.git
e1213d4a7ce16b9a4b7dda81dc835349289f2a9c
License: Apache-2.0 (declared in the pinned source Cargo.toml)
Local Rust 1.91 compatibility patch: patch/iroh-mdns-peer-lookup-rust-1.91.patch
hick 0.2.0: MIT OR Apache-2.0
NOTICE
done

cargo build --locked --release --lib
case "$mode" in
  linux) native=target/release/libiroh_ffi.so; platform=linux-x86-64 ;;
  windows) native=target/release/iroh_ffi.dll; platform=win32-x86-64 ;;
  android) native=target/release/libiroh_ffi.so; platform=linux-x86-64 ;;
  *) echo "unknown mode: $mode" >&2; exit 2 ;;
esac
cargo run --locked --release --bin uniffi-bindgen -- generate \
  --language kotlin --out-dir kotlin/lib/src/main/kotlin/ \
  --config uniffi.toml --library "$native"

case "$mode" in
  linux|windows)
    install -D -m644 "$native" "kotlin/lib/src/main/resources/$platform/$(basename "$native")"
    (cd kotlin && ./gradlew --no-daemon --console=plain :lib:test :lib:jar)
    ;;
  android)
    : "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME required}"
    command -v cargo-ndk >/dev/null
    cargo ndk -o ./android-jniLibs \
      -t armeabi-v7a -t arm64-v8a -t x86 -t x86_64 \
      build --locked --release --lib
    for abi in armeabi-v7a arm64-v8a x86 x86_64; do
      install -D -m644 "android-jniLibs/$abi/libiroh_ffi.so" \
        "kotlin/android/src/main/jniLibs/$abi/libiroh_ffi.so"
    done
    (cd kotlin && ./gradlew --no-daemon --console=plain \
      :android:assembleRelease :android-smoke:assembleDebugAndroidTest)
    ;;
esac

python3 "$script_dir/verify_artifacts.py" \
  "$source_dir" "$mode" "$artifact_dir"
