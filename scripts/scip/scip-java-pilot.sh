#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)"
ENV_FILE="$REPO_ROOT/config/scip-java/pilot.env"
LOCAL_ROOT="${LETTA_TOOL_CACHE_ROOT:-$REPO_ROOT/.local}"

# shellcheck disable=SC1090
source "$ENV_FILE"
[[ "$LOCAL_ROOT" == /* ]] || { printf 'scip-java: cache root must be absolute\n' >&2; exit 64; }
mkdir -p -- "$LOCAL_ROOT"
LOCAL_ROOT="$(CDPATH= cd -- "$LOCAL_ROOT" && pwd -P)"
SCIP_ROOT="$LOCAL_ROOT/scip-java"
BIN="$SCIP_ROOT/bin/scip-java-v$SCIP_JAVA_VERSION"
INDEX="$SCIP_ROOT/out/index.scip"
LOG="$SCIP_ROOT/out/index.log"
DOWNLOAD_URL="https://github.com/scip-code/scip-java/releases/download/v$SCIP_JAVA_VERSION/scip-java-v$SCIP_JAVA_VERSION"

usage() {
  printf 'Usage: %s {install|doctor|index|extract|clean}\n' "${0##*/}"
  printf 'Set LETTA_SCIP_JAVA_EXPERIMENTAL=1 to run index.\n'
}

