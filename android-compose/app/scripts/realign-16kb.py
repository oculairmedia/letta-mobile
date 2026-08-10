#!/usr/bin/env python3
# =============================================================================
# 16 KB ELF LOAD segment realigner (letta-mobile, 2026-08-09)
# =============================================================================
# Purpose
#   The Iroh QUIC transport native library (libiroh_ffi.so) and the Nimbus
#   JOSE/JWT JNI shim (libjnidispatch.so) are prebuilt ELF64 objects whose
#   PT_LOAD segments are aligned at 4 KB boundaries. Google Play requires
#   native libraries to be 16 KB-aligned for apps that target Android 15+
#   devices starting Nov 1, 2025 (developer.android.com/16kb-page-size).
#
#   The upstream computer.iroh:iroh-android:1.1.0 AAR (the latest version on
#   Maven Central, published 2026-07-17) ships the same unaligned binary,
#   so a version bump would not fix the warning. This script rewrites the
#   program headers in place to lift each PT_LOAD to the next 16 KB-aligned
#   offset, padding the file with zero bytes between segments where needed.
#
# Algorithm
#   1. Parse the ELF64 header + program header table.
#   2. If every PT_LOAD already satisfies
#      `off % 0x4000 == p_vaddr % 0x4000` AND has `p_align >= 0x4000`,
#      exit 0 without writing (idempotent).
#   3. Otherwise, for each PT_LOAD in order, compute a NEW FILE OFFSET ONLY
#      — p_vaddr / p_paddr / p_filesz / p_memsz are left byte-for-byte
#      untouched:
#        - The first segment keeps offset 0 (required: the ELF header +
#          program header table must live at file offset 0, and a
#          well-formed PIE .so already has p_vaddr == 0 for it).
#        - Each subsequent segment's new offset is the smallest value
#          >= the previous segment's new end such that
#          `new_offset % 0x4000 == p_vaddr % 0x4000` (the ELF spec's
#          segment congruence rule, generalized from 4 KB to 16 KB). This
#          guarantees the segment's bytes sit at a file position whose
#          page-offset-within-16K matches its virtual-address-offset-
#          within-16K, which is what lets the kernel mmap it at a 16
#          KB-aligned page boundary.
#      Because p_vaddr is never modified, the virtual-address space of
#      the module is byte-for-byte identical before and after realignment
#      — only where each segment's bytes live IN THE FILE changes.
#   4. Shift each PT_LOAD's data to its new offset.
#   5. Update section header table offsets to track segment moves.
#   6. Rewrite program headers with new offsets + p_align = 0x4000.
#   7. Atomically replace the original file via a sibling tempfile +
#      os.replace(): a crash mid-write leaves the original .so intact
#      (the linker can still dlopen() the unaligned version) instead of
#      a half-written corrupt file that fails to load.
#
# Non-PT_LOAD program headers (PT_DYNAMIC, PT_NOTE, PT_GNU_EH_FRAME,
# PT_GNU_RELRO, ...) sit INSIDE the data range of a PT_LOAD. The loader
# mmaps them along with the parent PT_LOAD and locates them by following
# p_offset. When a PT_LOAD's file offset moves, every phdr inside its
# range must move its p_offset by the same delta or the linker reads
# stale bytes — _apply_load_offsets handles this. Their p_vaddr / p_paddr
# are left untouched, same as their parent PT_LOAD's, because the address
# space itself never moves.
#
# DT_* pointers inside the .dynamic section (DT_HASH, DT_GNU_HASH,
# DT_SYMTAB, DT_STRTAB, DT_RELA, DT_JMPREL, DT_INIT_ARRAY, DT_VERSYM,
# DT_ANDROID_REL(A)/DT_ANDROID_RELR, and every r_offset inside .rela.dyn /
# .rela.plt) all store VIRTUAL ADDRESSES, resolved at runtime as
# `actual_addr = load_bias + vaddr`. There is deliberately no PT_HASH /
# PT_GNU_HASH program header — hash tables are only ever reached via
# those DT_* vaddrs. An earlier version of this script forced
# `p_vaddr = new p_offset` on every PT_LOAD ("PIE convention") without
# rewriting any of the above, which desynced every vaddr-space reference
# from the bytes it pointed at and crashed the dynamic linker with
# `dlopen failed: empty/missing DT_HASH/DT_GNU_HASH` (see PR #1158 device
# repro). Preserving p_vaddr unchanged — the approach here — sidesteps
# the problem entirely: since the virtual-address space never moves, none
# of DT_HASH / DT_SYMTAB / relocation r_offset / symbol st_value need to
# change, and we never have to parse or rewrite .dynamic contents. This
# is also how lld's `-z max-page-size=16384` output and Android's own
# 16 KB-alignment tooling do it.
#
# Why not lld / -Wl,-z,max-page-size=16384?
#   The iroh-ffi Rust source is not vendored in this repository, so we
#   cannot relink from scratch. The Nimbus JOSE/JWT jar ships only a
#   prebuilt libjnidispatch.so with no source. Post-processing the ELF in
#   place is the only option that keeps the dependency graph unchanged.
#
# Idempotency
#   Re-running on an already-aligned .so is a no-op: the script reads the
#   file, verifies all PT_LOAD offsets are multiples of 0x4000 and that
#   p_align >= 0x4000, and exits 0 without writing.
#
# Exit codes
#   0 - success (either already aligned, or realigned successfully)
#   1 - input file does not exist
#   2 - input is not a 64-bit little-endian ELF
#   3 - ELF is malformed (truncated program headers, etc.)
#   4 - disk write failure
# =============================================================================
from __future__ import annotations

