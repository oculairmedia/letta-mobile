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
from typing import NamedTuple

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

CURVE_HEIGHT = 9
INDENT = "      "
INPUT_TAGS = ("StateMachineNumber", "StateMachineBool", "StateMachineBoolean", "StateMachineTrigger")


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
        self.parent = {child: el for el in self.root.iter() for child in el}
        self.by_id = {}
        for el in self.root.iter():
            if el.get("id") is not None:
                self.by_id.setdefault(el.get("id"), el)
        self.animations = self._per_board("LinearAnimation")  # (artboard name, element)
        self.machines = self._per_board("StateMachine")       # (artboard name, element)

    def _per_board(self, tag):
        return [(board.get("name", "?"), el) for board in self.root.iter("Artboard") for el in board.iter(tag)]

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

    def animation_name(self, animation_id):
        """The name of the animation an AnimationState plays, or its id when it does not resolve."""
        anim = self.by_id.get(animation_id)
        return anim.get("name") if anim is not None else animation_id


# --- easing --------------------------------------------------------------------------------------

class Ease(NamedTuple):
    """How the segment leaving a keyframe is shaped."""
    kind: str
    bezier: tuple = None
    amplitude: float = None
    period: float = None
    easing: str = None

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


def _cubic_ease(kf):
    c = kf.find("CubicEaseInterpolator")
    if c is None:
        return HOLD
    b = tuple(float(c.get(a, 0)) for a in ("x1", "y1", "x2", "y2"))
    return Ease("linear" if b == (0.0, 0.0, 1.0, 1.0) else "cubic", bezier=b)


def _elastic_ease(kf):
    e = kf.find("ElasticInterpolator")
    if e is None:
        return HOLD
    return Ease("elastic", amplitude=float(e.get("amplitude", 1)),
                period=float(e.get("period", 0.4)), easing=e.get("easingValue"))


_EASE_READERS = {"cubic": _cubic_ease, "elastic": _elastic_ease}


def read_ease(kf):
    reader = _EASE_READERS.get(kf.get("interpolationType"))
    return reader(kf) if reader else HOLD


# --- keyframes -----------------------------------------------------------------------------------

class Key(NamedTuple):
    frame: int
    value: object
    ease: Ease
    kind: str
    raw: str = None


def _key(kf):
    """One keyframe element as a Key, or None when the element is not a keyframe."""
    frame = int(float(kf.get("frame", 0)))
    if kf.tag == "KeyFrameDouble":
        return Key(frame, float(kf.get("value", 0)), read_ease(kf), "double")
    if kf.tag == "KeyFrameColor":
        return Key(frame, kf.get("value", ""), HOLD, "color")
    if kf.tag == "KeyFrameId":
        return Key(frame, kf.get("value", ""), HOLD, "id")
    if kf.tag == "KeyFrameCallback":
        return Key(frame, None, HOLD, "callback")
    if kf.tag.startswith("KeyFrame"):
        return Key(frame, kf.get("value", ""), read_ease(kf), "other", raw=kf.tag)
    return None


def read_keys(keyed_property):
    keys = [k for k in (_key(kf) for kf in keyed_property) if k is not None]
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
    a, b = next((a, b) for a, b in zip(keys, keys[1:]) if a.frame <= frame <= b.frame)
    span = b.frame - a.frame
    if span <= 0:
        return b.value, False
    u = (frame - a.frame) / span
    return a.value + (b.value - a.value) * a.ease.at(u), a.ease.kind == "elastic"


# --- rendering -----------------------------------------------------------------------------------

def _key_columns(keys, last, width):
    """The plot columns a keyframe lands on, for a span 0..last drawn `width` columns wide."""
    return {min(width - 1, max(0, int(round(k.frame / float(last) * (width - 1))))) for k in keys}


def _plot(values, elastic, key_cols):
    """The value grid, top row first: '~' on an elastic segment, 'o' on a key column, '*' otherwise."""
    lo, hi = min(values), max(values)
    width = len(values)
    grid = [[" "] * width for _ in range(CURVE_HEIGHT)]
    for col, v in enumerate(values):
        row = min(CURVE_HEIGHT - 1, max(0, int(round((v - lo) / (hi - lo) * (CURVE_HEIGHT - 1)))))
        grid[CURVE_HEIGHT - 1 - row][col] = "~" if elastic[col] else ("o" if col in key_cols else "*")
    return grid


