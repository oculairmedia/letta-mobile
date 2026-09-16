"""The seam ledger: where one animation hands a property to another with a hard cut.

A layer blends: a transition's `duration` is how long Rive cross-fades the outgoing animation's
pose into the incoming one's. When the two animations key the same (object, property) at
different values and the duration is 0 ms, the value tears - that is a *seam*. The other half of
the same problem is a *hand-back*: a one-shot ends holding a property its target does not key, so
the value snaps back to whatever the layers below are showing (rest) at the cut.

    python -m rig.seams               # the committed scene.rml as a table
    python -m rig.seams --json        # the same rows as JSON
    python -m rig.seams /tmp/x.rml    # any generated document

A `cut=True` signature (rig/layers.py) accounts for the *hard cut* it sits on: the two animations
keyed that property and the author decided the jump is the design. It does NOT account for a
hand-back, which is a question about the layers below rather than about the transition. A
hand-back has its own signature, `hold=True` with a reason, which says the pose the one-shot lets
go of is the pose the layers underneath are already holding - so the value the ledger computes as
a snap to rest is not one. An unsigned hand-back stays on the ledger as a finding.

As a library this module gives `rig/layers.py` its blend policy: `animation_index()` turns a
generated document (or just the joined animation XML, before it is wrapped in an artboard) into
{animation id: {(objectId, propertyKey): (first, last, first_frame, last_frame)}}, and
`crossings()` reports what a hand-off from one animation to another actually moves. `Layer.rml()`
refuses a 0 ms transition across such a move unless the state or the transition is marked
`cut=True` with a reason (see rig/layers.py).

Parsing is timeline.py's: `read_keys` for keyframes, its property table for names. Read-only.

Epsilons are per property, in the property's own unit: 0.5 px for x/y, 0.5 deg for rotations,
0.01 for scale and opacity, and 0 for enums, object references and nested values - a glyph that
swaps is either the old one or the new one, so any difference is the whole difference.
"""
import argparse
import json
import math
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import NamedTuple

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from timeline import PROP_NAMES, ROTATION_KEYS, num, prop_name, property_key, read_keys  # noqa: E402

NESTED_FIRE = 401  # callbacks have no value; never a seam

POSITION_KEYS = {13, 14, 24, 25, 83, 85, 87}          # x / y / vertex x / y / handle distances
SCALE_KEYS = {16, 17, 18, 46}                          # scaleX, scaleY, opacity, gradient opacity
DEG = math.pi / 180.0


def epsilon(key):
    """How far a property may jump at a hard cut before a human sees it tear."""
    if key in POSITION_KEYS:
        return 0.5
    if key in ROTATION_KEYS:
        return 0.5 * DEG
    if key in SCALE_KEYS:
        return 0.01
    return 0.0


def rest_value(key):
    """The value a property falls back to when nothing keys it, or None when there is no such
    thing (a colour, an enum, a nested value: the layers below decide, not a constant)."""
    if key in POSITION_KEYS or key in ROTATION_KEYS:
        return 0.0
    if key in (16, 17):
        return 1.0
    return None


def display(key, value):
    """A value as the ledger prints it: rotations in degrees, everything else as written."""
    if isinstance(value, (int, float)) and key in ROTATION_KEYS:
        return f"{num(value / DEG, 2)} deg"
    return num(value) if isinstance(value, (int, float)) else str(value)


# --- the animation index --------------------------------------------------------------------
class Span(NamedTuple):
    """What one keyed property does over one animation: its ends, and where they are."""
    first: object
    last: object
    first_frame: int
    last_frame: int


