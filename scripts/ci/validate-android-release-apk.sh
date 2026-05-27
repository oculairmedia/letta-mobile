#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [apk-path]" >&2
  exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
android_dir="$repo_root/android-compose"
app_build_file="$android_dir/app/build.gradle.kts"
apk_path="${1:-$android_dir/app/build/outputs/apk/play/release/app-play-release.apk}"

fail() {
  echo "error: $*" >&2
  exit 1
}

[[ -f "$app_build_file" ]] || fail "missing app build file: $app_build_file"
[[ -f "$apk_path" ]] || fail "missing APK: $apk_path"

python3 - "$app_build_file" <<'PY'
import re
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
match = re.search(r"release\s*\{(?P<body>.*?)^\s*}\n\s*// `benchmark`", text, re.S | re.M)
if not match:
    raise SystemExit("error: could not locate release buildType block")

release = match.group("body")
checks = {
    "isMinifyEnabled = true": r"isMinifyEnabled\s*=\s*true",
    "isShrinkResources = true": r"isShrinkResources\s*=\s*true",
    "proguard-android-optimize.txt": r'getDefaultProguardFile\("proguard-android-optimize\.txt"\)',
}
for label, pattern in checks.items():
    if not re.search(pattern, release):
        raise SystemExit(f"error: release buildType must keep {label}")

if re.search(r'getDefaultProguardFile\("proguard-android\.txt"\)', release):
    raise SystemExit("error: release buildType must not use deprecated proguard-android.txt")
PY

size_bytes=$(wc -c < "$apk_path" | tr -d '[:space:]')
size_mib=$(python3 - "$size_bytes" <<'PY'
import sys
print(f"{int(sys.argv[1]) / 1024 / 1024:.2f}")
PY
)

echo "Android release APK validation passed"
echo "APK: $apk_path"
echo "APK size: ${size_mib} MiB (${size_bytes} bytes)"
echo "R8: proguard-android-optimize.txt, minify enabled, resource shrinking enabled"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "## Android release APK validation"
    echo
    echo "| Check | Result |"
    echo "| --- | --- |"
    echo "| APK | \`$apk_path\` |"
    echo "| Size | ${size_mib} MiB (${size_bytes} bytes) |"
    echo "| R8 default | \`proguard-android-optimize.txt\` |"
    echo "| Minify | enabled |"
    echo "| Resource shrinking | enabled |"
  } >> "$GITHUB_STEP_SUMMARY"
fi
