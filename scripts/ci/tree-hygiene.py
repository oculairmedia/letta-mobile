#!/usr/bin/env python3
"""Reject new misplaced files and additions to the shrink-only tree baseline."""
from __future__ import annotations

import argparse
import posixpath
import re
import subprocess
import sys
from pathlib import Path

from git_ref import resolve_commit

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
    workflow = path.startswith(".github/workflows/") and name.endswith((".yml", ".yaml"))
    makefile = name in {"Makefile", "makefile", "GNUmakefile"}
    build_or_script = name.endswith((".gradle", ".gradle.kts", *SCRIPT_SUFFIXES))
    return workflow or makefile or build_or_script


def contains_reference(candidate: str, content: str) -> bool:
    # Avoid a basename hit embedded in a different path or a larger filename.
    pattern = r"(?<![\w./-])" + re.escape(candidate) + r"(?![\w./-])"
    return bool(re.search(pattern, content))


def source_references(path: str, source: str, content: str) -> bool:
    if source == path:
        return False
    relative = posixpath.relpath(path, posixpath.dirname(source) or ".")
    candidates = (path, relative, "./" + relative)
    explicit = any(contains_reference(candidate, content) for candidate in candidates)
    rooted = re.search(ROOT_VARIABLE + re.escape(path) + r"(?![\w./-])", content)
    return explicit or bool(rooted)


def referenced(path: str, sources: dict[str, str]) -> bool:
    """Accept an explicit repo-relative path or a path relative to the referencing file."""
    return any(source_references(path, source, content) for source, content in sources.items())


def is_new_dot_dir(path: str, current: set[str]) -> bool:
    parts = path.split("/")
    if len(parts) <= 1:
        return False
    directory = parts[0]
    if not directory.startswith("."):
        return False
    if directory in ALWAYS_DOT_DIRS:
        return False
    return f"dot {directory}" not in current


def is_misplaced_markdown(path: str, current: set[str]) -> bool:
    if not path.endswith(".md"):
        return False
    if path.startswith("docs/"):
        return False
    if path in ROOT_MARKDOWN:
        return False
    if path.startswith("android-compose/"):
        if path.split("/")[-1] in MODULE_MARKDOWN:
            return False
    return f"md {path}" not in current


def is_unreferenced_script(path: str, current: set[str], sources: dict[str, str]) -> bool:
    if not path.endswith(SCRIPT_SUFFIXES):
        return False
    if path.startswith(("scripts/", "tools/")):
        return False
    if f"script {path}" in current:
        return False
    return not referenced(path, sources)


def baseline_problems(root: Path, sha: str, current: set[str]) -> list[str]:
    old = subprocess.run(["git", "-C", str(root), "show", f"{sha}:{BASELINE}"], capture_output=True)
    if old.returncode:
        return []
    return [f"baseline entry added: {entry}" for entry in sorted(current - entries(old.stdout.decode()))]


def reference_sources(root: Path) -> dict[str, str]:
    paths = set(git(root, "ls-files", "--cached", "--others", "--exclude-standard", "-z").decode().split("\0"))
    return {
        path: (root / path).read_text(encoding="utf-8", errors="replace")
        for path in paths if is_readable_source(root, path)
    }


def is_readable_source(root: Path, path: str) -> bool:
    if not path:
        return False
    if not is_reference_source(path):
        return False
    return (root / path).is_file()


def path_problems(path: str, current: set[str], sources: dict[str, str]) -> list[str]:
    rules = (
        (is_new_dot_dir(path, current), "new top-level dot-directory"),
        (is_misplaced_markdown(path, current), "misplaced Markdown"),
        (is_unreferenced_script(path, current, sources), "unreferenced script outside scripts/ and tools/"),
    )
    return [f"{message}: {path}" for matches, message in rules if matches]


def check(root: Path, base: str) -> list[str]:
    sha = resolve_commit(root, base)
    current = entries((root / BASELINE).read_text(encoding="utf-8"))
    problems = baseline_problems(root, sha, current)
    added = set(git(root, "diff", "--no-renames", "--diff-filter=A", "--name-only", "-z", sha, "--").decode().split("\0"))
    added.update(git(root, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0"))
    sources = reference_sources(root)
    for path in sorted(added - {""}):
        if (root / path).is_file():
            problems.extend(path_problems(path, current, sources))
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