def _framed(grid, lo, hi, last):
    """The grid with its value gutter, axis and frame labels."""
    width = len(grid[0])
    gutter = max(len(num(hi)), len(num(lo))) + 1
    tags = [num(hi)] + [""] * (CURVE_HEIGHT - 2) + [num(lo)]
    out = [f"{INDENT}{tag:>{gutter}} |{''.join(row)}" for tag, row in zip(tags, grid)]
    axis_left, axis_right = "0", str(last)
    pad = max(0, width - len(axis_left) - len(axis_right))
    out.append(f"{INDENT}{'':>{gutter}} +{'-' * width}")
    out.append(f"{INDENT}{'':>{gutter}}  {axis_left}{' ' * pad}{axis_right}  (frames)")
    return out


def curve(keys, duration, width):
    """An ASCII plot of the value over the animation's frames. 'o' marks a keyframe column."""
    if duration <= 0 or len(keys) < 2:
        return []
    width = max(12, width)
    # Always the animation's whole span, so two properties of the same timeline line up and a
    # property whose last key lands early visibly holds to the end.
    last = max(duration, keys[-1].frame)
    samples = [sample(keys, last * i / (width - 1)) for i in range(width)]
    values, elastic = [v for v, _e in samples], [e for _v, e in samples]
    lo, hi = min(values), max(values)
    if hi - lo < 1e-9:
        return [f"{INDENT}flat at {num(lo)}"]
    out = _framed(_plot(values, elastic, _key_columns(keys, last, width)), lo, hi, last)
    if any(elastic):
        gutter = max(len(num(hi)), len(num(lo))) + 1
        out.append(f"{INDENT}{'':>{gutter}}  ~ elastic segment, drawn at its target value")
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


def chart_line(keys, indent=INDENT):
    """The timing chart for a curve, as rig/chart.py draws it. Imported late: rig.chart reads
    this module, so importing it at the top would be a cycle."""
    from rig.chart import Chart
    try:
        return [f"{indent}{Chart.of(keys).text()}"]
    except ValueError:
        return []


class View(NamedTuple):
    """How print_animation draws each property: the curve's width and whether to add its chart."""
    width: int
    chart: bool = False


def property_key(raw_key):
    try:
        return int(raw_key)
    except (TypeError, ValueError):
        return -1


def print_key_table(scene, keys, rotation):
    """The frame / value [/ degrees] / easing table of one keyed property."""
    wf = max([len(str(k.frame)) for k in keys] + [5])
    wv = max([len(value_text(k, scene)) for k in keys] + [5])
    wd = max([len(degrees_text(k)) for k in keys] + [len("degrees")]) if rotation else 0
    degrees = (lambda text: f"  {text:>{wd}}") if rotation else (lambda _text: "")
    print(f"      {'frame':>{wf}}  {'value':<{wv}}" + degrees("degrees") + "  easing")
    for k in keys:
        print(f"      {k.frame:>{wf}}  {value_text(k, scene):<{wv}}" + degrees(degrees_text(k)) + f"  {k.ease}")


def print_property(scene, kp, duration, view):
    """One keyed property: its table, then (for a numeric curve) the plot and the chart."""
    raw_key = kp.get("propertyKey")
    keys = read_keys(kp)
    print(f"    {prop_name(raw_key)}")
    print_key_table(scene, keys, property_key(raw_key) in ROTATION_KEYS)
    if not all(k.kind == "double" for k in keys):
        return
    print_lines(curve(keys, duration, view.width))
    if view.chart:
        print_lines(chart_line(keys))


def print_lines(lines):
    for line in lines:
        print(line)


def print_animation(scene, anim, board, view):
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
            print_property(scene, kp, duration, view)
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

_COMPARATOR_LITERALS = {
    "TransitionValueEnumComparator": lambda scene, b: scene.enum_key(b.get("value", "")),
    "TransitionValueBooleanComparator": lambda scene, b: b.get("value", ""),
    "TransitionValueNumberComparator": lambda scene, b: num(b.get("value", "")),
    "TransitionValueTriggerComparator": lambda scene, b: None,
}


def _bound_property(scene, el, prop):
    """The view-model property a BindableProperty element reads, else `prop` unchanged."""
    ctx = el.find("DataBindContext") if el.tag.startswith("BindableProperty") else None
    return scene.vm_property(ctx.get("sourcePathIds")) if ctx is not None else prop


def vm_condition_text(scene, c):
    """'state == listening' / 'success fired' for one TransitionViewModelCondition."""
    op = OPS.get(c.get("opValue", "equal"), c.get("opValue", "?"))
    prop, literal = "?", ""
    for b in c.iter():
        prop = _bound_property(scene, b, prop)
        reader = _COMPARATOR_LITERALS.get(b.tag)
        if reader:
            literal = reader(scene, b)
    return f"{prop} fired" if literal is None else f"{prop} {op} {literal}"


