"""Checks that the shipped mascot exposes exactly the documented contract, and that the app agrees.

Three parties have to agree on a handful of strings, and a drift between any two of them fails
silently at runtime (the mascot simply never reacts):

    the contract   rive/mascot/rig/contract.py - every view-model property, its type, its range
    the file       the BUILT .riv, read directly by rivfile.py (no Rive CLI, so CI can run it)
    the app        RiveAvatarContract.kt - the names the renderer writes

The check fails unless:
  - the `Avatar` view model exposes exactly the contract's properties, each with its type
    (number / boolean / trigger / enum / colour), and `state` / `shape` use the right enums;
  - the `AvatarState` and `MascotShape` enums carry exactly the contract's keys, in order (Rive
    delivers an enum as its index, so a reorder is a silent break even with every key present);
  - the `Avatar` state machine exists;
  - every INPUT_ / TRIGGER_ name the app declares is a contract property the app may write, with
    a matching kind (TRIGGER_ is a trigger, INPUT_ is not), every `stateKey` value is an
    `AvatarState` key and every `shapeKey` value a `MascotShape` key;
  - every .riv given is byte-identical to the first (the Android and desktop copies ship together).

usage: python check_contract.py <rive/mascot dir> <RiveAvatarContract.kt> [mascot.riv ...]

With no .riv given, it checks the two shipped copies (src/androidMain/res/raw/mascot.riv and
src/jvmMain/resources/mascot/mascot.riv) and, when it exists, the freshly built build/mascot.riv.
Relative paths, `..` included, are resolved against the working directory.
"""
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rivfile import read  # noqa: E402

KINDS = {"ViewModelPropertyNumber": "number", "ViewModelPropertyBoolean": "boolean",
         "ViewModelPropertyTrigger": "trigger", "ViewModelPropertyEnumCustom": "enum",
         "ViewModelPropertyColor": "color", "ViewModelPropertyString": "string",
         "ViewModelPropertyList": "list", "ViewModelPropertyViewModel": "viewModel"}


def _cli_path(arg: str) -> Path:
    if "\x00" in arg:
        raise SystemExit(f"invalid path: {arg!r}")
    path = Path(arg).expanduser().resolve()
    if not path.exists():
        raise SystemExit(f"no such file or directory: {path}")
    return path


def load_contract(mascot_dir):
    """The rig's contract module (rive/mascot/rig/contract.py), imported from `mascot_dir`."""
    sys.path.insert(0, str(mascot_dir))
    from rig import contract
    return contract


# --- the file --------------------------------------------------------------------------------
def file_surface(riv):
    """{view models: {name: [(prop, kind, enum name or None)]}, enums: {name: [keys]}, machines}."""
    enums, enum_order, models, machines = {}, [], {}, []
    current_enum = current_model = None
    for o in riv.objects:
        if o.type == "DataEnumCustom":
            current_enum = o.props.get("name", f"enum{len(enum_order)}")
            enum_order.append(current_enum)
            enums[current_enum] = []
        elif o.type == "DataEnumValue" and current_enum is not None:
            enums[current_enum].append(o.props.get("key"))
        elif o.type == "ViewModel":
            current_model = o.props.get("name")
            models[current_model] = []
        elif o.type in KINDS and current_model is not None:
            enum_id = o.props.get("enumId", 0) if o.type == "ViewModelPropertyEnumCustom" else None
            enum = (enum_order[enum_id] if enum_id is not None and enum_id < len(enum_order) else None)
            models[current_model].append((o.props.get("name"), KINDS[o.type], enum))
        elif o.type == "StateMachine":
            machines.append(o.props.get("name"))
    return {"models": models, "enums": enums, "machines": machines}


def file_problems(surface, contract):
    """Every way the built file differs from the documented contract."""
    problems = []
    props = surface["models"].get(contract.VIEW_MODEL)
    if props is None:
        return [f"no view model {contract.VIEW_MODEL!r}; have {sorted(surface['models'])}"]
    have = {name: (kind, enum) for name, kind, enum in props}
    want = {p.name: p for p in contract.AVATAR}
    missing = [n for n in want if n not in have]
    extra = [n for n in have if n not in want]
    if missing:
        problems.append(f"view model {contract.VIEW_MODEL!r} is missing documented properties: {missing}")
    if extra:
        problems.append(f"view model {contract.VIEW_MODEL!r} exposes undocumented properties: {extra}")
    for name, p in want.items():
        if name in have and have[name][0] != p.type:
            problems.append(f"property {name!r} is a {have[name][0]} in the file, documented as {p.type}")
        if name in have and p.type == "enum" and have[name][1] != p.range:
            problems.append(f"enum property {name!r} uses enum {have[name][1]!r}, documented as {p.range!r}")
    for enum, keys in contract.ENUMS.items():
        got = surface["enums"].get(enum)
        if got is None:
            problems.append(f"enum {enum!r} is missing")
        elif got != keys:
            problems.append(f"enum {enum!r} keys are {got}, documented as {keys} (order matters: "
                            f"Rive delivers an enum value as its index)")
    if contract.STATE_MACHINE not in surface["machines"]:
        problems.append(f"state machine {contract.STATE_MACHINE!r} not found; have {surface['machines']}")
    return problems


