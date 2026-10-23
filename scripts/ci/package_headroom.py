#!/usr/bin/env python3
"""Package headroom and import-cycle check in seconds, without Gradle (letta-mobile-ivqlv).

CI's structure contract (tools/structure_report/structure_report.py over the architecture-tests
`structureReport` export) fails a PR when a package declares more named types than
android-compose/architecture-tests/structure-baseline.json allows (default 50) or when an import
edge joins a new directed package cycle. This tool rebuilds that export from source with the same
rules and hands it to structure_report's own counting functions, so its numbers are CI's numbers:

  scope   every directory directly under a `src/` in android-compose whose name is not a test set
          (`*test`, `*testFixtures*`), outside build/generated/ksp paths and the vendored drawbox
          module (KotlinSourcePolicy.productionScope); `.kt` files only.
  types   named classes, interfaces and objects (companions included), nested ones too, local and
          anonymous ones not; counted once per (module, kind, fully qualified name).
  edges   an import charges its file's package to the longest declared package prefixing it when
          what follows is a type, member or single top-level name (PackageImportGraph).

Usage: package_headroom.py [--changed [--base REF]] [--package PKG ...] [--would-import A->B ...]
                           [--compare report.json] [--root DIR]
  (no mode)        check the whole tree against the baseline; print the tightest packages.
  --changed        also print headroom for every package a file changed since REF touches.
  --package PKG    print headroom for PKG (at plan time: where you will add types).
  --would-import   pretend package A imports package B: report the cycle it would create.
  --compare FILE   prove parity with a CI kotlin-structure-report report.json.
Exit status: 0 clean, 1 contract failure / new cycle / parity mismatch, 2 usage error.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from collections import deque
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools/structure_report"))
import kotlin_source_scan as k  # noqa: E402
import structure_report as sr  # noqa: E402

PRUNED_DIRS = {"build", "generated", "ksp", ".gradle", ".git", "node_modules"}
VENDORED_MODULES = {"drawbox"}
TIGHT_HEADROOM = 5

_LITERALS = frozenset({"comment", "string", "char"})
_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z_0-9]*")
_PACKAGE = re.compile(r"(?m)^[ \t]*package[ \t]+([\w.`]+)")
_IMPORT = re.compile(r"(?m)^[ \t]*import[ \t]+([\w.`*]+)")
_HEADER = re.compile(r"(?<![\w$.:])(companion[ \t]+)?\b(class|interface|object)\b(?:[ \t\n]+`?([A-Za-z_]\w*)`?)?")
_MODIFIER = re.compile(
    r"(?:@[\w.]+(?:\([^)\n]*\))?|public|internal|private|protected|data|value|open|abstract|sealed|inner|annotation|"
    r"enum|expect|actual|final|external|const|inline|suspend|operator|infix|tailrec|override|lateinit|companion)"
    r"[ \t]+"
)
_DECLARATION_START = re.compile(r"(?:fun|val|var|class|interface|object|typealias|init)\b")
_HEADER_STOP = re.compile(r"[()\[\]{};=\n]")
_NESTING = {"(": 1, "[": 1, ")": -1, "]": -1}
NEWLINE = "\n"
_PLANNED_EDGE = re.compile(r"\s*([\w.]+)\s*->\s*([\w.]+)\s*")


# --- export: the same records StructureSourceExport writes ------------------------------------

def _is_test_set(name: str) -> bool:
    return name.lower().endswith("test") or "testfixtures" in name.lower()


def production_files(repo: Path) -> list[Path]:
    """KotlinSourcePolicy.productionScope: `.kt` files under each production source set."""
    found = set()
    for directory, subdirs, _ in os.walk(repo / k.ANDROID_COMPOSE):
        subdirs[:] = sorted(d for d in subdirs if d not in PRUNED_DIRS)
        if Path(directory).name == "src":
            found.update(_production_sources(Path(directory), subdirs))
    return sorted(found)


def _production_sources(src: Path, source_sets: list[str]) -> list[Path]:
    """Files of the non-test source sets under [src]; a vendored module's src/ has none."""
    if src.parent.name in VENDORED_MODULES:
        return []
    sets = [src / name for name in source_sets if not _is_test_set(name)]
    return [path for source_set in sets for path in source_set.rglob("*.kt") if not _generated(path)]


