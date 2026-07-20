#!/usr/bin/env python3
"""Apply mechanical Qodana notice/warning fixes from inventory CSV."""

from __future__ import annotations

import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "android-compose"


def read_file(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write_file(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8", newline="")


def fix_replace_with_operator_assignment(content: str) -> tuple[str, int]:
    pattern = re.compile(
        r"^(\s*)(\S[\w.()\[\]'\"]*)\s*=\s*\2\s*\+\s*(.+)$",
        re.MULTILINE,
    )
    count = 0

    def repl(m: re.Match[str]) -> str:
        nonlocal count
        count += 1
        return f"{m.group(1)}{m.group(2)} += {m.group(3)}"

    return pattern.sub(repl, content), count


def fix_remove_curly_braces(content: str) -> tuple[str, int]:
    count = 0

    def repl(m: re.Match[str]) -> str:
        nonlocal count
        expr = m.group(1)
        if re.search(r"[.?\s+\-*/%&|^<>!=]", expr):
            return m.group(0)
        count += 1
        return f"${expr}"

    return re.sub(r"\$\{([a-zA-Z_][\w]*)\}", repl, content), count


def fix_convert_to_range_check(content: str) -> tuple[str, int]:
    patterns = [
        (
            re.compile(
                r"(\S+)\s*>=\s*(\S+)\s*&&\s*\1\s*<=\s*(\S+)"
            ),
            r"\1 in \2..\3",
        ),
        (
            re.compile(
                r"(\S+)\s*<=\s*(\S+)\s*&&\s*\1\s*>=\s*(\S+)"
            ),
            r"\1 in \3..\2",
        ),
    ]
    count = 0
    for pattern, repl in patterns:
        new_content, n = pattern.subn(repl, content)
        if n:
            content = new_content
            count += n
    return content, count


def fix_enum_values(content: str) -> tuple[str, int]:
    pattern = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\.values\(\)")
    count = 0

    def repl(m: re.Match[str]) -> str:
        nonlocal count
        count += 1
        return f"{m.group(1)}.entries"

    return pattern.sub(repl, content), count


def fix_readline(content: str) -> tuple[str, int]:
    count = content.count(".readLine()")
    content = content.replace(".readLine()", ".readln()")
    return content, count


def fix_regexp_redundant_escape(content: str) -> tuple[str, int]:
    # Remove unnecessary backslashes before common unreserved regex chars in char classes
    count = 0
    for ch in "[](){}|.,:;!?@#%^&*-_=+<>~`'\"":
        new = content.replace(f"\\{ch}", ch)
        if new != content:
            count += content.count(f"\\{ch}")
            content = new
    return content, count


def fix_ellipsis_strings(content: str) -> tuple[str, int]:
    count = 0
    if '...' in content and '…' not in content:
        return content, 0
    # Only fix XML string resources with three dots as ellipsis
    if path_suffix := "":
        pass
    new = re.sub(r'>([^<]*?)\.\.\.([^<]*?)<', lambda m: f">{m.group(1)}…{m.group(2)}<" if '...' in m.group(0) else m.group(0), content)
    count = content.count('...') - new.count('...')
    return new, count


FIXERS = {
    "ReplaceWithOperatorAssignment": fix_replace_with_operator_assignment,
    "RemoveCurlyBracesFromTemplate": fix_remove_curly_braces,
    "ConvertTwoComparisonsToRangeCheck": fix_convert_to_range_check,
    "EnumValuesSoftDeprecate": fix_enum_values,
    "ReplaceReadLineWithReadln": fix_readline,
}


def main() -> int:
    inventory = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/target-findings.csv")
    if not inventory.exists():
        print(f"Inventory not found: {inventory}", file=sys.stderr)
        return 1

    by_file: dict[str, set[str]] = defaultdict(set)
    with inventory.open(newline="") as f:
        reader = csv.reader(f)
        for row in reader:
            if len(row) < 3:
                continue
            rule, _level, rel = row[0], row[1], row[2]
            if rule in FIXERS:
                by_file[rel].add(rule)

    totals: dict[str, int] = defaultdict(int)
    for rel, rules in sorted(by_file.items()):
        path = ROOT / rel
        if not path.exists():
            print(f"skip missing {rel}")
            continue
        original = read_file(path)
        content = original
        for rule in rules:
            content, n = FIXERS[rule](content)
            totals[rule] += n
        if content != original:
            write_file(path, content)
            print(f"updated {rel}")

    print("\nFixed counts:")
    for rule, n in sorted(totals.items(), key=lambda x: -x[1]):
        print(f"  {rule}: {n}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
