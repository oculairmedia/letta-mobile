#!/usr/bin/env python3
"""Shrink-only ratchet for the guardrail baselines (letta-mobile-ewtaq, letta-mobile-ojnvp).

The gates (parity_gates.py, platform-ui-gate.sh, the duplicate-code scan) compare the tree with a
committed baseline. This guard compares each baseline with the PR's base so a baseline can only
move one way. There is no override: no label, PR-body line or environment variable relaxes it.

scripts/ci/ratchet-baselines.txt lists the baselines, one `<kind> <path>` per line:
  set        `<key> [| reason]` lines; a key may be removed, never added.
  count      `<key> <n> ...` lines; a key may be removed or its n lowered, never added or raised.
  grow-only  lines may be added, never removed or changed (the list of things that must be bound).
  detekt     XML <ID> values in both issue sections; IDs may be removed, never added.
A non-detekt baseline listed for the first time is seeded by this change. Detekt baselines are
always compared with the base, even when first listed; a new file must contain no IDs, including
in a newly added module. Every android-compose/**/detekt-baseline.xml must be listed as detekt.
A baseline listed at the base may not be dropped from the list.

Usage: baseline-ratchet-guard.py --diff-base REF [--root DIR] [--config FILE]
Exit status: 0 clean, 1 a baseline moved the wrong way, 2 usage or git error.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from typing import Callable
from xml.etree import ElementTree

DEFAULT_CONFIG = "scripts/ci/ratchet-baselines.txt"
DETEKT_KIND = "detekt"
DETEKT_BASELINE_GLOB = "android-compose/**/detekt-baseline.xml"
FIX = {
    "set": "Fix the code instead (bind the slot, implement the shared type, move it to sharedLogic/sharedUI); never add a line.",
    "count": "Fix the code instead (move the logic to sharedLogic/sharedUI, delete the duplicate); never add or raise a line.",
    "grow-only": "Restore the line: these must stay enforced. Retire one only by deleting the thing it names from shared code.",
    DETEKT_KIND: "Fix the detekt finding instead; never add a baseline ID (new baseline files must contain no IDs, even in new modules).",
}


def entries(text: str) -> list[str]:
    return [line.rstrip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]


def _config_row(line: str) -> tuple[str, str]:
    parts = line.split()
    if len(parts) != 2 or parts[0] not in FIX:
        raise ValueError(f"malformed ratchet config line: '{line}' (want '<{'|'.join(FIX)}> <path>')")
    return parts[1], parts[0]


def parse_config(text: str) -> dict[str, str]:
    """{baseline path: kind}."""
    return dict(map(_config_row, entries(text)))


def _set_key(line: str) -> str:
    return " ".join(line.partition("|")[0].split())


def _count_row(line: str, name: str) -> tuple[str, int]:
    parts = line.split()
    if len(parts) < 2 or not parts[1].lstrip("-").isdigit():
        raise ValueError(f"{name}: malformed count line '{line}' (want '<key> <n> ...')")
    return parts[0], int(parts[1])


def _compare_set(name: str, base: list[str], head: list[str]) -> list[str]:
    added = set(map(_set_key, head)) - set(map(_set_key, base))
    return [f"{name}: line added: {key}" for key in sorted(added)]


def _compare_count(name: str, base: list[str], head: list[str]) -> list[str]:
    before = dict(_count_row(line, name) for line in base)
    after = dict(_count_row(line, name) for line in head)
    added = [f"{name}: line added: {key} {n}" for key, n in sorted(after.items()) if key not in before]
    raised = [f"{name}: raised {key} {before[key]} -> {n}" for key, n in sorted(after.items()) if n > before.get(key, n)]
    return added + raised


def _compare_grow_only(name: str, base: list[str], head: list[str]) -> list[str]:
    kept = {" ".join(line.split()) for line in head}
    return [f"{name}: line removed or changed: {line.strip()}" for line in base if " ".join(line.split()) not in kept]


def _detekt_ids(name: str, lines: list[str]) -> set[str]:
    try:
        baseline = ElementTree.fromstring("\n".join(lines))
    except ElementTree.ParseError as error:
        raise ValueError(f"{name}: malformed detekt baseline XML: {error}") from None
    return {(element.text or "").strip() for element in baseline.iter("ID")}


def _compare_detekt(name: str, base: list[str], head: list[str]) -> list[str]:
    before = _detekt_ids(name, base) if base else set()
    added = _detekt_ids(name, head) - before
    return [f"{name}: detekt ID added: {key}" for key in sorted(added)]


COMPARE: dict[str, Callable[[str, list[str], list[str]], list[str]]] = {
    "set": _compare_set,
    "count": _compare_count,
    "grow-only": _compare_grow_only,
    DETEKT_KIND: _compare_detekt,
}


def compare(kind: str, name: str, base: list[str], head: list[str]) -> list[str]:
    return COMPARE[kind](name, base, head)


_REF = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/@{}^~-]*")
_SHA = re.compile(r"[0-9a-f]{40}")
_REPO_PATH = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_./-]*")


def safe_path(path: str) -> str:
    """A repo-relative path from the config: no option-like, absolute or parent-escaping names."""
    if not _REPO_PATH.fullmatch(path) or ".." in path.split("/"):
        raise ValueError(f"unsafe baseline path '{path}'")
    return path


def resolve_commit(root: Path, ref: str) -> str | None:
    """The full SHA [ref] names, or None; only a SHA is ever passed on to git afterwards."""
    if not _REF.fullmatch(ref):
        return None
    result = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "--verify", "-q", "--end-of-options", f"{ref}^{{commit}}"],
        capture_output=True, text=True, encoding="utf-8",
    )
    sha = result.stdout.strip()
    return sha if result.returncode == 0 and _SHA.fullmatch(sha) else None


def git_show(root: Path, sha: str, path: str) -> str | None:
    if not _SHA.fullmatch(sha):
        raise ValueError(f"not a commit sha: '{sha}'")
    spec = f"{sha}:{safe_path(path)}"
    result = subprocess.run(["git", "-C", str(root), "show", "--end-of-options", spec], capture_output=True, text=True, encoding="utf-8")
    return result.stdout if result.returncode == 0 else None


def dropped_ratchets(config_name: str, base_config: dict[str, str], config: dict[str, str]) -> list[str]:
    dropped = [f"{config_name}: ratchet dropped: {kind} {path} - restore it ({FIX['grow-only']})" for path, kind in sorted(base_config.items()) if path not in config]
    changed = [
        f"{config_name}: ratchet kind changed for {path}: {kind} -> {config[path]}"
        for path, kind in sorted(base_config.items())
        if config.get(path, kind) != kind
    ]
    return dropped + changed


def baseline_drift(root: Path, ref: str, path: str, kind: str) -> list[str]:
    current = root / safe_path(path)
    if not current.is_file():
        return [f"{path}: listed in the ratchet config but missing - restore it"]
    base_text = git_show(root, ref, path)
    if base_text is None and kind != DETEKT_KIND:
        print(f"baseline-ratchet-guard: {path} is seeded by this change ({kind})")
        return []
    found = compare(kind, path, entries(base_text or ""), entries(current.read_text(encoding="utf-8")))
    return [f"{p}\n      -> {FIX[kind]}" for p in found]


def problems_for(root: Path, ref: str, config_name: str) -> tuple[list[str], int]:
    config = parse_config((root / config_name).read_text(encoding="utf-8"))
    base_config = parse_config(git_show(root, ref, config_name) or "")
    problems = dropped_ratchets(config_name, base_config, config)
    for current in sorted(root.glob(DETEKT_BASELINE_GLOB)):
        path = current.relative_to(root).as_posix()
        if config.get(path) != DETEKT_KIND:
            problems.append(f"{path}: detekt baseline must be listed as '{DETEKT_KIND} {path}' in {config_name}")
    for path, kind in sorted(config.items()):
        if path in base_config or kind == DETEKT_KIND:
            problems += baseline_drift(root, ref, path, kind)
        else:
            print(f"baseline-ratchet-guard: {path} is seeded by this change ({kind})")
    return problems, len(config)


def report(problems: list[str], count: int, ref: str) -> int:
    if not problems:
        print(f"baseline-ratchet-guard: ok ({count} baseline(s) vs {ref})")
        return 0
    print(f"::error title=baseline-ratchet-guard::{len(problems)} baseline line(s) moved the wrong way vs {ref}")
    print("baseline-ratchet-guard: FAILED - these baselines only shrink (there is no override):")
    print("\n".join(f"  {p}" for p in problems))
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--diff-base", required=True)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--config", default=DEFAULT_CONFIG)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    base = resolve_commit(root, args.diff_base)
    if base is None:
        print(f"baseline-ratchet-guard: base '{args.diff_base}' is not a commit", file=sys.stderr)
        return 2
    try:
        problems, count = problems_for(root, base, safe_path(args.config))
    except ValueError as error:
        print(f"baseline-ratchet-guard: {error}", file=sys.stderr)
        return 2
    return report(problems, count, args.diff_base)


if __name__ == "__main__":
    sys.exit(main())
