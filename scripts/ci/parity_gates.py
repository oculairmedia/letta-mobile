#!/usr/bin/env python3
"""Platform parity guardrails (letta-mobile-ewtaq; absorbs letta-mobile-ckmub.17.8).

Owner rule (2026-10-21): no unshared frontend or logic. Desktop, Android and web bind the shared
code in sharedLogic/sharedUI; they never reimplement it. Three source gates enforce that, each
against a committed shrink-only baseline under scripts/ci/parity/ (scripts/ci/baseline-ratchet-guard.py
rejects any line added or raised there):

  parity         every binding contract (a shared *Host/*Slots/*Actions/*Services/*Capabilities/
                 *Events/*Pages/*Binding(s)/*Contribution class a platform constructs) is bound
                 by every platform that constructs it, param by param: a param left at its default
                 or bound to null, an empty lambda, Unit, false, an empty collection, or an
                 Unavailable*/Noop*/NONE/Empty* value is unbound. The core contracts in
                 parity-contracts.txt must be constructed by every platform (directly or through
                 a listed shared builder).
  platform-code  every production .kt file in a platform module (app, feature-*, desktop, web)
                 without @Composable is either a pure binding (each top-level declaration is an
                 `actual`, an `external`, `fun main`, a Hilt/Dagger module, or a class/object that
                 implements a shared or OS/framework type) or listed with its line cap. @Composable
                 platform files are scripts/ci/platform-ui-gate.sh's (platform-ui-allowlist.txt).
  mirror-names   no Desktop*/Web*/Android*/Wasm* type whose remaining name is a shared type it
                 does not implement, or that mirrors another platform's type without both
                 implementing one shared contract of that name.

Usage: parity_gates.py [parity|platform-code|mirror-names|all] [--root DIR] [--print-actual]
                       [--prune]
  --print-actual  print what the tree has now, in baseline format (no reasons), and exit 0.
  --prune         delete stale baseline lines (the only rewrite the tool does: it never adds).
Exit status: 0 clean, 1 violations, 2 usage error.
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kotlin_source_scan as k  # noqa: E402

BASELINE_DIR = Path("scripts/ci/parity")
CONTRACTS_FILE = BASELINE_DIR / "parity-contracts.txt"
PLATFORM_CODE_SLACK = 5
PLATFORMS = list(k.PLATFORM_MODULES)

CONTRACT_SUFFIX = re.compile(r"\w(Host|Slots|Actions|Services|Capabilities|Events|Pages|Bindings?|Contributions?)$")
_UNBOUND_VALUE = re.compile(
    r"^(null|Unit|false|\{\s*\}|\{\s*Unit\s*\}|\{\s*[\w\s,:<>?]*->\s*(Unit)?\s*\}|"
    r"(emptyList|emptyMap|emptySet|listOf|mapOf|setOf|persistentListOf|persistentMapOf)\(\s*\))$"
)
_UNBOUND_HEAD = re.compile(r"^(?:[\w.]*\.)?(Unavailable\w*|Noop\w*|NoOp\w*|NONE|Empty[A-Z]\w*)\b")
OS_PACKAGES = (
    "android.", "androidx.", "java.", "javax.", "kotlinx.browser.", "org.w3c.", "com.sun.", "sun.",
    "dagger.", "com.google.", "org.cef.", "me.friwi.jcefmaven.",
)
# Framework base classes that hold logic rather than bind the OS: a ViewModel is a controller, and
# controllers are shared (sharedLogic), so extending one is not a binding.
LOGIC_CONTAINERS = ("androidx.lifecycle.ViewModel", "androidx.lifecycle.AndroidViewModel")
BEAD_REF = re.compile(r"\bletta-mobile-[a-z0-9]+(?:\.[0-9]+)*")
PLATFORM_PREFIX = re.compile(r"^(Desktop|Web|Android|Wasm)([A-Z]\w*)$")
_TOP_DECL = re.compile(
    r"(?m)^(?P<mods>(?:@[\w.]+(?:\([^)\n]*\))?[ \t]*\n?[ \t]*|(?:public|internal|private|protected|data|value|open|abstract|sealed|"
    r"inner|annotation|enum|expect|actual|final|external|const|inline|suspend|operator|infix|tailrec|override|lateinit)[ \t]+)*)"
    r"(?P<kind>class|interface|object|typealias|fun|val|var)\b(?P<rest>[^\n]*)"
)
_DECLARED_NAME = re.compile(r"\s+\w+")
_TYPE_HEAD = re.compile(r"\b(?:class|interface|object|typealias)\s+([A-Z]\w*)")


# --- baselines: one shrink-only comparison for every gate ------------------------------------

@dataclass(frozen=True)
class Baseline:
    """A baseline file: `<key> [<cap>] [| reason]` lines under a `#` header."""

    path: Path
    counted: bool = False
    reasoned: bool = False

    def lines(self) -> list[str]:
        if not self.path.is_file():
            return []
        return [line.rstrip() for line in self.path.read_text(encoding="utf-8").splitlines() if line.strip() and not line.startswith("#")]

    def key(self, line: str) -> str:
        head = line.partition("|")[0].split()
        return " ".join(head[:1] if self.counted else head)

    def entries(self) -> dict[str, tuple[int, str]]:
        return {self.key(line): (_cap(line) if self.counted else 0, line.partition("|")[2].strip()) for line in self.lines()}

    def prune(self, stale: set[str]) -> None:
        target = _baseline_target(self.path)
        text = target.read_text(encoding="utf-8").splitlines()
        header = [line for line in text if line.startswith("#")]
        keep = [line for line in self.lines() if self.key(line) not in stale]
        target.write_text("\n".join(header + keep) + "\n", encoding="utf-8")


BASELINE_NAMES = frozenset({"parity-exemptions.txt", "platform-code-baseline.txt", "mirror-name-baseline.txt"})


def _baseline_target(path: Path) -> Path:
    """Only the gates' own baselines under scripts/ci/parity/ are ever rewritten."""
    resolved = path.resolve()
    if resolved.name not in BASELINE_NAMES or resolved.parent.name != BASELINE_DIR.name:
        raise ValueError(f"refusing to rewrite '{path}': not a parity baseline")
    return resolved.parent / resolved.name


