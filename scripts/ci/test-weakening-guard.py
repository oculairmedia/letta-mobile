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
# Branch, tag, remote and SHA names with ~/^ suffixes: nothing git could read as an option.
SAFE_REF = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/~^-]{0,199}")
COMMIT_SHA = re.compile(r"[0-9a-f]{40}|[0-9a-f]{64}")
KOTLIN_TOKEN = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
KOTLIN_CLASS = re.compile(r"\b(?:class|object|interface)\s+([A-Za-z_]\w*)\b[^{}]*\{")
KOTLIN_TEST = re.compile(r"@(?:[\w.]+\.)?Test\b(?:(?!\b(?:fun|class|object|interface)\b|[{};])[\s\S])*?\bfun\s+(?:<[^>]+>\s*)?(`[^`]+`|[A-Za-z_]\w*)\s*\(")
KOTLIN_SKIP = re.compile(r'@(?:[\w.]+\.)?(Ignore|Disabled)\b|@(?:[\w.]+\.)?Suppress\s*\([^)]*\bUNUSED_STRING\b[^)]*\)|\.\s*(skip)\b')
CALL = re.compile(r"\b([A-Za-z_]\w*)\s*\(")
INFIX_SHOULD_BE = re.compile(r"\bshouldBe\b(?!\s*\()")


def kotlin_test_path(path):
    # The original path pattern permits one final newline, but not one in the file path.
    path = path.removesuffix("\n")
    if not path.endswith(".kt"):
        return False
    parts = path.split("/")
    last_newline = path.rfind("\n")
    offset = 0
    for parent, source_set, _ in zip(parts, parts[1:], parts[2:]):
        after_source_set = offset + len(parent) + len(source_set) + 2
        if is_test_source_dir(parent, source_set) and after_source_set > last_newline:
            return True
        offset += len(parent) + 1
    return False


def is_test_path(path: str) -> bool:
    name = Path(path).name
    return kotlin_test_path(path) or (
        path.startswith("scripts/") and name.endswith(".py")
        and (name.startswith("test_") or name.endswith("_test.py"))
    )


def assertion_name(name: str) -> bool:
    return any(name.startswith(item[:-1]) if item.endswith("*") else name == item for item in ASSERTION_CALLS)


def empty_record():
    return {"tests": Counter(), "assertions": 0, "skips": Counter()}


def clean_kotlin_token(match):
    # A sentinel shorter than the literal preserves offsets.
    token = match.group()
    return ('UNUSED' + ' ' * (len(token) - 6)) if token == '"unused"' else re.sub(r"[^\n]", " ", token)


def kotlin_classes(text):
    classes = []
    for match in KOTLIN_CLASS.finditer(text):
        depth = 1
        pos = match.end()
        while pos < len(text) and depth:
            depth += (text[pos] == "{") - (text[pos] == "}")
            pos += 1
        classes.append((match.start(), pos, match.group(1)))
    return classes


def kotlin_owner(classes, pos):
    candidates = [(start, name) for start, end, name in classes if start <= pos < end]
    return max(candidates)[1] if candidates else "<top-level>"


def count_kotlin_tests(text, classes, records):
    for match in KOTLIN_TEST.finditer(text):
        records[kotlin_owner(classes, match.start())]["tests"][match.group(1).strip('`')] += 1


def count_assertions(text, classes, records):
    for match in CALL.finditer(text):
        if assertion_name(match.group(1)):
            records[kotlin_owner(classes, match.start())]["assertions"] += 1
    for match in INFIX_SHOULD_BE.finditer(text):
        records[kotlin_owner(classes, match.start())]["assertions"] += 1


def is_test_fun(text, match, end):
    boundary = max(text.rfind(char, 0, match.start()) for char in "{};")
    return bool(re.search(r"@(?:[\w.]+\.)?Test\b", text[boundary + 1:end]))


def disable_annotation(text, match, classes):
    # Annotations count only when attached to a test function or test class.
    declaration = re.search(r"\b(fun|class|object|interface)\s+(?:<[^>]+>\s*)?(`[^`]+`|[A-Za-z_]\w*)", text[match.end():])
    if not declaration:
        return None
    end = match.end() + declaration.start()
    if re.search(r"[{};]", text[match.end():end]):
        return None
    if declaration.group(1) != "fun":
        return declaration.group(2), True
    if is_test_fun(text, match, end):
        return kotlin_owner(classes, match.start()), False
    return None


def count_kotlin_disables(text, classes, records):
    skip_pattern = KOTLIN_SKIP.pattern.replace("UNUSED_STRING", "UNUSED")
    for match in re.finditer(skip_pattern, text):
        kind = match.group(1) or match.group(2) or "Suppress(unused)"
        target = (kotlin_owner(classes, match.start()), True) if match.group(2) else disable_annotation(text, match, classes)
        if target is None:
            continue
        key, requires_tests = target
        if not requires_tests or records[key]["tests"]:
            records[key]["skips"][kind] += 1


