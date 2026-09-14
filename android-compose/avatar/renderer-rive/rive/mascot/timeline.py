"""See a curve without building: read scene.rml and print what an animation actually does.

    python timeline.py IdleBounce            # keyframes + an ASCII curve per property
    python timeline.py IdleBounce --chart    # ...and the timing chart (spacing) under each
    python timeline.py --list                # every animation, grouped by name prefix
    python timeline.py --layers              # the state machine as a tree

The Rive editor is the tool for this; when it is not available (see README, "Pulling editor
changes back - and the paywall"), this is. Standard library only. Read-only: it never writes.
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

FPS = 60.0

# Rive property keys (rml.py holds the authoritative table; these are the ones that get keyed).
PROP_NAMES = {
    13: "x", 14: "y", 15: "rotation", 16: "scaleX", 17: "scaleY", 18: "opacity",
    24: "vertexX", 25: "vertexY", 37: "color", 46: "gradientOpacity",
    82: "vertexRotation", 83: "vertexDistance",
    84: "vertexInRotation", 85: "vertexInDistance", 86: "vertexOutRotation", 87: "vertexOutDistance",
    202: "remapTime", 239: "nestedValue", 296: "activeChild", 299: "joystickX", 300: "joystickY",
    401: "nestedFire", 634: "bindBoolean", 637: "bindEnum", 686: "bindTrigger",
}
ROTATION_KEYS = {15, 82, 84, 86}

OPS = {
    "equal": "==", "notEqual": "!=", "lessThan": "<", "lessThanOrEqual": "<=",
    "greaterThan": ">", "greaterThanOrEqual": ">=",
}


def prop_name(key):
    try:
        k = int(key)
    except (TypeError, ValueError):
        return f"prop {key}"
    return PROP_NAMES.get(k, f"prop {k}")


def num(value, digits=4):
    """A number as a human would write it: 1.0417 not 1.0416666666666667."""
    try:
        f = float(value)
    except (TypeError, ValueError):
        return str(value)
    if f == int(f) and abs(f) < 1e15:
        return str(int(f))
    return f"{round(f, digits):g}"


# --- document ------------------------------------------------------------------------------------

class Scene:
    """scene.rml, indexed: ids to elements, animations to their artboard, view-model names."""

    def __init__(self, path):
        self.path = path
        self.root = ET.parse(path).getroot()
        self.by_id = {}
        self.parent = {}
        for el in self.root.iter():
            for child in el:
                self.parent[child] = el
            i = el.get("id")
            if i is not None and i not in self.by_id:
                self.by_id[i] = el
        self.animations = []  # (artboard name, element)
        self.machines = []    # (artboard name, element)
        for board in self.root.iter("Artboard"):
            name = board.get("name", "?")
            for anim in board.iter("LinearAnimation"):
                self.animations.append((name, anim))
            for sm in board.iter("StateMachine"):
                self.machines.append((name, sm))

    def describe(self, oid):
        """'Node BodyPlacement (0:233)' - the object an objectId points at."""
        el = self.by_id.get(oid)
        if el is None:
            return f"<unknown> ({oid})"
        name = el.get("name")
        return f"{el.tag} {name} ({oid})" if name else f"{el.tag} ({oid})"

    def label(self, oid):
        el = self.by_id.get(oid)
        if el is None:
            return oid
        return el.get("name") or f"{el.tag} {oid}"

    def vm_property(self, source_path_ids):
        """'1:50-1:1' -> 'state' (the last segment is the view-model property)."""
        pid = (source_path_ids or "").split("-")[-1]
        el = self.by_id.get(pid)
        return el.get("name") if el is not None and el.get("name") else pid

    def enum_key(self, value_id):
        el = self.by_id.get(value_id)
        return el.get("key") or el.get("name") or value_id if el is not None else value_id


# --- easing --------------------------------------------------------------------------------------

class Ease:
    """How the segment leaving a keyframe is shaped."""

    def __init__(self, kind, bezier=None, amplitude=None, period=None, easing=None):
        self.kind, self.bezier = kind, bezier
        self.amplitude, self.period, self.easing = amplitude, period, easing

    def __str__(self):
        if self.kind == "cubic":
            return "cubic " + " ".join(num(v) for v in self.bezier)
        if self.kind == "elastic":
            return f"elastic amp {num(self.amplitude)} period {num(self.period)} {self.easing or ''}".strip()
        return self.kind

    def at(self, u):
        """Eased 0..1 progress for linear progress u (may leave 0..1: BACK_OUT overshoots)."""
        if self.kind == "hold":
            return 0.0
        if self.kind == "linear":
            return u
        if self.kind == "elastic":
            return 1.0  # shown as the target value; the spring itself is not modelled
        return _bezier_y(self.bezier, u)


HOLD = Ease("hold")


def _bezier_x(b, t):
    x1, _, x2, _ = b
    mt = 1 - t
    return 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t ** 3


def _bezier_y(b, u):
    """Rive's cubic ease: solve x(t) = u by bisection, return y(t)."""
    if u <= 0:
        return 0.0
    if u >= 1:
        return 1.0
    lo, hi = 0.0, 1.0
    for _ in range(60):
        mid = (lo + hi) / 2
        if _bezier_x(b, mid) < u:
            lo = mid
        else:
            hi = mid
    t = (lo + hi) / 2
    _, y1, _, y2 = b
    mt = 1 - t
    return 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t ** 3