def _generated(path: Path) -> bool:
    return any(part in PRUNED_DIRS for part in path.parts)


def masked(text: str) -> str:
    """[text] with comments, strings and char literals blanked (offsets and newlines kept)."""
    return k.blank_literals(text, _LITERALS)


def _brace_pairs(code: str) -> list[tuple[int, int]]:
    pairs, stack = [], []
    for m in re.finditer(r"[{}]", code):
        if m.group(0) == "{":
            stack.append(m.start())
        elif stack:
            pairs.append((stack.pop(), m.start()))
    return pairs


def _continues_header(line: str) -> bool:
    """A depth-0 line after a type header still belongs to it (supertypes, `where`, a constructor).

    A blank line does too; a line starting another declaration, or closing the scope, does not.
    """
    rest = line.lstrip()
    while (m := _MODIFIER.match(rest)) is not None:
        rest = rest[m.end():]
    return not _DECLARATION_START.match(rest) and not rest.startswith("}")


def _ends_header(code: str, at: int) -> bool:
    """[at] is a depth-0 `{`, `}`, `;`, `=` or newline after a type header: does the header stop there?"""
    if code[at] != NEWLINE:
        return True
    end = code.find(NEWLINE, at + 1)
    return not _continues_header(code[at + 1 : len(code) if end < 0 else end])


def _body_open(code: str, start: int) -> int | None:
    """Offset of the `{` opening the body of the type header ending at [start], or None."""
    depth = 0
    for m in _HEADER_STOP.finditer(code, start):
        if m.group(0) in _NESTING:
            depth += _NESTING[m.group(0)]
        elif depth == 0 and _ends_header(code, m.start()):
            return m.start() if m.group(0) == "{" else None
    return None


@dataclass(frozen=True)
class _Header:
    kind: str
    name: str
    start: int
    body: tuple[int, int] | None


def _named_headers(code: str, pairs: list[tuple[int, int]]) -> list[_Header]:
    """Named class/interface/object headers (an unnamed companion is `Companion`; anonymous objects skipped)."""
    closes = dict(pairs)
    result = []
    for m in _HEADER.finditer(code):
        name = m.group(3) or ("Companion" if m.group(1) else None)
        if name is not None:
            opened = _body_open(code, m.end())
            body = None if opened is None else (opened, closes.get(opened, len(code)))
            result.append(_Header(m.group(2), name, m.start(), body))
    return result


def declared_types(code: str) -> list[tuple[str, str]]:
    """(kind, dotted name relative to the package) of every non-local named type in [code]."""
    pairs = _brace_pairs(code)
    headers = _named_headers(code, pairs)
    type_bodies = {header.body: header.name for header in headers if header.body is not None}
    found = []
    for header in headers:
        enclosing = sorted(pair for pair in pairs if pair[0] < header.start < pair[1])
        if all(pair in type_bodies for pair in enclosing):
            found.append((header.kind, ".".join([*(type_bodies[pair] for pair in enclosing), header.name])))
    return found


def file_records(repo: Path, path: Path) -> tuple[dict, list[dict], list[str]]:
    """The file row, its type rows and its import paths, as StructureSourceExport records them."""
    relative = k.rel(repo, path)
    code = masked(k.read(path))
    package_match = _PACKAGE.search(code)
    package = package_match.group(1).replace("`", "") if package_match else ""
    module = relative.split(k.ANDROID_COMPOSE + "/", 1)[-1].split("/src/", 1)[0]
    file_row = {"type": "file", "path": relative, "module": module, "package": package}
    prefix = package + "." if package else ""
    types = [dict(file_row, type="type", name=prefix + name, kind=kind) for kind, name in declared_types(code)]
    return file_row, types, [m.group(1).replace("`", "") for m in _IMPORT.finditer(code)]


def export_lines(repo: Path) -> list[str]:
    """The file, type and importEdge lines of StructureSourceExport's sources.jsonl for [repo].

    Its `public` rows feed only the advisory unused-declaration table, so they are not rebuilt.
    """
    structure = scan(repo)
    mentions = {path: " ".join(sorted(set(_IDENTIFIER.findall(k.read(repo / path))))) for path in
                (row["path"] for row in structure.files)}
    rows = [dict(row, mentions=mentions[row["path"]]) for row in structure.files] + structure.types
    rows += [{"type": "importEdge", "from": source, "to": target} for source, target in sorted(structure.edges)]
    return [json.dumps(row, separators=(",", ":"), ensure_ascii=False) for row in rows]