@dataclass(repr=False, eq=False, slots=True)
class Anim:
    """One LinearAnimation's keyed ends. Indexes like the mapping it wraps:
    `index[aid][(objectId, propertyKey)]` is a Span."""

    id: str
    name: str
    loop: str
    duration: int
    props: dict

    def __getitem__(self, k):
        return self.props[k]

    def __contains__(self, k):
        return k in self.props

    def __iter__(self):
        return iter(self.props)

    def __len__(self):
        return len(self.props)

    def get(self, k, default=None):
        return self.props.get(k, default)

    def items(self):
        return self.props.items()

    def keys(self):
        return self.props.keys()

    def __repr__(self):
        return f"<Anim {self.name} ({self.id}) {self.loop} {len(self.props)} properties>"


def parse(document):
    """An ElementTree root from a path, a document string, or a bare run of LinearAnimations
    (the joined animation XML gen_scene.py holds before it becomes an artboard)."""
    if isinstance(document, ET.Element):
        return document
    text = document
    if not str(document).lstrip().startswith("<"):
        with open(document, encoding="utf-8") as f:
            text = f.read()
    try:
        return ET.fromstring(text)
    except ET.ParseError:
        return ET.fromstring("<SeamFragment>\n" + text + "\n</SeamFragment>")


def animation_index(document):
    """{animation id: Anim} for every LinearAnimation in the document."""
    root = parse(document)
    return {anim.get("id"): Anim(anim.get("id"), anim.get("name", "?"), anim.get("loopValue", "oneShot"),
                                 int(float(anim.get("duration", 0))), keyed_spans(anim))
            for anim in root.iter("LinearAnimation")}


def keyed_spans(anim):
    """{(objectId, propertyKey): Span} for every valued keyed property of one animation."""
    props = {}
    for ko in anim.findall("KeyedObject"):
        for kp in ko.findall("KeyedProperty"):
            key = property_key(kp.get("propertyKey"))
            keys = read_keys(kp) if key not in (-1, NESTED_FIRE) else []
            if keys:
                props[(ko.get("objectId"), key)] = Span(keys[0].value, keys[-1].value, keys[0].frame, keys[-1].frame)
    return props


def object_names(document):
    """{object id: name} for naming the ledger's rows; empty for a bare animation fragment."""
    return {el.get("id"): el.get("name") for el in parse(document).iter()
            if el.get("id") and el.get("name")}


# --- what a hand-off moves ------------------------------------------------------------------
class Cross(NamedTuple):
    """One property that changes value when `from_anim` hands over to `to_anim`."""
    obj: str
    key: int
    from_value: object
    to_value: object
    delta: float
    eps: float
    hand_back: bool

    @property
    def normalised(self):
        """The delta in units of its own epsilon, so px, degrees and scale compare. An epsilon
        of 0 (enums, references, nested values) counts the raw difference."""
        return self.delta / self.eps if self.eps else self.delta


def _delta(a, b):
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(float(b) - float(a))
    return 0.0 if a == b else 1.0


def crossings(index, from_anim, to_anim):
    """Every property that moves when `from_anim` hands over to `to_anim`, past its epsilon.

    Two kinds: both animations key the property and the values differ (a seam when the blend is
    0 ms), or the outgoing one-shot keys it and the target does not, so it snaps back to rest
    (a hand-back - the layers below take the property again at the cut)."""
    a = index.get(from_anim)
    if a is None:
        return []
    b = index.get(to_anim)
    moves = (_handoff(a, b, prop, span) for prop, span in a.props.items())
    return [c for c in moves if c is not None]


_UNTAKEN = object()   # the hand-off leaves the property to nobody: no crossing to weigh


def _target(a, b, prop):
    """(the value `prop` lands on after the hand-off, is it a hand-back?). The value is _UNTAKEN
    when nothing takes the property: a looping source, or a hand-back with no rest value."""
    if b is not None and prop in b.props:
        return b.props[prop].first, False
    rest = rest_value(prop[1]) if a.loop == "oneShot" else None
    return (_UNTAKEN if rest is None else rest), True


def _handoff(a, b, prop, span):
    """The Cross `prop` makes when `a` hands over to `b`, or None when it stays within epsilon."""
    target, hand_back = _target(a, b, prop)
    if target is _UNTAKEN:
        return None
    eps = epsilon(prop[1])
    d = _delta(span.last, target)
    return Cross(prop[0], prop[1], span.last, target, d, eps, hand_back) if d > eps else None


