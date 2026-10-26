#!/usr/bin/env bash
set -euo pipefail

repo_root=$(git rev-parse --show-toplevel)
unit="$repo_root/android-compose/native/iroh-mdns-binding"
output="${1:-$unit/build/upstream}"
[[ ! -e "$output" ]] || { echo "Refusing existing output: $output" >&2; exit 1; }
mkdir -p "$(dirname "$output")"

python3 "$repo_root/scripts/iroh-mdns-binding/verify_pin.py" --patch-only

git clone --depth 1 --branch v1.1.0 https://github.com/n0-computer/iroh-ffi.git "$output"
actual=$(git -C "$output" rev-parse HEAD)
[[ "$actual" == 5e451092dba0c1a09ee83ff6e5be37b1152a5c58 ]] || {
  echo "Upstream tag moved: $actual" >&2; exit 1;
}

git -C "$output" apply --check "$unit/patch/iroh-ffi-v1.1.0-mdns.patch"
git -C "$output" apply "$unit/patch/iroh-ffi-v1.1.0-mdns.patch"
install -D -m644 "$unit/tests/native_mdns.rs" "$output/tests/native_mdns.rs"
git -C "$output" diff --check
cargo metadata --manifest-path "$output/Cargo.toml" --locked --format-version 1 > "$output/metadata.json"
python3 "$repo_root/scripts/iroh-mdns-binding/verify_pin.py" "$output"
printf 'Prepared pinned source at %s\n' "$output"
