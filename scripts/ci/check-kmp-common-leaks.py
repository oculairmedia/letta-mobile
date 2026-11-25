#!/usr/bin/env python3
"""Reject JVM-only APIs inside multiplatform common source, where they break other targets.

`commonMain`/`commonTest` compile for every target of the shared modules — including the
`hostNative` and `wasmJs` compiles the required `shared-multiplatform` CI job runs — so a
JVM-only API that compiles fine on Android and the JVM desktop target turns a full CI run
red only at the end. This gate flags those calls in seconds by reading source (no Gradle):

  import java.* / javax.*       read through expect/actual or a multiplatform library instead
  String.format(                 use a string template or buildString { }
  Thread.sleep(                  use kotlinx.coroutines delay
  System.currentTimeMillis( / nanoTime( / getenv(   use kotlin.time / platform expect/actual
  String.toByteArray(            use encodeToByteArray() / decodeToString()

Only real code is scanned: comments (line, block, nested, KDoc) and string literal
contents are masked first, so prose examples of `System.currentTimeMillis` do not match.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import re

SPEC = importlib.util.spec_from_file_location("package_coverage", Path(__file__).with_name("check-api-package-coverage.py"))
coverage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage)

PRUNED_DIRS = {"build", "generated", "ksp", ".gradle", ".git", "node_modules"}
# A common kotlin tree ends in src/common{Main,Test}/kotlin; the kotlin files of that
# tree (at any depth, directly in kotlin/ included) are exactly what this gate scans.
COMMON_TREES = {("src", source_set, "kotlin") for source_set in ("commonMain", "commonTest")}

TOKEN = re.compile(r'/\*|//[^\n]*|"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'')
# The multiplatform way to turn a String into bytes is encodeToByteArray(); String's
# toByteArray(charset) exists only on the JVM, so only String-shaped receivers match:
# a string literal, a toString()/decodeToString() call, or a charset argument.
STRING_RECEIVER = re.compile(r"(?:\bto(?:Plain)?String\s*\(\s*\)|\bdecodeToString\s*\(\s*\))[ \t]*$")
TO_BYTE_ARRAY = re.compile(r"\.[ \t]*toByteArray[ \t]*\(")
CHARSET_ARGUMENT = re.compile(r"\bCharsets\b|\bcharset\b")
LITERAL_LEAK = "String.toByteArray( is JVM-only: use encodeToByteArray()"
CHARSET_LEAK = "toByteArray(charset) is the JVM-only String overload: use encodeToByteArray()"
LEAKS = (
    (re.compile(r"(?m)^[ \t]*import[ \t]+((?:java|javax)\.[\w.]+)"),
     "import {0} is JVM-only: use a multiplatform API or move the code to a platform source set"),
    (re.compile(r"\bString[ \t]*\.[ \t]*format[ \t]*\("),
     "String.format( is JVM-only: use a string template or buildString { }"),
    (re.compile(r"\bThread[ \t]*\.[ \t]*sleep[ \t]*\("),
     "Thread.sleep( is JVM-only: use kotlinx.coroutines delay()"),
    (re.compile(r"\bSystem[ \t]*\.[ \t]*(currentTimeMillis|nanoTime|getenv)[ \t]*\("),
     "System.{0}( is JVM-only: use kotlin.time (TimeSource/Clock) or an expect/actual"),
)
# Cheap raw-bytes pre-filter: a superset of everything the masked scan can flag, so only
# candidate files pay for the lexer. Import targets never wrap, so \s+ is the only slack.
MAYBE = re.compile(
    rb"(?:import\s+(?:java|javax)[.\s]|String\s*\.\s*format|Thread\s*\.\s*sleep[ \t(]"
    rb"|System\s*\.\s*(?:currentTimeMillis|nanoTime|getenv)|[.\s]toByteArray)"
)


def blank(value):
    """[value] with each non-newline character a space, keeping offsets and line numbers."""
    return re.sub(r"[^\n]", " ", value)


def masked_piece(value):
    """One comment or literal of [value] with its body blanked; literal quotes survive."""
    if not value.startswith("/"):
        return value[0] + blank(value[1:-1]) + value[-1]
    return blank(value)


def masked(text):
    """[text] with comment bodies and string-literal contents blanked, offsets kept."""
    pieces, offset = [], 0
    while match := TOKEN.search(text, offset):
        start, end = match.span()
        token = match[0]
        if token == "/*":
            end = coverage.block_comment_end(text, end)
            token = text[start:end]
        pieces.append(text[offset:start])
        pieces.append(masked_piece(token))
        offset = end
    pieces.append(text[offset:])
    return "".join(pieces)


def call_argument(code, open_paren):
    """The balanced argument text of the call opened at [open_paren]; '' when unterminated."""
    depth = 0
    for walked, char in enumerate(code[open_paren:]):
        depth += (char == "(") - (char == ")")
        if depth == 0:
            return code[open_paren + 1:open_paren + walked]
    return code[open_paren + 1:]


def message_for(message, match):
    """[message] with the pattern's captures substituted for {0} (imports name the leak)."""
    if not match.groups():
        return message
    return message.format(*match.groups())