# --- the transitions of a document ------------------------------------------------------------
class Trans(NamedTuple):
    """One way out of one state, as the document writes it."""
    machine: str
    layer: str
    from_node: str
    to_node: str
    from_anim: str
    to_anim: str
    ms: int
    kind: str


def _kind(t):
    if t.get("enableExitTime") == "true":
        return "exit"
    for c in t.iter():
        if c.tag == "TransitionValueEnumComparator":
            return "enum"
        if c.tag == "TransitionValueBooleanComparator":
            return "bool"
        if c.tag == "TransitionValueTriggerComparator":
            return "trigger"
    if t.find("TransitionNumberCondition") is not None or t.find("TransitionTriggerCondition") is not None:
        return "input"
    return "always"


def transitions(document):
    """Every state-to-state transition in every layer. An AnyState transition is expanded over
    every state in its layer: that is what it does at runtime, so every source it could cut from
    is a hand-off the ledger has to weigh."""
    root = parse(document)
    return [t for sm in root.iter("StateMachine") for layer in sm.findall("StateMachineLayer")
            for t in layer_transitions(sm.get("name", "?"), layer)]


def layer_transitions(machine, layer):
    """Every state-to-state transition of one layer, AnyState expanded over its states."""
    name = layer.get("name", "?")
    states = [(st.get("id"), st.get("animationId")) for st in layer.findall("AnimationState")]
    anim_of = dict(states)
    any_state = layer.find("AnyState")
    any_transitions = [] if any_state is None else any_state.findall("StateTransition")
    expanded = [Trans(machine, name, node, t.get("stateToId"), aid, anim_of.get(t.get("stateToId")),
                      int(t.get("duration", 0)), "any")
                for t in any_transitions for node, aid in states if node != t.get("stateToId")]
    own = [Trans(machine, name, st.get("id"), t.get("stateToId"), st.get("animationId"),
                 anim_of.get(t.get("stateToId")), int(t.get("duration", 0)), _kind(t))
           for st in layer.findall("AnimationState") for t in st.findall("StateTransition")]
    return expanded + own


# --- the ledger ---------------------------------------------------------------------------------
class Row(NamedTuple):
    """One seam: one property, torn by one 0 ms transition."""
    machine: str
    layer: str
    frm: str
    to: str
    kind: str
    ms: int
    obj: str
    prop: str
    from_value: object
    to_value: object
    delta: float
    normalised: float
    hand_back: bool
    cut: bool
    reason: str


def cut_marks():
    """The signatures the rig declares, as (cuts, holds), each
    {(layer, source node, target node): reason}.

    They live in Python (rig/machine.py, rig/plate.py), not in the document, so reading them
    means building the layers: importing the two modules and running their builders populates
    rig.layers.CUT_MARKS and rig.layers.HOLD_MARKS as a side effect."""
    from rig import layers as layer_mod
    from rig.machine import root_machine
    from rig.plate import plate_component
    layer_mod.CUT_MARKS.clear()
    layer_mod.HOLD_MARKS.clear()
    root_machine()
    plate_component()
    return dict(layer_mod.CUT_MARKS), dict(layer_mod.HOLD_MARKS)


def ledger(document, marks=None):
    """Every seam in the document, worst first by normalised delta."""
    root = parse(document)
    index = animation_index(root)
    marks, holds = cut_marks() if marks is None else marks
    rows = [row for t in transitions(root) if t.ms == 0 for row in seam_rows(index, t, (marks, holds))]
    rows.sort(key=lambda r: (-r.normalised, r.layer, r.frm, r.to, r.obj, r.prop))
    return rows


def _signature(marks, t):
    """The reason a signature table gives transition `t`: its own edge, else any edge into its target."""
    return marks.get((t.layer, t.from_node, t.to_node), marks.get((t.layer, None, t.to_node)))