def read_ease(kf):
    kind = kf.get("interpolationType")
    if kind == "cubic":
        c = kf.find("CubicEaseInterpolator")
        if c is None:
            return HOLD
        b = tuple(float(c.get(a, 0)) for a in ("x1", "y1", "x2", "y2"))
        return Ease("linear" if b == (0.0, 0.0, 1.0, 1.0) else "cubic", bezier=b)
    if kind == "elastic":
        e = kf.find("ElasticInterpolator")
        if e is None:
            return HOLD
        return Ease("elastic", amplitude=float(e.get("amplitude", 1)),
                    period=float(e.get("period", 0.4)), easing=e.get("easingValue"))
    return HOLD


# --- keyframes -----------------------------------------------------------------------------------

class Key:
    def __init__(self, frame, value, ease, kind, raw=None):
        self.frame, self.value, self.ease, self.kind, self.raw = frame, value, ease, kind, raw


def read_keys(keyed_property):
    keys = []
    for kf in keyed_property:
        frame = int(float(kf.get("frame", 0)))
        if kf.tag == "KeyFrameDouble":
            keys.append(Key(frame, float(kf.get("value", 0)), read_ease(kf), "double"))
        elif kf.tag == "KeyFrameColor":
            keys.append(Key(frame, kf.get("value", ""), HOLD, "color"))
        elif kf.tag == "KeyFrameId":
            keys.append(Key(frame, kf.get("value", ""), HOLD, "id"))
        elif kf.tag == "KeyFrameCallback":
            keys.append(Key(frame, None, HOLD, "callback"))
        elif kf.tag.startswith("KeyFrame"):
            keys.append(Key(frame, kf.get("value", ""), read_ease(kf), "other", raw=kf.tag))
    keys.sort(key=lambda k: k.frame)
    return keys


def sample(keys, frame):
    """(value, on an elastic segment?) at a frame, following Rive's leaving-key semantics."""
    if not keys:
        return 0.0, False
    if frame <= keys[0].frame:
        return keys[0].value, False
    if frame >= keys[-1].frame:
        return keys[-1].value, False
    for a, b in zip(keys, keys[1:]):
        if a.frame <= frame <= b.frame:
            span = b.frame - a.frame
            if span <= 0:
                return b.value, False
            u = (frame - a.frame) / span
            return a.value + (b.value - a.value) * a.ease.at(u), a.ease.kind == "elastic"
    return keys[-1].value, False


# --- rendering -----------------------------------------------------------------------------------

def curve(keys, duration, width, height=9, indent="      "):
    """An ASCII plot of the value over the animation's frames. 'o' marks a keyframe column."""
    if duration <= 0 or len(keys) < 2:
        return []
    width = max(12, width)
    # Always the animation's whole span, so two properties of the same timeline line up and a
    # property whose last key lands early visibly holds to the end.
    first, last = 0, max(duration, keys[-1].frame)
    if last <= first:
        return []
    frames = [first + (last - first) * i / (width - 1) for i in range(width)]
    values, elastic = [], []
    for f in frames:
        v, e = sample(keys, f)
        values.append(v)
        elastic.append(e)
    lo, hi = min(values), max(values)
    if hi - lo < 1e-9:
        return [f"{indent}flat at {num(lo)}"]
    key_cols = set()
    for k in keys:
        pos = (k.frame - first) / float(last - first)
        key_cols.add(min(width - 1, max(0, int(round(pos * (width - 1))))))
    grid = [[" "] * width for _ in range(height)]
    for col, v in enumerate(values):
        row = int(round((v - lo) / (hi - lo) * (height - 1)))
        row = min(height - 1, max(0, row))
        grid[height - 1 - row][col] = "~" if elastic[col] else ("o" if col in key_cols else "*")
    gutter = max(len(num(hi)), len(num(lo))) + 1
    out = []
    for r, row in enumerate(grid):
        tag = num(hi) if r == 0 else (num(lo) if r == height - 1 else "")
        out.append(f"{indent}{tag:>{gutter}} |{''.join(row)}")
    axis_left, axis_right = str(first), str(last)
    pad = max(0, width - len(axis_left) - len(axis_right))
    out.append(f"{indent}{'':>{gutter}} +{'-' * width}")
    out.append(f"{indent}{'':>{gutter}}  {axis_left}{' ' * pad}{axis_right}  (frames)")
    if any(elastic):
        out.append(f"{indent}{'':>{gutter}}  ~ elastic segment, drawn at its target value")
    return out


