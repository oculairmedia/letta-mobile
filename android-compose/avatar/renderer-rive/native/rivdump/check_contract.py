"""Checks that the built mascot exposes exactly what RiveAvatarContract.kt writes.

The Brilliant/Koji handoff idea as a test: the file's inputs are read from `rive inspect --json`,
the app's expectations from the Kotlin source, and any drift fails the run. Run it after every
art pass - renaming a property or an enum key in the editor breaks the app silently otherwise.

usage: python check_contract.py <rive project dir> <RiveAvatarContract.kt>
"""
import json
import re
import subprocess
import sys
from pathlib import Path

RIVE = str(Path.home() / ".rive" / "bin" / "rive.exe")


def _cli_path(arg: str) -> Path:
    if "\x00" in arg or ".." in Path(arg).parts:
        raise SystemExit(f"invalid path: {arg}")
    return Path(arg).expanduser().resolve()


def _walk(o):
    stack = [o]
    while stack:
        cur = stack.pop()
        if isinstance(cur, dict):
            yield cur
            stack.extend(cur.values())
        elif isinstance(cur, list):
            stack.extend(cur)


def find(o, t):
    return (node for node in _walk(o) if node.get("type") == t)


def _missing_enum_keys(want, have):
    problems = []
    for enum_name, key in (("AvatarState", "states"), ("MascotShape", "shapes")):
        missing = want[key] - have["enums"].get(enum_name, set())
        if missing:
            problems.append(f"enum {enum_name} is missing keys the app writes: {sorted(missing)}")
    return problems


def _problems(want, have):
    problems = []
    missing = want["props"] - have["props"]
    if missing:
        problems.append(f"view model is missing properties the app writes: {sorted(missing)}")
    if want["machine"] not in have["machines"]:
        problems.append(
            f"state machine {want['machine']!r} not found; have {sorted(have['machines'])}")
    return problems + _missing_enum_keys(want, have)


PROP_TYPES = (
    "ViewModelPropertyEnumCustom", "ViewModelPropertyNumber",
    "ViewModelPropertyTrigger", "ViewModelPropertyColor", "ViewModelPropertyBoolean",
)


def _wants(kt):
    return {
        "props": set(re.findall(r'const val (?:INPUT|TRIGGER)_\w+: String = "(\w+)"', kt)),
        "machine": re.search(r'const val STATE_MACHINE: String = "(\w+)"', kt).group(1),
        "states": set(re.findall(r'AvatarState\.\w+ -> "(\w+)"', kt)),
        "shapes": set(re.findall(r'MascotShape\.\w+ -> "(\w+)"', kt)),
    }


def _prop_names(dump):
    return {p["name"] for t in PROP_TYPES for p in find(dump, t)}


def _have(dump):
    return {
        "props": _prop_names(dump),
        "machines": {m["name"] for m in find(dump, "StateMachine")},
        "enums": {e["name"]: {v["key"] for v in find(e, "DataEnumValue")} for e in find(dump, "DataEnumCustom")},
    }


def _report(want, have):
    problems = _problems(want, have)
    if problems:
        print("CONTRACT DRIFT:\n  " + "\n  ".join(problems))
        raise SystemExit(1)
    print(f"contract OK: {sorted(want['props'])} on machine {want['machine']!r}; "
          f"{len(want['states'])} state keys, {len(want['shapes'])} shape keys present")


def _inspect(project):
    return json.loads(subprocess.run(
        [RIVE, "inspect", ".", "--json"], cwd=project, capture_output=True, text=True,
    ).stdout)


def main(argv=None):
    argv = sys.argv if argv is None else argv
    if len(argv) < 3:
        raise SystemExit("usage: python check_contract.py <rive project dir> <RiveAvatarContract.kt>")
    project, contract = _cli_path(argv[1]), _cli_path(argv[2])
    _report(_wants(contract.read_text(encoding="utf-8")), _have(_inspect(project)))


if __name__ == "__main__":
    main()
