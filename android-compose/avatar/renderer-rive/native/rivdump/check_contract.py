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


if len(sys.argv) < 3:
    raise SystemExit("usage: python check_contract.py <rive project dir> <RiveAvatarContract.kt>")
project, contract = _cli_path(sys.argv[1]), _cli_path(sys.argv[2])
kt = contract.read_text(encoding="utf-8")
# Every INPUT_* / TRIGGER_* string const is required on the .riv, including
# INPUT_TURN_X/Y ("turnX"/"turnY") written by RiveAvatarRuntime.setHeadTurn.
want_props = set(re.findall(r'const val (?:INPUT|TRIGGER)_\w+: String = "(\w+)"', kt))
want_machine = re.search(r'const val STATE_MACHINE: String = "(\w+)"', kt).group(1)
want_state_keys = set(re.findall(r'AvatarState\.\w+ -> "(\w+)"', kt))
want_shape_keys = set(re.findall(r'MascotShape\.\w+ -> "(\w+)"', kt))

dump = json.loads(subprocess.run([RIVE, "inspect", ".", "--json"], cwd=project, capture_output=True, text=True).stdout)


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


have_props = {p["name"] for t in ("ViewModelPropertyEnumCustom", "ViewModelPropertyNumber",
                                  "ViewModelPropertyTrigger", "ViewModelPropertyColor", "ViewModelPropertyBoolean")
              for p in find(dump, t)}
have_machines = {m["name"] for m in find(dump, "StateMachine")}
enums = {e["name"]: {v["key"] for v in find(e, "DataEnumValue")} for e in find(dump, "DataEnumCustom")}

problems = []
missing = want_props - have_props
if missing:
    problems.append(f"view model is missing properties the app writes: {sorted(missing)}")
if want_machine not in have_machines:
    problems.append(f"state machine {want_machine!r} not found; have {sorted(have_machines)}")
for enum_name, want in (("AvatarState", want_state_keys), ("MascotShape", want_shape_keys)):
    have = enums.get(enum_name, set())
    if want - have:
        problems.append(f"enum {enum_name} is missing keys the app writes: {sorted(want - have)}")

if problems:
    print("CONTRACT DRIFT:\n  " + "\n  ".join(problems))
    sys.exit(1)
print(f"contract OK: {sorted(want_props)} on machine {want_machine!r}; "
      f"{len(want_state_keys)} state keys, {len(want_shape_keys)} shape keys present")
