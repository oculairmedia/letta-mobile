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

from timeline import PROP_NAMES, ROTATION_KEYS, num, prop_name, property_key, read_keys, sample  # noqa: E402

NESTED_FIRE = 401  # callbacks have no value; never a seam

# The two kinds of property a blend cannot carry. Everything else is a *pose* property: it moves
# on screen, so a state change that moves it has to take at least MIN_TRANSITION_MS
# (rig/constants.py) - a pose that changes in one frame is a pop, whatever reason is signed.
#
#   SWEEP_KEYS  a number that indexes something: the plate's `expr` (NestedNumber.value) picks a
#               glyph by band, so a blend between 2 and 9 shows glyphs 3, 4, ... 8 on the way.
#               It may only ever cut, and nothing may blend across a change in it.
#   SWAP_KEYS   a reference: Solo.activeComponentId. It has no in-between, so a blend just swaps
#               it on the first frame; a cut and a blend look the same.
SWEEP_KEYS = {239}
SWAP_KEYS = {296}
DISCRETE_KEYS = SWEEP_KEYS | SWAP_KEYS


def is_pose(key):
    """True for a property that moves on screen and therefore blends (see DISCRETE_KEYS)."""
    return key not in DISCRETE_KEYS

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
    """What one keyed property does over one animation: its ends, where they are, and the keys
    themselves (timeline.py's Key tuples, for sampling the value at any frame)."""
    first: object
    last: object
    first_frame: int
    last_frame: int
    keys: tuple = ()

    def at(self, frame):
        """The value at `frame`, as Rive plays it (timeline.sample: leaving-key easing)."""
        if not self.keys or not isinstance(self.first, (int, float)):
            held = [k for k in self.keys if k.frame <= frame]
            return held[-1].value if held else self.first
        return sample(self.keys, frame)[0]

    def values(self, lo, hi):
        """Every value the property takes over frames lo..hi (inclusive), sampled per frame."""
        return [self.at(f) for f in range(max(0, lo), max(lo, hi) + 1)]


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
                props[(ko.get("objectId"), key)] = Span(keys[0].value, keys[-1].value, keys[0].frame,
                                                        keys[-1].frame, tuple(keys))
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


# --- what a state change moves, frame by frame ---------------------------------------------------
# crossings() above weighs the two ENDS of a hand-off: the last key of the outgoing animation
# against the first of the incoming one. That is only what happens when the outgoing animation
# is left at its end - an exit-time transition out of a one-shot. Every other way out fires at
# whatever frame the request arrives: a loop is left mid-bob, mid-lean, mid-turn, and a one-shot
# interrupted by another request is left wherever it got to. Its last key says nothing about
# that frame. pose_moves() weighs every frame the source can actually be left at.
class Move(NamedTuple):
    """One pose property a state change moves, in its worst case."""
    obj: str
    key: int
    from_value: object
    to_value: object
    delta: float
    eps: float
    kind: str           # "seam" (both key it), "hand-back" (only the source), "hand-in" (only the target)

    @property
    def normalised(self):
        return self.delta / self.eps if self.eps else self.delta

    @property
    def hand_back(self):
        return self.kind == "hand-back"


def departure_values(anim, span, depart):
    """The values `span` can be left at. `depart` is "end" (an exit-time transition out of a
    one-shot: its end only), an int (an exit-time gate in frames: any frame from that one on), or
    None (any frame at all - a loop, or a request that can arrive at any moment)."""
    if anim.loop != "oneShot":
        return span.values(0, anim.duration)
    if depart == "end":
        return [span.at(anim.duration)]
    return span.values(depart or 0, anim.duration)


def _worst(values, target):
    """(the value furthest from `target`, how far) over a list of departure values."""
    pairs = [(v, _delta(v, target)) for v in values]
    return max(pairs, key=lambda p: p[1]) if pairs else (target, 0.0)


