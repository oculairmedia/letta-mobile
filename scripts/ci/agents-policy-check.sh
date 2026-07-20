#!/usr/bin/env bash
# Advisory AGENTS.md greps. Always exits 0 after a successful scan.
# Stdout: SEVERITY|RULE|PATH:LINE|message
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCAN="${ROOT}/android-compose"
DIFF_BASE=""
[[ "${1:-}" == "--diff-base" ]] && DIFF_BASE="${2:-}"

TARGETS=("$SCAN")
if [[ -n "$DIFF_BASE" ]]; then
  TARGETS=()
  while IFS= read -r rel; do
    [[ "$rel" == android-compose/*.kt ]] || [[ "$rel" == android-compose/*/*.kt ]] \
      || [[ "$rel" == android-compose/*/*/*.kt ]] || [[ "$rel" == android-compose/*/*/*/*.kt ]] \
      || [[ "$rel" == android-compose/*/*/*/*/*.kt ]] || [[ "$rel" == android-compose/*/*/*/*/*/*.kt ]] \
      || [[ "$rel" == android-compose/*/*/*/*/*/*/*.kt ]] || continue
    [[ -f "$ROOT/$rel" ]] && TARGETS+=("$ROOT/$rel")
  done < <(git -C "$ROOT" diff --name-only --diff-filter=ACMR "${DIFF_BASE}...HEAD" 2>/dev/null \
    || git -C "$ROOT" diff --name-only --diff-filter=ACMR "$DIFF_BASE")
  if [[ ${#TARGETS[@]} -eq 0 ]]; then
    echo "agents-policy-check: 0 finding(s)" >&2
    exit 0
  fi
fi

OUT="$(mktemp)"; trap 'rm -f "$OUT"' EXIT

rg -n --glob '*.kt' '\bColor\s*\(\s*0x[0-9A-Fa-f]+\s*\)' "${TARGETS[@]}" 2>/dev/null \
  | while IFS=: read -r f l _; do
      r="${f#"$ROOT"/}"; b="$(basename "$f")"
      case "$b" in Color.kt|Theme.kt|ChatTheme.kt|TypeHierarchy.kt|*Tokens*|*Palette*|CustomColors*) continue ;; esac
      case "$r" in */theme/*) continue ;; esac
      case "$r" in android-compose/app/*|android-compose/designsystem/*|android-compose/feature-*)
        echo "WARN|no-raw-hex-color|${r}:${l}|raw Color(0x...) — use theme roles" ;;
      esac
    done >>"$OUT" || true

rg -n --glob '*.kt' '\bString\.format\s*\(|\bStringBuilder\.delete\s*\(|\.toByteArray\s*\(' "${TARGETS[@]}" 2>/dev/null \
  | while IFS=: read -r f l _; do
      r="${f#"$ROOT"/}"
      case "$r" in */sharedLogic/*/commonMain/*|*/sharedLogic/src/commonMain/*)
        echo "WARN|sharedlogic-jvm-api|${r}:${l}|forbidden JVM-only API in commonMain" ;;
      esac
    done >>"$OUT" || true

rg -n --glob '*.kt' '\bAlertDialog\s*\(' "${TARGETS[@]}" 2>/dev/null \
  | while IFS=: read -r f l _; do
      r="${f#"$ROOT"/}"
      case "$r" in android-compose/app/*|android-compose/feature-*)
        echo "WARN|raw-alertdialog|${r}:${l}|prefer ConfirmDialog / designsystem" ;;
      esac
    done >>"$OUT" || true

sort -u "$OUT"
n="$(sort -u "$OUT" | wc -l | tr -d ' ')"
echo "agents-policy-check: ${n} finding(s)" >&2
exit 0
