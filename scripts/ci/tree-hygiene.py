#!/usr/bin/env python3
"""Reject new misplaced files and additions to the shrink-only tree baseline."""
from __future__ import annotations

import argparse
import posixpath
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "scripts/ci/tree-hygiene-baseline.txt"
ALWAYS_DOT_DIRS = {".github", ".githooks", ".beads"}
ROOT_MARKDOWN = {"README.md", "CONTRIBUTING.md", "AGENTS.md"}
MODULE_MARKDOWN = {"README.md", "Module.md"}
SCRIPT_SUFFIXES = (".sh", ".py")
ROOT_VARIABLE = r"(?:\$\{[^}\n]+\}|\$[A-Za-z_]\w*)/"


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True)
    if result.returncode:
        raise ValueError(result.stderr.decode(errors="replace").strip())
    return result.stdout


def entries(text: str) -> set[str]:
    return {line.strip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")}


def is_reference_source(path: str) -> bool:
    name = posixpath.basename(path)
    return (path.startswith(".github/workflows/") and name.endswith((".yml", ".yaml"))) or name in {"Makefile", "makefile", "GNUmakefile"} or name.endswith((".gradle", ".gradle.kts", *SCRIPT_SUFFIXES))


def referenced(path: str, sources: dict[str, str]) -> bool:
    """Accept an explicit repo-relative path or a path relative to the referencing file."""
    for source, content in sources.items():
        if source == path:
            continue
        relative = posixpath.relpath(path, posixpath.dirname(source) or ".")
        for candidate in {path, relative, "./" + relative}:
            # Avoid a basename hit embedded in a different path or a larger filename.
            pattern = r"(?<![\w./-])" + re.escape(candidate) + r"(?![\w./-])"
            if re.search(pattern, content):
                return True
            if candidate == path and re.search(ROOT_VARIABLE + re.escape(path) + r"(?![\w./-])", content):
                return True
    return False


def check(root: Path, base: str) -> list[str]:
    sha = git(root, "rev-parse", "--verify", "--end-of-options", base + "^{commit}").decode().strip()
    current = entries((root / BASELINE).read_text(encoding="utf-8"))
    old = subprocess.run(["git", "-C", str(root), "show", f"{sha}:{BASELINE}"], capture_output=True)
    problems = []
    if old.returncode == 0:
        problems.extend(f"baseline entry added: {entry}" for entry in sorted(current - entries(old.stdout.decode())))
    added = set(git(root, "diff", "--no-renames", "--diff-filter=A", "--name-only", "-z", sha, "--").decode().split("\0"))
    added.update(git(root, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0"))
    paths = set(git(root, "ls-files", "--cached", "--others", "--exclude-standard", "-z").decode().split("\0"))
    sources = {}
    for path in paths:
        if path and is_reference_source(path) and (root / path).is_file():
            sources[path] = (root / path).read_text(encoding="utf-8", errors="replace")
    for path in sorted(added - {""}):
        if not (root / path).is_file():
            continue
        parts = path.split("/")
        if len(parts) > 1 and parts[0].startswith(".") and parts[0] not in ALWAYS_DOT_DIRS and f"dot {parts[0]}" not in current:
            problems.append(f"new top-level dot-directory: {path}")
        markdown_allowed = path.startswith("docs/") or path in ROOT_MARKDOWN or (path.startswith("android-compose/") and parts[-1] in MODULE_MARKDOWN)
        if path.endswith(".md") and not markdown_allowed and f"md {path}" not in current:
            problems.append(f"misplaced Markdown: {path}")
        if path.endswith(SCRIPT_SUFFIXES) and not path.startswith(("scripts/", "tools/")) and f"script {path}" not in current and not referenced(path, sources):
            problems.append(f"unreferenced script outside scripts/ and tools/: {path}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--diff-base", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        problems = check(args.root.resolve(), args.diff_base)
    except (OSError, ValueError) as error:
        print(f"tree-hygiene: {error}", file=sys.stderr)
        return 2
    for problem in problems:
        print(f"::error title=Tree hygiene::{problem}", file=sys.stderr)
    if not problems:
        print(f"tree-hygiene: ok vs {args.diff_base}")
    return int(bool(problems))


if __name__ == "__main__":
    sys.exit(main())
