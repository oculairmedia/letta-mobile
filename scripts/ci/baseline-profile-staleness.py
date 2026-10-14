#!/usr/bin/env python3
"""Report how much of the committed Baseline Profile still names real app classes.

A Baseline Profile is a list of class/method descriptors captured on a device.
When the app's classes move or are deleted, the stale entries are silently
ignored by ART, so startup quietly loses its AOT coverage while the file still
looks healthy. This check makes that decay visible (letta-mobile-by4vm.32).

It resolves only first-party classes (``Lcom/letta/...;``): third-party
classes cannot be resolved without the dependency classpath. A profile class
resolves when a Kotlin/Java source file under ``android-compose/`` declares it,
either as a top-level class/object/interface or as a Kotlin file facade
(``FooKt`` for ``Foo.kt``, or an ``@file:JvmName``). Classes produced by code
generators (Hilt, Dagger, Room) resolve when their origin class does (``Hilt_Foo``,
``Foo_Factory`` -> ``Foo``); R and BuildConfig are
not checked.

Usage:
    python3 scripts/ci/baseline-profile-staleness.py [--profile PATH]
        [--max-unresolved-pct N] [--list]

Exit status: 0 when the unresolved share is at most ``--max-unresolved-pct``
(default 100, i.e. report only), 1 above it, 2 on bad input.
"""

import argparse
import pathlib
import re
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
GRADLE_ROOT = REPO_ROOT / "android-compose"
DEFAULT_PROFILE = GRADLE_ROOT / "app/src/playRelease/generated/baselineProfiles/baseline-prof.txt"
FIRST_PARTY_PREFIX = "com/letta/"

CLASS_REF = re.compile(r"^[HSP]*L([^;]+);")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
JVM_NAME = re.compile(r"^\s*@file:JvmName\(\"(\w+)\"\)", re.MULTILINE)
DECLARATION = re.compile(
    r"^(?:[ \t]*(?:public|internal|private|protected|abstract|open|sealed|data|enum|"
    r"annotation|value|inline|fun|final|static|expect|actual)\s+)*"
    r"(?:class|object|interface|record|@interface)\s+(\w+)",
    re.MULTILINE,
)
# Generated at build time from resources or the build itself; always present.
ALWAYS_PRESENT = {"R", "BuildConfig", "Manifest"}
GENERATED_PREFIX = re.compile(r"^(?:Hilt_|Dagger)(?=\w)")
SKIPPED_DIRS = {"build", ".gradle", ".kotlin", "node_modules", ".git"}


def source_files(root: pathlib.Path):
    for suffix in ("*.kt", "*.java"):
        yield from (p for p in root.rglob(suffix) if not SKIPPED_DIRS.intersection(p.parts))


def classes_in(path: pathlib.Path) -> set[str]:
    """Slash-separated outer class names a source file declares, file facade included."""
    text = path.read_text(encoding="utf-8", errors="replace")
    match = PACKAGE.search(text)
    package = match.group(1).replace(".", "/") + "/" if match else ""
    names = {package + declared for declared in DECLARATION.findall(text)}
    if path.suffix == ".kt":
        facade = JVM_NAME.search(text)
        names.add(package + (facade.group(1) if facade else path.stem + "Kt"))
    return names


def declared_classes(root: pathlib.Path) -> set[str]:
    """Every first-party class name (slash-separated, outer class only) declared in source."""
    return set().union(*(classes_in(path) for path in source_files(root)))


def outer_class(descriptor: str) -> str:
    """`a/b/Foo$Bar` -> `a/b/Foo`; Compose's `ComposableSingletons$FooKt` -> `a/b/FooKt`."""
    package, _, simple = descriptor.rpartition("/")
    parts = simple.split("$")
    head = parts[1] if parts[0] == "ComposableSingletons" and len(parts) > 1 else parts[0]
    return f"{package}/{head}"


def source_class(name: str) -> str | None:
    """The class whose source a generated class comes from, or None when it always exists.

    Hilt/Dagger/Room name generated code after its origin in the same package:
    `Hilt_Foo`, `DaggerFoo_HiltComponents_SingletonC`, `Foo_Factory`, `FooDao_Impl`,
    `FooViewModel_HiltModules_KeyModule_Provide_LazyMapKey` all resolve when `Foo` does.
    """
    package, _, simple = name.rpartition("/")
    if simple in ALWAYS_PRESENT:
        return None
    origin = GENERATED_PREFIX.sub("", simple).split("_", 1)[0] or simple
    return f"{package}/{origin}"


def profile_classes(profile: pathlib.Path) -> set[str]:
    classes = set()
    for line in profile.read_text(encoding="utf-8", errors="replace").splitlines():
        match = CLASS_REF.match(line)
        if match and match.group(1).startswith(FIRST_PARTY_PREFIX):
            classes.add(outer_class(match.group(1)))
    return classes


def unresolved_classes(wanted: set[str], known: set[str]) -> tuple[list[str], int]:
    """(sorted unresolved classes, number of classes checked); R/BuildConfig are skipped."""
    origins = {c: source_class(c) for c in wanted}
    checked = [c for c in wanted if origins[c] is not None]
    return sorted(c for c in checked if origins[c] not in known), len(checked)


def unresolved_pct(unresolved: list[str], checked: int) -> float:
    return 100.0 * len(unresolved) / checked if checked else 0.0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--profile", type=pathlib.Path, default=DEFAULT_PROFILE)
    parser.add_argument("--source-root", type=pathlib.Path, default=GRADLE_ROOT)
    parser.add_argument("--max-unresolved-pct", type=float, default=100.0)
    parser.add_argument("--list", action="store_true", help="print every unresolved class")
    args = parser.parse_args(argv)
    wanted = profile_classes(args.profile) if args.profile.is_file() else set()
    if not wanted:
        print(f"baseline-profile-staleness: no first-party classes in {args.profile}", file=sys.stderr)
        return 2

    unresolved, checked = unresolved_classes(wanted, declared_classes(args.source_root))
    pct = unresolved_pct(unresolved, checked)
    print(f"profile: {args.profile}")
    print(f"first-party classes: {len(wanted)} (R/BuildConfig, not checked: {len(wanted) - checked})")
    print(f"unresolved: {len(unresolved)}/{checked} ({pct:.1f}%), limit {args.max_unresolved_pct:.1f}%")
    if args.list:
        print("\n".join(f"  {name}" for name in unresolved))
    if pct > args.max_unresolved_pct:
        print(
            "baseline-profile-staleness: the Baseline Profile no longer describes this app; "
            "regenerate it (docs/performance/perf-gate.md#baseline-profile-freshness)",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
