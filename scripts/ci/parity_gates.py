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

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kotlin_source_scan as k  # noqa: E402

BASELINE_DIR = Path("scripts/ci/parity")
CONTRACTS_FILE = BASELINE_DIR / "parity-contracts.txt"
PARITY_FILE = BASELINE_DIR / "parity-exemptions.txt"
PLATFORM_CODE_FILE = BASELINE_DIR / "platform-code-baseline.txt"
MIRROR_FILE = BASELINE_DIR / "mirror-name-baseline.txt"
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

HOW_TO_FIX = {
    "parity": (
        "Bind the slot on that platform with a real implementation (the shared controller, port or page),\n"
        "or, if the shared default is wrong, make the param required in sharedUI/sharedLogic so every platform\n"
        "must bind it. Do not add an exemption: scripts/ci/parity/parity-exemptions.txt only shrinks."
    ),
    "platform-code": (
        "Move the logic to sharedLogic/commonMain (or the UI to sharedUI/commonMain) and keep only the OS/browser\n"
        "binding here: a class that implements a shared interface/expect, an `actual`, an `external`, or a DI module.\n"
        "scripts/ci/parity/platform-code-baseline.txt only shrinks; a listed file may not grow past its cap + 5."
    ),
    "mirror-names": (
        "Do not mirror shared or other-platform types. Use the shared type, or make the platform type implement\n"
        "the shared contract of that name (DesktopFoo : Foo). Two platforms needing the same thing means it\n"
        "belongs in sharedLogic/sharedUI. scripts/ci/parity/mirror-name-baseline.txt only shrinks."
    ),
}


# --- baselines ------------------------------------------------------------------------------

def read_lines(path: Path) -> list[str]:
    if not path.is_file():
        return []
    return [line.rstrip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip() and not line.lstrip().startswith("#")]


def header_lines(path: Path) -> list[str]:
    if not path.is_file():
        return []
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            out.append(line)
        else:
            break
    return out


def keyed(lines: list[str]) -> dict[str, str]:
    """`key | reason` lines -> {key: reason}."""
    out = {}
    for line in lines:
        key, _, reason = line.partition("|")
        out[" ".join(key.split())] = reason.strip()
    return out


def rewrite(path: Path, keep: list[str]) -> None:
    path.write_text("\n".join(header_lines(path) + keep) + "\n", encoding="utf-8")


# --- parity ---------------------------------------------------------------------------------

@dataclass
class Contract:
    name: str
    params: list[k.Param]


def unbound(value: str) -> bool:
    value = value.strip()
    return bool(_UNBOUND_VALUE.match(value) or _UNBOUND_HEAD.match(value))


def parse_contract_list(lines: list[str]) -> dict[str, list[str]]:
    """parity-contracts.txt: `<Contract> [shared builder names...]`."""
    out = {}
    for line in lines:
        parts = line.split()
        out[parts[0]] = parts[1:]
    return out


def parity_actual(repo: Path) -> list[str]:
    shared = k.shared_classes(repo, k.shared_files(repo))
    core = parse_contract_list(read_lines(repo / CONTRACTS_FILE))
    candidates = {name for name in shared if CONTRACT_SUFFIX.search(name)} | set(core)
    candidates = {c for c in candidates if c in shared and len(shared[c]) == 1}
    files = k.platform_files(repo)
    sites: dict[str, dict[str, list[k.Call]]] = {}
    texts: dict[str, str] = {}
    for platform, paths in files.items():
        chunks = []
        for path in paths:
            chunks.append(k.strip_comments(path.read_text(encoding="utf-8", errors="replace")))
            for name, call in k.constructor_calls(repo, path, candidates):
                sites.setdefault(name, {}).setdefault(platform, []).append(call)
        texts[platform] = "\n".join(chunks)

    violations = []
    for contract in sorted(set(sites) | set(core)):
        params = shared[contract][0].params if contract in shared else []
        for platform in PLATFORMS:
            calls = sites.get(contract, {}).get(platform)
            if not calls:
                if contract in core and not any(
                    re.search(r"(?<![\w])" + re.escape(builder) + r"\b", texts[platform]) for builder in core[contract]
                ):
                    violations.append(f"{platform} {contract}")
                continue
            for index, param in enumerate(params):
                if not any(_binds(call, index, param, len(params)) for call in calls):
                    violations.append(f"{platform} {contract}.{param.name}")
    return sorted(set(violations))


