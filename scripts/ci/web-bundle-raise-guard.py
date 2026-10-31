#!/usr/bin/env python3
"""Require owner approval in the PR description for web bundle ceiling increases."""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

from git_ref import resolve_commit

ROOT = Path(__file__).resolve().parents[2]
BUDGET = "android-compose/web/bundle-budget.json"
APPROVAL = "Budget raise approved-by: oculairmedia"
APPROVAL_LINE = re.compile(r"^[ \t]*" + re.escape(APPROVAL) + r"[ \t]*$", re.MULTILINE)


def ceilings(data: dict) -> dict[str, int]:
    groups = data["groups"]
    if not isinstance(groups, dict):
        raise ValueError("groups must be an object")
    result = {}
    for name, group in groups.items():
        value = group["ceiling_gzip_bytes"]
        if type(value) is not int or value < 0:
            raise ValueError(f"{name}: ceiling_gzip_bytes must be a non-negative integer")
        result[name] = value
    return result


def pr_body() -> str:
    body = os.environ.get("PR_BODY", "")
    if not body and os.environ.get("GITHUB_EVENT_PATH"):
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
        body = event.get("pull_request", {}).get("body") or ""
    return body


def check(root: Path, ref: str, body: str) -> list[str]:
    resolved = resolve_commit(root, ref)
    old = subprocess.run(["git", "-C", str(root), "show", f"{resolved}:{BUDGET}"], capture_output=True, text=True, check=True).stdout
    before = ceilings(json.loads(old))
    after = ceilings(json.loads((root / BUDGET).read_text(encoding="utf-8")))
    raised = [f"{name}: {before.get(name, 0)} -> {value}" for name, value in sorted(after.items()) if name not in before or value > before[name]]
    return [] if APPROVAL_LINE.search(body.replace("\r", "")) else raised


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--diff-base", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        problems = check(args.root.resolve(), args.diff_base, pr_body())
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        print(f"web-bundle-raise-guard: {error}", file=sys.stderr)
        return 2
    if problems:
        print(f"::error title=Web bundle ceiling raised::Add '{APPROVAL}' to the PR description: {', '.join(problems)}", file=sys.stderr)
        return 1
    print(f"web-bundle-raise-guard: ok vs {args.diff_base}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
