#!/usr/bin/env python3
"""Detect removed tests, assertions and newly disabled tests in a committed PR diff.

Compares changed test files at the merge base and HEAD, aggregating by class so tests
can move between files. PR_BODY, or the GitHub event body when it is empty, supplies
the optional explanation. Exit 0 on pass, 1 on weakening, 2 on an input error.
"""
from __future__ import annotations

import argparse
import ast
from collections import Counter, defaultdict
import json
import os
from pathlib import Path
import re
import subprocess
import sys

# One list shared by Kotlin and Python. The wildcard covers assertEquals, assertTrue, etc.
ASSERTION_CALLS = ("assert*", "verify", "expectThat", "shouldBe")
REASON_MIN_LENGTH = 20
REASON_LINE = re.compile(r"^\s*Test change reason:\s*(.*?)\s*$")
KOTLIN_TEST_PATH = re.compile(r"(?:^|/)src/(?:[^/]*Test[^/]*|test|androidTest)/.*\.kt$")
KOTLIN_TOKEN = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
KOTLIN_CLASS = re.compile(r"\b(?:class|object|interface)\s+([A-Za-z_]\w*)\b[^{}]*\{")
KOTLIN_TEST = re.compile(r"@(?:[\w.]+\.)?Test\b(?:(?!\b(?:fun|class|object|interface)\b|[{};])[\s\S])*?\bfun\s+(?:<[^>]+>\s*)?(`[^`]+`|[A-Za-z_]\w*)\s*\(")
KOTLIN_SKIP = re.compile(r'@(?:[\w.]+\.)?(Ignore|Disabled)\b|@(?:[\w.]+\.)?Suppress\s*\([^)]*\bUNUSED_STRING\b[^)]*\)|\.\s*(skip)\b')
CALL = re.compile(r"\b([A-Za-z_]\w*)\s*\(")
INFIX_SHOULD_BE = re.compile(r"\bshouldBe\b(?!\s*\()")


def is_test_path(path: str) -> bool:
    name = Path(path).name
    return bool(KOTLIN_TEST_PATH.search(path)) or (
        path.startswith("scripts/") and name.endswith(".py")
        and (name.startswith("test_") or name.endswith("_test.py"))
    )


def assertion_name(name: str) -> bool:
    return any(name.startswith(item[:-1]) if item.endswith("*") else name == item for item in ASSERTION_CALLS)


def empty_record():
    return {"tests": Counter(), "assertions": 0, "skips": Counter()}


def kotlin_records(source: str):
    # A sentinel shorter than the literal preserves offsets.
    def clean(match):
        token = match.group()
        return ('UNUSED' + ' ' * (len(token) - 6)) if token == '"unused"' else re.sub(r"[^\n]", " ", token)

    text = KOTLIN_TOKEN.sub(clean, source)
    skip_pattern = KOTLIN_SKIP.pattern.replace("UNUSED_STRING", "UNUSED")
    records = defaultdict(empty_record)
    classes = []
    for match in KOTLIN_CLASS.finditer(text):
        depth = 1
        pos = match.end()
        while pos < len(text) and depth:
            depth += (text[pos] == "{") - (text[pos] == "}")
            pos += 1
        classes.append((match.start(), pos, match.group(1)))

    def owner(pos):
        candidates = [(start, name) for start, end, name in classes if start <= pos < end]
        return max(candidates)[1] if candidates else "<top-level>"

    for match in KOTLIN_TEST.finditer(text):
        records[owner(match.start())]["tests"][match.group(1).strip('`')] += 1
    for match in CALL.finditer(text):
        if assertion_name(match.group(1)):
            records[owner(match.start())]["assertions"] += 1
    for match in INFIX_SHOULD_BE.finditer(text):
        records[owner(match.start())]["assertions"] += 1
    for match in re.finditer(skip_pattern, text):
        kind = match.group(1) or match.group(2) or "Suppress(unused)"
        if match.group(2):
            key = owner(match.start())
            if records[key]["tests"]:
                records[key]["skips"][kind] += 1
            continue
        # Annotations count only when attached to a test function or test class.
        declaration = re.search(r"\b(fun|class|object|interface)\s+(?:<[^>]+>\s*)?(`[^`]+`|[A-Za-z_]\w*)", text[match.end():])
        if not declaration:
            continue
        end = match.end() + declaration.start()
        between = text[match.end():end]
        if re.search(r"[{};]", between):
            continue
        if declaration.group(1) != "fun":
            key = declaration.group(2)
            if records[key]["tests"]:
                records[key]["skips"][kind] += 1
        else:
            boundary = max(text.rfind(char, 0, match.start()) for char in "{};")
            annotations = text[boundary + 1:end]
            if re.search(r"@(?:[\w.]+\.)?Test\b", annotations):
                records[owner(match.start())]["skips"][kind] += 1
    return records