def kotlin_records(source: str):
    text = KOTLIN_TOKEN.sub(clean_kotlin_token, source)
    classes = kotlin_classes(text)
    records = defaultdict(empty_record)
    count_kotlin_tests(text, classes, records)
    count_assertions(text, classes, records)
    count_kotlin_disables(text, classes, records)
    return records


def decorator_name(node):
    if isinstance(node, ast.Call):
        return decorator_name(node.func)
    if isinstance(node, ast.Attribute):
        return decorator_name(node.value) + "." + node.attr
    return node.id if isinstance(node, ast.Name) else ""


def count_python_decorators(decorators, owner, records):
    for decorator in decorators:
        name = decorator_name(decorator)
        if name.split('.')[-1] in ("skip", "skipIf", "skipUnless", "skipif"):
            records[owner]["skips"][name] += 1


def count_python_call(node, owner, records):
    name = decorator_name(node.func).split('.')[-1]
    if assertion_name(name):
        records[owner]["assertions"] += 1
    if name in ("skip", "skipTest"):
        records[owner]["skips"][name] += 1


def count_python_node(node, owner, records):
    if isinstance(node, ast.Assert):
        records[owner]["assertions"] += 1
    elif isinstance(node, ast.Call):
        count_python_call(node, owner, records)


def count_python_assertions(body, owner, records):
    for statement in body:
        for child in ast.walk(statement):
            count_python_node(child, owner, records)


def scan_python_function(node, owner, records):
    if node.name.startswith("test"):
        records[owner]["tests"][node.name] += 1
        count_python_decorators(node.decorator_list, owner, records)
    count_python_assertions(node.body, owner, records)


def scan_python_body(body, owner, records):
    for node in body:
        if isinstance(node, ast.ClassDef):
            count_python_decorators(node.decorator_list, node.name, records)
            scan_python_body(node.body, node.name, records)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            scan_python_function(node, owner, records)


def python_records(source: str, path: str):
    tree = ast.parse(source, filename=path)
    records = defaultdict(empty_record)
    scan_python_body(tree.body, "<top-level>", records)
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


def needs_event_body(body, event):
    return not body and bool(event) and Path(event).is_file()


def pr_body():
    body = os.environ.get("PR_BODY", "")
    event = os.environ.get("GITHUB_EVENT_PATH", "")
    if needs_event_body(body, event):
        with open(event, encoding="utf-8") as stream:
            body = (json.load(stream).get("pull_request") or {}).get("body") or ""
    return body


def is_test_source_set(name):
    return "Test" in name or name == "test"


def is_test_source_dir(parent, source_set):
    """`src/<test source set>`: the directory pair that marks a Kotlin test source tree."""
    return parent == "src" and is_test_source_set(source_set)


def reason_on_line(line):
    """The text after a leading 'Test change reason:' marker, or None when the line has none."""
    prefix, separator, reason = line.lstrip().partition("Test change reason:")
    return reason.strip() if separator and not prefix else None


def valid_reason(body):
    reasons = (reason_on_line(line) for line in body.splitlines())
    return any(reason is not None and len(reason) >= REASON_MIN_LENGTH for reason in reasons)


def resolve_base(root, ref):
    """The merge base of HEAD and [ref]; only verified SHAs are passed on to git."""
    if not SAFE_REF.fullmatch(ref):
        raise ValueError("diff-base must be a branch, tag or commit name (no leading '-')")
    resolved = git(root, "rev-parse", "--verify", "--quiet", "--end-of-options", f"{ref}^{{commit}}").decode().strip()
    if not COMMIT_SHA.fullmatch(resolved):
        raise ValueError(f"diff-base does not resolve to a commit: {ref!r}")
    return git(root, "merge-base", resolved, "HEAD").decode().strip()


def changed_test_paths(root, base):
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
    return before, after


def collect_records(root, ref):
    base = resolve_base(root, ref)
    before, after = changed_test_paths(root, base)
    return records_for(root, base, before), records_for(root, "HEAD", after), bool(before)


def compare_tests(name, old, new):
    problems = [f"{name}: @Test/test function removed or renamed: {test} ({count})"
                for test, count in sorted((old["tests"] - new["tests"]).items())]
    old_count, new_count = old["assertions"], new["assertions"]
    if new_count < old_count:
        problems.append(f"{name}: assertion calls decreased: {old_count} -> {new_count}")
    return problems


def compare_disables(name, old, new):
    return [f"{name}: test disable added: {marker} ({count})"
            for marker, count in sorted((new["skips"] - old["skips"]).items())]


def compare_records(old, new, has_before):
    problems = []
    for name in sorted(old):
        problems.extend(compare_tests(name, old[name], new[name]))
    if has_before:
        for name in sorted(old):
            problems.extend(compare_disables(name, old[name], new[name]))
    return problems


def check(root, ref):
    old, new, has_before = collect_records(root, ref)
    return compare_records(old, new, has_before)


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
