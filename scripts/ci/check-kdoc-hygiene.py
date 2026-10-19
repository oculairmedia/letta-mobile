#!/usr/bin/env python3
"""Reject issue-tracker references in published production KDoc, not implementation comments."""
import argparse
import importlib.util
from pathlib import Path
import re


SPEC = importlib.util.spec_from_file_location("package_coverage", Path(__file__).with_name("check-api-package-coverage.py"))
coverage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage)
TRACKER = re.compile(r"\bletta-mobile-[a-z0-9]+(?:[-.][a-z0-9]+)*(?:/\.[a-z0-9]+)*\b|\bCodeScene\s*#?\s*\d+\b", re.IGNORECASE)
TOKEN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*')


def kdoc_blocks(text):
    """Locate real documentation comments while skipping literals and ordinary comments."""
    offset = 0
    while match := TOKEN.search(text, offset):
        start, offset = match.span()
        if match[0] == "/*":
            offset = coverage.block_comment_end(text, offset)
            if text.startswith("/**", start):
                yield start, offset, text[start:offset]


def file_errors(path, root):
    text = path.read_text(encoding="utf-8-sig")
    for start, _, comment in kdoc_blocks(text):
        for match in TRACKER.finditer(comment):
            line = text.count("\n", 0, start + match.start()) + 1
            yield f"{path.relative_to(root)}:{line}: describe intent instead of {match[0]}"


def check(root):
    errors = []
    for module in coverage.published_modules(root):
        directory = coverage.module_directory(root, module)
        for path in coverage.production_source_files(directory):
            errors.extend(file_errors(path, root))
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    try:
        errors = check(args.root)
    except (OSError, ValueError) as error:
        parser.exit(1, str(error) + "\n")
    if errors:
        parser.exit(1, "\n".join(errors) + "\n")
    print("Published KDoc hygiene: no tracking IDs")


if __name__ == "__main__":
    main()