# --- the app ---------------------------------------------------------------------------------
def app_wants(kt):
    """What RiveAvatarContract.kt declares: {inputs, triggers, states, shapes, machine}."""
    machine = re.search(r'const val STATE_MACHINE: String = "(\w+)"', kt)
    return {
        "inputs": set(re.findall(r'const val INPUT_\w+: String = "(\w+)"', kt)),
        "triggers": set(re.findall(r'const val TRIGGER_\w+: String = "(\w+)"', kt)),
        "machine": machine.group(1) if machine else None,
        "states": set(re.findall(r'AvatarState\.\w+ -> "(\w+)"', kt)),
        "shapes": set(re.findall(r'MascotShape\.\w+ -> "(\w+)"', kt)),
    }


def app_problems(want, contract):
    """Every name the app writes that the contract does not offer it, or offers as another kind."""
    problems = []
    doc = contract.by_name()
    for name in sorted(want["inputs"] | want["triggers"]):
        p = doc.get(name)
        if p is None:
            problems.append(f"RiveAvatarContract.kt writes {name!r}, which is not in the contract")
            continue
        if p.owner == "bench":
            problems.append(f"RiveAvatarContract.kt writes {name!r}, a bench-only tunable")
        if name in want["triggers"] and p.type != "trigger":
            problems.append(f"RiveAvatarContract.kt fires {name!r} as a trigger; the contract says {p.type}")
        if name in want["inputs"] and p.type == "trigger":
            problems.append(f"RiveAvatarContract.kt writes {name!r} as an input; the contract says trigger")
    if want["machine"] != contract.STATE_MACHINE:
        problems.append(f"RiveAvatarContract.kt names state machine {want['machine']!r}, "
                        f"the contract {contract.STATE_MACHINE!r}")
    states = set(contract.ENUMS["AvatarState"])
    if want["states"] - states:
        problems.append(f"stateKey maps to keys AvatarState does not have: {sorted(want['states'] - states)}")
    shapes = set(contract.ENUMS["MascotShape"])
    if want["shapes"] != shapes:
        problems.append(f"shapeKey keys {sorted(want['shapes'])} differ from MascotShape {sorted(shapes)}")
    return problems


# --- the shipped copies ----------------------------------------------------------------------
def default_rivs(mascot_dir):
    module = Path(mascot_dir).resolve().parent.parent
    rivs = [module / "src" / "androidMain" / "res" / "raw" / "mascot.riv",
            module / "src" / "jvmMain" / "resources" / "mascot" / "mascot.riv"]
    built = Path(mascot_dir) / "build" / "mascot.riv"
    return rivs + ([built] if built.exists() else [])


def copy_problems(rivs):
    first = rivs[0].read_bytes()
    return [f"{p} is not byte-identical to {rivs[0]} (regenerate and copy both: README 'The loop')"
            for p in rivs[1:] if p.read_bytes() != first]


def check(mascot_dir, kt_path, rivs=None):
    """All problems, as strings; an empty list is a pass."""
    contract = load_contract(mascot_dir)
    rivs = rivs or default_rivs(mascot_dir)
    missing = [str(p) for p in rivs if not Path(p).exists()]
    if missing:
        return [f"no .riv at {m}" for m in missing]
    problems = copy_problems([Path(p) for p in rivs])
    problems += file_problems(file_surface(read(str(rivs[0]))), contract)
    problems += app_problems(app_wants(Path(kt_path).read_text(encoding="utf-8")), contract)
    return problems


def main(argv=None):
    argv = sys.argv if argv is None else argv
    if len(argv) < 3:
        raise SystemExit("usage: python check_contract.py <rive/mascot dir> <RiveAvatarContract.kt> [mascot.riv ...]")
    mascot_dir, kt = _cli_path(argv[1]), _cli_path(argv[2])
    rivs = [_cli_path(a) for a in argv[3:]] or None
    problems = check(mascot_dir, kt, rivs)
    if problems:
        print("CONTRACT DRIFT:\n  " + "\n  ".join(problems))
        raise SystemExit(1)
    contract = load_contract(mascot_dir)
    print(f"contract OK: {len(contract.AVATAR)} view-model properties with their types, "
          f"{len(contract.ENUMS['AvatarState'])} state keys, {len(contract.ENUMS['MascotShape'])} shape keys, "
          f"machine {contract.STATE_MACHINE!r}; {len(rivs or default_rivs(mascot_dir))} identical .riv copies")


if __name__ == "__main__":
    main()