def pattern_findings(code):
    """(offset, message) for each regex-leak match in the masked [code]."""
    return [(match.start(), message_for(message, match))
            for pattern, message in LEAKS
            for match in pattern.finditer(code)]


def to_byte_array_finding(code, match):
    """(offset, message) when [.match] turns a String into JVM bytes, else None.

    A String receiver leaks (String.toByteArray is JVM-only); so does the charset
    overload (only String has it); anything else is a multiplatform byte API.
    """
    receiver = code[:match.start()].rstrip(" \t")
    if receiver.endswith('"'):
        return match.start(), LITERAL_LEAK
    if CHARSET_ARGUMENT.search(call_argument(code, match.end() - 1)):
        return match.start(), CHARSET_LEAK
    if not receiver.endswith(")"):
        return None
    if not STRING_RECEIVER.search(receiver):
        return None
    return match.start(), LITERAL_LEAK


def string_to_byte_array_leaks(code):
    """Every String.toByteArray( leak of the masked [code], as (offset, message)."""
    return list(filter(None, (to_byte_array_finding(code, match)
                              for match in TO_BYTE_ARRAY.finditer(code))))


def findings(code):
    """Every leak (offset, message) in the masked [code], earlier offsets first."""
    return sorted(pattern_findings(code) + string_to_byte_array_leaks(code))


def shown_path(path, root):
    """[path] reported relative to [root]; outside it (an explicit --files listing), itself."""
    try:
        return path.relative_to(root)
    except ValueError:
        return path


def file_errors(path, root):
    """Every 'path:line: message' leak that [path] contains in its common code."""
    raw = path.read_bytes()
    if MAYBE.search(raw) is None:
        return []
    code = masked(raw.decode("utf-8-sig"))
    return [f"{shown_path(path, root)}:{code.count(chr(10), 0, offset) + 1}: {message}"
            for offset, message in findings(code)]


def prune(subdirs):
    """[subdirs] reduced to real source directories, sorted so scans are stable."""
    subdirs[:] = sorted(name for name in subdirs if name not in PRUNED_DIRS)


def common_kotlin_trees(root):
    """Every src/common{Main,Test}/kotlin directory of the tree, without build output."""
    for folder, subdirs, _ in os.walk(root / "android-compose"):
        prune(subdirs)
        directory = Path(folder)
        if directory.parts[-3:] in COMMON_TREES:
            yield directory


def common_files(root):
    """All android-compose/**/src/common{Main,Test}/kotlin/**/*.kt — the shared sources."""
    for directory in common_kotlin_trees(root):
        yield from directory.rglob("*.kt")


def check(root, files):
    """(number of files scanned, leak lines) for [files] relative to [root]."""
    errors, scanned = [], 0
    for path in files:
        scanned += 1
        errors.extend(file_errors(path, root))
    return scanned, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("files", nargs="*", type=Path, help="scan these .kt files instead of the"
                                                             " common source sets under the root")
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        files = args.files or list(common_files(root))
        scanned, errors = check(root, files)
    except (OSError, ValueError) as error:
        parser.exit(1, str(error) + "\n")
    if errors:
        parser.exit(1, "\n".join(errors) + "\n")
    print(f"KMP common source: no JVM-only API leaks in {scanned} files")


if __name__ == "__main__":
    main()
