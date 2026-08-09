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
#   2. If every PT_LOAD already satisfies `off % 0x4000 == 0` AND has
#      `p_align >= 0x4000`, exit 0 without writing (idempotent).
#   3. Otherwise, for each PT_LOAD in order:
#        - The first segment keeps offset 0 (it always is for a PIE .so).
#        - Each subsequent segment's new offset is the smallest multiple of
#          0x4000 >= the previous segment's new end. This guarantees the
#          segment data sits immediately after the previous segment, padded
#          with zero bytes to the next 16 KB boundary.
#      The new vaddr/paddr match the new offset (PIE .so convention).
#   4. Shift each PT_LOAD's data to its new offset.
#   5. Update section header table offsets to track segment moves.
#   6. Rewrite program headers with new offsets + p_align = 0x4000.
#
# Why not lld / -Wl,-z,max-page-size=16384?
#   The iroh-ffi Rust source is not vendored in this repository, so we
#   cannot relink from scratch. The Nimbus JOSE/JWT jar ships only a
#   prebuilt libjnidispatch.so with no source. Post-processing the ELF in
#   place is the only option that keeps the dependency graph unchanged.
#   Chromium and Flutter use the same trick for the same problem.
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
from pathlib import Path

# 64-bit ELF, little-endian (the only ABI Android cares about for arm64-v8a).
ELFCLASS64 = 2
ELFDATA2LSB = 1
ET_DYN = 3  # Shared object (PIE .so)
PT_LOAD = 1
PAGE_16K = 0x4000

# ELF64 sizes (bytes).
ELF64_EHDR_SIZE = 64
ELF64_PHDR_SIZE = 56
SHDR_SIZE = 64


def fatal(msg: str, code: int) -> "NoReturn":  # type: ignore[name-defined]
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
    # e_ident(16) + e_type(2) + e_machine(2) + e_version(4)
    # + e_entry(8) + e_phoff(8) + e_shoff(8) + e_flags(4)
    # + e_ehsize(2) + e_phentsize(2) + e_phnum(2) + e_shentsize(2)
    # + e_shnum(2) + e_shstrndx(2)  = 16+2+2+4+8+8+8+4+2+2+2+2+2+2 = 64.
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
        if ph["p_offset"] % PAGE_16K != 0:
            return False
        if ph["p_align"] < PAGE_16K:
            return False
    return True


def realign(buf: bytearray, phdrs: list[dict], e_phoff: int, e_phentsize: int) -> bytes:
    # Walk PT_LOADs in file order. For each, compute a new offset:
    #   - first PT_LOAD: keeps offset 0 (it's always 0 for a PIE .so).
    #   - each subsequent PT_LOAD: smallest 0x4000-multiple >= the previous
    #     PT_LOAD's new end.
    load_phdrs = [ph for ph in phdrs if ph["p_type"] == PT_LOAD]

    new_offsets: list[int] = []
    prev_end = 0
    for i, ph in enumerate(load_phdrs):
        if i == 0:
            new_off = 0
        else:
            new_off = ((prev_end + PAGE_16K - 1) // PAGE_16K) * PAGE_16K
        new_offsets.append(new_off)
        prev_end = new_off + ph["p_filesz"]

    # Determine new file size: section header table goes after the last
    # segment, aligned to 8 bytes (ELF64 requirement).
    e_shoff = struct.unpack_from("<Q", buf, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", buf, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", buf, 0x3C)[0]
    sh_size = e_shentsize * e_shnum if e_shnum else 0
    new_shoff = ((prev_end + 7) // 8) * 8
    new_size = new_shoff + sh_size

    out = bytearray(new_size)

    # Copy each PT_LOAD's data to its new offset. The gaps between segments
    # are left as zero bytes (bytearray default).
    for ph, new_off in zip(load_phdrs, new_offsets):
        out[new_off : new_off + ph["p_filesz"]] = buf[
            ph["p_offset"] : ph["p_offset"] + ph["p_filesz"]
        ]

    # Copy section headers if present.
    if sh_size and e_shoff and e_shoff + sh_size <= len(buf):
        out[new_shoff : new_shoff + sh_size] = buf[e_shoff : e_shoff + sh_size]

    # Update program headers: only the loadable PT_LOAD entries change;
    # everything else (PT_DYNAMIC, PT_NOTE, PT_GNU_EH_FRAME, etc.) keeps
    # its original position + offset. This is safe because those non-load
    # segments live between the PT_LOADs and are addressed by file offset,
    # not virtual address.
    for ph in phdrs:
        if ph["p_type"] == PT_LOAD:
            load_idx = load_phdrs.index(ph)
            new_off = new_offsets[load_idx]
            ph["p_offset"] = new_off
            # PIE .so convention: p_vaddr == p_offset for every segment.
            ph["p_vaddr"] = new_off
            ph["p_paddr"] = new_off
            ph["p_align"] = PAGE_16K

    # Re-pack program headers in place.
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

    # Rewrite e_shoff to point at the moved section header table.
    if sh_size:
        struct.pack_into("<Q", out, 0x28, new_shoff)

    return bytes(out)


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
    try:
        path.write_bytes(new_bytes)
    except OSError as exc:
        fatal(f"write failed: {exc}", 4)
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