def value_text(key, scene=None):
    if key.kind == "callback":
        return "fire"
    if key.kind == "id":
        return scene.describe(key.value) if scene else str(key.value)
    if key.kind in ("color", "other"):
        return str(key.value)
    return num(key.value)


def degrees_text(key):
    """Rive stores rotation in radians; a designer reads degrees."""
    if key.kind != "double":
        return ""
    return f"{num(key.value * 180.0 / 3.141592653589793, 2)} deg"


def chart_line(keys, indent="      "):
    """The timing chart for a curve, as rig/chart.py draws it. Imported late: rig.chart reads
    this module, so importing it at the top would be a cycle."""
    from rig.chart import Chart
    try:
        return [f"{indent}{Chart.of(keys).text()}"]
    except ValueError:
        return []


def print_animation(scene, anim, board, width, chart=False):
    duration = int(float(anim.get("duration", 0)))
    ms = duration / FPS * 1000.0
    print(f"{anim.get('name')}   [{board}]  id {anim.get('id')}")
    print(f"  {duration} frames ({num(ms, 1)} ms at {int(FPS)} fps), loop {anim.get('loopValue', 'oneShot')}")
    keyed = list(anim.findall("KeyedObject"))
    if not keyed:
        print("  (no keyed objects - a rest/placeholder timeline)")
        print()
        return
    for ko in keyed:
        print()
        print(f"  {scene.describe(ko.get('objectId'))}")
        for kp in ko.findall("KeyedProperty"):
            raw_key = kp.get("propertyKey")
            try:
                key_num = int(raw_key)
            except (TypeError, ValueError):
                key_num = -1
            keys = read_keys(kp)
            print(f"    {prop_name(raw_key)}")
            rot = key_num in ROTATION_KEYS
            wf = max([len(str(k.frame)) for k in keys] + [5])
            wv = max([len(value_text(k, scene)) for k in keys] + [5])
            wd = max([len(degrees_text(k)) for k in keys] + [len("degrees")]) if rot else 0
            head = f"      {'frame':>{wf}}  {'value':<{wv}}"
            print(head + (f"  {'degrees':>{wd}}" if rot else "") + "  easing")
            for k in keys:
                line = f"      {k.frame:>{wf}}  {value_text(k, scene):<{wv}}"
                if rot:
                    line += f"  {degrees_text(k):>{wd}}"
                print(f"{line}  {k.ease}")
            if all(k.kind == "double" for k in keys):
                for line in curve(keys, duration, width):
                    print(line)
                if chart:
                    for line in chart_line(keys):
                        print(line)
    print()


# --- --list --------------------------------------------------------------------------------------

def group_of(name):
    """Enter_idle_listening -> Enter_*, IdleBounce -> Idle*, Breath -> Breath*."""
    if "_" in name:
        return name.split("_")[0] + "_*"
    m = re.match(r"[A-Z][a-z0-9]*", name)
    return (m.group(0) + "*") if m else name


def print_list(scene):
    groups = {}
    for board, anim in scene.animations:
        groups.setdefault(group_of(anim.get("name", "?")), []).append((board, anim))
    for g in sorted(groups):
        items = groups[g]
        print(f"{g}  ({len(items)})")
        for board, anim in items:
            d = int(float(anim.get("duration", 0)))
            print(f"    {anim.get('name'):<28} {d:>5} f  {num(d / FPS * 1000.0, 1):>8} ms  "
                  f"{anim.get('loopValue', 'oneShot'):<8} {anim.get('id'):<8} [{board}]")
        print()
    print(f"{len(scene.animations)} animations in {len(groups)} groups")


# --- --layers ------------------------------------------------------------------------------------

