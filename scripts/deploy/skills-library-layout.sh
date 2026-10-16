#!/usr/bin/env bash
# OPTIONAL, OPS-ONLY, for hosts whose letta-code skills folder is one directory symlink into a
# skills checkout (e.g. ~/.letta/skills -> /opt/skills). The app does NOT need this: it follows
# stock letta-code semantics, where only a symlink inside <LETTA_HOME>/skills can be turned off
# (skill_disable unlinks it) and every real folder is shown read-only. This script makes such a
# host's skills individually controllable from the app by turning the skills folder into a real
# directory holding one symlink per skill:
#
#   <LETTA_HOME>/skills            (was: -> <library>)    becomes a real directory
#   <LETTA_HOME>/skills/<name>  -> <library>/<name>       for each <library>/<name>/SKILL.md
#
# The original directory link is kept at <LETTA_HOME>/skills.pre-library-layout for --rollback.
# Nothing in the library is moved, copied or deleted.
#
# Usage: skills-library-layout.sh [--apply | --rollback [--dry-run]] [--letta-home DIR] [--library DIR]
#   (no mode)   dry run: print exactly what --apply would do, change nothing
#   --apply     perform it (idempotent: re-running adds links for new library skills only)
#   --rollback  remove the per-skill links (links only) and restore the directory link
#               (--rollback --dry-run previews it)
# Defaults: --letta-home "${LETTA_HOME:-$HOME/.letta}", --library = the current target of
# <letta-home>/skills when it is a directory link (required otherwise).
# Refuses (exit 2) on anything unexpected: a real folder or a foreign link in skills/, a
# missing library, an existing backup that is not the expected link.
# Restart the App Server afterwards: on Node, letta-code's skill-list cache misses symlink
# changes (see docs/architecture/iroh-admin-ownership-matrix.md, "Skill controls").
set -euo pipefail

mode=dry-run
dry=1
letta_home="${LETTA_HOME:-$HOME/.letta}"
library=""
while (($#)); do
  case "$1" in
    --apply) mode=apply; dry=0 ;;
    --rollback) mode=rollback; dry=0 ;;
    --dry-run) preview=1 ;;
    --letta-home) letta_home="$2"; shift ;;
    --library) library="$2"; shift ;;
    -h|--help) sed -n '2,27p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 64 ;;
  esac
  shift
done
[[ ${preview:-0} == 1 ]] && dry=1

skills="$letta_home/skills"
backup="$letta_home/skills.pre-library-layout"

refuse() { echo "REFUSED: $*" >&2; exit 2; }
plan() { echo "$( ((dry)) && echo 'would' || echo 'do'): $*"; }
run() { plan "$*"; ((dry)) || "$@"; }

if [[ -z "$library" ]]; then
  if [[ -L "$skills" ]]; then library="$(readlink -f "$skills")"
  elif [[ -L "$backup" ]]; then library="$(readlink -f "$backup")"
  else refuse "--library is required: $skills is not a directory link and no backup link exists"; fi
fi
library="${library%/}"
[[ -d "$library" ]] || refuse "library $library is not a directory"

library_skills() {  # names of top-level library dirs holding a SKILL.md (any case), .git skipped
  local d
  for d in "$library"/*/; do
    d="${d%/}"
    [[ "$(basename "$d")" == .git ]] && continue
    compgen -G "$d/[Ss][Kk][Ii][Ll][Ll].[Mm][Dd]" >/dev/null && basename "$d"
  done
  return 0
}

check_skills_dir() {  # every entry must be a link into the library; anything else is unexpected
  local e target
  for e in "$skills"/* "$skills"/.[!.]*; do
    [[ -e "$e" || -L "$e" ]] || continue
    [[ -L "$e" ]] || refuse "$e is a real file/folder; move it out (or into $library) first"
    target="$(readlink "$e")"
    [[ "$target" == "$library/$(basename "$e")" ]] || refuse "$e links to $target, not into $library"
  done
}

echo "mode=$mode dry_run=$dry letta_home=$letta_home library=$library"
case "$mode" in
  dry-run|apply)
    if [[ -L "$skills" ]]; then
      [[ "$(readlink -f "$skills")" == "$(readlink -f "$library")" ]] || refuse "$skills links to $(readlink "$skills"), not $library"
      [[ -e "$backup" || -L "$backup" ]] && refuse "$backup already exists; inspect it before re-running"
      run mv "$skills" "$backup"
      run mkdir "$skills"
    elif [[ -d "$skills" ]]; then
      check_skills_dir
    elif [[ -e "$skills" ]]; then
      refuse "$skills exists and is neither a directory nor a directory link"
    else
      run mkdir -p "$skills"
    fi
    added=0
    while IFS= read -r name; do
      [[ -L "$skills/$name" ]] && continue
      run ln -s "$library/$name" "$skills/$name"
      added=$((added + 1))
    done < <(library_skills)
    echo "links to add: $added"
    ;;
  rollback)
    [[ -L "$backup" ]] || refuse "no backup link at $backup; nothing to roll back to"
    [[ -d "$skills" && ! -L "$skills" ]] || refuse "$skills is not the real directory --apply creates"
    check_skills_dir
    for e in "$skills"/* "$skills"/.[!.]*; do
      [[ -L "$e" ]] && run rm "$e"
    done
    run rmdir "$skills"
    run mv "$backup" "$skills"
    ;;
esac
((dry)) && echo "dry run: nothing changed"
exit 0
