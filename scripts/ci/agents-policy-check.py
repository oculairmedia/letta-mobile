#!/usr/bin/env python3
"""Advisory semantic lint for greppable AGENTS.md rules.

Exit codes:
  0 — always for successful runs (findings are advisory)
  1 — only on script / usage errors

Output: one finding per line
  SEVERITY|RULE|PATH:LINE|message
then a human summary with counts.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SCAN_ROOT = REPO_ROOT / "android-compose"

# ---------------------------------------------------------------------------
# Findings
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Finding:
    severity: str  # WARN | ERROR
    rule: str
    path: str  # repo-relative, forward slashes
    line: int
    message: str

    def format(self) -> str:
        return f"{self.severity}|{self.rule}|{self.path}:{self.line}|{self.message}"


# ---------------------------------------------------------------------------
# Path helpers
# ---------------------------------------------------------------------------


def repo_rel(path: Path) -> str:
    try:
        return path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def norm_parts(rel: str) -> list[str]:
    return [p for p in rel.replace("\\", "/").split("/") if p and p != "."]


def under_android_compose(rel: str) -> bool:
    parts = norm_parts(rel)
    return bool(parts) and parts[0] == "android-compose"


def module_segment(rel: str) -> str | None:
    """Return the top module under android-compose/ (e.g. app, feature-chat)."""
    parts = norm_parts(rel)
    if len(parts) < 2 or parts[0] != "android-compose":
        return None
    return parts[1]


def is_kotlin(path: Path) -> bool:
    return path.suffix == ".kt"


def basename_matches(name: str, patterns: Iterable[str]) -> bool:
    """Simple glob-ish match: exact name, or prefix*/suffix* wildcards only."""
    for pat in patterns:
        if "*" not in pat:
            if name == pat:
                return True
            continue
        if pat.startswith("*") and pat.endswith("*") and pat.count("*") == 2:
            mid = pat[1:-1]
            if mid and mid in name:
                return True
            continue
        if pat.startswith("*") and pat.count("*") == 1:
            if name.endswith(pat[1:]):
                return True
            continue
        if pat.endswith("*") and pat.count("*") == 1:
            if name.startswith(pat[:-1]):
                return True
            continue
    return False


# ---------------------------------------------------------------------------
# Diff mode: map path -> set of added/changed line numbers
# ---------------------------------------------------------------------------


def parse_unified_diff_added_lines(diff_text: str) -> dict[str, set[int]]:
    """Parse `git diff -U0` output into {repo-rel-path: {line_numbers}}."""
    result: dict[str, set[int]] = {}
    current: str | None = None
    new_line = 0

    hunk_re = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")

    for raw in diff_text.splitlines():
        if raw.startswith("+++ "):
            # +++ b/path or +++ /dev/null
            token = raw[4:].strip()
            if token == "/dev/null":
                current = None
                continue
            if token.startswith("b/"):
                token = token[2:]
            current = token.replace("\\", "/")
            result.setdefault(current, set())
            continue

        m = hunk_re.match(raw)
        if m:
            new_line = int(m.group(1))
            continue

        if current is None:
            continue

        if raw.startswith("+") and not raw.startswith("+++"):
            result.setdefault(current, set()).add(new_line)
            new_line += 1
        elif raw.startswith("-") and not raw.startswith("---"):
            # deleted line — does not advance new-file line counter
            continue
        elif raw.startswith("\\"):
            # "\ No newline at end of file"
            continue
        else:
            # context (shouldn't appear with -U0, but be safe)
            new_line += 1

    return result


def git_diff_added_lines(diff_base: str) -> dict[str, set[int]]:
    cmd = ["git", "diff", "-U0", "--no-color", f"{diff_base}...HEAD"]
    try:
        proc = subprocess.run(
            cmd,
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as exc:
        raise RuntimeError(f"failed to run git diff: {exc}") from exc

    if proc.returncode != 0:
        # Fallback without three-dot if merge-base unavailable
        cmd2 = ["git", "diff", "-U0", "--no-color", diff_base]
        proc2 = subprocess.run(
            cmd2,
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        if proc2.returncode != 0:
            err = (proc.stderr or proc2.stderr or "").strip()
            raise RuntimeError(
                f"git diff against {diff_base!r} failed: {err or 'unknown error'}"
            )
        return parse_unified_diff_added_lines(proc2.stdout)

    return parse_unified_diff_added_lines(proc.stdout)


# ---------------------------------------------------------------------------
# File discovery
# ---------------------------------------------------------------------------


def iter_kotlin_files(roots: list[Path]) -> Iterator[Path]:
    for root in roots:
        root = root.resolve()
        if root.is_file():
            if is_kotlin(root):
                yield root
            continue
        if not root.is_dir():
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            # Skip build outputs and VCS
            dirnames[:] = [
                d
                for d in dirnames
                if d not in {".git", "build", ".gradle", ".kotlin", "node_modules"}
            ]
            for name in filenames:
                if name.endswith(".kt"):
                    yield Path(dirpath) / name


# ---------------------------------------------------------------------------
# Rules
# ---------------------------------------------------------------------------

HEX_COLOR_RE = re.compile(r"\bColor\s*\(\s*0x[0-9A-Fa-f]+\s*\)")
JVM_APIS = (
    (re.compile(r"\bString\.format\s*\("), "String.format("),
    (re.compile(r"\bStringBuilder\.delete\s*\("), "StringBuilder.delete("),
    (re.compile(r"\.toByteArray\s*\("), ".toByteArray("),
)
ALERT_DIALOG_RE = re.compile(r"\bAlertDialog\s*\(")

HEX_ALLOW_BASENAMES = (
    "Color.kt",
    "Theme.kt",
    "ChatTheme.kt",
    "TypeHierarchy.kt",
    "CustomColors*",
    "*Tokens*",
    "*Palette*",
)


def allow_raw_hex(rel: str) -> bool:
    name = Path(rel).name
    if basename_matches(name, HEX_ALLOW_BASENAMES):
        return True
    # paths containing /theme/
    parts = norm_parts(rel)
    return "theme" in parts


def check_no_raw_hex_color(
    rel: str, lines: list[str], line_filter: set[int] | None
) -> list[Finding]:
    mod = module_segment(rel)
    if mod is None:
        return []
    in_scope = mod == "app" or mod == "designsystem" or mod.startswith("feature-")
    if not in_scope:
        return []
    if allow_raw_hex(rel):
        return []

    findings: list[Finding] = []
    for i, text in enumerate(lines, start=1):
        if line_filter is not None and i not in line_filter:
            continue
        if HEX_COLOR_RE.search(text):
            findings.append(
                Finding(
                    "WARN",
                    "no-raw-hex-color",
                    rel,
                    i,
                    "raw Color(0x...) — use theme/color roles instead of hardcoded hex",
                )
            )
    return findings


def check_sharedlogic_jvm_api(
    rel: str, lines: list[str], line_filter: set[int] | None
) -> list[Finding]:
    parts = norm_parts(rel)
    # sharedLogic/**/commonMain/**
    try:
        sl = parts.index("sharedLogic")
    except ValueError:
        return []
    if "commonMain" not in parts[sl + 1 :]:
        return []

    findings: list[Finding] = []
    for i, text in enumerate(lines, start=1):
        if line_filter is not None and i not in line_filter:
            continue
        for regex, label in JVM_APIS:
            if regex.search(text):
                findings.append(
                    Finding(
                        "WARN",
                        "sharedlogic-jvm-api",
                        rel,
                        i,
                        f"forbidden JVM-only API in commonMain: {label}",
                    )
                )
    return findings


def check_platform_repo_duplication(
    rel: str, line_filter: set[int] | None
) -> list[Finding]:
    """Warn on new/edited *Repository.kt under desktop/ or app/ (not sharedLogic).

    Simple basename heuristic for PR diffs — full scans skip this rule to avoid
    permanent noise from existing thin platform bindings.
    """
    # Only meaningful for new/edited files (diff mode).
    if line_filter is None:
        return []
    if not line_filter:
        return []

    parts = norm_parts(rel)
    if "sharedLogic" in parts:
        return []
    mod = module_segment(rel)
    if mod not in {"desktop", "app"}:
        return []
    name = Path(rel).name
    if not name.endswith("Repository.kt"):
        return []

    return [
        Finding(
            "WARN",
            "platform-repo-duplication",
            rel,
            min(line_filter),
            f"{mod} *Repository.kt outside sharedLogic — prefer sharedLogic + thin platform binding",
        )
    ]


def check_raw_alertdialog(
    rel: str, lines: list[str], line_filter: set[int] | None
) -> list[Finding]:
    mod = module_segment(rel)
    if mod is None:
        return []
    # designsystem allowed; only app/ and feature-*/
    if not (mod == "app" or mod.startswith("feature-")):
        return []

    findings: list[Finding] = []
    for i, text in enumerate(lines, start=1):
        if line_filter is not None and i not in line_filter:
            continue
        if ALERT_DIALOG_RE.search(text):
            findings.append(
                Finding(
                    "WARN",
                    "raw-alertdialog",
                    rel,
                    i,
                    "AlertDialog( in feature UI — prefer ConfirmDialog / designsystem wrappers",
                )
            )
    return findings


def scan_file(
    path: Path, line_filter: set[int] | None
) -> list[Finding]:
    rel = repo_rel(path)
    if not under_android_compose(rel) and not rel.startswith("android-compose"):
        # Still allow explicit paths outside default root if under android-compose
        pass

    try:
        content = path.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        raise RuntimeError(f"cannot read {rel}: {exc}") from exc

    lines = content.splitlines()
    findings: list[Finding] = []
    findings.extend(check_no_raw_hex_color(rel, lines, line_filter))
    findings.extend(check_sharedlogic_jvm_api(rel, lines, line_filter))
    findings.extend(check_platform_repo_duplication(rel, line_filter))
    findings.extend(check_raw_alertdialog(rel, lines, line_filter))
    return findings


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="agents-policy-check.py",
        description=(
            "Advisory semantic lint for AGENTS.md rules "
            "(raw hex colors, sharedLogic JVM APIs, platform Repository "
            "duplication, raw AlertDialog). Always exits 0 on success."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
examples:
  %(prog)s
  %(prog)s --diff-base origin/main
  %(prog)s android-compose/app android-compose/sharedLogic
""",
    )
    p.add_argument(
        "paths",
        nargs="*",
        help="Paths to scan (default: android-compose/). Relative to repo root.",
    )
    p.add_argument(
        "--diff-base",
        metavar="REF",
        default=None,
        help=(
            "Only check added/changed lines vs git REF "
            "(git diff -U0 REF...HEAD). Empty/omitted = full scan."
        ),
    )
    return p