def condition_text(scene, transition):
    bits = []
    if transition.get("enableExitTime") == "true":
        bits.append(f"on exit {transition.get('exitTime', '100')}%")
    for c in transition.findall("TransitionViewModelCondition"):
        op = OPS.get(c.get("opValue", "equal"), c.get("opValue", "?"))
        prop, literal = "?", ""
        for b in c.iter():
            if b.tag.startswith("BindableProperty"):
                ctx = b.find("DataBindContext")
                if ctx is not None:
                    prop = scene.vm_property(ctx.get("sourcePathIds"))
            elif b.tag == "TransitionValueEnumComparator":
                literal = scene.enum_key(b.get("value", ""))
            elif b.tag == "TransitionValueBooleanComparator":
                literal = b.get("value", "")
            elif b.tag == "TransitionValueNumberComparator":
                literal = num(b.get("value", ""))
            elif b.tag == "TransitionValueTriggerComparator":
                literal = None
        bits.append(f"{prop} fired" if literal is None else f"{prop} {op} {literal}")
    for c in transition.findall("TransitionNumberCondition"):
        op = OPS.get(c.get("opValue", "equal"), c.get("opValue", "?"))
        bits.append(f"{scene.label(c.get('inputId'))} {op} {num(c.get('value', 0))}")
    for c in transition.findall("TransitionBoolCondition"):
        bits.append(f"{scene.label(c.get('inputId'))} == {c.get('value', '')}")
    for c in transition.findall("TransitionTriggerCondition"):
        bits.append(f"{scene.label(c.get('inputId'))} fired")
    return " and ".join(bits) if bits else "always"


def state_label(scene, layer, state_id):
    for el in layer.iter():
        if el.get("id") != state_id:
            continue
        if el.tag == "AnimationState":
            anim = scene.by_id.get(el.get("animationId"))
            name = anim.get("name") if anim is not None else el.get("animationId")
            flags = [f for f in ("reset", "random") if el.get(f) == "true"]
            return f"{name}" + (f" [{', '.join(flags)}]" if flags else "")
        return el.tag
    return f"<{state_id}>"


def print_transitions(scene, layer, holder, indent):
    for t in holder.findall("StateTransition"):
        target = state_label(scene, layer, t.get("stateToId"))
        weight = t.get("randomWeight")
        bits = [f"{indent}-> {target}", f"{t.get('duration', 0)} ms"]
        if weight:
            bits.append(f"weight {weight}")
        bits.append(condition_text(scene, t))
        print("  ".join(bits))


def print_layers(scene):
    for board, sm in scene.machines:
        inputs, listeners = [], []
        for e in sm:
            if e.tag in ("StateMachineNumber", "StateMachineBool", "StateMachineBoolean", "StateMachineTrigger"):
                inputs.append(f"{e.get('name')} ({e.tag.replace('StateMachine', '').lower()})")
            elif e.tag.startswith("StateMachineListener"):
                listeners.append(f"{e.get('name')} on {scene.label(e.get('targetId'))} "
                                 f"({e.get('listenerTypeValue', '?')})")
        print(f"StateMachine {sm.get('name')}  [{board}]  id {sm.get('id')}")
        if inputs:
            print(f"  inputs: {', '.join(inputs)}")
        if listeners:
            print(f"  listeners: {', '.join(listeners)}")
        for layer in sm.findall("StateMachineLayer"):
            states = layer.findall("AnimationState")
            print(f"  layer {layer.get('name')}  ({len(states)} states)")
            entry = layer.find("EntryState")
            if entry is not None:
                print_transitions(scene, layer, entry, "      entry ")
            any_state = layer.find("AnyState")
            if any_state is not None and list(any_state.findall("StateTransition")):
                print("    AnyState")
                print_transitions(scene, layer, any_state, "      ")
            for st in states:
                anim = scene.by_id.get(st.get("animationId"))
                name = anim.get("name") if anim is not None else st.get("animationId")
                flags = [f for f in ("reset", "random") if st.get(f) == "true"]
                suffix = f"  [{', '.join(flags)}]" if flags else ""
                print(f"    {name}  ({st.get('id')}){suffix}")
                print_transitions(scene, layer, st, "      ")
        print()


# --- main ----------------------------------------------------------------------------------------

def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("pattern", nargs="?", help="animation name or substring")
    p.add_argument("--rml", default=os.path.join(here, "scene.rml"), help="the document to read")
    p.add_argument("--width", type=int, default=60, help="curve width in columns")
    p.add_argument("--list", action="store_true", help="every animation, grouped by prefix")
    p.add_argument("--layers", action="store_true", help="the state machine layers as a tree")
    p.add_argument("--chart", action="store_true",
                   help="a timing chart under each property (rig/chart.py)")
    args = p.parse_args(argv)

    scene = Scene(args.rml)
    if args.list:
        print_list(scene)
        return 0
    if args.layers:
        print_layers(scene)
        return 0
    if not args.pattern:
        p.print_help()
        return 2

    needle = args.pattern.lower()
    hits = [(b, a) for b, a in scene.animations if needle in (a.get("name") or "").lower()]
    if not hits:
        print(f"no animation matching {args.pattern!r}; try --list")
        return 1
    for board, anim in hits:
        print_animation(scene, anim, board, args.width, chart=args.chart)
    if len(hits) > 1:
        print(f"{len(hits)} animations matched {args.pattern!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
