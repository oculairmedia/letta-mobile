#!/usr/bin/env python3
"""Fail when the Letta Web production bundle grows past its budget (letta-mobile-hv54v).

Every visitor downloads these files before the first frame (the Iroh transport on the first
connect), so their compressed size is what the page costs on the network. The budget file
(android-compose/web/bundle-budget.json) holds a gzip ceiling per asset group; a ceiling only
ever comes down. Raw sizes are reported too, since the browser compiles the raw wasm.

Usage: web-bundle-budget.py <dist-dir> [--budget FILE] [--summary-md FILE]
"""

from __future__ import annotations

import argparse
import fnmatch
import gzip
import json
import sys
from dataclasses import dataclass
from pathlib import Path

DEFAULT_BUDGET = Path(__file__).resolve().parents[2] / "android-compose" / "web" / "bundle-budget.json"


@dataclass(frozen=True)
class GroupSize:
    name: str
    files: tuple[str, ...]
    raw_bytes: int
    gzip_bytes: int
    ceiling_gzip_bytes: int

    @property
    def over_budget(self) -> bool:
        return self.gzip_bytes > self.ceiling_gzip_bytes


def gzip_size(data: bytes) -> int:
    # Level 9 with a fixed mtime, so the number is reproducible run to run.
    return len(gzip.compress(data, compresslevel=9, mtime=0))


def matches(path: str, pattern: str) -> bool:
    # A pattern names files at its own depth: "*.wasm" is the page's root, not iroh/*.wasm.
    return path.count("/") == pattern.count("/") and fnmatch.fnmatchcase(path, pattern)


def measure(dist: Path, budget: dict) -> list[GroupSize]:
    files = sorted(p.relative_to(dist).as_posix() for p in dist.rglob("*") if p.is_file())
    sizes = []
    for name, group in budget["groups"].items():
        matched = tuple(f for f in files if any(matches(f, pattern) for pattern in group["include"]))
        if not matched:
            raise SystemExit(f"bundle group {name!r} matched no file under {dist} (patterns {group['include']})")
        raw = gz = 0
        for rel in matched:
            data = (dist / rel).read_bytes()
            raw += len(data)
            gz += gzip_size(data)
        sizes.append(GroupSize(name, matched, raw, gz, int(group["ceiling_gzip_bytes"])))
    return sizes


def render_markdown(sizes: list[GroupSize]) -> str:
    def kib(n: int) -> str:
        return f"{n / 1024:,.1f} KiB"

    lines = [
        "## Letta Web bundle budget",
        "",
        "| Group | Files | Raw | Gzip | Ceiling (gzip) | Status |",
        "|---|---:|---:|---:|---:|---|",
    ]
    for s in sizes:
        status = "over budget" if s.over_budget else "ok"
        lines.append(
            f"| {s.name} | {len(s.files)} | {kib(s.raw_bytes)} | {kib(s.gzip_bytes)} | {kib(s.ceiling_gzip_bytes)} | {status} |"
        )
    total_raw = sum(s.raw_bytes for s in sizes)
    total_gz = sum(s.gzip_bytes for s in sizes)
    lines.append(f"| **total** | {sum(len(s.files) for s in sizes)} | {kib(total_raw)} | {kib(total_gz)} | | |")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("dist", type=Path)
    parser.add_argument("--budget", type=Path, default=DEFAULT_BUDGET)
    parser.add_argument("--summary-md", type=Path)
    args = parser.parse_args(argv)

    budget = json.loads(args.budget.read_text(encoding="utf-8"))
    sizes = measure(args.dist, budget)
    report = render_markdown(sizes)
    print(report)
    if args.summary_md:
        args.summary_md.parent.mkdir(parents=True, exist_ok=True)
        args.summary_md.write_text(report, encoding="utf-8")

    over = [s for s in sizes if s.over_budget]
    for s in over:
        print(
            f"::error title=Web bundle over budget::{s.name} is {s.gzip_bytes} gzip bytes, "
            f"ceiling {s.ceiling_gzip_bytes} ({', '.join(s.files)})",
            file=sys.stderr,
        )
    return 1 if over else 0


if __name__ == "__main__":
    sys.exit(main())