def _binds(call: k.Call, index: int, param: k.Param, count: int) -> bool:
    value = call.named.get(param.name)
    if value is None and index < len(call.positional):
        value = call.positional[index]
    if value is None and index == count - 1 and call.trailing_lambda is not None:
        value = call.trailing_lambda
    return value is not None and not unbound(value)


def check_parity(repo: Path, prune: bool, print_actual: bool) -> list[str]:
    actual = parity_actual(repo)
    if print_actual:
        print("\n".join(actual))
        return []
    lines = read_lines(repo / PARITY_FILE)
    exempt = keyed(lines)
    problems = []
    for key, reason in exempt.items():
        if not reason or not BEAD_REF.search(reason):
            problems.append(
                f"EXEMPTION WITHOUT A REASON AND BEAD: '{key}' - every entry is "
                "'<platform> <Contract>[.<param>] | <reason naming the letta-mobile-<id> bead that removes it>'"
            )
    for v in actual:
        if v not in exempt:
            platform, _, slot = v.partition(" ")
            what = "does not construct the core contract" if "." not in slot else "leaves this slot unbound"
            problems.append(f"NEW PARITY GAP: {platform} {what}: {slot}")
    stale = [key for key in exempt if key not in set(actual)]
    if prune and stale:
        rewrite(repo / PARITY_FILE, [line for line in lines if " ".join(line.partition("|")[0].split()) not in stale])
    elif stale:
        problems += [f"STALE EXEMPTION (now bound - delete the line, or run with --prune): {key}" for key in stale]
    keys = [" ".join(line.partition("|")[0].split()) for line in lines]
    if keys != sorted(keys) or len(set(keys)) != len(keys):
        problems.append(f"{PARITY_FILE} must be sorted with one entry per line")
    return problems


# --- platform code --------------------------------------------------------------------------

_TOP_DECL = re.compile(
    r"(?m)^(?P<mods>(?:@[\w.]+(?:\([^)\n]*\))?[ \t]*\n?[ \t]*|(?:public|internal|private|protected|data|value|open|abstract|sealed|"
    r"inner|annotation|enum|expect|actual|final|external|const|inline|suspend|operator|infix|tailrec|override|lateinit|fun)[ \t]+)*)"
    r"(?P<kind>class|interface|object|typealias|fun|val|var)\b(?P<rest>[^\n]*)"
)


def _imports(text: str) -> tuple[dict[str, str], list[str], str]:
    exact = {}
    wildcard = []
    for m in re.finditer(r"(?m)^import\s+([\w.]+)(?:\s+as\s+(\w+))?", text):
        fqn, alias = m.group(1), m.group(2)
        if fqn.endswith(".*"):
            wildcard.append(fqn[:-2])
        else:
            exact[alias or fqn.rsplit(".", 1)[-1]] = fqn
    pkg = re.search(r"(?m)^package\s+([\w.]+)", text)
    return exact, wildcard, pkg.group(1) if pkg else ""


def shared_index(repo: Path) -> tuple[set[str], set[str]]:
    """(FQNs of shared types, packages that hold them)."""
    fqns, packages = set(), set()
    for path in k.shared_files(repo):
        text = k.strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        pkg = re.search(r"(?m)^package\s+([\w.]+)", text)
        package = pkg.group(1) if pkg else ""
        packages.add(package)
        for name in k.declared_type_names(path):
            fqns.add(f"{package}.{name}")
    return fqns, packages


