#!/usr/bin/env python3
"""Shrink-only ratchet for the parity guardrail baselines (letta-mobile-ewtaq).

The gates (parity_gates.py, platform-ui-gate.sh, the duplicate-code scan) compare the tree with a
committed baseline. This guard compares each baseline with the PR's base so a baseline can only
move one way. There is no override: no label, PR-body line or environment variable relaxes it.

scripts/ci/ratchet-baselines.txt lists the baselines, one `<kind> <path>` per line:
  set        `<key> [| reason]` lines; a key may be removed, never added.
  count      `<key> <n> ...` lines; a key may be removed or its n lowered, never added or raised.
  grow-only  lines may be added, never removed or changed (the list of things that must be bound).
A baseline listed for the first time in this change is seeded by it (nothing to compare against);
a baseline listed at the base may not be dropped from the list.

Usage: baseline-ratchet-guard.py --diff-base REF [--root DIR] [--config FILE]
Exit status: 0 clean, 1 a baseline moved the wrong way, 2 usage or git error.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

DEFAULT_CONFIG = "scripts/ci/ratchet-baselines.txt"
KINDS = ("set", "count", "grow-only")
FIX = {
    "set": "Fix the code instead (bind the slot, implement the shared type, move it to sharedLogic/sharedUI); never add a line.",
    "count": "Fix the code instead (move the logic to sharedLogic/sharedUI, delete the duplicate); never add or raise a line.",
    "grow-only": "Restore the line: these must stay enforced. Retire one only by deleting the thing it names from shared code.",
}


def entries(text: str) -> list[str]:
    return [line.rstrip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]


def parse_config(text: str) -> dict[str, str]:
    config = {}
    for line in entries(text):
        parts = line.split()
        if len(parts) != 2 or parts[0] not in KINDS:
            raise ValueError(f"malformed ratchet config line: '{line}' (want '<{'|'.join(KINDS)}> <path>')")
        config[parts[1]] = parts[0]
    return config


def set_keys(lines: list[str]) -> set[str]:
    return {" ".join(line.partition("|")[0].split()) for line in lines}


def counts(lines: list[str], name: str) -> dict[str, int]:
    out = {}
    for line in lines:
        parts = line.split()
        if len(parts) < 2 or not parts[1].lstrip("-").isdigit():
            raise ValueError(f"{name}: malformed count line '{line}' (want '<key> <n> ...')")
        out[parts[0]] = int(parts[1])
    return out


def compare(kind: str, name: str, base: list[str], head: list[str]) -> list[str]:
    problems = []
    if kind == "set":
        for key in sorted(set_keys(head) - set_keys(base)):
            problems.append(f"{name}: line added: {key}")
    elif kind == "count":
        base_counts, head_counts = counts(base, name), counts(head, name)
        for key, n in sorted(head_counts.items()):
            if key not in base_counts:
                problems.append(f"{name}: line added: {key} {n}")
            elif n > base_counts[key]:
                problems.append(f"{name}: raised {key} {base_counts[key]} -> {n}")
    else:
        normalized_head = {" ".join(line.split()) for line in head}
        for line in base:
            if " ".join(line.split()) not in normalized_head:
                problems.append(f"{name}: line removed or changed: {line.strip()}")
    return problems


def git_show(root: Path, ref: str, path: str) -> str | None:
    result = subprocess.run(["git", "-C", str(root), "show", f"{ref}:{path}"], capture_output=True, text=True, encoding="utf-8")
    return result.stdout if result.returncode == 0 else None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--diff-base", required=True)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--config", default=DEFAULT_CONFIG)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    if subprocess.run(["git", "-C", str(root), "rev-parse", "--verify", "-q", f"{args.diff_base}^{{commit}}"], capture_output=True).returncode:
        print(f"baseline-ratchet-guard: base '{args.diff_base}' is not a commit", file=sys.stderr)
        return 2
    try:
        config = parse_config((root / args.config).read_text(encoding="utf-8"))
        base_config_text = git_show(root, args.diff_base, args.config)
        base_config = parse_config(base_config_text) if base_config_text is not None else {}
        problems = []
        for path, kind in sorted(base_config.items()):
            if path not in config:
                problems.append(f"{args.config}: ratchet dropped: {kind} {path} - restore it ({FIX['grow-only']})")
            elif config[path] != kind:
                problems.append(f"{args.config}: ratchet kind changed for {path}: {kind} -> {config[path]}")
        for path, kind in sorted(config.items()):
            current = root / path
            if not current.is_file():
                problems.append(f"{path}: listed in {args.config} but missing - restore it")
                continue
            base_text = git_show(root, args.diff_base, path) if path in base_config else None
            if base_text is None:
                print(f"baseline-ratchet-guard: {path} is seeded by this change ({kind})")
                continue
            found = compare(kind, path, entries(base_text), entries(current.read_text(encoding="utf-8")))
            problems += [f"{p}\n      -> {FIX[kind]}" for p in found]
    except ValueError as error:
        print(f"baseline-ratchet-guard: {error}", file=sys.stderr)
        return 2
    if problems:
        print(f"::error title=baseline-ratchet-guard::{len(problems)} baseline line(s) moved the wrong way vs {args.diff_base}")
        print("baseline-ratchet-guard: FAILED - these baselines only shrink (there is no override):")
        for p in problems:
            print(f"  {p}")
        return 1
    print(f"baseline-ratchet-guard: ok ({len(config)} baseline(s) vs {args.diff_base})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