def resolve_roots(path_args: list[str]) -> list[Path]:
    if not path_args:
        return [DEFAULT_SCAN_ROOT]
    roots: list[Path] = []
    for raw in path_args:
        p = Path(raw)
        if not p.is_absolute():
            p = REPO_ROOT / p
        roots.append(p)
    return roots


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    diff_base = args.diff_base
    if diff_base is not None:
        diff_base = diff_base.strip()
        if not diff_base:
            diff_base = None

    line_map: dict[str, set[int]] | None = None
    if diff_base:
        try:
            line_map = git_diff_added_lines(diff_base)
        except RuntimeError as exc:
            print(f"ERROR|script|-:|{exc}", file=sys.stderr)
            return 1

    roots = resolve_roots(args.paths)

    # If diff mode, restrict to changed kotlin files that intersect scan roots
    files: list[Path]
    if line_map is not None:
        root_resolved = [r.resolve() for r in roots]
        files = []
        for rel, lines in sorted(line_map.items()):
            if not lines:
                continue
            path = (REPO_ROOT / rel).resolve()
            if not is_kotlin(path):
                continue
            if not path.is_file():
                continue
            # Must be under at least one scan root
            if not any(
                path == r or r in path.parents or (r.is_file() and path == r)
                for r in root_resolved
            ):
                # Also accept when root is a parent dir
                ok = False
                for r in root_resolved:
                    try:
                        path.relative_to(r if r.is_dir() else r.parent)
                        ok = True
                        break
                    except ValueError:
                        continue
                if not ok:
                    continue
            files.append(path)
    else:
        files = sorted(set(iter_kotlin_files(roots)))

    all_findings: list[Finding] = []
    try:
        for path in files:
            rel = repo_rel(path)
            lf = line_map.get(rel) if line_map is not None else None
            # Also try path as stored in diff (already repo-rel)
            if line_map is not None and lf is None:
                # normalize keys
                for k, v in line_map.items():
                    if k.replace("\\", "/") == rel:
                        lf = v
                        break
            all_findings.extend(scan_file(path, lf))
    except RuntimeError as exc:
        print(f"ERROR|script|-:|{exc}", file=sys.stderr)
        return 1

    all_findings.sort(key=lambda f: (f.path, f.line, f.rule))

    for f in all_findings:
        print(f.format())

    by_rule: dict[str, int] = {}
    for f in all_findings:
        by_rule[f.rule] = by_rule.get(f.rule, 0) + 1

    mode = f"diff vs {diff_base}" if diff_base else "full scan"
    print()
    print(
        f"agents-policy-check: {len(all_findings)} finding(s) "
        f"({mode}, {len(files)} file(s) checked)"
    )
    if by_rule:
        for rule, count in sorted(by_rule.items()):
            print(f"  {rule}: {count}")
    else:
        print("  (no findings)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
