#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)"
ENV_FILE="$REPO_ROOT/config/mcp/serena.env"
CONFIG_TEMPLATE="$REPO_ROOT/config/mcp/serena-config.yml"
LOCAL_ROOT="${LETTA_TOOL_CACHE_ROOT:-$REPO_ROOT/.local}"

if [[ "$LOCAL_ROOT" != /* ]]; then
  printf 'serena: LETTA_TOOL_CACHE_ROOT must be an absolute path\n' >&2
  exit 64
fi

# shellcheck disable=SC1090
source "$ENV_FILE"
SERENA_HOME="$LOCAL_ROOT/serena/home"
UV_CACHE_DIR="$LOCAL_ROOT/uv/cache"
UV_TOOL_DIR="$LOCAL_ROOT/uv/tools"
UV_PYTHON_INSTALL_DIR="$LOCAL_ROOT/uv/python"
UV_PYTHON_CACHE_DIR="$LOCAL_ROOT/uv/python-cache"
export SERENA_HOME UV_CACHE_DIR UV_TOOL_DIR UV_PYTHON_INSTALL_DIR UV_PYTHON_CACHE_DIR
export XDG_CACHE_HOME="$LOCAL_ROOT/xdg/cache"
export XDG_CONFIG_HOME="$LOCAL_ROOT/xdg/config"
export XDG_DATA_HOME="$LOCAL_ROOT/xdg/data"
export XDG_STATE_HOME="$LOCAL_ROOT/xdg/state"

usage() {
  printf 'Usage: %s {setup|serve|check|clean|paths}\n' "${0##*/}"
}

require_uvx() {
  command -v uvx >/dev/null 2>&1 || {
    printf 'serena: uvx is required; install uv without writing into this repository\n' >&2
    exit 69
  }
}

setup() {
  require_uvx
  mkdir -p "$SERENA_HOME" "$UV_CACHE_DIR" "$UV_TOOL_DIR" \
    "$UV_PYTHON_INSTALL_DIR" "$UV_PYTHON_CACHE_DIR" "$XDG_CACHE_HOME" \
    "$XDG_CONFIG_HOME" "$XDG_DATA_HOME" "$XDG_STATE_HOME"
  install -m 0600 "$CONFIG_TEMPLATE" "$SERENA_HOME/serena_config.yml"
  timeout --signal=TERM --kill-after=10s "$SERENA_STARTUP_TIMEOUT_SECONDS" \
    uvx --from "git+https://github.com/oraios/serena.git@$SERENA_GIT_REVISION" \
    serena --version >/dev/null
  printf 'serena: pinned v%s is ready under %s\n' "$SERENA_VERSION" "$LOCAL_ROOT"
}

apply_limits() {
  ulimit -v "$((SERENA_MEMORY_MB * 1024))" 2>/dev/null || \
    printf 'serena: warning: virtual-memory limit unavailable on this shell\n' >&2
  ulimit -t "$SERENA_CPU_SECONDS" 2>/dev/null || \
    printf 'serena: warning: CPU-time limit unavailable on this shell\n' >&2
  ulimit -n "$SERENA_FILE_LIMIT" 2>/dev/null || \
    printf 'serena: warning: file-descriptor limit unavailable on this shell\n' >&2
  ulimit -u "$SERENA_PROCESS_LIMIT" 2>/dev/null || \
    printf 'serena: warning: process limit unavailable on this shell\n' >&2
}

serve() {
  require_uvx
  [[ -f "$SERENA_HOME/serena_config.yml" ]] || {
    printf 'serena: run "%s setup" before starting an MCP client\n' "$0" >&2
    exit 66
  }
  apply_limits
  exec uvx --offline --from \
    "git+https://github.com/oraios/serena.git@$SERENA_GIT_REVISION" \
    serena start-mcp-server --transport stdio --project "$REPO_ROOT" \
    --context codex --tool-timeout "$SERENA_TOOL_TIMEOUT_SECONDS" \
    --enable-web-dashboard false --enable-gui-log-window false
}

check() {
  require_uvx
  [[ -f "$SERENA_HOME/serena_config.yml" ]] || {
    printf 'serena: run "%s setup" before checking the project\n' "$0" >&2
    exit 66
  }
  apply_limits
  uvx --offline --from \
    "git+https://github.com/oraios/serena.git@$SERENA_GIT_REVISION" \
    serena project health-check "$REPO_ROOT"
}

clean() {
  case "$LOCAL_ROOT" in
    "$REPO_ROOT/.local"|"$REPO_ROOT/.local/"* ) ;;
    *)
      printf 'serena: refusing to clean cache outside %s/.local\n' "$REPO_ROOT" >&2
      exit 64
      ;;
  esac
  rm -rf -- "$LOCAL_ROOT/serena" "$LOCAL_ROOT/uv" "$LOCAL_ROOT/xdg"
  printf 'serena: removed project-local cache under %s\n' "$LOCAL_ROOT"
}

case "${1:-}" in
  setup) setup ;;
  serve) serve ;;
  check) check ;;
  clean) clean ;;
  paths) printf 'SERENA_HOME=%s\nUV_CACHE_DIR=%s\nUV_TOOL_DIR=%s\nUV_PYTHON_INSTALL_DIR=%s\n' \
    "$SERENA_HOME" "$UV_CACHE_DIR" "$UV_TOOL_DIR" "$UV_PYTHON_INSTALL_DIR" ;;
  *) usage >&2; exit 64 ;;
esac
