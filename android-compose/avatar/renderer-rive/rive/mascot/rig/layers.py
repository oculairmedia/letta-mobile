"""A typed builder for state-machine layers, so a bad layer fails in Python rather than in
`rive --verify` (or, worse, silently at runtime). The XML still comes from rml.py: this module
only declares the shape of a layer and checks it before handing it over.

    Layer("Blink", "3:3", BLINK_REST_NODE,
          any_transitions=[OnTrigger(BLINK_NODE, VM_BLINK)],
          states=[State(BLINK_REST_ANIM, BLINK_REST_NODE, 0),
                  State(BLINK_ANIM, BLINK_NODE, 1, reset=True,
                        transitions=[Exit(BLINK_REST_NODE)])]).rml()

Transitions keep their declared order - Rive evaluates a state's transitions in order, so the
park check has to be written first (see README, "Rive gotchas"). Read by rig/machine.py (the root
layers) and rig/plate.py (the plate's); it knows nothing about the mascot.

Rive rules this builder checks for you: an exit-time transition never fires on a looping
animation; a randomWeight only means anything leaving a random="true" state, and such a state
needs at least two weighted ways out; every transition must target a state in its own layer.

The blend policy (MOTION-PIPELINE step A) is the fourth check. Given an animation index from
rig/seams.py, `Layer.rml()` refuses a 0 ms transition whose two animations key the same property
at different values - that value tears at the cut - unless the transition, or the state it leaves,
says `cut=True, reason="..."`. The index arrives either per call (`layer.rml(seams=index)`) or
through the module-level `set_animation_index()`, which gen_scene.py and rig/plate.py set once
from the animation XML they have just built. The marks are also recorded in `CUT_MARKS` so
`python -m rig.seams` can say which seams are accounted for.
"""
import re
from dataclasses import dataclass, field

from rml import (EASE_OUT, VM_STATE, anim_state, bool_transition, enum_transition,
                 exit_transition, input_transition, layer_frame, trigger_transition, weighted)
from rig.seams import crossings, prop_name as _prop

# The animation index every Layer.rml() validates against, when the caller has set one. It is
# module-level on purpose: the layer builders are called deep inside rig/machine.py and
# rig/plate.py, and threading an index through every one of them would be all plumbing and no
# meaning. gen_scene.py sets it from the root artboard's animations, rig/plate.py from the
# plate's; None (the default) disables the blend check and nothing else.
_ANIMATION_INDEX = None

# Every declared cut, as {(layer name, source state node or None for AnyState, target node):
# reason}. Rebuilt whenever the layers are; read by rig/seams.py to tell a designed cut from a
# finding.
CUT_MARKS = {}

# The same, for hand-backs: `hold=True` on a transition says the one-shot it leaves ends holding
# a pose ON PURPOSE, because the layers underneath are already showing that pose - so the value
# the ledger reads as "snaps back to rest" does not, in fact, snap. A cut signature never covers
# a hand-back (see rig/seams.py); this is the signature that does, and like `cut` it needs a
# reason. {(layer name, source state node or None, target node): reason}.
HOLD_MARKS = {}


def set_animation_index(index):
    """Install the animation index the blend policy checks against (None turns it off)."""
    global _ANIMATION_INDEX
    _ANIMATION_INDEX = index


def animation_index():
    """The installed index, for a caller that wants to validate something by hand."""
    return _ANIMATION_INDEX


def _name(index, aid):
    a = index.get(aid)
    return f"{a.name} ({aid})" if a is not None else str(aid)


# --- transitions --------------------------------------------------------------------------------
# Each wraps one rml.py builder and, on top of the XML, declares the target the layer validates.
# `cut=True` with a `reason` is a signature on a 0 ms hand-off: it says the tear is the design
# (hidden by the blink shutter, a self-returning flash, a park) and not an oversight.
@dataclass(frozen=True)
class Exit:
    """Exit-time transition: fires when the animation reaches its end. Meaningless on a looping
    animation, which never reaches one. A weight is only legal leaving a random="true" state."""
    to: str
    ms: int = 0
    bezier: str = None
    weight: int = None
    cut: bool = False
    reason: str = None
    hold: bool = False

    def rml(self):
        t = exit_transition(self.to, self.ms, self.bezier)
        return weighted(t, self.weight) if self.weight is not None else t


