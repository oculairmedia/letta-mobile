#!/usr/bin/env bash
# =============================================================================
# Post-build ELF sanity check (letta-mobile, 2026-08-10)
# =============================================================================
# Refuses to ship an APK whose libiroh_ffi.so lacks the dynamic-metadata
# tables the linker needs to load it. The 16KB realigner can produce a
# .so that PASSES llvm-objdump -p alignment checks but FAILS dlopen()
# because DT_HASH / DT_GNU_HASH / DT_STRTAB / DT_SYMTAB / DT_DYNAMIC are
# missing or have null d_un.d_ptr values.
#
# Background: in fire #53 (2026-08-09) the user installed a build where
# realign-16kb.py had written a libiroh_ffi.so that was 16 KB-aligned
# (alignment check passed) but had no DT_HASH / DT_GNU_HASH entries
# (dynamic linker couldn't resolve symbols, app crashed with SIGSEGV at
# 0.79s). The existing check-elf-16kb-alignment.sh only verifies LOAD
# alignment, not the metadata tables the linker actually reads. This
# script closes that gap.
#
# Usage:
#   so-sanity-check.sh <llvm-readelf> <path-to-shared-object>
#
# Exit codes:
#   0 - all dynamic-metadata tables present and non-null
#   1 - invocation error (missing arg, readelf missing, etc.)
#   2 - one or more required tables missing, null, or empty
# =============================================================================
set -euo pipefail

# Trap ANY early-exit so the failure surfaces a useful diagnostic
# instead of an empty stderr that the gradle task can't act on.
# Set FIRST (before the util check below) so even missing-util exits
# produce a useful diagnostic.
trap 'echo "so-sanity-check: script aborted (exit=$?, line=$LINENO)" >&2; echo "so-sanity-check: \$READELF=${READELF:-unset} \$SO=${SO:-unset}" >&2' ERR

# Verify the basic utilities the script relies on exist. On minimal CI
# images (Alpine, distroless, etc.) grep/awk may be missing, and the
# subsequent commands would silently fail under `set -e` without this.
for util in grep awk; do
    if ! command -v "$util" >/dev/null 2>&1; then
        echo "so-sanity-check: required utility '$util' not found in PATH" >&2
        exit 1
    fi
done

if [[ $# -ne 2 ]]; then
    echo "usage: so-sanity-check.sh <llvm-readelf> <shared-object>" >&2
    exit 1
fi

READELF="$1"
SO="$2"

if [[ ! -x "$READELF" ]]; then
    echo "so-sanity-check: llvm-readelf not found or not executable: $READELF" >&2
    exit 1
fi

if [[ ! -f "$SO" ]]; then
    echo "so-sanity-check: shared object not found: $SO" >&2
    exit 1
fi

# Required dynamic tags. Without at least one of HASH / GNU_HASH the
# dynamic linker cannot resolve any symbols -> dlopen fails with
# "empty/missing DT_HASH/DT_GNU_HASH". Without DT_STRTAB / DT_SYMTAB,
# name lookup fails.
required_tags=(STRTAB SYMTAB)

bad=0
# Capture both stdout AND stderr from readelf into $dump. `set -e` is
# disabled for this one call via `|| true` because a failing readelf
# should be reported as "missing tags" (bad=1) rather than aborting
# the whole script before the user sees why; without `|| true` the
# set -e + pipefail combination would silently exit 2 with no output,
# which is exactly what was happening on the CI runner when readelf
# hit an unrelated error (e.g. not finding the file due to a
# different Android NDK toolchain layout).
dump="$("$READELF" -d "$SO" 2>&1 || true)"

# Parse "(HASH)" or "(GNU_HASH)" or "(STRTAB)" lines for tag presence.
# llvm-readelf prints e.g. "  (HASH)               0x00000000012345".
for tag in "${required_tags[@]}"; do
    if ! grep -qE "\(\s*${tag}\b" <<<"$dump"; then
        echo "so-sanity-check: $SO missing DT_${tag}" >&2
        bad=1
    fi
done

# At least one of HASH or GNU_HASH must be present. (HASH is the legacy
# SysV hash; GNU_HASH is the GNU extension .gnu.hash. Modern .so files
# typically have GNU_HASH only, but old SysV-style .so files have
# HASH only. Either is sufficient -- the linker accepts both.)
if ! grep -qE "\(\s*(GNU_)?HASH\b" <<<"$dump"; then
    echo "so-sanity-check: $SO missing DT_HASH AND DT_GNU_HASH" >&2
    bad=1
fi

# Verify HASH / GNU_HASH entries are non-null. A HASH line with d_ptr=0
# is corrupt (hash table with 0 buckets). Libre instant crasher.
for tag in HASH GNU_HASH; do
    if grep -qE "\(\s*${tag}\b" <<<"$dump"; then
        val="$(grep -E "\(\s*${tag}\b" <<<"$dump" | awk '{print $NF}')"
        if [[ "$val" == "0x0" || "$val" == "0x0000000000000000" ]]; then
            echo "so-sanity-check: $SO DT_${tag} has d_ptr=0 (empty hash table)" >&2
            bad=1
        fi
    fi
done

# Verify STRTAB and SYMTAB d_ptr values are non-null. A NULL string
# table or symbol table is never valid and crashes linkers that
# don't bound-check.
for tag in STRTAB SYMTAB; do
    if grep -qE "\(\s*${tag}\b" <<<"$dump"; then
        val="$(grep -E "\(\s*${tag}\b" <<<"$dump" | awk '{print $NF}')"
        if [[ "$val" == "0x0" || "$val" == "0x0000000000000000" ]]; then
            echo "so-sanity-check: $SO DT_${tag} has d_ptr=0 (NULL ${tag,,})" >&2
            bad=1
        fi
    fi
done

if (( bad )); then
    # Diagnostic: print what bad was set to and what the dump looks like.
    # This appears on EXIT (not ERR) because bad=1 + exit 2 is a normal
    # exit path -- set -e doesn't fire it. Without this, exit 2 with
    # no other stderr is indistinguishable from a silent set -e kill.
    echo "so-sanity-check: bad=$bad, dump length=${#dump}" >&2
    exit 2
fi
exit 0