def _cap(line: str) -> int:
    return int(line.split()[1])


@dataclass(frozen=True)
class Messages:
    new: str
    grew: str
    stale: str


def _grown(actual: dict[str, int], known: dict[str, tuple[int, str]], slack: int) -> dict[str, int]:
    return {key: count for key, count in actual.items() if count > known.get(key, (count, ""))[0] + slack}


def _unexplained(known: dict[str, tuple[int, str]]) -> list[str]:
    return [
        f"EXEMPTION WITHOUT A REASON AND BEAD: '{key}' - every entry is '<platform> <Contract>[.<param>] | "
        "<reason naming the letta-mobile-<id> bead that removes it>'"
        for key, (_, why) in known.items()
        if not BEAD_REF.search(why)
    ]


def _unsorted(baseline: Baseline) -> list[str]:
    keys = [baseline.key(line) for line in baseline.lines()]
    ordered = keys == sorted(set(keys))
    return [] if ordered else [f"{baseline.path.name} must be sorted with one entry per line"]


def drift(actual: dict[str, int], baseline: Baseline, slack: int, messages: Messages) -> tuple[list[str], set[str]]:
    """(problems, stale keys) of [actual] against [baseline]; counts above cap + [slack] grew."""
    known = baseline.entries()
    problems = [messages.new.format(key=key, count=count) for key, count in actual.items() if key not in known]
    if baseline.counted:
        grown = _grown(actual, known, slack)
        problems += [messages.grew.format(key=key, count=count, cap=known[key][0], slack=slack) for key, count in grown.items()]
    if baseline.reasoned:
        problems += _unexplained(known)
    return problems + _unsorted(baseline), set(known) - set(actual)


# --- parity ---------------------------------------------------------------------------------

def unbound(value: str) -> bool:
    value = value.strip()
    return bool(_UNBOUND_VALUE.match(value) or _UNBOUND_HEAD.match(value))


def core_contracts(repo: Path) -> dict[str, list[str]]:
    """parity-contracts.txt: `<Contract> [shared builder names...]`."""
    rows = [line.split() for line in Baseline(repo / CONTRACTS_FILE).lines()]
    return {row[0]: row[1:] for row in rows}