def _supertypes(header_rest: str, text: str, start: int) -> list[str]:
    """Simple names after the class header's ':' (constructor params skipped)."""
    i = start
    n = len(text)
    # skip name and type params, then the primary constructor
    m = re.match(r"\s*\w+\s*(<[^{(:]*?>)?\s*(?:@[\w.]+\s*)*(?:(?:public|internal|private|protected)\s+)?(?:constructor\s*)?", text[i:])
    if m:
        i += m.end()
    if i < n and text[i] == "(":
        i = k._skip_balanced(text, i, "(", ")")
    rest = text[i : i + 600]
    colon = re.match(r"\s*:", rest)
    if not colon:
        return []
    body = rest[colon.end():]
    end = len(body)
    for stop in ("{", "\n\n", " where "):
        pos = body.find(stop)
        if pos >= 0:
            end = min(end, pos)
    names = []
    for part in k.split_top_level(body[:end]):
        part = re.sub(r"\(.*$", "", part, flags=re.S)
        part = re.sub(r"<.*$", "", part, flags=re.S)
        part = part.split(" by ")[0].strip()
        if part:
            names.append(part)
    return names


def _resolves_to_binding_target(name: str, imports, shared_fqns: set[str]) -> bool:
    """Whether a supertype is a shared type (sharedLogic/sharedUI/core/...) or an OS/framework type."""
    exact, wildcard, package = imports
    head = name.split(".")[0]
    if head and head[0].islower():  # written fully qualified
        fqns = [name]
    elif head in exact:
        fqns = [exact[head] + name[len(head):]]
    else:
        fqns = [f"{p}.{name}" for p in wildcard] + [f"{package}.{name}"]
        if any(p.startswith(OS_PACKAGES) for p in wildcard):
            return True
    for fqn in fqns:
        if fqn in LOGIC_CONTAINERS:
            return False
        if fqn.startswith(OS_PACKAGES):
            return True
        # a nested shared type: its outer type is indexed
        parts = fqn.split(".")
        for end in range(len(parts), 1, -1):
            if ".".join(parts[:end]) in shared_fqns:
                return True
    return False


def is_pure_binding(text: str, shared_fqns: set[str], shared_packages: set[str]) -> bool:
    clean = k.strip_comments(text)
    imports = _imports(clean)
    found_any = False
    depth_map = _depth_map(clean)
    for m in _TOP_DECL.finditer(clean):
        if depth_map[m.start("kind")] != 0:
            continue
        found_any = True
        mods, kind, rest = m.group("mods"), m.group("kind"), m.group("rest")
        if re.search(r"\b(actual|external)\b", mods):
            continue
        if kind == "fun" and re.match(r"\s*main\s*\(", rest):
            continue
        if kind in ("class", "object") and re.search(r"@(dagger\.)?Module\b", mods):
            continue
        if kind in ("class", "object"):
            supers = _supertypes(rest, clean, m.end("kind"))
            if supers and any(_resolves_to_binding_target(s, imports, shared_fqns) for s in supers):
                continue
        return False
    return found_any


def _depth_map(text: str) -> list[int]:
    """Brace depth at each index (strings and comments already handled by strip_comments' caller)."""
    depth = 0
    out = [0] * (len(text) + 1)
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = k._skip_string(text, i)
            for x in range(i, min(j, n)):
                out[x] = depth
            i = j
            continue
        if c == "'":
            j = k._skip_char(text, i)
            for x in range(i, min(j, n)):
                out[x] = depth
            i = j
            continue
        out[i] = depth
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        i += 1
    return out


def platform_code_actual(repo: Path) -> dict[str, int]:
    shared_fqns, shared_packages = shared_index(repo)
    out = {}
    for paths in k.platform_files(repo).values():
        for path in paths:
            text = path.read_text(encoding="utf-8", errors="replace")
            if "@Composable" in text:
                continue  # platform-ui-gate.sh owns these
            if not is_pure_binding(text, shared_fqns, shared_packages):
                out[k.rel(repo, path)] = len(text.splitlines())
    return out


def check_platform_code(repo: Path, prune: bool, print_actual: bool) -> list[str]:
    actual = platform_code_actual(repo)
    if print_actual:
        print("\n".join(f"{p} {n}" for p, n in sorted(actual.items())))
        return []
    lines = read_lines(repo / PLATFORM_CODE_FILE)
    caps = {}
    for line in lines:
        path, _, cap = line.partition(" ")
        caps[path] = int(cap.split()[0])
    problems = []
    for path, count in sorted(actual.items()):
        if path not in caps:
            problems.append(f"NEW PLATFORM LOGIC FILE: {path} ({count} lines) is not a pure binding")
        elif count > caps[path] + PLATFORM_CODE_SLACK:
            problems.append(f"PLATFORM LOGIC GREW: {path} has {count} lines, cap {caps[path]} (+{PLATFORM_CODE_SLACK} slack)")
    stale = [p for p in caps if p not in actual]
    if prune and stale:
        rewrite(repo / PLATFORM_CODE_FILE, [line for line in lines if line.partition(" ")[0] not in stale])
    elif stale:
        problems += [f"STALE (file gone or now a pure binding - delete the line, or run with --prune): {p}" for p in stale]
    paths = [line.partition(" ")[0] for line in lines]
    if paths != sorted(paths) or len(set(paths)) != len(paths):
        problems.append(f"{PLATFORM_CODE_FILE} must be sorted with one entry per line")
    return problems


