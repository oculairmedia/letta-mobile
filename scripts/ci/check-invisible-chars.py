#!/usr/bin/env python3
"""Reject literal invisible and bidirectional-control characters in tracked source files.

Script twin of the InvisibleOrBidiCharacter detekt rule (quality/detekt-rules), so the check runs
without Gradle and also covers Python, shell and other non-Kotlin sources. SonarCloud fails a PR on
these characters: they render as nothing or reorder text, so the reviewed code differs from the code
that runs. Write them as \\uXXXX escapes instead.

Not reported: a byte-order mark as the first character of a file (encoding metadata) and a zero-width
joiner between two emoji (how multi-person and skin-tone emoji are spelled; renders as one glyph).
"""
import argparse
from pathlib import Path
import re
import subprocess

EXTENSIONS = {".kt", ".kts", ".java", ".py", ".sh", ".js", ".mjs", ".ts", ".tsx", ".gradle"}
RANGES = ((0x200B, 0x200F), (0x202A, 0x202E), (0x2060, 0x2064), (0x2066, 0x2069), (0xFEFF, 0xFEFF), (0x061C, 0x061C))
CHARACTERS = re.compile("[" + "".join(f"{chr(low)}-{chr(high)}" for low, high in RANGES) + "]")
BOM = chr(0xFEFF)
ZERO_WIDTH_JOINER = chr(0x200D)
VARIATION_SELECTOR = chr(0xFE0F)
NEWLINE = chr(10)


def is_emoji(char):
    if char is None:
        return False
    code = ord(char)
    return code >= 0x1F000 or 0x2600 <= code <= 0x27BF or char == VARIATION_SELECTOR


def is_leading_bom(char, index):
    return char == BOM and index == 0


def is_joiner_between_emoji(text, index):
    """True when the character at [index] is a zero-width joiner flanked by emoji."""
    if text[index] != ZERO_WIDTH_JOINER or index == 0:
        return False
    after = text[index + 1] if index + 1 < len(text) else None
    return is_emoji(text[index - 1]) and is_emoji(after)


def is_allowed(text, index):
    return is_leading_bom(text[index], index) or is_joiner_between_emoji(text, index)


def findings(text):
    """Yield (line, column, code point) for every reportable character in [text]."""
    for match in CHARACTERS.finditer(text):
        index = match.start()
        if is_allowed(text, index):
            continue
        line = text.count(NEWLINE, 0, index) + 1
        column = index - text.rfind(NEWLINE, 0, index)
        yield line, column, ord(match.group())


def is_source_file(name, path):
    if not name:
        return False
    return path.suffix in EXTENSIONS and path.is_file()


def tracked_sources(root):
    output = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], check=True, capture_output=True).stdout
    for name in output.decode("utf-8").split("\0"):
        path = root / name
        if is_source_file(name, path):
            yield path


def check(root, paths=None):
    errors = []
    for path in paths if paths is not None else tracked_sources(root):
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for line, column, code in findings(text):
            errors.append(f"{path.relative_to(root).as_posix()}:{line}:{column}: literal U+{code:04X}; "
                          f"write it as the escape \\u{code:04X} (or delete it)")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    errors = check(args.root.resolve())
    if errors:
        parser.exit(1, "\n".join(errors) + "\n")
    print("Invisible characters: none in tracked sources")


if __name__ == "__main__":
    main()
