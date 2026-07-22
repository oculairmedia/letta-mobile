#!/usr/bin/env bash
# Advisory AGENTS.md checks. Findings do not fail the scan; scan errors do.
# Stdout: SEVERITY|RULE|PATH:LINE|message
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCAN="${ROOT}/android-compose"
DIFF_BASE=""
if [[ "${1:-}" == "--diff-base" ]]; then
  [[ -n "${2:-}" ]] || { echo "agents-policy-check: --diff-base requires a ref" >&2; exit 2; }
  DIFF_BASE="$2"
fi

declare -a TARGETS=("$SCAN")
declare -A CHANGED_LINES=()
if [[ -n "$DIFF_BASE" ]]; then
  if ! DIFF="$(git -C "$ROOT" diff --unified=0 --diff-filter=ACMR "${DIFF_BASE}...HEAD" -- 'android-compose/**/*.kt')"; then
    echo "agents-policy-check: failed to diff '$DIFF_BASE...HEAD'" >&2
    exit 1
  fi
  TARGETS=()
  current=""
  while IFS= read -r line; do
    case "$line" in
      "+++ b/"*)
        current="${line#+++ b/}"
        [[ -f "$ROOT/$current" ]] && TARGETS+=("$ROOT/$current")
        ;;
      "@@ "*)
        [[ -n "$current" ]] || continue
        range="${line#* +}"
        range="${range%% *}"
        start="${range%%,*}"
        count="1"
        [[ "$range" == *,* ]] && count="${range#*,}"
        for ((offset = 0; offset < count; offset++)); do
          CHANGED_LINES["$current:$((start + offset))"]=1
        done
        ;;
    esac
  done <<<"$DIFF"
  if [[ ${#TARGETS[@]} -eq 0 ]]; then
    echo "agents-policy-check: 0 finding(s)" >&2
    exit 0
  fi
fi

OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

is_changed() {
  [[ -z "$DIFF_BASE" || -n "${CHANGED_LINES[$1:$2]+x}" ]]
}

run_rg() {
  local pattern="$1"
  shift
  local matches status
  set +e
  matches="$(rg -n --glob '*.kt' "$pattern" "${TARGETS[@]}" 2>/dev/null)"
  status=$?
  set -e
  if ((status > 1)); then
    echo "agents-policy-check: rg failed for pattern '$pattern'" >&2
    return "$status"
  fi
  printf '%s' "$matches"
}

RAW_COLOR_MATCHES="$(run_rg '\bColor\s*\(\s*0x[0-9A-Fa-f]+\s*\)')"
while IFS=: read -r file line _; do
  [[ -n "$file" ]] || continue
  rel="${file#"$ROOT"/}"
  is_changed "$rel" "$line" || continue
  base="$(basename "$file")"
  case "$base" in Color.kt|Theme.kt|ChatTheme.kt|TypeHierarchy.kt|*Tokens*|*Palette*|CustomColors*) continue ;; esac
  case "$rel" in */theme/*) continue ;; esac
  case "$rel" in android-compose/app/*|android-compose/designsystem/*|android-compose/feature-*)
    echo "WARN|no-raw-hex-color|${rel}:${line}|raw Color(0x...) - use theme roles" >>"$OUT" ;;
  esac
done <<<"$RAW_COLOR_MATCHES"

JVM_API_MATCHES="$(run_rg '\bString\.format\s*\(|\bStringBuilder\.delete\s*\(|\.toByteArray\s*\(')"
while IFS=: read -r file line _; do
  [[ -n "$file" ]] || continue
  rel="${file#"$ROOT"/}"
  is_changed "$rel" "$line" || continue
  case "$rel" in */sharedLogic/*/commonMain/*|*/sharedLogic/src/commonMain/*)
    echo "WARN|sharedlogic-jvm-api|${rel}:${line}|forbidden JVM-only API in commonMain" >>"$OUT" ;;
  esac
done <<<"$JVM_API_MATCHES"

ALERT_DIALOG_MATCHES="$(run_rg '\bAlertDialog\s*\(')"
while IFS=: read -r file line _; do
  [[ -n "$file" ]] || continue
  rel="${file#"$ROOT"/}"
  is_changed "$rel" "$line" || continue
  case "$rel" in android-compose/app/*|android-compose/feature-*)
    echo "WARN|raw-alertdialog|${rel}:${line}|prefer ConfirmDialog / designsystem" >>"$OUT" ;;
  esac
done <<<"$ALERT_DIALOG_MATCHES"

sort -u "$OUT"
count="$(sort -u "$OUT" | wc -l | tr -d ' ')"
echo "agents-policy-check: ${count} finding(s)" >&2
