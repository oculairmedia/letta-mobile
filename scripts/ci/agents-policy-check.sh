#!/usr/bin/env bash
# Advisory AGENTS.md checks. Findings do not fail the scan; scan errors do.
# Stdout: SEVERITY|RULE|PATH:LINE|message
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCAN="${ROOT}/android-compose"

# --require-shared-search: the one REQUIRED rule here (letta-mobile-ewtaq, AGENTS.md "No unshared
# frontend or logic"). The PR body (env PR_BODY, else the GitHub event at GITHUB_EVENT_PATH) must
# carry a "Shared implementation searched: <paths>" line naming at least one searched path (a token
# with a '/'). Exit 1 without it. The only exemption is a PR opened by dependabot[bot] (env
# PR_AUTHOR), whose body is generated; no label or other body line relaxes it.
if [[ "${1:-}" == "--require-shared-search" ]]; then
  body="${PR_BODY:-}"
  if [[ -z "$body" && -n "${GITHUB_EVENT_PATH:-}" && -f "${GITHUB_EVENT_PATH}" ]] && command -v jq >/dev/null 2>&1; then
    body="$(jq -r '.pull_request.body // ""' "$GITHUB_EVENT_PATH")"
  fi
  if [[ "${PR_AUTHOR:-}" == "dependabot[bot]" ]]; then
    echo "agents-policy-check: dependabot PR, generated body - shared-search line not required"
    exit 0
  fi
  searched="$(printf '%s\n' "$body" | tr -d '\r' | sed -n 's/^[[:space:]]*Shared implementation searched:[[:space:]]*\(.*[^[:space:]]\)[[:space:]]*$/\1/p' | head -n 1)"
  if [[ -n "$searched" && "$searched" != *"<paths>"* && "$searched" == */* ]]; then
    echo "agents-policy-check: Shared implementation searched: $searched"
    exit 0
  fi
  echo "::error title=Shared implementation searched::PR description is missing a 'Shared implementation searched: <paths>' line"
  cat <<'MSG'
agents-policy-check: FAILED - the PR description has no "Shared implementation searched:" line.
  Owner rule (AGENTS.md, "No unshared frontend or logic"): before adding code, search sharedLogic/
  and sharedUI/ for an existing implementation and reuse it. Add one line to the PR description:
    Shared implementation searched: sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/<area>/, sharedLogic/src/commonMain/kotlin/com/letta/mobile/<area>/
  naming the shared paths you searched (at least one path with a '/'), then re-run this job.
MSG
  exit 1
fi
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

search_kotlin() {
  local pattern="$1"
  local matches status tool
  set +e
  if command -v rg >/dev/null 2>&1 && [[ "${AGENTS_POLICY_FORCE_GREP:-0}" != "1" ]]; then
    tool="rg"
    matches="$(rg -n --with-filename --glob '*.kt' "$pattern" "${TARGETS[@]}" 2>/dev/null)"
  else
    tool="grep"
    # -r is required: --include only filters during a RECURSIVE search, and the
    # TARGETS include directories. Without -r, grep hits a directory arg and exits
    # 2 ("Is a directory") under `set -e` on runners without ripgrep (the CI path),
    # AND never actually descends into .kt files. rg above recurses by default.
    matches="$(grep -rEnH --include='*.kt' "$pattern" "${TARGETS[@]}" 2>/dev/null)"
  fi
  status=$?
  set -e
  if ((status > 1)); then
    echo "agents-policy-check: ${tool} failed for pattern '$pattern'" >&2
    return "$status"
  fi
  printf '%s' "$matches"
}

RAW_COLOR_MATCHES="$(search_kotlin '\bColor\s*\(\s*0x[0-9A-Fa-f]+\s*\)')"
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

JVM_API_MATCHES="$(search_kotlin '\bString\.format\s*\(|\bStringBuilder\.delete\s*\(|\.toByteArray\s*\(')"
while IFS=: read -r file line _; do
  [[ -n "$file" ]] || continue
  rel="${file#"$ROOT"/}"
  is_changed "$rel" "$line" || continue
  case "$rel" in */sharedLogic/*/commonMain/*|*/sharedLogic/src/commonMain/*|*/sharedLogic/*/commonTest/*|*/sharedLogic/src/commonTest/*)
    echo "WARN|sharedlogic-jvm-api|${rel}:${line}|forbidden JVM-only API in commonMain" >>"$OUT" ;;
  esac
done <<<"$JVM_API_MATCHES"

ALERT_DIALOG_MATCHES="$(search_kotlin '\bAlertDialog\s*\(')"
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
