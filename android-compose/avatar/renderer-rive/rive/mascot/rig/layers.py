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
"""
from dataclasses import dataclass, field

from rml import (EASE_OUT, VM_STATE, anim_state, bool_transition, enum_transition,
                 exit_transition, input_transition, layer_frame, trigger_transition, weighted)


# --- transitions --------------------------------------------------------------------------------
# Each wraps one rml.py builder and, on top of the XML, declares the target the layer validates.
@dataclass(frozen=True)
class Exit:
    """Exit-time transition: fires when the animation reaches its end. Meaningless on a looping
    animation, which never reaches one. A weight is only legal leaving a random="true" state."""
    to: str
    ms: int = 0
    bezier: str = None
    weight: int = None

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
    weight = None

    def rml(self):
        return bool_transition(self.to, self.prop, self.value, self.ms, self.bezier)


@dataclass(frozen=True)
class OnTrigger:
    """Fires once when a view-model trigger is pulled."""
    to: str
    prop: str
    weight = None

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
    weight = None

    def rml(self):
        return input_transition(self.to, self.input_id, self.value, self.ms)


@dataclass(frozen=True)
class Raw:
    """Escape hatch for a transition whose XML is built elsewhere (a hand-rolled condition pair).
    It still declares its target, so the layer's target and duplicate checks cover it."""
    to: str
    xml: str
    weight = None

    def rml(self):
        return self.xml


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

    def rml(self):
        """The layer's XML, after validate() has checked it."""
        self.validate()
        return layer_frame(self.name, self.id, self.entry,
                           "\n".join(t.rml() for t in self.any_transitions),
                           "\n".join(s.rml() for s in self.states))

    # --- validation ------------------------------------------------------------------------
    def validate(self):
        """Raise ValueError on a layer Rive would accept and then animate wrongly."""
        nodes = []
        for s in self.states:
            if s.node in nodes:
                raise ValueError(f'layer "{self.name}": duplicate state node id {s.node} '
                                 f'(second use by animation {s.anim})')
            nodes.append(s.node)
        known = set(nodes) | {self.entry}

        for t in self.any_transitions:
            self._check_target("AnyState", t, known)
            if t.weight is not None:
                raise ValueError(f'layer "{self.name}": AnyState -> {t.to} carries '
                                 f'randomWeight={t.weight}; only a random="true" state randomises')

        for s in self.states:
            weighted_exits = 0
            for t in s.transitions:
                self._check_target(s.node, t, known)
                if t.weight is not None:
                    if not s.random:
                        raise ValueError(f'layer "{self.name}": state {s.node} -> {t.to} carries '
                                         f'randomWeight={t.weight} but {s.node} is not random="true"')
                    weighted_exits += 1
                if (isinstance(t, Exit) and self.anim_loops
                        and self.anim_loops.get(s.anim) == "loop"):
                    raise ValueError(f'layer "{self.name}": state {s.node} -> {t.to} is an '
                                     f'exit-time transition, but animation {s.anim} loops and '
                                     f'never reaches its exit time')
            if s.random and weighted_exits < 2:
                raise ValueError(f'layer "{self.name}": state {s.node} is random="true" but has '
                                 f'{weighted_exits} weighted transition(s); a random pick needs '
                                 f'at least two')

    def _check_target(self, frm, t, known):
        if t.to not in known:
            raise ValueError(f'layer "{self.name}": {frm} -> {t.to} targets a state that is not '
                             f'in this layer')