def pose_moves(index, from_anim, to_anim, depart=None):
    """Every POSE property (seams.is_pose) a change from `from_anim` to `to_anim` moves past its
    epsilon, from the worst frame the source can be left at. Discrete properties are not here:
    they cannot blend, so they are what a cut is for (see sweeps() for the other half)."""
    a, b = index.get(from_anim), index.get(to_anim)
    if a is None or b is None:
        return []
    out = []
    for prop in sorted(set(a.props) | set(b.props), key=lambda p: (p[0], p[1])):
        if not is_pose(prop[1]):
            continue
        move = _pose_move(a, b, prop, depart)
        if move is not None:
            out.append(move)
    return out


def _pose_ends(a, b, prop, depart):
    """(the worst value the change leaves `prop` at, the value it goes to, the kind of move), or
    None when only one side keys a property that has no rest value to weigh it against."""
    if prop in a.props and prop in b.props:
        to = b.props[prop].at(0)
        return _worst(departure_values(a, a.props[prop], depart), to)[0], to, "seam"
    rest = rest_value(prop[1])
    if rest is None:
        return None
    if prop in a.props:
        return _worst(departure_values(a, a.props[prop], depart), rest)[0], rest, "hand-back"
    return rest, b.props[prop].at(0), "hand-in"


def _pose_move(a, b, prop, depart):
    ends = _pose_ends(a, b, prop, depart)
    if ends is None:
        return None
    v, to, kind = ends
    d, eps = _delta(v, to), epsilon(prop[1])
    return Move(prop[0], prop[1], v, to, d, eps, kind) if d > eps else None


class Blend(NamedTuple):
    """A transition's blend as sweeps() weighs it: how long it takes, and where its source can be
    left (as departure_values() takes it)."""
    ms: int
    depart: object = None

    @property
    def frames(self):
        return max(1, round(self.ms * 60 / 1000))


def sweeps(index, from_anim, to_anim, blend):
    """Every SWEEP property (an index, `expr`) a blend of `ms` would interpolate across a change.

    During a blend the outgoing animation keeps playing and the incoming one starts from frame 0,
    and Rive mixes the two numbers: if they differ anywhere in that window, the index passes
    through every value between them. A property only one side keys is mixed against whatever the
    layers below hold, which no blend can know - so it counts as a sweep too."""
    if blend.ms <= 0:
        return []
    a, b = index.get(from_anim), index.get(to_anim)
    if a is None or b is None:
        return []
    swept = (_sweep(a, b, prop, blend) for prop in sorted(set(a.props) | set(b.props))
             if prop[1] in SWEEP_KEYS)
    return [m for m in swept if m is not None]


def _one_sided_sweep(a, b, prop):
    """A sweep property only one side keys: mixed against the layers below, so always a sweep."""
    v = (a if prop in a.props else b).props[prop].first
    return Move(prop[0], prop[1], v if prop in a.props else None, v if prop in b.props else None,
                1.0, 0.0, "sweep")


def _outgoing_window(a, span, blend):
    """The values the outgoing animation plays through over an n-frame blend that leaves it at
    `depart` (a loop can be left anywhere, so all of them)."""
    if a.loop != "oneShot":
        return set(span.values(0, a.duration))
    lo = a.duration if blend.depart == "end" else (blend.depart or 0)
    return set(span.values(lo, lo + blend.frames))


def _sweep(a, b, prop, blend):
    if prop not in a.props or prop not in b.props:
        return _one_sided_sweep(a, b, prop)
    incoming = set(b.props[prop].values(0, blend.frames - 1))
    outgoing = _outgoing_window(a, a.props[prop], blend)
    if len(incoming | outgoing) <= 1:
        return None
    v0, v1 = sorted(outgoing)[0], sorted(incoming)[-1]
    return Move(prop[0], prop[1], v0, v1, _delta(v0, v1), 0.0, "sweep")


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
    after: int = None     # an exit-time gate in frames (`exitTime` in ms, not a percentage)

    @property
    def depart(self):
        """Where the source can be left, as departure_values() takes it."""
        return "end" if self.kind == "exit" else self.after


