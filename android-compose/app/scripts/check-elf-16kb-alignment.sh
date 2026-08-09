#!/usr/bin/env bash
# =============================================================================
# 16 KB ELF alignment checker (letta-mobile, 2026-08-09)
# =============================================================================
# Runs llvm-objdump on a shared object and exits non-zero if any PT_LOAD
# segment is aligned at less than 2**14 (16 KB).
#
# Usage:
#   check-elf-16kb-alignment.sh <path-to-llvm-objdump> <path-to-shared-object>
#
# Exit codes:
#   0 - all PT_LOAD segments are aligned at >= 2**14
#   1 - invocation error (missing arg, objdump missing, etc.)
#   2 - one or more PT_LOAD segments have align < 2**14
# =============================================================================
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: check-elf-16kb-alignment.sh <llvm-objdump> <shared-object>" >&2
  exit 1
fi

OBJDUMP="$1"
SO="$2"

if [[ ! -x "$OBJDUMP" ]]; then
  echo "check-elf-16kb-alignment: llvm-objdump not found or not executable: $OBJDUMP" >&2
  exit 1
fi

if [[ ! -f "$SO" ]]; then
  echo "check-elf-16kb-alignment: shared object not found: $SO" >&2
  exit 1
fi

# llvm-objdump -p prints program headers; "LOAD" lines look like:
#   LOAD off    0x0000000000000000 vaddr 0x0000000000000000 paddr 0x0000000000000000 align 2**14
bad=0
while IFS= read -r line; do
  align_field=$(echo "$line" | awk '{ for (i=1; i<=NF; i++) if ($i == "align") { print $(i+1); exit } }')
  if [[ -z "$align_field" ]]; then
    continue
  fi
  # align_field looks like "2**14" or "2**13".
  exponent="${align_field#2\*\*}"
  if (( exponent < 14 )); then
    echo "check-elf-16kb-alignment: $SO has misaligned LOAD: $line" >&2
    bad=1
  fi
done < <("$OBJDUMP" -p "$SO" | grep "LOAD")

if (( bad )); then
  exit 2
fi
exit 0