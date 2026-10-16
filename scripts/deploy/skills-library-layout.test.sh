#!/usr/bin/env bash
# Tests for skills-library-layout.sh against throwaway temp dirs (never a real LETTA_HOME).
#   bash scripts/deploy/skills-library-layout.test.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/skills-library-layout.sh"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
fails=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

lib="$T/library"; home="$T/home"
mkdir -p "$lib/alpha" "$lib/beta" "$lib/.git/objects" "$lib/not-a-skill" "$home"
echo "# alpha" > "$lib/alpha/SKILL.md"
echo "# beta" > "$lib/beta/skill.md"
ln -s "$lib" "$home/skills"
snapshot() { (cd "$T" && find . -printf '%p %y %l\n' | sort); }

before="$(snapshot)"
out="$("$SCRIPT" --letta-home "$home")"
check "dry run changes nothing" '[[ "$(snapshot)" == "$before" ]]'
check "dry run prints the move" 'grep -q "would: mv $home/skills $home/skills.pre-library-layout" <<<"$out"'
check "dry run links skills only" 'grep -q "would: ln -s $lib/alpha" <<<"$out" && grep -q "would: ln -s $lib/beta" <<<"$out" && ! grep -q "not-a-skill\|\.git" <<<"$out"'

"$SCRIPT" --apply --letta-home "$home" >/dev/null
check "skills is now a real directory" '[[ -d "$home/skills" && ! -L "$home/skills" ]]'
check "one link per skill" '[[ "$(readlink "$home/skills/alpha")" == "$lib/alpha" && "$(readlink "$home/skills/beta")" == "$lib/beta" && ! -e "$home/skills/not-a-skill" ]]'
check "backup keeps the directory link" '[[ "$(readlink "$home/skills.pre-library-layout")" == "$lib" ]]'
check "library untouched" '[[ -f "$lib/alpha/SKILL.md" && -d "$lib/.git" ]]'

mkdir "$lib/gamma" && echo "# gamma" > "$lib/gamma/SKILL.md"
out="$("$SCRIPT" --apply --letta-home "$home")"
check "re-apply is idempotent and adds only new skills" 'grep -q "links to add: 1" <<<"$out" && [[ -L "$home/skills/gamma" ]]'

mkdir "$home/skills/real-one"
set +e; "$SCRIPT" --apply --letta-home "$home" >/dev/null 2>&1; rc=$?; set -e
check "refuses a real folder in skills/" '[[ $rc == 2 ]]'
rmdir "$home/skills/real-one"

rm "$home/skills/alpha"   # a skill turned off from the app (skill_disable unlinked it)
"$SCRIPT" --rollback --letta-home "$home" >/dev/null
check "rollback restores the directory link" '[[ -L "$home/skills" && "$(readlink "$home/skills")" == "$lib" && ! -e "$home/skills.pre-library-layout" ]]'
check "rollback leaves the library intact" '[[ -f "$lib/alpha/SKILL.md" && -f "$lib/gamma/SKILL.md" ]]'

if ((fails)); then echo "$fails failure(s)"; exit 1; fi
echo "all passed"
