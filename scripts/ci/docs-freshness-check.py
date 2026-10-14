#!/usr/bin/env python3
"""Fail when the top-level docs drift from the tree they describe.

Every check compares a doc claim against a source of truth already in the repo,
so the docs cannot silently go stale when a module, gate or link changes:

  modules   every `include(":x")` in settings.gradle.kts is named in the README
            module tables, and every path the root README module table names exists
  tasks     every `:module:task` reference names a declared Gradle module
  gates     the README "Required CI gates" list equals CHECKS in
            scripts/bootstrap-branch-protection.sh, and each gate is a workflow job
  links     relative markdown links resolve
  gradle    "Gradle X.Y.Z" matches the wrapper's distributionUrl

Usage: docs-freshness-check.py [repo-root]   (defaults to this script's repo)
Runs in the required `test` job of .github/workflows/android.yml.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[2])
BUILD = ROOT / "android-compose"

# Docs whose links, task references and Gradle version are checked.
DOCS = ["README.md", "CONTRIBUTING.md", "CLAUDE.md", "AGENTS.md", "android-compose/README.md"]
# Docs that must name every Gradle module.
MODULE_DOCS = ["README.md", "android-compose/README.md"]

errors: list[str] = []


def fail(doc: str, msg: str) -> None:
    errors.append(f"{doc}: {msg}")


def read(rel: str) -> str | None:
    path = ROOT / rel
    return path.read_text(encoding="utf-8") if path.is_file() else None


def section(text: str, heading: str) -> str:
    """Body of the `## heading` section, up to the next heading of level <= 2."""
    match = re.search(rf"^##\s+{re.escape(heading)}\s*$(.*?)(?=^#{{1,2}}\s|\Z)", text, re.M | re.S)
    return match.group(1) if match else ""


def strip_code_fences(text: str) -> str:
    return re.sub(r"^```.*?^```", "", text, flags=re.M | re.S)


def declared_modules() -> list[str]:
    settings = (BUILD / "settings.gradle.kts").read_text(encoding="utf-8")
    return re.findall(r'^\s*include\(\s*"(:[^"]+)"\s*\)', settings, re.M)


def present_docs(docs: list[str]):
    """(doc, text) for each doc that exists."""
    return [(doc, text) for doc in docs if (text := read(doc)) is not None]


def module_path(module: str) -> str:
    return module.lstrip(":").replace(":", "/")


def check_modules(modules: list[str]) -> None:
    for doc in (d for d in MODULE_DOCS if read(d) is None):
        fail(doc, "missing")
    for doc, text in present_docs(MODULE_DOCS):
        check_modules_named(doc, text, modules)
    check_module_table_rows()


def check_modules_named(doc: str, text: str, modules: list[str]) -> None:
    named = set(re.findall(r"`([A-Za-z0-9_./-]+?)/?`", text))
    for module in (m for m in modules if module_path(m) not in named):
        fail(doc, f"module {module} is not named (expected `{module_path(module)}/` in the module table)")


def check_module_table_rows() -> None:
    """No stale rows: paths need not be modules (perf/, native/, build-logic/), but must exist."""
    table = section(read("README.md") or "", "Module layout")
    rows = re.findall(r"^\|([^|\n]*)\|", table, re.M)
    paths = [path for row in rows for path in re.findall(r"`([A-Za-z0-9_./-]+)/`", row)]
    for path in (p for p in paths if not (BUILD / p).is_dir()):
        fail("README.md", f"module table names `{path}/`, which does not exist under android-compose/")


def task_refs(text: str) -> set[str]:
    # A negated mention ("not a `:core:schemas` Gradle module") is a claim that it is absent.
    text = re.sub(r"\b(?:not an?|no)\s+`:[\w:-]+`", "", text)
    return set(re.findall(r"(?<![\w:/.-])(:[a-z][\w-]*(?::[\w-]+)+)", text))


def owns_module(ref: str, known: set[str]) -> bool:
    """True when the ref is a module, or a module followed by a task name."""
    parts = ref.split(":")[1:]
    return any(":" + ":".join(parts[:i]) in known for i in range(1, len(parts) + 1))


def check_tasks(modules: list[str]) -> None:
    known = set(modules)
    for doc, text in present_docs(DOCS):
        for ref in sorted(r for r in task_refs(text) if not owns_module(r, known)):
            fail(doc, f"`{ref}` does not start with a declared Gradle module")


def jobs_in(workflow_text: str) -> set[str]:
    """Job ids and `name:` values under the top-level `jobs:` key."""
    jobs = workflow_text.split("\njobs:", 1)[1] if "\njobs:" in workflow_text else ""
    ids = re.findall(r"^  ([A-Za-z0-9_-]+):\s*$", jobs, re.M)
    names = re.findall(r"^    name:\s*['\"]?(.+?)['\"]?\s*$", jobs, re.M)
    return set(ids) | set(names)


def workflow_jobs() -> set[str]:
    workflows = (ROOT / ".github" / "workflows").glob("*.y*ml")
    return set().union(*(jobs_in(w.read_text(encoding="utf-8")) for w in workflows))


def check_gates() -> None:
    bootstrap = read("scripts/bootstrap-branch-protection.sh") or ""
    match = re.search(r"^CHECKS=\(([^)]*)\)", bootstrap, re.M)
    if not match:
        fail("scripts/bootstrap-branch-protection.sh", "CHECKS=(...) not found")
        return
    required = match.group(1).split()

    body = section(read("README.md") or "", "Required CI gates")
    listed = re.findall(r"^- `([^`]+)`", body, re.M)
    if sorted(listed) != sorted(required):
        fail("README.md", f"Required CI gates lists {listed}; branch protection requires {required}")

    jobs = workflow_jobs()
    for gate in (g for g in required if g not in jobs):
        fail("scripts/bootstrap-branch-protection.sh", f"required gate `{gate}` is not a workflow job")


def relative_links(text: str) -> list[str]:
    targets = re.findall(r"\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)", strip_code_fences(text))
    return [t for t in targets if not (re.match(r"^[a-z][a-z0-9+.-]*:", t, re.I) or t.startswith("#"))]


def check_links() -> None:
    for doc, text in present_docs(DOCS):
        base = (ROOT / doc).parent
        for target in relative_links(text):
            if not (base / target.split("#", 1)[0]).exists():
                fail(doc, f"broken link ({target})")


def check_gradle_version() -> None:
    wrapper = (BUILD / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
    match = re.search(r"gradle-([0-9][\w.-]*?)-(?:bin|all)\.zip", wrapper)
    if not match:
        fail("gradle-wrapper.properties", "distributionUrl version not found")
        return
    version = match.group(1)
    for doc, text in present_docs(DOCS):
        for found in re.findall(r"\bGradle ([0-9]+\.[0-9]+(?:\.[0-9]+)?)\b", text):
            if found != version:
                fail(doc, f"says Gradle {found}; the wrapper is {version}")


def main() -> int:
    modules = declared_modules()
    check_modules(modules)
    check_tasks(modules)
    check_gates()
    check_links()
    check_gradle_version()
    if errors:
        print("Docs are out of date with the tree:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        print("Fix the doc (or the source of truth) and re-run scripts/ci/docs-freshness-check.py.",
              file=sys.stderr)
        return 1
    print(f"docs-freshness: OK ({len(modules)} modules, {len(DOCS)} docs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