def owning_package(import_path: str, declared: set[str]) -> str | None:
    """PackageImportGraph.owningPackage: the longest declared prefix, if the rest names a member."""
    path = import_path
    while path:
        if path in declared:
            member = import_path[len(path):].lstrip(".")
            return path if not member or member[0].isupper() or "." not in member else None
        path = path.rpartition(".")[0]
    return None


def import_edges(files: list[tuple[str, list[str]]]) -> set[tuple[str, str]]:
    declared = {package for package, _ in files}
    edges = set()
    for package, imports in files:
        for target in (owning_package(item.removesuffix(".*"), declared) for item in imports):
            if target is not None and target != package:
                edges.add((package, target))
    return edges


@dataclass
class Structure:
    files: list[dict]
    types: list[dict]
    edges: set[tuple[str, str]]

    def report(self) -> dict:
        mirrors, _ = sr.name_candidates(self.types)
        return {"packages": sr.package_metrics(self.files, self.types, self.edges),
                "cyclicImportEdges": sr.cyclic_edges(self.edges), "mirroredNameCandidates": mirrors}


def scan(repo: Path) -> Structure:
    files, types, imports = [], [], []
    for path in production_files(repo):
        file_row, file_types, file_imports = file_records(repo, path)
        files.append(file_row)
        types += file_types
        imports.append((file_row["package"], file_imports))
    return Structure(files, types, import_edges(imports))


# --- checks -----------------------------------------------------------------------------------

def load_baseline(repo: Path) -> dict:
    baseline = json.loads((repo / sr.BASELINE).read_text(encoding="utf-8"))
    sr.validate_baseline_shape(baseline)
    return baseline


def headroom_rows(report: dict, baseline: dict, packages: list[str]) -> list[str]:
    counts = {row["package"]: row["types"] for row in report["packages"]}
    rows = []
    for package in packages:
        cap = baseline["packageTypeLimits"].get(package, sr.DEFAULT_TYPE_LIMIT)
        used = counts.get(package, 0)
        note = "  <- FULL: split the package or move types out first" if used >= cap else (
            "  <- tight" if cap - used <= TIGHT_HEADROOM else "")
        rows.append(f"  {package}: {used}/{cap} types, headroom {cap - used}{note}")
    return rows


def contract_errors(report: dict, baseline: dict) -> list[str]:
    """structure_report's drift errors (size over/under baseline, new or stale cyclic edges)."""
    return sr.baseline_drift(sr.expected_baseline(report), baseline)


def parse_edge(text: str) -> tuple[str, str]:
    match = _PLANNED_EDGE.fullmatch(text)
    if match is None:
        raise argparse.ArgumentTypeError(f"expected PKG_A->PKG_B, got {text!r}")
    return match.group(1), match.group(2)


def would_import(structure: Structure, baseline: dict, planned: list[tuple[str, str]]) -> list[str]:
    """New cyclic edges [planned] imports would add beyond the baseline (and today's tree)."""
    allowed = set(baseline["cyclicImportEdges"]) | set(sr.cyclic_edges(structure.edges))
    return sorted(set(sr.cyclic_edges(structure.edges | set(planned))) - allowed)


def import_path(edges: set[tuple[str, str]], source: str, target: str) -> list[str]:
    """A shortest chain of imports from [source] to [target], or [] when none exists."""
    adjacency: dict[str, list[str]] = {}
    for start, end in sorted(edges):
        adjacency.setdefault(start, []).append(end)
    previous, pending = {source: source}, deque([source])
    while pending and target not in previous:
        node = pending.popleft()
        for following in adjacency.get(node, []):
            if following not in previous:
                previous[following] = node
                pending.append(following)
    if target not in previous:
        return []
    chain = [target]
    while chain[-1] != source:
        chain.append(previous[chain[-1]])
    return chain[::-1]