@dataclass(frozen=True)
class OnEnum:
    """Fires while a view-model enum property equals (or, with op, differs from) a value."""
    to: str
    enum_value_id: str
    ms: int = 160
    bezier: str = EASE_OUT
    prop: str = VM_STATE
    op: str = "equal"
    cut: bool = False
    reason: str = None
    hold: bool = False
    weight = None

    def rml(self):
        return enum_transition(self.to, self.enum_value_id, self.ms, self.bezier, self.prop, op=self.op)


@dataclass(frozen=True)
class OnBool:
    """Fires while a view-model boolean property holds `value`."""
    to: str
    prop: str
    value: str
    ms: int = 0
    bezier: str = None
    cut: bool = False
    reason: str = None
    hold: bool = False
    weight = None

    def rml(self):
        return bool_transition(self.to, self.prop, self.value, self.ms, self.bezier)


@dataclass(frozen=True)
class OnTrigger:
    """Fires once when a view-model trigger is pulled. Always an instant cut (0 ms)."""
    to: str
    prop: str
    cut: bool = False
    reason: str = None
    hold: bool = False
    weight = None
    ms = 0

    def rml(self):
        return trigger_transition(self.to, self.prop)


@dataclass(frozen=True)
class OnInput:
    """A nested-artboard input condition (the plate's `expr`). Banded, not exact - see
    rml.input_transition."""
    to: str
    input_id: str
    value: float
    ms: int = 160
    cut: bool = False
    reason: str = None
    hold: bool = False
    weight = None

    def rml(self):
        return input_transition(self.to, self.input_id, self.value, self.ms)


@dataclass(frozen=True)
class Raw:
    """Escape hatch for a transition whose XML is built elsewhere (a hand-rolled condition pair).
    It still declares its target, so the layer's target and duplicate checks cover it."""
    to: str
    xml: str
    cut: bool = False
    reason: str = None
    hold: bool = False
    weight = None

    def rml(self):
        return self.xml

    @property
    def ms(self):
        """The duration out of the hand-rolled XML (absent means 0, as Rive reads it)."""
        m = re.search(r'duration="(\d+)"', self.xml)
        return int(m.group(1)) if m else 0


# --- states and layers --------------------------------------------------------------------------
@dataclass
class State:
    """One AnimationState. `index` drives the y position only (y = 40 + 60*index)."""
    anim: str
    node: str
    index: int
    reset: bool = False
    random: bool = False
    transitions: list = field(default_factory=list)
    # A cut declared here covers every way out of this state, so a matrix of instant cuts is
    # signed once instead of once per transition.
    cut: bool = False
    reason: str = None

    @property
    def extra(self):
        # Attribute order matters for a byte-identical scene.rml: reset, then random.
        return (' reset="true"' if self.reset else "") + (' random="true"' if self.random else "")

    def rml(self):
        return anim_state(self.anim, self.node, self.index, self.extra,
                          "\n".join(t.rml() for t in self.transitions))