import os
import struct
import sys
import tempfile
from pathlib import Path
from typing import NoReturn

# 64-bit ELF, little-endian (the only ABI Android cares about for arm64-v8a).
ELFCLASS64 = 2
ELFDATA2LSB = 1
ET_DYN = 3  # Shared object (PIE .so)
PT_LOAD = 1
PAGE_16K = 0x4000

# ELF64 sizes (bytes).
ELF64_EHDR_SIZE = 64
ELF64_PHDR_SIZE = 56


def fatal(msg: str, code: int) -> NoReturn:
    print(f"realign-16kb: {msg}", file=sys.stderr)
    sys.exit(code)


def read_elf(path: Path) -> bytes:
    if not path.is_file():
        fatal(f"file not found: {path}", 1)
    try:
        return path.read_bytes()
    except OSError as exc:
        fatal(f"read failed: {exc}", 1)


def parse_ehdr(buf: bytes):
    # ELF64 header layout (offsets from the start of the file).
    if len(buf) < ELF64_EHDR_SIZE:
        fatal("file too small to be an ELF", 2)
    if buf[:4] != b"\x7fELF":
        fatal("missing ELF magic", 2)
    if buf[4] != ELFCLASS64:
        fatal(f"not ELF64 (got ELFCLASS{buf[4]})", 2)
    if buf[5] != ELFDATA2LSB:
        fatal(f"not little-endian (got data encoding {buf[5]})", 2)
    e_type = struct.unpack_from("<H", buf, 16)[0]
    if e_type != ET_DYN:
        # Executables / relocatables would be unusual for a .so but let's
        # not silently mis-handle them.
        fatal(f"expected ET_DYN (3) for a .so, got e_type={e_type}", 2)
    # Remaining fields start at offset 18: e_machine(2), e_version(4),
    # e_entry(8), e_phoff(8), e_shoff(8), e_flags(4), e_ehsize(2),
    # e_phentsize(2), e_phnum(2), e_shentsize(2), e_shnum(2), e_shstrndx(2).
    (
        _e_machine,
        _e_version,
        _e_entry,
        e_phoff,
        e_shoff,
        _e_flags,
        _e_ehsize,
        e_phentsize,
        e_phnum,
        e_shentsize,
        e_shnum,
        _e_shstrndx,
    ) = struct.unpack_from(
        "<H" "I" "Q" "Q" "Q" "I" "H" "H" "H" "H" "H" "H",
        buf,
        18,
    )
    return e_phoff, e_phentsize, e_phnum, e_shoff, e_shentsize, e_shnum