def seam_rows(index, t, signatures):
    """The ledger rows of one 0 ms transition: one per property its hand-off moves."""
    reason, held = (_signature(m, t) for m in signatures)
    rows = []
    for c in crossings(index, t.from_anim, t.to_anim):
        # A cut signature covers the hard cut it sits on, never the hand-back underneath it: what
        # the lower layers do with a property nobody keys is not the transition's to give. A
        # hand-back needs its own signature (`hold=True`), which says the pose the one-shot lets
        # go of is the pose the layers below are already holding.
        signed = held if c.hand_back else reason
        rows.append(Row(t.machine, t.layer, _anim_name(index, t.from_anim),
                        _anim_name(index, t.to_anim), t.kind, t.ms, c.obj, prop_name(c.key),
                        c.from_value, c.to_value, c.delta, c.normalised, c.hand_back,
                        bool(signed), signed))
    return rows


def _anim_name(index, aid):
    a = index.get(aid)
    return a.name if a is not None else str(aid)


def unexplained(rows):
    """The seams nobody has taken responsibility for: no cut=True, no reason."""
    return [r for r in rows if not r.cut]


# --- the table ------------------------------------------------------------------------------
_KEY_OF = {name: key for key, name in PROP_NAMES.items()}


def _key_of(prop):
    """The property key behind a ledger row's property name (the row carries the name)."""
    return _KEY_OF.get(prop, -1)


def table(rows, names=None):
    """The ledger as lines of text, worst first."""
    names = names or {}
    body = [table_cells(r, names) for r in rows]
    widths = [max([len(h)] + [len(row[i]) for row in body]) for i, h in enumerate(TABLE_HEAD)]
    out = [_aligned(TABLE_HEAD, widths), "  ".join("-" * w for w in widths)]
    return out + [_aligned(row, widths) for row in body]


TABLE_HEAD = ["layer", "from", "to", "kind", "ms", "object", "property", "from", "to", "delta", "why"]


def _aligned(cells, widths):
    return "  ".join(c.ljust(w) for c, w in zip(cells, widths)).rstrip()


def table_cells(r, names):
    """One ledger row as the table's text cells."""
    key = _key_of(r.prop)
    kind = r.kind + ("/handback" if r.hand_back else "")
    why = (("hold: " if r.hand_back else "cut: ") + r.reason) if r.reason else ""
    return [f"{r.machine}/{r.layer}", r.frm, r.to, kind, f"{r.ms}", names.get(r.obj, r.obj), r.prop,
            display(key, r.from_value), display(key, r.to_value), f"{num(r.normalised, 1)}x", why]


def summary(rows):
    bad = unexplained(rows)
    hand = sum(1 for r in bad if r.hand_back)
    return (f"{len(rows)} seams across {len({(r.machine, r.layer) for r in rows})} layers: "
            f"{len(rows) - len(bad)} signed cut=True, {len(bad)} unexplained "
            f"({hand} hand-backs, {len(bad) - hand} hard cuts)")


def main(argv=None):
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("rml", nargs="?", default=os.path.join(here, "scene.rml"),
                   help="the document to read (default: the committed scene.rml)")
    p.add_argument("--json", action="store_true", help="the rows as JSON")
    p.add_argument("--all", action="store_true", help="include the seams marked cut=True")
    p.add_argument("--limit", type=int, default=0, help="print only the worst N rows")
    args = p.parse_args(argv)

    rows = ledger(args.rml)
    shown = rows if args.all else unexplained(rows)
    if args.limit:
        shown = shown[:args.limit]
    if args.json:
        print(json.dumps({"summary": summary(rows), "seams": [r._asdict() for r in shown]},
                         indent=2, default=str))
        return 0
    for line in table(shown, object_names(args.rml)):
        print(line)
    print()
    print(summary(rows) + ("" if args.all else "; --all also lists the marked ones"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
