#!/usr/bin/env python3
"""Check wire-contract pairs/index and the shrink-only uncovered baseline.

Registry membership and real-handler conformance are checked by WireContractsTest
in :sharedLogic:jvmTest, never guessed from Kotlin source text here.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

BASELINE = "scripts/ci/wire-contract-uncovered-baseline.txt"
SPECS = "docs/reference/contracts"
FIXTURES = "android-compose/sharedLogic/src/commonTest/resources/contracts"
INDEX = SPECS + "/README.md"
NAME = re.compile(r"[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+")


BASELINE_RULES = (
    (lambda entries: all(entry == entry.strip() for entry in entries),
     "Wire-contract baseline entries must not have surrounding whitespace"),
    (lambda entries: entries == sorted(set(entries)), "Wire-contract baseline must contain sorted, unique methods"),
    (lambda entries: all(NAME.fullmatch(entry) for entry in entries), "Wire-contract baseline entries must be dotted method names"),
)


def read_baseline(text):
    entries = [line for line in text.splitlines() if line and not line.startswith("#")]
    for holds, message in BASELINE_RULES:
        if not holds(entries):
            raise ValueError(message)
    return set(entries)


def require_no_growth(accepted, prior):
    added = accepted - prior if prior is not None else set()
    if added:
        raise ValueError("Wire-contract baseline grew (new methods need contracts):\n" + "\n".join("  " + name for name in sorted(added)))


def previous_baseline(root, diff_base):
    # Reuse the hardened Git-ref validation and first-introduction semantics of the KDoc ratchet.
    path = Path(__file__).with_name("check-api-doc-undocumented.py")
    spec = importlib.util.spec_from_file_location("api_doc_ratchet", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    commit = module.resolve_commit(root, diff_base)
    tree = subprocess.run(["git", "ls-tree", "--name-only", commit, "--", BASELINE], cwd=root, capture_output=True, text=True, check=True)
    if not tree.stdout.strip():
        return None
    prior = subprocess.run(["git", "show", f"{commit}:{BASELINE}"], cwd=root, capture_output=True, text=True, check=True)
    return read_baseline(prior.stdout)


def contracts(root):
    specs = {p.relative_to(root / SPECS).with_suffix("").as_posix(): p for p in (root / SPECS).rglob("*.md") if p != root / INDEX}
    fixtures = {p.relative_to(root / FIXTURES).with_suffix("").as_posix(): p for p in (root / FIXTURES).rglob("*.json")}
    require_pairs(specs, fixtures)
    return [contract_row(root, key, path, specs[key]) for key, path in sorted(fixtures.items())]


def require_pairs(specs, fixtures):
    errors = [
        label + ":\n" + "\n".join("  " + value for value in sorted(values))
        for label, values in (("Specs without fixture", specs.keys() - fixtures.keys()), ("Fixtures without spec", fixtures.keys() - specs.keys()))
        if values
    ]
    if errors:
        raise ValueError("\n".join(errors))
    if not fixtures:
        raise ValueError("Expected at least one wire-contract fixture")


def contract_row(root, key, path, spec):
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"{path}: fixture must be a JSON object")
    method, version = data.get("method"), data.get("version")
    require_identity(path, key.split("/"), method, version)
    require_cases(path, data.get("cases"))
    handler = data.get("handler")
    if not is_repository_file(root, handler):
        raise ValueError(f"{path}: handler must name a repository file")
    return (method, version, spec.relative_to(root).as_posix(), path.relative_to(root).as_posix(), handler)


def require_identity(path, parts, method, version):
    if len(parts) != 2 or not isinstance(method, str) or method != parts[0] or not NAME.fullmatch(method):
        raise ValueError(f"{path}: method/path mismatch")
    if type(version) is not int or version < 1 or not re.fullmatch(r"[a-z0-9-]+-v" + str(version), parts[1]):
        raise ValueError(f"{path}: version/path mismatch")


def require_cases(path, cases):
    if not isinstance(cases, list) or not cases:
        raise ValueError(f"{path}: expected non-empty cases")
    names = set()
    for case in cases:
        name = case.get("name") if isinstance(case, dict) else None
        if not isinstance(name, str) or not name or name in names:
            raise ValueError(f"{path}: cases need unique non-empty names")
        names.add(name)
        missing = [field for field in CASE_FIELDS if not isinstance(case.get(field), dict)]
        if missing:
            raise ValueError(f"{path}: case {name} requires object {missing[0]}")


def is_repository_file(root, handler):
    if not isinstance(handler, str):
        return False
    target = root / handler
    return target.resolve().is_relative_to(root.resolve()) and target.is_file()


CASE_FIELDS = ("context", "host_state", "params", "expected_response")


def render_index(rows):
    text = "# Admin RPC Wire Contracts\n\nGenerated by `python3 scripts/ci/check-wire-contracts.py --write-index`; do not edit.\n\n"
    text += "Registry coverage and real-handler conformance: `:sharedLogic:jvmTest` (`WireContractsTest`).\n"
    text += "Uncovered methods live in `scripts/ci/wire-contract-uncovered-baseline.txt`, which only shrinks.\n\n"
    text += "| Method | Version | Spec | Fixture | Implementing Handler |\n| --- | --- | --- | --- | --- |\n"
    for method, version, spec, fixture, handler in sorted(rows):
        text += f"| `{method}` | v{version} | [{Path(spec).name}](../../../{spec}) | [{Path(fixture).name}](../../../{fixture}) | [{Path(handler).name}](../../../{handler}) |\n"
    return text


def check_index(root, rows, write=False):
    path = root / INDEX
    expected = render_index(rows)
    if write:
        path.write_text(expected, encoding="utf-8", newline="\n")
    elif not path.is_file() or path.read_text(encoding="utf-8") != expected:
        raise ValueError("Stale wire-contract index; run python3 scripts/ci/check-wire-contracts.py --write-index")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--diff-base", default="origin/main")
    parser.add_argument("--check", action="store_true", help="Check generated index (the default)")
    parser.add_argument("--write-index", action="store_true")
    args = parser.parse_args(argv)
    try:
        accepted = read_baseline((args.root / BASELINE).read_text(encoding="utf-8"))
        require_no_growth(accepted, previous_baseline(args.root, args.diff_base))
        rows = contracts(args.root)
        check_index(args.root, rows, args.write_index)
        print(f"Wire-contract tooling: OK ({len(rows)} contracts; {len(accepted)} uncovered methods; registry checked by :sharedLogic:jvmTest)")
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
