#!/usr/bin/env bash
set -euo pipefail
source_dir=$(realpath "${1:?prepared upstream path required}")
cd "$source_dir/kotlin"
./gradlew --no-daemon --console=plain :android-smoke:connectedDebugAndroidTest
