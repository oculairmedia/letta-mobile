#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: scripts/iroh_probe.sh <iroh-address-or-ticket> [probe args...]" >&2
  exit 2
fi

ADDRESS="$1"
shift

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR/android-compose"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

./gradlew --quiet :cli:run -PcliArgs="app-server-iroh-probe --address ${ADDRESS} $*"