def input_condition_texts(scene, transition):
    """The legacy state-machine-input conditions of a transition, as text."""
    bits = []
    for c in transition.findall("TransitionNumberCondition"):
        op = OPS.get(c.get("opValue", "equal"), c.get("opValue", "?"))
        bits.append(f"{scene.label(c.get('inputId'))} {op} {num(c.get('value', 0))}")
    bits += [f"{scene.label(c.get('inputId'))} == {c.get('value', '')}" for c in transition.findall("TransitionBoolCondition")]
    bits += [f"{scene.label(c.get('inputId'))} fired" for c in transition.findall("TransitionTriggerCondition")]
    return bits


def condition_text(scene, transition):
    bits = []
    if transition.get("enableExitTime") == "true":
        bits.append(f"on exit {transition.get('exitTime', '100')}%")
    bits += [vm_condition_text(scene, c) for c in transition.findall("TransitionViewModelCondition")]
    bits += input_condition_texts(scene, transition)
    return " and ".join(bits) if bits else "always"


def state_flags(state, sep):
    """'[reset, random]' for an AnimationState's set flags, prefixed by `sep`; '' when none are set."""
    flags = [f for f in ("reset", "random") if state.get(f) == "true"]
    return f"{sep}[{', '.join(flags)}]" if flags else ""


def state_label(scene, layer, state_id):
    el = next((el for el in layer.iter() if el.get("id") == state_id), None)
    if el is None:
        return f"<{state_id}>"
    if el.tag != "AnimationState":
        return el.tag
    return f"{scene.animation_name(el.get('animationId'))}" + state_flags(el, " ")


def print_transitions(scene, layer, holder, indent):
    for t in holder.findall("StateTransition"):
        target = state_label(scene, layer, t.get("stateToId"))
        weight = t.get("randomWeight")
        bits = [f"{indent}-> {target}", f"{t.get('duration', 0)} ms"]
        if weight:
            bits.append(f"weight {weight}")
        bits.append(condition_text(scene, t))
        print("  ".join(bits))


def machine_inputs(scene, sm):
    """(inputs, listeners) of a state machine, as text."""
    inputs = [f"{e.get('name')} ({e.tag.replace('StateMachine', '').lower()})" for e in sm if e.tag in INPUT_TAGS]
    listeners = [f"{e.get('name')} on {scene.label(e.get('targetId'))} ({e.get('listenerTypeValue', '?')})"
                 for e in sm if e.tag.startswith("StateMachineListener")]
    return inputs, listeners


def print_layer(scene, layer):
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
        print(f"    {scene.animation_name(st.get('animationId'))}  ({st.get('id')}){state_flags(st, '  ')}")
        print_transitions(scene, layer, st, "      ")


def print_layers(scene):
    for board, sm in scene.machines:
        inputs, listeners = machine_inputs(scene, sm)
        print(f"StateMachine {sm.get('name')}  [{board}]  id {sm.get('id')}")
        if inputs:
            print(f"  inputs: {', '.join(inputs)}")
        if listeners:
            print(f"  listeners: {', '.join(listeners)}")
        for layer in sm.findall("StateMachineLayer"):
            print_layer(scene, layer)
        print()


# --- main ----------------------------------------------------------------------------------------

def parser():
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("pattern", nargs="?", help="animation name or substring")
    p.add_argument("--rml", default=os.path.join(here, "scene.rml"), help="the document to read")
    p.add_argument("--width", type=int, default=60, help="curve width in columns")
    p.add_argument("--list", action="store_true", help="every animation, grouped by prefix")
    p.add_argument("--layers", action="store_true", help="the state machine layers as a tree")
    p.add_argument("--chart", action="store_true",
                   help="a timing chart under each property (rig/chart.py)")
    return p


def print_matches(scene, pattern, view):
    """Every animation whose name contains `pattern`; 1 when there is none."""
    needle = pattern.lower()
    hits = [(b, a) for b, a in scene.animations if needle in (a.get("name") or "").lower()]
    if not hits:
        print(f"no animation matching {pattern!r}; try --list")
        return 1
    for board, anim in hits:
        print_animation(scene, anim, board, view)
    if len(hits) > 1:
        print(f"{len(hits)} animations matched {pattern!r}")
    return 0


def main(argv=None):
    p = parser()
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
    return print_matches(scene, args.pattern, View(args.width, args.chart))


if __name__ == "__main__":
    sys.exit(main())
