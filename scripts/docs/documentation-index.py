#!/usr/bin/env python3
"""Generate docs/README.md navigation from tracked docs Markdown; no network/dependencies."""

import argparse
from html import unescape
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
from urllib.parse import quote


START = "<!-- documentation-index:start -->"
END = "<!-- documentation-index:end -->"
INDEX = PurePosixPath("docs/README.md")


def tracked_documents(root: Path) -> list[PurePosixPath]:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z", "--", "docs/"],
        check=True, capture_output=True,
    )
    paths = {
        PurePosixPath(raw.decode("utf-8"))
        for raw in result.stdout.split(b"\0") if raw
    }
    # Git supplies the scope: ignored build outputs and untracked files are never inputs.
    # Missing tracked files are pending deletions, not an excuse to keep dead index links.
    return sorted(
        (p for p in paths if p != INDEX and p.suffix.lower() == ".md"
         and (root / p).is_file()),
        key=lambda p: (p.as_posix().casefold(), p.as_posix()),
    )


def prose_lines(text: str):
    """Yield prose, replacing fenced blocks with a paragraph break."""
    closing = None
    for line in text.splitlines():
        if closing is not None:
            if closing.fullmatch(line):
                closing = None
            continue
        opening = re.match(r"^ {0,3}(`{3,}|~{3,})", line)
        if opening:
            marker = opening.group(1)
            closing = re.compile(r"^ {0,3}" + re.escape(marker[0])
                                 + "{" + str(len(marker)) + r",}\s*$")
            yield ""
            continue
        yield line


def title(path: Path) -> str:
    text = path.read_text(encoding="utf-8-sig")
    # YAML metadata comments are not document headings.
    text = re.sub(r"\A---[ \t]*\n.*?\n(?:---|\.\.\.)[ \t]*(?:\n|\Z)", "", text,
                  count=1, flags=re.DOTALL)
    previous = ""
    for line in prose_lines(text):
        heading = re.match(r"^ {0,3}#{1,6}\s+(.+?)\s*$", line)
        if heading:
            return unescape(re.sub(r"\s+#+\s*$", "", heading.group(1)).strip())
        if previous and re.fullmatch(r" {0,3}(?:=+|-+)\s*", line):
            return unescape(previous)
        previous = line.strip()
    return path.stem


def label(text: str) -> str:
    text = re.sub(r"\s+", " ", text).strip()
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return re.sub(r"([\\`*_{}\[\]()!|])", r"\\\1", text)


def navigation(root: Path) -> str:
    groups: dict[str, list[PurePosixPath]] = {}
    for path in tracked_documents(root):
        relative = path.relative_to("docs")
        group = "" if relative.parent == PurePosixPath(".") else relative.parent.as_posix()
        groups.setdefault(group, []).append(path)
    lines = ["## Documents", ""]
    for group in sorted(groups, key=lambda value: (bool(value), value.casefold(), value)):
        lines.extend(["### " + label(group or "General"), ""])
        for path in groups[group]:
            url = quote(path.relative_to("docs").as_posix(), safe="/-._~")
            lines.append(f"- [{label(title(root / path))}]({url})")
        lines.append("")
    return "\n".join(lines)


def render(root: Path, current: str) -> str:
    if current.count(START) != 1 or current.count(END) != 1:
        raise ValueError("docs/README.md must contain exactly one index marker pair")
    before, rest = current.split(START)
    if END not in rest:
        raise ValueError("documentation index end marker must follow its start marker")
    _, after = rest.split(END)
    return before + START + "\n" + navigation(root) + END + after


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="replace only marked navigation")
    mode.add_argument("--check", action="store_true", help="fail if marked navigation is stale")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2],
                        help="repository root (defaults to this script's checkout)")
    args = parser.parse_args()
    root = args.root.resolve()
    path = root / INDEX
    try:
        current = path.read_text(encoding="utf-8")
        expected = render(root, current)
        if expected == current:
            print("documentation-index: current")
            return 0
        if args.check:
            print("documentation-index: stale; run python scripts/docs/documentation-index.py --write",
                  file=sys.stderr)
            return 1
        path.write_bytes(expected.encode("utf-8"))
        print("documentation-index: updated docs/README.md")
        return 0
    except (OSError, UnicodeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"documentation-index: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