def parse_phdrs(buf: bytes, e_phoff: int, e_phentsize: int, e_phnum: int):
    if e_phentsize < ELF64_PHDR_SIZE:
        fatal(
            f"phentsize {e_phentsize} < ELF64_PHDR_SIZE ({ELF64_PHDR_SIZE})",
            3,
        )
    end = e_phoff + e_phentsize * e_phnum
    if end > len(buf):
        fatal(
            f"program header table extends past EOF ({end} > {len(buf)})",
            3,
        )
    phdrs = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        (
            p_type,
            p_flags,
            p_offset,
            p_vaddr,
            p_paddr,
            p_filesz,
            p_memsz,
            p_align,
        ) = struct.unpack_from("<IIQQQQQQ", buf, off)
        phdrs.append(
            {
                "idx": i,
                "p_type": p_type,
                "p_flags": p_flags,
                "p_offset": p_offset,
                "p_vaddr": p_vaddr,
                "p_paddr": p_paddr,
                "p_filesz": p_filesz,
                "p_memsz": p_memsz,
                "p_align": p_align,
            }
        )
    return phdrs


def is_aligned(phdrs: list[dict]) -> bool:
    for ph in phdrs:
        if ph["p_type"] != PT_LOAD:
            continue
        if ph["p_offset"] % PAGE_16K != ph["p_vaddr"] % PAGE_16K:
            return False
        if ph["p_align"] < PAGE_16K:
            return False
    return True


def _compute_new_load_offsets(load_phdrs: list[dict]) -> list[int]:
    """Compute the new file offset for each PT_LOAD segment.

    p_vaddr is NEVER changed by this script (see module docstring), so
    the new offset for each segment must satisfy the ELF congruence
    rule `new_offset % 0x4000 == p_vaddr % 0x4000` — otherwise the
    kernel can't mmap the segment's file pages onto its virtual pages.

    The first PT_LOAD is forced to offset 0 so the ELF header + program
    header table (which must live at file offsets 0..ehdr+phdrs and
    inside the first PT_LOAD's data so the dynamic linker can mmap them)
    always sit at the beginning of the file. This requires its p_vaddr
    to already be 0 mod 16K, true for every well-formed PIE .so (the
    first PT_LOAD's p_vaddr is 0) — enforced by an assertion below.

    Each subsequent segment's new offset is the smallest value
    >= the previous segment's new end that is congruent to that
    segment's own p_vaddr mod 16 KB, padding gaps with zero bytes.
    """
    new_offsets: list[int] = []
    prev_end = 0
    for i, ph in enumerate(load_phdrs):
        if i == 0:
            if ph["p_vaddr"] % PAGE_16K != 0:
                fatal(
                    "first PT_LOAD has p_vaddr not 16K-aligned "
                    f"(0x{ph['p_vaddr']:x}); unsupported layout",
                    3,
                )
            new_off = 0
        else:
            target_congruence = ph["p_vaddr"] % PAGE_16K
            new_off = prev_end - (prev_end % PAGE_16K) + target_congruence
            if new_off < prev_end:
                new_off += PAGE_16K
        new_offsets.append(new_off)
        prev_end = new_off + ph["p_filesz"]
    return new_offsets


def _copy_section_headers(out: bytearray, src: bytes, e_shoff: int, sh_size: int, new_shoff: int) -> None:
    if sh_size and e_shoff and e_shoff + sh_size <= len(src):
        out[new_shoff : new_shoff + sh_size] = src[e_shoff : e_shoff + sh_size]


