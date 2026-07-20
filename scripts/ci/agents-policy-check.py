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
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SCAN_ROOT = REPO_ROOT / "android-compose"
SKIP_DIRS = frozenset({".git", "build", ".gradle", ".kotlin", "node_modules"})

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
        stars = pat.count("*")
        if stars == 0:
            if name == pat:
                return True
            continue
        if stars == 2 and pat.startswith("*") and pat.endswith("*"):
            mid = pat[1:-1]
            if mid and mid in name:
                return True
            continue
        if stars == 1 and pat.startswith("*") and name.endswith(pat[1:]):
            return True
        if stars == 1 and pat.endswith("*") and name.startswith(pat[:-1]):
            return True
    return False


def is_sharedlogic_commonmain(rel: str) -> bool:
    parts = norm_parts(rel)
    try:
        idx = parts.index("sharedLogic")
    except ValueError:
        return False
    return "commonMain" in parts[idx + 1 :]


def is_hex_color_scope(mod: str) -> bool:
    return mod in {"app", "designsystem"} or mod.startswith("feature-")


def is_app_or_feature_module(mod: str) -> bool:
    return mod == "app" or mod.startswith("feature-")


# ---------------------------------------------------------------------------
# Line iteration helpers (shared by rule checks)
# ---------------------------------------------------------------------------


def iter_scoped_lines(
    lines: list[str], line_filter: set[int] | None
) -> Iterator[tuple[int, str]]:
    for lineno, text in enumerate(lines, start=1):
        if line_filter is not None and lineno not in line_filter:
            continue
        yield lineno, text


def warn_on_regex(
    rel: str,
    lines: list[str],
    line_filter: set[int] | None,
    pattern: re.Pattern[str],
    rule: str,
    message: str,
) -> list[Finding]:
    return [
        Finding("WARN", rule, rel, lineno, message)
        for lineno, text in iter_scoped_lines(lines, line_filter)
        if pattern.search(text)
    ]


# ---------------------------------------------------------------------------
# Diff mode: map path -> set of added/changed line numbers
# ---------------------------------------------------------------------------

_HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def parse_unified_diff_added_lines(diff_text: str) -> dict[str, set[int]]:
    """Parse `git diff -U0` output into {repo-rel-path: {line_numbers}}."""
    result: dict[str, set[int]] = {}
    current: str | None = None
    new_line = 0

    for raw in diff_text.splitlines():
        if raw.startswith("+++ "):
            token = raw[4:].strip()
            if token == "/dev/null":
                current = None
                continue
            if token.startswith("b/"):
                token = token[2:]
            current = token.replace("\\", "/")
            result.setdefault(current, set())
            continue

        m = _HUNK_RE.match(raw)
        if m:
            new_line = int(m.group(1))
            continue

        if current is None:
            continue

        if raw.startswith("+") and not raw.startswith("+++"):
            result.setdefault(current, set()).add(new_line)
            new_line += 1
        elif raw.startswith("-") and not raw.startswith("---"):
            continue
        elif raw.startswith("\\"):
            continue
        else:
            new_line += 1

    return result


def _run_git_diff(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )


def git_diff_added_lines(diff_base: str) -> dict[str, set[int]]:
    three_dot = ["git", "diff", "-U0", "--no-color", f"{diff_base}...HEAD"]
    try:
        proc = _run_git_diff(three_dot)
    except OSError as exc:
        raise RuntimeError(f"failed to run git diff: {exc}") from exc

    if proc.returncode == 0:
        return parse_unified_diff_added_lines(proc.stdout)

    two_dot = ["git", "diff", "-U0", "--no-color", diff_base]
    proc2 = _run_git_diff(two_dot)
    if proc2.returncode != 0:
        err = (proc.stderr or proc2.stderr or "").strip()
        raise RuntimeError(
            f"git diff against {diff_base!r} failed: {err or 'unknown error'}"
        )
    return parse_unified_diff_added_lines(proc2.stdout)


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
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for name in filenames:
                if name.endswith(".kt"):
                    yield Path(dirpath) / name


def path_under_root(path: Path, root: Path) -> bool:
    base = root if root.is_dir() else root.parent
    try:
        path.relative_to(base)
        return True
    except ValueError:
        return False


def path_under_any_root(path: Path, roots: list[Path]) -> bool:
    return any(path_under_root(path, root) for root in roots)


def collect_diff_files(
    line_map: dict[str, set[int]], roots: list[Path]
) -> list[Path]:
    root_resolved = [r.resolve() for r in roots]
    files: list[Path] = []
    for rel, changed_lines in sorted(line_map.items()):
        if not changed_lines:
            continue
        path = (REPO_ROOT / rel).resolve()
        if not is_kotlin(path) or not path.is_file():
            continue
        if not path_under_any_root(path, root_resolved):
            continue
        files.append(path)
    return files


