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
class _Surface:
    """Walks the object stream once. Records arrive in document order, so an enum value belongs to
    the enum declared before it and a property to the view model declared before it; one small
    handler per record type keeps that bookkeeping out of a single long branch."""

    def __init__(self):
        self.enums, self.enum_order, self.models, self.machines = {}, [], {}, []
        self.enum = self.model = None

    def on_enum(self, o):
        self.enum = o.props.get("name", f"enum{len(self.enum_order)}")
        self.enum_order.append(self.enum)
        self.enums[self.enum] = []

    def on_enum_value(self, o):
        if self.enum is not None:
            self.enums[self.enum].append(o.props.get("key"))

    def on_model(self, o):
        self.model = o.props.get("name")
        self.models[self.model] = []

    def on_machine(self, o):
        self.machines.append(o.props.get("name"))

    def enum_of(self, o):
        """The enum an enum property uses (its `enumId` is an index into the declared enums)."""
        if o.type != "ViewModelPropertyEnumCustom":
            return None
        enum_id = o.props.get("enumId", 0)
        return self.enum_order[enum_id] if enum_id < len(self.enum_order) else None

    def on_property(self, o):
        if self.model is not None:
            self.models[self.model].append((o.props.get("name"), KINDS[o.type], self.enum_of(o)))

    def take(self, o):
        handler = _HANDLERS.get(o.type) or (_Surface.on_property if o.type in KINDS else None)
        if handler:
            handler(self, o)


_HANDLERS = {"DataEnumCustom": _Surface.on_enum, "DataEnumValue": _Surface.on_enum_value,
             "ViewModel": _Surface.on_model, "StateMachine": _Surface.on_machine}


def file_surface(riv):
    """{view models: {name: [(prop, kind, enum name or None)]}, enums: {name: [keys]}, machines}."""
    s = _Surface()
    for o in riv.objects:
        s.take(o)
    return {"models": s.models, "enums": s.enums, "machines": s.machines}


def _membership_problems(vm, have, want):
    missing = [n for n in want if n not in have]
    extra = [n for n in have if n not in want]
    problems = [f"view model {vm!r} is missing documented properties: {missing}"] if missing else []
    if extra:
        problems.append(f"view model {vm!r} exposes undocumented properties: {extra}")
    return problems


def _type_problems(have, want):
    problems = []
    for name in (n for n in want if n in have):
        p, (kind, enum) = want[name], have[name]
        if kind != p.type:
            problems.append(f"property {name!r} is a {kind} in the file, documented as {p.type}")
        elif p.type == "enum" and enum != p.range:
            problems.append(f"enum property {name!r} uses enum {enum!r}, documented as {p.range!r}")
    return problems


def _enum_problem(enum, keys, got):
    if got is None:
        return f"enum {enum!r} is missing"
    if got != keys:
        return (f"enum {enum!r} keys are {got}, documented as {keys} (order matters: "
                f"Rive delivers an enum value as its index)")
    return None


def _enum_problems(surface, contract):
    found = (_enum_problem(enum, keys, surface["enums"].get(enum)) for enum, keys in contract.ENUMS.items())
    return [p for p in found if p]


def _machine_problems(surface, contract):
    if contract.STATE_MACHINE in surface["machines"]:
        return []
    return [f"state machine {contract.STATE_MACHINE!r} not found; have {surface['machines']}"]


def file_problems(surface, contract):
    """Every way the built file differs from the documented contract."""
    props = surface["models"].get(contract.VIEW_MODEL)
    if props is None:
        return [f"no view model {contract.VIEW_MODEL!r}; have {sorted(surface['models'])}"]
    have = {name: (kind, enum) for name, kind, enum in props}
    want = {p.name: p for p in contract.AVATAR}
    return (_membership_problems(contract.VIEW_MODEL, have, want) + _type_problems(have, want)
            + _enum_problems(surface, contract) + _machine_problems(surface, contract))


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


def _name_problems(name, p, as_trigger):
    """What is wrong with the app writing `name` (contract entry `p`) the way it does."""
    if p is None:
        return [f"RiveAvatarContract.kt writes {name!r}, which is not in the contract"]
    problems = [f"RiveAvatarContract.kt writes {name!r}, a bench-only tunable"] if p.owner == "bench" else []
    if as_trigger and p.type != "trigger":
        problems.append(f"RiveAvatarContract.kt fires {name!r} as a trigger; the contract says {p.type}")
    if not as_trigger and p.type == "trigger":
        problems.append(f"RiveAvatarContract.kt writes {name!r} as an input; the contract says trigger")
    return problems


def _names_problems(want, contract):
    doc = contract.by_name()
    written = [(n, False) for n in sorted(want["inputs"])] + [(n, True) for n in sorted(want["triggers"])]
    return [problem for name, trig in written for problem in _name_problems(name, doc.get(name), trig)]


def _key_problems(want, contract):
    problems = []
    if want["machine"] != contract.STATE_MACHINE:
        problems.append(f"RiveAvatarContract.kt names state machine {want['machine']!r}, "
                        f"the contract {contract.STATE_MACHINE!r}")
    unknown = want["states"] - set(contract.ENUMS["AvatarState"])
    if unknown:
        problems.append(f"stateKey maps to keys AvatarState does not have: {sorted(unknown)}")
    shapes = set(contract.ENUMS["MascotShape"])
    if want["shapes"] != shapes:
        problems.append(f"shapeKey keys {sorted(want['shapes'])} differ from MascotShape {sorted(shapes)}")
    return problems


def app_problems(want, contract):
    """Every name the app writes that the contract does not offer it, or offers as another kind."""
    return _names_problems(want, contract) + _key_problems(want, contract)


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
