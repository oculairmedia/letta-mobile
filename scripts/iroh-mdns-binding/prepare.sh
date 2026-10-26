#!/usr/bin/env bash
set -euo pipefail

repo_root=$(git rev-parse --show-toplevel)
unit="$repo_root/android-compose/native/iroh-mdns-binding"
output="${1:-$unit/build/upstream}"
[[ ! -e "$output" ]] || { echo "Refusing existing output: $output" >&2; exit 1; }
mkdir -p "$(dirname "$output")"

python3 "$repo_root/scripts/iroh-mdns-binding/verify_pin.py" --patch-only

# Hash the same source bytes on Windows and Unix without changing user Git settings.
git -c core.autocrlf=false clone --depth 1 --branch v1.1.0 https://github.com/n0-computer/iroh-ffi.git "$output"
actual=$(git -C "$output" rev-parse HEAD)
[[ "$actual" == 5e451092dba0c1a09ee83ff6e5be37b1152a5c58 ]] || {
  echo "Upstream tag moved: $actual" >&2; exit 1;
}

git -c core.autocrlf=false -C "$output" apply --check "$unit/patch/iroh-ffi-v1.1.0-mdns.patch"
git -c core.autocrlf=false -C "$output" apply "$unit/patch/iroh-ffi-v1.1.0-mdns.patch"
install -D -m644 "$unit/tests/native_mdns.rs" "$output/tests/native_mdns.rs"
git -c core.autocrlf=false -C "$output" diff --check

# The pinned peer-lookup source uses unstable slice::as_array on Rust 1.91.1.
# Copy that exact revision and apply only the stable array conversion patch.
peer_checkout=$(mktemp -d)
trap 'rm -rf "$peer_checkout"' EXIT
git -c core.autocrlf=false clone --quiet https://codeberg.org/nfnitloop/sned.git "$peer_checkout"
git -c core.autocrlf=false -C "$peer_checkout" checkout --quiet --detach e1213d4a7ce16b9a4b7dda81dc835349289f2a9c
peer_actual=$(git -C "$peer_checkout" rev-parse HEAD)
[[ "$peer_actual" == e1213d4a7ce16b9a4b7dda81dc835349289f2a9c ]] || {
  echo "Peer lookup source moved: $peer_actual" >&2; exit 1;
}
git -c core.autocrlf=false -C "$peer_checkout" apply --unidiff-zero --check "$unit/patch/iroh-mdns-peer-lookup-rust-1.91.patch"
git -c core.autocrlf=false -C "$peer_checkout" apply --unidiff-zero "$unit/patch/iroh-mdns-peer-lookup-rust-1.91.patch"
git -c core.autocrlf=false -C "$peer_checkout" diff --check
mkdir -p "$output/vendor"
cp -rf "$peer_checkout/crates/iroh-mdns-peer-lookup" "$output/vendor/iroh-mdns-peer-lookup"

cargo metadata --manifest-path "$output/Cargo.toml" --locked --format-version 1 > "$output/metadata.json"
python3 "$repo_root/scripts/iroh-mdns-binding/verify_pin.py" "$output"
printf 'Prepared pinned source at %s\n' "$output"