def _bound_value(call: k.Call, index: int, param: k.Param, count: int) -> str | None:
    positional = call.positional[index] if index < len(call.positional) else None
    trailing = call.trailing_lambda if index == count - 1 else None
    return call.named.get(param.name, positional or trailing)


def _binds(call: k.Call, index: int, param: k.Param, count: int) -> bool:
    value = _bound_value(call, index, param, count)
    return value is not None and not unbound(value)


@dataclass
class PlatformSources:
    calls: dict[str, list[k.Call]]
    text: str

    def uses(self, builder: str) -> bool:
        return re.search(r"(?<![\w])" + re.escape(builder) + r"\b", self.text) is not None


def _platform_sources(repo: Path, paths: list[Path], candidates: set[str]) -> PlatformSources:
    calls: dict[str, list[k.Call]] = {}
    for path in paths:
        for name, call in k.constructor_calls(repo, path, candidates):
            calls.setdefault(name, []).append(call)
    return PlatformSources(calls, "\n".join(k.strip_comments(k.read(path)) for path in paths))


@dataclass(frozen=True)
class Contract:
    """A shared binding contract: its constructor params and, for a core one, its shared builders."""

    name: str
    params: list[k.Param]
    builders: list[str] | None

    def unbound_params(self, calls: list[k.Call]) -> list[str]:
        count = len(self.params)
        return [p.name for i, p in enumerate(self.params) if not any(_binds(call, i, p, count) for call in calls)]

    def gaps(self, platform: str, sources: PlatformSources) -> list[str]:
        calls = sources.calls.get(self.name)
        if calls:
            return [f"{platform} {self.name}.{param}" for param in self.unbound_params(calls)]
        return [f"{platform} {self.name}"] if self.missing_on(sources) else []

    def missing_on(self, sources: PlatformSources) -> bool:
        """A core contract a platform neither constructs nor reaches through a shared builder."""
        return self.builders is not None and not any(map(sources.uses, self.builders))


def _is_candidate(name: str, found: list[k.SharedClass], core: dict[str, list[str]]) -> bool:
    named_like_contract = CONTRACT_SUFFIX.search(name) is not None or name in core
    return len(found) == 1 and named_like_contract


def _scan_platforms(repo: Path, candidates: set[str]) -> dict[str, PlatformSources]:
    return {platform: _platform_sources(repo, paths, candidates) for platform, paths in k.platform_files(repo).items()}


def _gaps(contract: Contract, platforms: dict[str, PlatformSources]) -> list[str]:
    return [gap for platform, sources in platforms.items() for gap in contract.gaps(platform, sources)]


def parity_actual(repo: Path) -> dict[str, int]:
    shared = k.shared_classes(repo, k.shared_files(repo))
    core = core_contracts(repo)
    candidates = {name for name, found in shared.items() if _is_candidate(name, found, core)}
    platforms = _scan_platforms(repo, candidates)
    constructed = set().union(*(sources.calls for sources in platforms.values()))
    contracts = [Contract(name, shared[name][0].params, core.get(name)) for name in sorted(candidates & (constructed | set(core)))]
    return dict.fromkeys((gap for contract in contracts for gap in _gaps(contract, platforms)), 1)


PARITY = Messages(
    new="NEW PARITY GAP: {key} is unbound (a core contract not constructed, or a slot left null/empty/false/default)",
    grew="",
    stale="STALE EXEMPTION (now bound - delete the line, or run with --prune): {key}",
)


# --- platform code --------------------------------------------------------------------------

@dataclass(frozen=True)
class SharedIndex:
    fqns: frozenset[str]

    def is_shared(self, fqn: str) -> bool:
        parts = fqn.split(".")
        return any(".".join(parts[:end]) in self.fqns for end in range(len(parts), 1, -1))


def shared_index(repo: Path) -> SharedIndex:
    fqns = set()
    for path in k.shared_files(repo):
        package = _package(k.strip_comments(k.read(path)))
        fqns.update(f"{package}.{name}" for name in k.declared_type_names(path))
    return SharedIndex(frozenset(fqns))


def _package(text: str) -> str:
    m = re.search(r"(?m)^package\s+([\w.]+)", text)
    return m.group(1) if m else ""