def python_records(source: str, path: str):
    tree = ast.parse(source, filename=path)
    records = defaultdict(empty_record)

    def decorator_name(node):
        if isinstance(node, ast.Call):
            return decorator_name(node.func)
        if isinstance(node, ast.Attribute):
            return decorator_name(node.value) + "." + node.attr
        return node.id if isinstance(node, ast.Name) else ""

    def scan(body, owner):
        for node in body:
            if isinstance(node, ast.ClassDef):
                key = node.name
                for dec in node.decorator_list:
                    name = decorator_name(dec)
                    if name.split('.')[-1] in ("skip", "skipIf", "skipUnless", "skipif"):
                        records[key]["skips"][name] += 1
                scan(node.body, key)
            elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                if node.name.startswith("test"):
                    records[owner]["tests"][node.name] += 1
                    for dec in node.decorator_list:
                        name = decorator_name(dec)
                        if name.split('.')[-1] in ("skip", "skipIf", "skipUnless", "skipif"):
                            records[owner]["skips"][name] += 1
                for child in (item for statement in node.body for item in ast.walk(statement)):
                    if isinstance(child, ast.Assert):
                        records[owner]["assertions"] += 1
                    elif isinstance(child, ast.Call):
                        name = decorator_name(child.func).split('.')[-1]
                        if assertion_name(name):
                            records[owner]["assertions"] += 1
                        if name == "skip" or name == "skipTest":
                            records[owner]["skips"][name] += 1
    scan(tree.body, "<top-level>")
    return records


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise ValueError(result.stderr.decode("utf-8", "replace").strip())
    return result.stdout


def records_for(root, revision, paths):
    combined = defaultdict(empty_record)
    for path in sorted(paths):
        source = git(root, "show", f"{revision}:{path}").decode("utf-8")
        records = kotlin_records(source) if path.endswith(".kt") else python_records(source, path)
        for name, record in records.items():
            combined[name]["tests"].update(record["tests"])
            combined[name]["assertions"] += record["assertions"]
            combined[name]["skips"].update(record["skips"])
    return combined


def pr_body():
    body = os.environ.get("PR_BODY", "")
    event = os.environ.get("GITHUB_EVENT_PATH", "")
    if not body and event and Path(event).is_file():
        with open(event, encoding="utf-8") as stream:
            body = (json.load(stream).get("pull_request") or {}).get("body") or ""
    return body


def valid_reason(body):
    for line in body.splitlines():
        match = REASON_LINE.fullmatch(line)
        if match and len(match.group(1).strip()) >= REASON_MIN_LENGTH:
            return True
    return False


def check(root, ref):
    resolved = git(root, "rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}").decode().strip()
    base = git(root, "merge-base", resolved, "HEAD").decode().strip()
    # Disable rename detection: the source and destination must both be compared.
    changed = git(root, "diff", "--name-status", "-z", "--no-renames", base, "HEAD").decode().split("\0")
    before, after = set(), set()
    for status, path in zip(changed[0::2], changed[1::2]):
        if not is_test_path(path):
            continue
        if status in ("M", "D", "T"):
            before.add(path)
        if status in ("M", "A", "T"):
            after.add(path)
    old = records_for(root, base, before)
    new = records_for(root, "HEAD", after)
    problems = []
    for name in sorted(old):
        for test, count in sorted((old[name]["tests"] - new[name]["tests"]).items()):
            problems.append(f"{name}: @Test/test function removed or renamed: {test} ({count})")
        old_count, new_count = old[name]["assertions"], new[name]["assertions"]
        if new_count < old_count:
            problems.append(f"{name}: assertion calls decreased: {old_count} -> {new_count}")
    # Compare disables in existing classes across all changed test files.
    if before:
        for name in sorted(old):
            for marker, count in sorted((new[name]["skips"] - old[name]["skips"]).items()):
                problems.append(f"{name}: test disable added: {marker} ({count})")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--diff-base", required=True)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    try:
        problems = check(args.root.resolve(), args.diff_base)
        reason = valid_reason(pr_body())
    except (ValueError, OSError, UnicodeError, SyntaxError, json.JSONDecodeError) as error:
        print(f"test-weakening-guard: error: {error}", file=sys.stderr)
        return 2
    if not problems:
        print("test-weakening-guard: PASS")
        return 0
    if reason:
        print("test-weakening-guard: PASS - test changes explained in PR body")
        return 0
    print(f"::error title=Test weakening::{len(problems)} potential test weakening(s) detected")
    for problem in problems:
        print(f"test-weakening-guard: {problem}")
    print(f"test-weakening-guard: FAILED - add 'Test change reason: <at least {REASON_MIN_LENGTH} characters>' to the PR description or restore the tests.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