def compare(report: dict, ci_report: dict) -> list[str]:
    """Every difference between this scan and a CI report.json, per package and per cyclic edge."""
    keys = ("types", "files", "fanIn", "fanOut")
    ours = {row["package"]: row for row in report["packages"]}
    theirs = {row["package"]: row for row in ci_report["packages"]}
    problems = [f"package {p}: ours {[ours.get(p, {}).get(k) for k in keys]} vs CI {[theirs.get(p, {}).get(k) for k in keys]}"
                for p in sorted(ours.keys() | theirs.keys())
                if [ours.get(p, {}).get(k) for k in keys] != [theirs.get(p, {}).get(k) for k in keys]]
    for name in ("cyclicImportEdges", "mirroredNameCandidates"):
        if report[name] != ci_report[name]:
            problems.append(f"{name} differ: {len(report[name])} ours vs {len(ci_report[name])} CI")
    return problems


def changed_packages(repo: Path, base: str, structure: Structure) -> list[str]:
    def git(*args: str) -> list[str]:
        return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True).stdout.split("\n")

    merge_base = git("merge-base", base, "HEAD")[0].strip()
    paths = set(git("diff", "--name-only", merge_base)) | set(git("ls-files", "--others", "--exclude-standard"))
    by_path = {row["path"]: row["package"] for row in structure.files}
    return sorted({by_path[path] for path in paths if path in by_path})


def _report_compare(report: dict, path: Path) -> bool:
    problems = compare(report, json.loads(path.read_text(encoding="utf-8")))
    print("\n".join(problems) or f"Parity with {path}: {len(report['packages'])} packages and "
                                 f"{len(report['cyclicImportEdges'])} cyclic edges identical")
    return bool(problems)


def _report_contract(report: dict, baseline: dict) -> bool:
    errors = contract_errors(report, baseline)
    for error in errors:
        print(f"FAIL structure: {error}")
    if errors:
        print("  fix: split a full package (or move types out); remove the import that closes a cycle; "
              f"lower stale lines in {sr.BASELINE} (it only shrinks)")
    return bool(errors)


def _report_would_import(structure: Structure, baseline: dict, planned: list[tuple[str, str]]) -> bool:
    new = would_import(structure, baseline, planned)
    for edge in new:
        print(f"FAIL would-import: creates cyclic edge {edge}")
    for source, target in planned:
        back = import_path(structure.edges, target, source)
        if back:
            print(f"  {source} -> {target} closes: " + " -> ".join(back))
    if not new:
        print("would-import: no new cycle for " + ", ".join(f"{a} -> {b}" for a, b in planned))
    return bool(new)


def _report_tightest(report: dict, baseline: dict) -> None:
    limits = baseline["packageTypeLimits"]
    rows = sorted((limits.get(row["package"], sr.DEFAULT_TYPE_LIMIT) - row["types"], row["package"]) for row in report["packages"])
    full = [package for room, package in rows if room <= 0]
    print(f"{len(full)} packages are at their type cap (every package over {sr.DEFAULT_TYPE_LIMIT} types is); "
          "use --package PKG to check one. Tightest with headroom:")
    print("\n".join(headroom_rows(report, baseline, [package for room, package in rows if room > 0][:8])))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--changed", action="store_true")
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--package", action="append", default=[])
    parser.add_argument("--would-import", action="append", default=[], type=parse_edge)
    parser.add_argument("--compare", type=Path)
    args = parser.parse_args(argv)
    repo = args.root.resolve()
    structure = scan(repo)
    report = structure.report()
    baseline = load_baseline(repo)

    failed = bool(args.compare) and _report_compare(report, args.compare)
    contract_failed = _report_contract(report, baseline)
    packages = sorted(set(args.package + (changed_packages(repo, args.base, structure) if args.changed else [])))
    if packages:
        print("Package headroom (types used/cap):")
        print("\n".join(headroom_rows(report, baseline, packages)))
    failed |= bool(args.would_import) and _report_would_import(structure, baseline, args.would_import)
    if not packages and not args.would_import:
        _report_tightest(report, baseline)
    print(f"Structure: {len(report['packages'])} packages, {len(report['cyclicImportEdges'])} cyclic edges, "
          f"{'FAIL' if contract_failed else 'contract OK'}")
    return int(failed or contract_failed)


if __name__ == "__main__":
    sys.exit(main())