def _apply_load_offsets(phdrs: list[dict], load_phdrs: list[dict], new_offsets: list[int]) -> None:
    """Rewrite file offsets for every program header. p_vaddr / p_paddr
    are NEVER modified — see the module docstring for why preserving the
    virtual-address space is what makes this transform safe without
    touching .dynamic / relocation / symbol contents.

    PT_LOAD entries get their p_offset moved to the new aligned location
    and p_align bumped to 16 KB.

    Non-PT_LOAD entries (PT_DYNAMIC, PT_NOTE, PT_GNU_EH_FRAME,
    PT_GNU_STACK, PT_GNU_RELRO, ...) live inside PT_LOAD data ranges
    (the loader mmaps them by following their PT_LOAD parent). When a
    PT_LOAD's bytes move to a new file offset, every phdr inside its
    range must move its p_offset by the same delta — otherwise the
    linker reads stale bytes. We compute the delta per PT_LOAD and apply
    it to any phdr whose original p_offset falls inside
    [orig_off, orig_off + orig_filesz). Their p_vaddr is left alone: the
    address space didn't move, only where those bytes live in the file.

    p_align is left alone for non-PT_LOAD phdrs (it is informational
    only; the linker uses the surrounding PT_LOAD's alignment).
    """
    # Map ph -> its position in load_phdrs (O(1) lookup).
    load_pos = {id(ph): i for i, ph in enumerate(load_phdrs)}

    # Snapshot original offsets/fileszs BEFORE mutating p_offset below —
    # otherwise the delta computed in the second loop is always 0 because
    # orig_ph["p_offset"] would already equal new_off.
    orig_offsets = [ph["p_offset"] for ph in load_phdrs]
    orig_fileszs = [ph["p_filesz"] for ph in load_phdrs]

    # Update PT_LOAD entries first. p_vaddr / p_paddr are untouched.
    for ph in load_phdrs:
        new_off = new_offsets[load_pos[id(ph)]]
        ph["p_offset"] = new_off
        ph["p_align"] = PAGE_16K

    # Snapshot non-PT_LOAD phdrs' original offsets too. The range check
    # below must always test the ORIGINAL position, never a value already
    # shifted by an earlier iteration of this loop — otherwise a phdr can
    # get double-shifted if its new offset happens to coincidentally fall
    # inside another PT_LOAD's original range.
    non_load_orig_offsets = {
        id(ph): ph["p_offset"] for ph in phdrs if ph["p_type"] != PT_LOAD
    }

    # Update non-PT_LOAD phdrs that sit inside a moved PT_LOAD's range.
    # Only p_offset shifts; p_vaddr / p_paddr are left exactly as-is.
    for orig_off, orig_filesz, new_off in zip(
        orig_offsets,
        orig_fileszs,
        new_offsets,
        strict=True,
    ):
        delta = new_off - orig_off
        if delta == 0:
            continue
        orig_start = orig_off
        orig_end = orig_start + orig_filesz
        for ph in phdrs:
            if ph["p_type"] == PT_LOAD:
                continue
            po = non_load_orig_offsets[id(ph)]
            if orig_start <= po < orig_end:
                ph["p_offset"] = po + delta


def _write_program_headers(out: bytearray, phdrs: list[dict], e_phoff: int, e_phentsize: int) -> None:
    for ph in phdrs:
        off = e_phoff + ph["idx"] * e_phentsize
        struct.pack_into(
            "<IIQQQQQQ",
            out,
            off,
            ph["p_type"],
            ph["p_flags"],
            ph["p_offset"],
            ph["p_vaddr"],
            ph["p_paddr"],
            ph["p_filesz"],
            ph["p_memsz"],
            ph["p_align"],
        )