@dataclass
class Layer:
    """One StateMachineLayer: an entry state, optional AnyState transitions, and its states."""
    name: str
    id: str
    entry: str
    any_transitions: list = field(default_factory=list)
    states: list = field(default_factory=list)
    # {animation id: loopValue} when the caller knows it; enables the exit-time-on-a-loop check.
    anim_loops: dict = None

    def rml(self, seams=None):
        """The layer's XML, after validate() has checked it.

        `seams` is an animation index from rig/seams.py; without one (and without a
        module-level index) the blend policy is not checked, only the structural rules."""
        self.validate(seams)
        return layer_frame(self.name, self.id, self.entry,
                           "\n".join(t.rml() for t in self.any_transitions),
                           "\n".join(s.rml() for s in self.states))

    # --- validation ------------------------------------------------------------------------
    def validate(self, seams=None):
        """Raise ValueError on a layer Rive would accept and then animate wrongly."""
        known = self._check_state_nodes()
        for t in self.any_transitions:
            self._check_any_transition(t, known)
        for s in self.states:
            self._check_state_exits(s, known)
        index = _ANIMATION_INDEX if seams is None else seams
        self._check_blends(index)

    def _check_state_nodes(self):
        """Every state node is used once; returns the node ids a transition may target."""
        nodes = []
        for s in self.states:
            if s.node in nodes:
                raise ValueError(f'layer "{self.name}": duplicate state node id {s.node} '
                                 f'(second use by animation {s.anim})')
            nodes.append(s.node)
        return set(nodes) | {self.entry}

    def _check_any_transition(self, t, known):
        self._check_target("AnyState", t, known)
        if t.weight is not None:
            raise ValueError(f'layer "{self.name}": AnyState -> {t.to} carries '
                             f'randomWeight={t.weight}; only a random="true" state randomises')

    def _check_state_exits(self, s, known):
        for t in s.transitions:
            self._check_target(s.node, t, known)
            self._check_weight(s, t)
            self._check_exit_time(s, t)
        weighted_exits = sum(1 for t in s.transitions if t.weight is not None)
        if s.random and weighted_exits < 2:
            raise ValueError(f'layer "{self.name}": state {s.node} is random="true" but has '
                             f'{weighted_exits} weighted transition(s); a random pick needs '
                             f'at least two')

    def _check_weight(self, s, t):
        if t.weight is not None and not s.random:
            raise ValueError(f'layer "{self.name}": state {s.node} -> {t.to} carries '
                             f'randomWeight={t.weight} but {s.node} is not random="true"')

    def _check_exit_time(self, s, t):
        loops = (self.anim_loops or {}).get(s.anim) == "loop"
        if loops and isinstance(t, Exit):
            raise ValueError(f'layer "{self.name}": state {s.node} -> {t.to} is an '
                             f'exit-time transition, but animation {s.anim} loops and '
                             f'never reaches its exit time')

    # --- the blend policy --------------------------------------------------------------------
    def _signed(self):
        """(source state or None, transition, cut, reason) for every way out of this layer, with
        each signature recorded in CUT_MARKS. Runs whether or not an index is installed, so the
        ledger can read the marks from a plain build."""
        pairs = [(None, t) for t in self.any_transitions]
        pairs += [(s, t) for s in self.states for t in s.transitions]
        return [self._sign(src, t) for src, t in pairs]

    def _sign(self, src, t):
        """(src, t, cut, reason) for one way out, recording its cut / hold signature."""
        source = getattr(src, "node", None)
        cut = bool(t.cut or getattr(src, "cut", False))
        hold = bool(getattr(t, "hold", False))
        reason = t.reason or getattr(src, "reason", None)
        if cut or hold:
            self._record(source, t, reason, (CUT_MARKS if cut else None, HOLD_MARKS if hold else None))
        return src, t, cut, reason

    def _record(self, source, t, reason, tables):
        """File a signed way out in each marks table given; a signature needs a reason."""
        if not reason:
            raise ValueError(f'layer "{self.name}": {source or "AnyState"} -> '
                             f'{t.to} is marked cut/hold with no reason; say why')
        for table in (t for t in tables if t is not None):
            table[(self.name, source, t.to)] = reason

    def _check_blends(self, index):
        """Refuse a 0 ms transition that tears a property both animations key, unless it is
        signed `cut=True` with a reason.

        Only the transition's own two animations are weighed here. A hand-back - a one-shot
        that ends holding a property its target does not key, so the layers below take it back
        at the cut - is a layering question rather than a transition one, and belongs to the
        ledger (`python -m rig.seams`), not to this gate."""
        signed = self._signed()       # always: building the layer records its signatures
        if not index:
            return
        anim_of = {s.node: s.anim for s in self.states}
        for src, t, cut, _reason in signed:
            if t.ms or cut:
                continue
            for s in self._sources(src, t):
                self._check_seam(index, s, t, anim_of.get(t.to))

    def _sources(self, src, t):
        """The states a transition can leave from: its own state, or every other state for AnyState."""
        return [s for s in ([src] if src is not None else self.states) if s.node != t.to]

    def _check_seam(self, index, s, t, target_anim):
        c = next((c for c in crossings(index, s.anim, target_anim) if not c.hand_back), None)
        if c is None:
            return
        raise ValueError(
            f'layer "{self.name}": {s.node} -> {t.to} is a 0 ms cut, but '
            f'{_name(index, s.anim)} leaves object {c.obj} '
            f'{_prop(c.key)} at {c.from_value} and {_name(index, target_anim)} '
            f'starts it at {c.to_value} (delta {c.delta:g}, '
            f'{c.normalised:.1f}x the {c.eps:g} tolerance). Blend it, or mark the '
            f'transition cut=True with a reason.')

    def _check_target(self, frm, t, known):
        if t.to not in known:
            raise ValueError(f'layer "{self.name}": {frm} -> {t.to} targets a state that is not '
                             f'in this layer')