@dataclass(frozen=True)
class Imports:
    exact: dict[str, str]
    wildcard: list[str]
    package: str

    def candidates(self, name: str) -> list[str]:
        head = name.split(".")[0]
        if head[:1].islower():
            return [name]  # written fully qualified
        if head in self.exact:
            return [self.exact[head] + name[len(head):]]
        return [f"{p}.{name}" for p in self.wildcard] + [f"{self.package}.{name}"]


def imports_of(text: str) -> Imports:
    rows = re.findall(r"(?m)^import\s+([\w.]+(?:\.\*)?)(?:\s+as\s+(\w+))?", text)
    exact = {alias or fqn.rsplit(".", 1)[-1]: fqn for fqn, alias in rows if not fqn.endswith(".*")}
    return Imports(exact, [fqn[:-2] for fqn, _ in rows if fqn.endswith(".*")], _package(text))


def _binding_target(fqn: str, index: SharedIndex) -> bool:
    if fqn in LOGIC_CONTAINERS:
        return False
    return fqn.startswith(OS_PACKAGES) or index.is_shared(fqn)


def binds_supertype(name: str, imports: Imports, index: SharedIndex) -> bool:
    """Whether a supertype is a shared type (sharedLogic/sharedUI/core/...) or an OS/framework type."""
    os_wildcard = any(p.startswith(OS_PACKAGES) for p in imports.wildcard) and name.split(".")[0] not in imports.exact
    return os_wildcard or any(_binding_target(fqn, index) for fqn in imports.candidates(name))


def _always_binding(mods: str, kind: str, rest: str) -> bool:
    """`actual`/`external` declarations, `fun main`, and DI modules bind by construction."""
    if re.search(r"\b(actual|external)\b", mods) or re.search(r"@(dagger\.)?Module\b", mods):
        return True
    return kind == "fun" and re.match(r"\s*main\s*\(", rest) is not None


def _is_binding_declaration(m: re.Match[str], top: str, imports: Imports, index: SharedIndex) -> bool:
    if _always_binding(m.group("mods"), m.group("kind"), m.group("rest")):
        return True
    name = _DECLARED_NAME.match(top, m.end("kind")) if m.group("kind") in ("class", "object") else None
    return name is not None and any(binds_supertype(s, imports, index) for s in k.supertypes(top, name.end()))


def is_pure_binding(text: str, index: SharedIndex) -> bool:
    clean = k.strip_comments(text)
    top = k.without_bodies(clean)
    imports = imports_of(clean)
    declarations = list(_TOP_DECL.finditer(top))
    return bool(declarations) and all(_is_binding_declaration(m, top, imports, index) for m in declarations)


def platform_code_actual(repo: Path) -> dict[str, int]:
    index = shared_index(repo)
    texts = {path: k.read(path) for paths in k.platform_files(repo).values() for path in paths}
    return {
        k.rel(repo, path): len(text.splitlines())
        for path, text in texts.items()
        # @Composable files are platform-ui-gate.sh's.
        if "@Composable" not in text and not is_pure_binding(text, index)
    }


PLATFORM_CODE = Messages(
    new="NEW PLATFORM LOGIC FILE: {key} ({count} lines) is not a pure binding",
    grew="PLATFORM LOGIC GREW: {key} has {count} lines, cap {cap} (+{slack} slack)",
    stale="STALE (file gone or now a pure binding - delete the line, or run with --prune): {key}",
)


# --- mirror names ---------------------------------------------------------------------------

def _platform_types(path: Path) -> dict[str, set[str]]:
    """Platform-prefixed types declared in [path], with their supertypes' simple names."""
    clean = k.strip_comments(k.read(path))
    return {
        m.group(1): {s.split(".")[-1] for s in k.supertypes(clean, m.end())}
        for m in _TYPE_HEAD.finditer(clean)
        if PLATFORM_PREFIX.match(m.group(1))
    }


def _rest(name: str) -> str:
    return PLATFORM_PREFIX.match(name).group(2)


def _shared_mirrors(name: str, supers: set[str], shared_names: set[str]) -> list[str]:
    rest = _rest(name)
    return [f"{name} mirrors shared {s}" for s in (rest, "Shared" + rest, "Common" + rest) if s in shared_names and s not in supers]