def realign(buf: bytearray, phdrs: list[dict], e_phoff: int, e_phentsize: int) -> bytes:
    """Rewrite the ELF so every PT_LOAD segment is 16 KB-aligned.

    Strategy:
      1. Start by copying the entire original file into `out`. This
         preserves non-PT_LOAD bytes (PT_DYNAMIC blobs, .note blobs,
         .eh_frame blobs, .dynsym, .dynstr, etc.) at their original
         file offsets — phdrs that point at them by p_offset stay valid.
      2. For each PT_LOAD, splice its relocated data into the right
         place: bytes that were at buf[orig_off:orig_end] now live at
         out[new_off:new_off+filesz]. The original location is left as
         stale bytes that the linker never reads (because phdr p_offsets
         for non-PT_LOAD entries inside the moved range are shifted
         in lockstep by _apply_load_offsets).
      3. Rewrite the program header table and any moved section headers.

    Non-PT_LOAD phdrs that sit inside a moved PT_LOAD get their
    p_offset shifted by the same delta — see _apply_load_offsets. Their
    p_vaddr is untouched, same as their parent PT_LOAD's; the address
    space never moves, only where the bytes live in the file. This is
    what keeps PT_DYNAMIC, PT_NOTE, etc. pointing at the right bytes
    after relocation.
    """
    load_phdrs = [ph for ph in phdrs if ph["p_type"] == PT_LOAD]
    phdr_table_end = e_phoff + e_phentsize * len(phdrs)

    # For each PT_LOAD, grab the source bytes to copy to its new
    # location (see below for the first-PT_LOAD-offset>0 bail-out).
    segment_data: list[bytes] = []
    for ph in load_phdrs:
        data = bytes(buf[ph["p_offset"] : ph["p_offset"] + ph["p_filesz"]])
        if ph is load_phdrs[0] and ph["p_offset"] > 0:
            # Prepending bytes here would grow p_filesz/p_memsz without a
            # matching change to p_vaddr, desyncing the segment's file
            # content from its (deliberately unchanged) virtual-address
            # range — the same class of bug this rewrite exists to fix.
            # Every prebuilt .so seen in this repo has its first PT_LOAD
            # at file offset 0, so this is a defensive bail-out rather
            # than a supported path.
            fatal(
                "first PT_LOAD has p_offset > 0 "
                f"(0x{ph['p_offset']:x}); unsupported layout for the "
                "vaddr-preserving realigner",
                3,
            )
        segment_data.append(data)

    new_offsets = _compute_new_load_offsets(load_phdrs)
    last_end = new_offsets[-1] + len(segment_data[-1]) if load_phdrs else 0

    # Section header table (if present) goes after the last segment,
    # aligned to 8 bytes per the ELF64 spec.
    e_shoff = struct.unpack_from("<Q", buf, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", buf, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", buf, 0x3C)[0]
    sh_size = e_shentsize * e_shnum if e_shnum else 0
    new_shoff = ((last_end + 7) // 8) * 8 if sh_size else 0

    # Initialize the output buffer by COPYING THE ENTIRE ORIGINAL FILE.
    # This preserves all non-PT_LOAD bytes (PT_DYNAMIC blobs, .note
    # blobs, .eh_frame blobs, .dynsym/.dynstr tables, etc.) at their
    # original file offsets so phdrs that point at them by p_offset stay
    # valid. We then overwrite the PT_LOAD regions with their relocated
    # data, and _apply_load_offsets updates any phdrs that point inside
    # a moved PT_LOAD. The end size is whichever is bigger.
    out = bytearray(buf)
    if new_shoff + sh_size > len(out):
        out.extend(b"\x00" * (new_shoff + sh_size - len(out)))
    elif len(out) < phdr_table_end:
        out.extend(b"\x00" * (phdr_table_end - len(out)))

    # Overwrite each PT_LOAD's data at its new offset. The original
    # location still holds the stale bytes — they don't matter because
    # _apply_load_offsets shifts any phdrs that pointed there.
    for data, new_off in zip(segment_data, new_offsets, strict=True):
        if new_off + len(data) > len(out):
            out.extend(b"\x00" * (new_off + len(data) - len(out)))
        out[new_off : new_off + len(data)] = data
    _copy_section_headers(out, buf, e_shoff, sh_size, new_shoff)

    _apply_load_offsets(phdrs, load_phdrs, new_offsets)
    _write_program_headers(out, phdrs, e_phoff, e_phentsize)

    if sh_size:
        struct.pack_into("<Q", out, 0x28, new_shoff)
    return bytes(out)


def write_atomic(path: Path, new_bytes: bytes) -> None:
    """Write new_bytes to path via a sibling tempfile + os.replace().

    A crash mid-write leaves the original file intact instead of a
    half-written corrupt one.
    """
    tmp_fd, tmp_path = tempfile.mkstemp(
        prefix=path.name + ".", suffix=".tmp", dir=path.parent
    )
    try:
        with os.fdopen(tmp_fd, "wb") as tmp_file:
            tmp_file.write(new_bytes)
            tmp_file.flush()
            os.fsync(tmp_file.fileno())
        os.replace(tmp_path, path)
    except OSError as exc:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        fatal(f"write failed: {exc}", 4)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: realign-16kb.py <path-to-shared-object>", file=sys.stderr)
        return 1

    path = Path(argv[1])
    buf = read_elf(path)
    e_phoff, e_phentsize, e_phnum, _e_shoff, _e_shentsize, _e_shnum = parse_ehdr(buf)
    phdrs = parse_phdrs(buf, e_phoff, e_phentsize, e_phnum)

    if is_aligned(phdrs):
        # Idempotent: already 16 KB-aligned. Print nothing on success.
        return 0

    new_bytes = realign(bytearray(buf), phdrs, e_phoff, e_phentsize)
    write_atomic(path, new_bytes)
    n_load = sum(1 for p in phdrs if p["p_type"] == PT_LOAD)
    print(
        f"realign-16kb: realigned {path.name} "
        f"({len(buf)} -> {len(new_bytes)} bytes, "
        f"{n_load} PT_LOAD segments)",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))