safe_root() {
  [[ "$LOCAL_ROOT" == /* ]] || { printf 'scip-java: cache root must be absolute\n' >&2; exit 64; }
  case "$LOCAL_ROOT" in
    "$REPO_ROOT/.local"|"$REPO_ROOT/.local/"* ) ;;
    *) printf 'scip-java: cache root must remain under %s/.local\n' "$REPO_ROOT" >&2; exit 64 ;;
  esac
}

install_binary() {
  safe_root
  command -v curl >/dev/null 2>&1 || { printf 'scip-java: curl is required\n' >&2; exit 69; }
  mkdir -p "$SCIP_ROOT/bin"
  if [[ -e "$BIN" ]]; then
    printf '%s  %s\n' "$SCIP_JAVA_SHA256" "$BIN" | sha256sum --check --status || {
      printf 'scip-java: cached binary checksum mismatch; remove it with clean and reinstall\n' >&2
      exit 65
    }
  else
    tmp="$BIN.tmp.$$"
    trap 'rm -f -- "$tmp"' EXIT
    curl --fail --location --silent --show-error "$DOWNLOAD_URL" --output "$tmp"
    printf '%s  %s\n' "$SCIP_JAVA_SHA256" "$tmp" | sha256sum --check --status || {
      printf 'scip-java: checksum mismatch for v%s\n' "$SCIP_JAVA_VERSION" >&2
      exit 65
    }
    chmod 0755 "$tmp"
    mv -f -- "$tmp" "$BIN"
    trap - EXIT
  fi
  [[ -x "$BIN" ]] || { printf 'scip-java: cached binary is not executable\n' >&2; exit 65; }
  version_output="$($BIN --version)"
  printf 'scip-java: pinned v%s ready (%s)\n' "$SCIP_JAVA_VERSION" "$version_output"
}

doctor() {
  local failed=0
  printf 'scip-java pilot compatibility matrix:\n'
  printf '  scip-java: %s (pinned)\n' "$SCIP_JAVA_VERSION"
  printf '  Gradle:    9.4.1\n'
  printf '  Kotlin:    2.4.0\n'
  printf '  AGP:       9.2.0\n'
  printf '  JVM:       target 17 (appserver-cli target 21)\n'
  printf '  modules:   %s\n' "$SCIP_TARGET_MODULES"
  [[ -x "$REPO_ROOT/android-compose/gradlew" ]] || { printf 'scip-java: Gradle wrapper missing\n' >&2; failed=1; }
  rg -q 'gradle-9\.4\.1-' "$REPO_ROOT/android-compose/gradle/wrapper/gradle-wrapper.properties" || { printf 'scip-java: expected Gradle 9.4.1\n' >&2; failed=1; }
  rg -q 'version "2\.4\.0"' "$REPO_ROOT/android-compose/build.gradle.kts" || { printf 'scip-java: expected Kotlin 2.4.0\n' >&2; failed=1; }
  rg -q 'com\.android\.application.*version "9\.2\.0"' "$REPO_ROOT/android-compose/build.gradle.kts" || { printf 'scip-java: expected AGP 9.2.0\n' >&2; failed=1; }
  command -v java >/dev/null 2>&1 || { printf 'scip-java: Java is required\n' >&2; failed=1; }
  command -v timeout >/dev/null 2>&1 || { printf 'scip-java: GNU timeout is required\n' >&2; failed=1; }
  printf '  support:   upstream supports Gradle/Kotlin but explicitly does not support Android Gradle integration\n'
  printf '  policy:    experimental, advisory-only, never a build or CI prerequisite\n'
  return "$failed"
}

index_pilot() {
  safe_root
  if [[ "${LETTA_SCIP_JAVA_EXPERIMENTAL:-0}" != 1 ]]; then
    printf 'scip-java: pilot disabled. Android Gradle integration is unsupported upstream.\n' >&2
    printf 'scip-java: opt in with LETTA_SCIP_JAVA_EXPERIMENTAL=1; failure is expected and non-blocking.\n' >&2
    exit 78
  fi
  doctor
  [[ -x "$BIN" ]] || install_binary
  mkdir -p "$SCIP_ROOT/out" "$SCIP_ROOT/gradle-home"
  rm -f -- "$INDEX" "$LOG"
  ulimit -v "$((SCIP_MEMORY_MB * 1024))" 2>/dev/null || \
    printf 'scip-java: warning: virtual-memory limit unavailable on this shell\n' >&2
  ulimit -t "$SCIP_CPU_SECONDS" 2>/dev/null || \
    printf 'scip-java: warning: CPU-time limit unavailable on this shell\n' >&2
  ulimit -u "$SCIP_PROCESS_LIMIT" 2>/dev/null || \
    printf 'scip-java: warning: process limit unavailable on this shell\n' >&2
  ulimit -n "$SCIP_FILE_LIMIT" 2>/dev/null || \
    printf 'scip-java: warning: file-descriptor limit unavailable on this shell\n' >&2
  export GRADLE_USER_HOME="$SCIP_ROOT/gradle-home"
  if [[ -z "${SCIP_JAVA_HOME:-}" && -d /usr/lib/jvm/java-21-openjdk-amd64 ]]; then
    SCIP_JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  fi
  if [[ -n "${SCIP_JAVA_HOME:-}" ]]; then
    [[ -x "$SCIP_JAVA_HOME/bin/java" ]] || { printf 'scip-java: invalid SCIP_JAVA_HOME: %s\n' "$SCIP_JAVA_HOME" >&2; exit 69; }
    export JAVA_HOME="$SCIP_JAVA_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
  java_major="$(java -version 2>&1 | awk -F '[".]' '/version/ {print $2; exit}')"
  if [[ -z "$java_major" || "$java_major" -lt 21 ]]; then
    printf 'scip-java: this Gradle/AGP stack requires JDK 21+; set SCIP_JAVA_HOME explicitly\n' >&2
    exit 69
  fi
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Xmx${SCIP_JVM_HEAP_MB}m -XX:MaxMetaspaceSize=512m"

  read -r -a tasks <<< "$SCIP_GRADLE_TASKS"
  gradle_jvm_args="-Xmx${SCIP_JVM_HEAP_MB}m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
  candidate="$REPO_ROOT/android-compose/index.scip"
  rm -f -- "$candidate"
  printf 'scip-java: running bounded experimental index; log: %s\n' "$LOG"
  set +e
  (cd "$REPO_ROOT/android-compose" && timeout --signal=TERM --kill-after=30s \
    "$SCIP_WALL_SECONDS" "$BIN" index -- "${tasks[@]}" --no-daemon --max-workers=2 \
    "-Dorg.gradle.jvmargs=$gradle_jvm_args") \
    >"$LOG" 2>&1
  status=$?
  set -e
  if (( status != 0 )); then
    rm -f -- "$candidate"
    if (( status == 124 || status == 137 )); then
      printf 'scip-java: bounded pilot timed out (exit %s); no support is implied. See %s\n' "$status" "$LOG" >&2
    else
      printf 'scip-java: unsupported-stack pilot failed (exit %s); no support is implied. See %s\n' "$status" "$LOG" >&2
    fi
    exit "$status"
  fi
  [[ -s "$candidate" ]] || { printf 'scip-java: command succeeded but produced no index.scip\n' >&2; exit 65; }
  size_mb=$(( ($(stat -c %s "$candidate") + 1048575) / 1048576 ))
  if (( size_mb > SCIP_MAX_INDEX_MB )); then
    rm -f -- "$candidate"
    printf 'scip-java: index exceeded %s MiB cap and was removed\n' "$SCIP_MAX_INDEX_MB" >&2
    exit 75
  fi
  mv -f -- "$candidate" "$INDEX"
  printf 'scip-java: advisory index written to %s (%s MiB)\n' "$INDEX" "$size_mb"
}

extract() {
  [[ -s "$INDEX" ]] || { printf 'scip-java: no advisory index; run the opt-in pilot first\n' >&2; exit 66; }
  "$SCRIPT_DIR/extract-scip-graph.py" "$INDEX" "$SCIP_ROOT/out/graph.jsonl" \
    --max-records "$SCIP_MAX_GRAPH_RECORDS"
}

clean() {
  safe_root
  rm -rf -- "$SCIP_ROOT"
  printf 'scip-java: removed %s\n' "$SCIP_ROOT"
}

case "${1:-}" in
  install) install_binary ;;
  doctor) doctor ;;
  index) index_pilot ;;
  extract) extract ;;
  clean) clean ;;
  *) usage >&2; exit 64 ;;
esac