def _gate(t):
    """The exit-time gate of a transition in frames, when it has one that is not its end."""
    if t.get("enableExitTime") == "true" and t.get("exitTimeIsPercetange") != "true":
        return round(int(t.get("exitTime", 0)) * 60 / 1000)
    return None


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
                 anim_of.get(t.get("stateToId")), int(t.get("duration", 0)),
                 "enum" if _gate(t) is not None and _kind(t) == "exit" else _kind(t), _gate(t))
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
    below = keyed_below(root, index)
    rows = [row for t in transitions(root)
            for row in seam_rows(index, t, (marks, holds), below.get((t.machine, t.layer), set()))]
    rows.sort(key=lambda r: (-r.normalised, r.layer, r.frm, r.to, r.obj, r.prop))
    return rows


def _signature(marks, t):
    """The reason a signature table gives transition `t`: its own edge, else any edge into its target."""
    return marks.get((t.layer, t.from_node, t.to_node), marks.get((t.layer, None, t.to_node)))


def keyed_below(document, index):
    """{(machine, layer): every (object, property) some EARLIER layer of that machine keys}.

    Layers apply in order, each over the ones before it. A property only the outgoing animation
    keys is handed back to whatever the layers below put there - or, when none of them keys it,
    it simply holds its last value (nothing writes it), which is no snap at all."""
    out = {}
    for sm in parse(document).iter("StateMachine"):
        seen = set()
        for layer in sm.findall("StateMachineLayer"):
            out[(sm.get("name", "?"), layer.get("name", "?"))] = set(seen)
            seen |= _layer_props(layer, index)
    return out


def _layer_props(layer, index):
    """Every (object, property) some animation of one state machine layer keys."""
    anims = (index.get(st.get("animationId")) for st in layer.findall("AnimationState"))
    return {prop for a in anims if a is not None for prop in a.props}


def _pose_rows(index, t, held, below):
    """[(move, signature)] for the pose properties a transition moves: its hand-backs onto a
    property a layer below keys (signed by `hold=True`), and, under the floor, everything else."""
    from rig.constants import MIN_TRANSITION_MS
    under_floor = t.ms < MIN_TRANSITION_MS
    rows = []
    for m in pose_moves(index, t.from_anim, t.to_anim, t.depart):
        if m.hand_back and (m.obj, m.key) in below:
            rows.append((m, held))
        elif not m.hand_back and under_floor:
            rows.append((m, None))
    return rows


def _cut_rows(index, t, reason, held):
    """[(crossing, signature)] for the discrete properties a 0 ms transition swaps. A cut
    signature covers the hard cut it sits on, never the hand-back underneath it."""
    if t.ms:
        return []
    return [(c, held if c.hand_back else reason) for c in crossings(index, t.from_anim, t.to_anim)
            if not is_pose(c.key)]


def seam_rows(index, t, signatures, below=frozenset()):
    """The ledger rows of one transition, under the blend policy rig/layers.py enforces:

      - under MIN_TRANSITION_MS, every pose property it moves from any frame its source can be
        left at (pose_moves) - never signed by a cut;
      - at ANY duration, every hand-back of a pose property a layer below also keys: Rive plays
        the outgoing animation at full weight for the whole blend and lets the layers below have
        the property only when it ends, so a duration does not soften one. Signed by `hold=True`
        when the layers below are holding that pose by design;
      - at 0 ms, every discrete property it swaps (a glyph, a reference) - signed by `cut=True`;
      - with a duration, every index it would sweep (sweeps) - never signed."""
    reason, held = (_signature(m, t) for m in signatures)
    moves = _pose_rows(index, t, held, below) + _cut_rows(index, t, reason, held)
    moves += [(m, None) for m in sweeps(index, t.from_anim, t.to_anim, Blend(t.ms, t.depart))]
    return [Row(t.machine, t.layer, _anim_name(index, t.from_anim), _anim_name(index, t.to_anim),
                t.kind, t.ms, c.obj, prop_name(c.key), c.from_value, c.to_value, c.delta,
                c.normalised, c.hand_back, bool(signed), signed)
            for c, signed in moves]


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