def lookup_line_filter(rel: str, line_map: dict[str, set[int]]) -> set[int] | None:
    if rel in line_map:
        return line_map[rel]
    for key, lines in line_map.items():
        if key.replace("\\", "/") == rel:
            return lines
    return None


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
    if basename_matches(Path(rel).name, HEX_ALLOW_BASENAMES):
        return True
    return "theme" in norm_parts(rel)


def check_no_raw_hex_color(
    rel: str, lines: list[str], line_filter: set[int] | None
) -> list[Finding]:
    mod = module_segment(rel)
    if mod is None or not is_hex_color_scope(mod) or allow_raw_hex(rel):
        return []

    return warn_on_regex(
        rel,
        lines,
        line_filter,
        HEX_COLOR_RE,
        "no-raw-hex-color",
        "raw Color(0x...) — use theme/color roles instead of hardcoded hex",
    )


def check_sharedlogic_jvm_api(
    rel: str, lines: list[str], line_filter: set[int] | None
) -> list[Finding]:
    if not is_sharedlogic_commonmain(rel):
        return []

    findings: list[Finding] = []
    for lineno, text in iter_scoped_lines(lines, line_filter):
        for regex, label in JVM_APIS:
            if regex.search(text):
                findings.append(
                    Finding(
                        "WARN",
                        "sharedlogic-jvm-api",
                        rel,
                        lineno,
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
    if not line_filter:
        return []

    parts = norm_parts(rel)
    if "sharedLogic" in parts:
        return []
    mod = module_segment(rel)
    if mod not in {"desktop", "app"}:
        return []
    if not Path(rel).name.endswith("Repository.kt"):
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
    if mod is None or not is_app_or_feature_module(mod):
        return []

    return warn_on_regex(
        rel,
        lines,
        line_filter,
        ALERT_DIALOG_RE,
        "raw-alertdialog",
        "AlertDialog( in feature UI — prefer ConfirmDialog / designsystem wrappers",
    )


def scan_file(path: Path, line_filter: set[int] | None) -> list[Finding]:
    rel = repo_rel(path)
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        raise RuntimeError(f"cannot read {rel}: {exc}") from exc

    return [
        *check_no_raw_hex_color(rel, lines, line_filter),
        *check_sharedlogic_jvm_api(rel, lines, line_filter),
        *check_platform_repo_duplication(rel, line_filter),
        *check_raw_alertdialog(rel, lines, line_filter),
    ]


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


def normalize_diff_base(raw: str | None) -> str | None:
    if raw is None:
        return None
    stripped = raw.strip()
    return stripped or None


def collect_files_to_scan(
    roots: list[Path], line_map: dict[str, set[int]] | None
) -> list[Path]:
    if line_map is not None:
        return collect_diff_files(line_map, roots)
    return sorted(set(iter_kotlin_files(roots)))


def print_findings_and_summary(
    findings: list[Finding],
    diff_base: str | None,
    file_count: int,
) -> None:
    findings.sort(key=lambda f: (f.path, f.line, f.rule))
    for finding in findings:
        print(finding.format())

    by_rule: dict[str, int] = {}
    for finding in findings:
        by_rule[finding.rule] = by_rule.get(finding.rule, 0) + 1

    mode = f"diff vs {diff_base}" if diff_base else "full scan"
    print()
    print(
        f"agents-policy-check: {len(findings)} finding(s) "
        f"({mode}, {file_count} file(s) checked)"
    )
    if by_rule:
        for rule, count in sorted(by_rule.items()):
            print(f"  {rule}: {count}")
    else:
        print("  (no findings)")


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    diff_base = normalize_diff_base(args.diff_base)

    line_map: dict[str, set[int]] | None = None
    if diff_base:
        try:
            line_map = git_diff_added_lines(diff_base)
        except RuntimeError as exc:
            print(f"ERROR|script|-:|{exc}", file=sys.stderr)
            return 1

    roots = resolve_roots(args.paths)
    files = collect_files_to_scan(roots, line_map)

    all_findings: list[Finding] = []
    try:
        for path in files:
            rel = repo_rel(path)
            line_filter = lookup_line_filter(rel, line_map) if line_map else None
            all_findings.extend(scan_file(path, line_filter))
    except RuntimeError as exc:
        print(f"ERROR|script|-:|{exc}", file=sys.stderr)
        return 1

    print_findings_and_summary(all_findings, diff_base, len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