# --- mirror names ---------------------------------------------------------------------------

def mirror_actual(repo: Path) -> list[str]:
    shared_names = set()
    for path in k.shared_files(repo):
        shared_names.update(k.declared_type_names(path))
    platform_types: dict[str, dict[str, set[str]]] = {}  # platform -> name -> supertypes
    for platform, paths in k.platform_files(repo).items():
        for path in paths:
            clean = k.strip_comments(path.read_text(encoding="utf-8", errors="replace"))
            for m in re.finditer(r"\b(?:class|interface|object|typealias)\s+([A-Z]\w*)", clean):
                name = m.group(1)
                if not PLATFORM_PREFIX.match(name):
                    continue
                supers = {s.split(".")[-1] for s in _supertypes("", clean, m.end(0) - len(name))}
                platform_types.setdefault(platform, {}).setdefault(name, set()).update(supers)
    by_rest: dict[str, set[str]] = {}
    for platform, types in platform_types.items():
        for name in types:
            by_rest.setdefault(PLATFORM_PREFIX.match(name).group(2), set()).add(name)
    found = set()
    for platform, types in platform_types.items():
        for name, supers in types.items():
            rest = PLATFORM_PREFIX.match(name).group(2)
            for shared in (rest, "Shared" + rest, "Common" + rest):
                if shared in shared_names and shared not in supers:
                    found.add(f"{name} mirrors shared {shared}")
            for other in sorted(by_rest[rest] - {name}):
                other_supers = next(t[other] for t in platform_types.values() if other in t)
                common = {s for s in supers & other_supers if s in shared_names}
                if not common:
                    found.add(f"{name} mirrors platform {other}")
    return sorted(found)


def check_mirror_names(repo: Path, prune: bool, print_actual: bool) -> list[str]:
    actual = mirror_actual(repo)
    if print_actual:
        print("\n".join(actual))
        return []
    lines = read_lines(repo / MIRROR_FILE)
    known = [" ".join(line.split()) for line in lines]
    problems = [f"NEW MIRRORED TYPE: {entry}" for entry in actual if entry not in known]
    stale = [entry for entry in known if entry not in actual]
    if prune and stale:
        rewrite(repo / MIRROR_FILE, [line for line in lines if " ".join(line.split()) not in stale])
    elif stale:
        problems += [f"STALE (renamed or now implements the shared type - delete the line, or run with --prune): {e}" for e in stale]
    if known != sorted(known) or len(set(known)) != len(known):
        problems.append(f"{MIRROR_FILE} must be sorted with one entry per line")
    return problems


# --- main -----------------------------------------------------------------------------------

GATES = {"parity": check_parity, "platform-code": check_platform_code, "mirror-names": check_mirror_names}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("gate", nargs="?", default="all", choices=[*GATES, "all"])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--print-actual", action="store_true")
    parser.add_argument("--prune", action="store_true")
    args = parser.parse_args(argv)
    repo = args.root.resolve()
    failed = False
    for name in GATES if args.gate == "all" else [args.gate]:
        problems = GATES[name](repo, args.prune, args.print_actual)
        if args.print_actual:
            continue
        if problems:
            failed = True
            print(f"::error title=parity-gates {name}::{len(problems)} problem(s); see the log")
            print(f"parity-gates {name}: FAILED")
            for p in problems:
                print(f"  {p}")
            print("  What to do:")
            for line in HOW_TO_FIX[name].splitlines():
                print(f"    {line}")
        else:
            print(f"parity-gates {name}: ok")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
