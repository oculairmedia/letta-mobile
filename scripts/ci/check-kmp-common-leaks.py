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

SOURCE_SETS = ("commonMain", "commonTest")
PRUNED_DIRS = {"build", "generated", "ksp", ".gradle", ".git", "node_modules"}

TOKEN = re.compile(r'/\*|//[^\n]*|"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'')
# The multiplatform way to turn a String into bytes is encodeToByteArray(); String's
# toByteArray(charset) exists only on the JVM, so only String-shaped receivers match:
# a string literal, a toString()/decodeToString() call, or a charset argument.
STRING_RECEIVER = re.compile(r"(?:\bto(?:Plain)?String\s*\(\s*\)|\bdecodeToString\s*\(\s*\))[ \t]*$")
TO_BYTE_ARRAY = re.compile(r"\.[ \t]*toByteArray[ \t]*\(")
CHARSET_ARGUMENT = re.compile(r"\bCharsets\b|\bcharset\b")
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


def masked(text):
    """Blank comment and string-literal bodies (keeping delimiters), preserving offsets."""
    output, offset = [], 0
    while match := TOKEN.search(text, offset):
        start, end = match.span()
        output.append(text[offset:start])
        value = match[0]
        if value.startswith("//") or value == "/*":
            if value == "/*":
                end = coverage.block_comment_end(text, end)
                value = text[start:end]
            output.append(re.sub(r"[^\n]", " ", value))
        else:
            output.append(value[0] + re.sub(r"[^\n]", " ", value[1:-1]) + value[-1])
        offset = end
    output.append(text[offset:])
    return "".join(output)


def call_argument(masked_text, open_paren):
    """The balanced argument text of the call opened at [open_paren] ("" when unterminated)."""
    depth = 0
    for index in range(open_paren, len(masked_text)):
        char = masked_text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return masked_text[open_paren + 1:index]
    return masked_text[open_paren + 1:]


def string_to_byte_array_leaks(code):
    """String.toByteArray( leaks: a String receiver, or the JVM-only charset overload."""
    findings = []
    for match in TO_BYTE_ARRAY.finditer(code):
        prefix = code[:match.start()].rstrip(" \t")
        arguments = call_argument(code, match.end() - 1)
        if prefix.endswith('"') or (prefix.endswith(")") and STRING_RECEIVER.search(prefix)):
            findings.append((match.start(), "String.toByteArray( is JVM-only: use encodeToByteArray()"))
        elif CHARSET_ARGUMENT.search(arguments):
            findings.append((match.start(), "toByteArray(charset) is the JVM-only String overload: use encodeToByteArray()"))
    return findings


def file_errors(path, root):
    raw = path.read_bytes()
    if MAYBE.search(raw) is None:
        return []
    code = masked(raw.decode("utf-8-sig"))
    findings = []
    for pattern, message in LEAKS:
        for match in pattern.finditer(code):
            findings.append((match.start(), message.format(*match.groups()) if match.groups() else message))
    findings.extend(string_to_byte_array_leaks(code))
    errors = []
    for start, message in sorted(findings):
        line = code.count("\n", 0, start) + 1
        try:
            shown = path.relative_to(root)
        except ValueError:
            shown = path
        errors.append(f"{shown}:{line}: {message}")
    return errors


def common_files(root):
    """All android-compose/**/src/common{Main,Test}/kotlin/**/*.kt, without build output."""
    for directory, subdirs, names in os.walk(root / "android-compose"):
        subdirs[:] = sorted(name for name in subdirs if name not in PRUNED_DIRS)
        folder = Path(directory)
        parts = folder.relative_to(root).parts
        for index, part in enumerate(parts):
            if part == "src" and index + 3 <= len(parts) \
                    and parts[index + 1] in SOURCE_SETS and parts[index + 2] == "kotlin":
                for name in names:
                    if name.endswith(".kt"):
                        yield folder / name
                break


def check(root, files):
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