def _platform_mirrors(name: str, types: dict[str, set[str]], shared_names: set[str]) -> list[str]:
    twins = [other for other in types if other != name and _rest(other) == _rest(name)]
    return [f"{name} mirrors platform {other}" for other in twins if not types[name] & types[other] & shared_names]


def mirror_actual(repo: Path) -> dict[str, int]:
    shared_names = {name for path in k.shared_files(repo) for name in k.declared_type_names(path)}
    types: dict[str, set[str]] = {}
    for paths in k.platform_files(repo).values():
        for path in paths:
            for name, supers in _platform_types(path).items():
                types.setdefault(name, set()).update(supers)
    found = [m for name, supers in types.items() for m in _shared_mirrors(name, supers, shared_names) + _platform_mirrors(name, types, shared_names)]
    return dict.fromkeys(sorted(found), 1)


MIRROR = Messages(
    new="NEW MIRRORED TYPE: {key}",
    grew="",
    stale="STALE (renamed or now implements the shared type - delete the line, or run with --prune): {key}",
)


# --- gates ----------------------------------------------------------------------------------

@dataclass(frozen=True)
class Gate:
    actual: Callable[[Path], dict[str, int]]
    baseline: str
    messages: Messages
    how_to_fix: str
    counted: bool = False
    reasoned: bool = False
    slack: int = 0

    def check(self, repo: Path, prune: bool) -> list[str]:
        baseline = Baseline(repo / BASELINE_DIR / self.baseline, self.counted, self.reasoned)
        problems, stale = drift(self.actual(repo), baseline, self.slack, self.messages)
        if prune and stale:
            baseline.prune(stale)
            return problems
        return problems + [self.messages.stale.format(key=key) for key in sorted(stale)]


GATES = {
    "parity": Gate(
        parity_actual, "parity-exemptions.txt", PARITY, reasoned=True,
        how_to_fix=(
            "Bind the slot on that platform with a real implementation (the shared controller, port or page),\n"
            "or, if the shared default is wrong, make the param required in sharedUI/sharedLogic so every platform\n"
            "must bind it. Never stub it. Do not add an exemption: scripts/ci/parity/parity-exemptions.txt only shrinks."
        ),
    ),
    "platform-code": Gate(
        platform_code_actual, "platform-code-baseline.txt", PLATFORM_CODE, counted=True, slack=PLATFORM_CODE_SLACK,
        how_to_fix=(
            "Move the logic to sharedLogic/commonMain (or the UI to sharedUI/commonMain) and keep only the OS/browser\n"
            "binding here: a class that implements a shared interface/expect, an `actual`, an `external`, or a DI module.\n"
            "scripts/ci/parity/platform-code-baseline.txt only shrinks; a listed file may not grow past its cap + 5."
        ),
    ),
    "mirror-names": Gate(
        mirror_actual, "mirror-name-baseline.txt", MIRROR,
        how_to_fix=(
            "Do not mirror shared or other-platform types. Use the shared type, or make the platform type implement\n"
            "the shared contract of that name (DesktopFoo : Foo). Two platforms needing the same thing means it\n"
            "belongs in sharedLogic/sharedUI. scripts/ci/parity/mirror-name-baseline.txt only shrinks."
        ),
    ),
}


def print_actual(name: str, repo: Path) -> None:
    gate = GATES[name]
    rows = gate.actual(repo)
    print("\n".join(f"{key} {count}" if gate.counted else key for key, count in sorted(rows.items())))


def report(name: str, problems: list[str]) -> bool:
    if not problems:
        print(f"parity-gates {name}: ok")
        return True
    print(f"::error title=parity-gates {name}::{len(problems)} problem(s); see the log")
    print(f"parity-gates {name}: FAILED")
    print("\n".join(f"  {p}" for p in problems))
    print("  What to do:")
    print("\n".join(f"    {line}" for line in GATES[name].how_to_fix.splitlines()))
    return False


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("gate", nargs="?", default="all", choices=[*GATES, "all"])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--print-actual", action="store_true")
    parser.add_argument("--prune", action="store_true")
    args = parser.parse_args(argv)
    repo = args.root.resolve()
    names = list(GATES) if args.gate == "all" else [args.gate]
    if args.print_actual:
        for name in names:
            print_actual(name, repo)
        return 0
    results = [report(name, GATES[name].check(repo, args.prune)) for name in names]
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
