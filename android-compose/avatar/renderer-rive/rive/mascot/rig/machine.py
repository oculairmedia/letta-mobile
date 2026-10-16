"""The root state machine `Avatar`: one function per layer, assembled by root_machine().

Owns which animation plays when - the twelve layers, their states, their transitions and the
file's own hover/drag listeners. The animations themselves come from rig/motion.py and rig/face.py, the
ids from rig/ids.py; only gen_scene.py reads this module. A new sustained state is a vocabulary
entry in rig/ids.py, a loop in rig/motion.py, and a state in _expression_layer() here.

Rive rules that bite here:
  - AnyState transitions are evaluated BEFORE a state's own, so an AnyState fan-out swallows every
    designed entry, and one whose condition still holds re-enters the current state and self-blends
    it (that is how every glyph came to fade in). Expression and Shape use explicit matrices.
  - layers mix: a beat's keys play on top of whatever the layers below put on the same nodes, so
    a beat blends in and out (160/320 ms) instead of cutting - a cut would snap the lower value.
    Transitions are also evaluated in declared order, which is why each park check is written first.
"""
from textwrap import indent

from rml import (BIND_BOOL, EASE_OUT, SOFT_OUT, SPRING, VM_BLINK, VM_DRAGGED, VM_ERROR, VM_HOVER,
                 VM_SHAPE, VM_STATE, VM_SUCCESS, bind, when_bool, when_enum, when_number, when_trigger)
from rig.constants import BLEND, CALM_HANDOFF, DEFAULT_SHAPE, DESIGNED_PAIRS, IDLE_WAITS, WANDER_WAITS
from rig.ids import (
    BLINK_ANIM, BLINK_NODE, BLINK_REST_ANIM, BLINK_REST_NODE, BREATH_ANIM, BREATH_CALM_ANIM,
    BREATH_CALM_NODE, BREATH_NODE, DRAG_ANIM, DRAG_NODE, DRAG_REST_ANIM, DRAG_REST_NODE, ERROR_ANIM,
    ERROR_CALM_ANIM, ERROR_CALM_NODE, ERROR_NODE, FLASH_REST_ANIM, FLASH_REST_NODE, HITBOX,
    HOVER_ANIM, HOVER_HELD_ANIM, HOVER_HELD_NODE, HOVER_NODE, HOVER_REST_ANIM, HOVER_REST_NODE,
    IDLE_A_NODE, IDLE_BEATS, IDLE_GLANCE_ANIM, IDLE_GLANCE_NODE, IDLE_HOLD_NODE, IDLE_SLEEP_NODE,
    IDLE_WAIT_A, PET_ANIM, PET_FACE_ANIM, PET_FACE_NODE, PET_FACE_REST_NODE, PET_NODE, PET_REST_ANIM,
    PET_REST_NODE, REACT_CALM_ANIM, REACT_CALM_NODE, REACT_REST_ANIM, REACT_REST_NODE, REACTIONS,
    RESTING, SHAPES, SM, SUCCESS_ANIM, SUCCESS_CALM_ANIM, SUCCESS_CALM_NODE, SUCCESS_NODE, SUSTAINED,
    VM_NEW, WANDER_A_NODE, WANDER_GLANCE, WANDER_GLANCE_NODE, WANDER_HOLD_NODE, WANDER_PEEK,
    WANDER_PEEK_NODE, WANDER_SLEEP_SHIFT, WANDER_SLEEP_SHIFT_NODE, WANDER_SLEEP_WAIT,
    WANDER_SLEEP_WAIT_NODE, WANDER_SPIN, WANDER_SPIN_NODE, WANDER_WAIT_A, calm_state_anim,
    calm_state_node, enter_anim, enter_node, react_anim, react_node, root_state_anim,
    root_state_node, shape_anim, shape_enum_ids, shape_node, state_enum_ids,
)
from rig.layers import Exit, Layer, OnBool, OnEnum, OnTrigger, State, When
from rig.motion import enter_duration

VM_REDUCE, VM_PETTING = VM_NEW["reduceMotion"], VM_NEW["petting"]

# Layer ids for te3n1.5's three layers (the original ten are 3:1..3:10, written inline).
PET_LAYER, PET_FACE_LAYER, REACT_LAYER = "3:11", "3:12", "3:13"


def _shape_layer():
    return Layer(
        "Shape", "3:9", shape_node[DEFAULT_SHAPE],
        any_transitions=[OnEnum(shape_node[s], shape_enum_ids[s], 240, SOFT_OUT, VM_SHAPE) for s in SHAPES],
        states=[State(shape_anim[s], shape_node[s], i) for i, s in enumerate(SHAPES)]).rml()


def _expression_layer():
    # No AnyState fan-out: Rive evaluates AnyState before a state's own transitions, which would
    # swallow the entries. Every sustained state cuts (0 ms) into the entry for the requested
    # state; the entry hands off to the sustained loop at its end. Designed entries land on the
    # target's rest and cut; generic ones blend the hand-off so held face/body/tint ease in.
    ENTRY_CUT = "hidden by the blink shutter: the entry fires the blink at frame 0"
    LANDS = "the designed entry lands on the target's rest pose"
    #
    # reduceMotion (te3n1.5): every sustained loop has a calm twin - the same pose held at the
    # loop's frame 0 - and `reduceMotion` blends between the two. An entry hands off to the twin
    # instead of the loop while it is on, over at most CALM_HANDOFF ms.
    entries = lambda st: [OnEnum(enter_node[(st, to)], state_enum_ids[to], 0, None, cut=True, reason=ENTRY_CUT)
                          for to in SUSTAINED if to != st]
    states = []
    for i, st in enumerate(SUSTAINED):
        own = entries(st) + [OnBool(calm_state_node[st], VM_REDUCE, "true", BLEND["calm"], SOFT_OUT)]
        states.append(State(root_state_anim[st], root_state_node[st], i, transitions=own))
    for j, ((frm, to), nid) in enumerate(enter_node.items()):
        # An entry can be interrupted by any other request (through that state's own entry).
        designed = (frm, to) in DESIGNED_PAIRS
        hand_off = (0, None) if designed else (min(120, enter_duration(frm, to).ms), EASE_OUT)
        calm_off = (0, None) if designed else (min(CALM_HANDOFF, hand_off[0]), EASE_OUT)
        why = LANDS if designed else None
        own = [When(calm_state_node[to], (when_bool(VM_REDUCE, "true"),), *calm_off, exit=True, cut=designed, reason=why),
               Exit(root_state_node[to], *hand_off, cut=designed, reason=why)]
        own += entries(to)
        states.append(State(enter_anim[(frm, to)], nid, len(SUSTAINED) + j, reset=True, transitions=own))
    base = len(SUSTAINED) + len(enter_node)
    for i, st in enumerate(SUSTAINED):
        own = entries(st) + [OnBool(root_state_node[st], VM_REDUCE, "false", BLEND["calm"], SOFT_OUT)]
        states.append(State(calm_state_anim[st], calm_state_node[st], base + i, transitions=own))

    return Layer("Expression", "3:1", root_state_node["idle"], states=states).rml()


def _static_layers():
    """Breath, Blink, Hover, Flash, Drag: one animation each, driven by the view model."""
    breath = Layer("Breath", "3:2", BREATH_NODE, states=[
        State(BREATH_ANIM, BREATH_NODE, 0,
              transitions=[OnBool(BREATH_CALM_NODE, VM_REDUCE, "true", BLEND["calm"], SOFT_OUT)]),
        State(BREATH_CALM_ANIM, BREATH_CALM_NODE, 1,
              transitions=[OnBool(BREATH_NODE, VM_REDUCE, "false", BLEND["calm"], SOFT_OUT)])]).rml()
    blink = Layer(
        "Blink", "3:3", BLINK_REST_NODE,
        any_transitions=[OnTrigger(BLINK_NODE, VM_BLINK)],
        states=[State(BLINK_REST_ANIM, BLINK_REST_NODE, 0),
                State(BLINK_ANIM, BLINK_NODE, 1, reset=True, transitions=[Exit(BLINK_REST_NODE)])]).rml()
    hover = Layer(
        "Hover", "3:4", HOVER_REST_NODE,
        states=[
            State(HOVER_REST_ANIM, HOVER_REST_NODE, 0,
                  transitions=[OnBool(HOVER_NODE, VM_HOVER, "true", 0, None)]),
            # Perk hands off to the attentive hold at its end; leaving hover blends out (220/260 ms)
            # because these keys sit on top of Breath and the host's turn.
            State(HOVER_ANIM, HOVER_NODE, 1, reset=True,
                  transitions=[Exit(HOVER_HELD_NODE),
                               OnBool(HOVER_REST_NODE, VM_HOVER, "false", 220, SOFT_OUT)]),
            State(HOVER_HELD_ANIM, HOVER_HELD_NODE, 2,
                  transitions=[OnBool(HOVER_REST_NODE, VM_HOVER, "false", BLEND["hover_out"], SOFT_OUT)])]).rml()
    # A flash is fired, not requested: it starts from wherever the character is, so the trigger
    # cuts. Interrupting one flash with the other cuts across the error drop (24 px) on purpose -
    # the new flash re-poses the body from its own frame 0.
    FLASH_CUT = "trigger flash, self-returning: it re-poses from its own frame 0"
    ERROR_HOLD = ("error flash holds its drop; `state` settles to error behind it, so the "
                  "Expression layer is already holding that pose when Flash lets go")
    # Under reduceMotion the two flashes keep their meaning and lose their motion: the glyph under
    # a blink, no hop, no spin, no shake (te3n1.5). The calm ones cut out at their end: a blended
    # `expr` would sweep through every glyph index between the flash's and the state's.
    calm_off = when_bool(VM_REDUCE, "false")
    calm_on = when_bool(VM_REDUCE, "true")
    CALM_END = "calm flash: only `expr` is keyed, and the state's own glyph is back under the blink"
    # A calm flash fired over a running ErrorFlash (reduceMotion switched on mid-shake) lets go of
    # the error drop; the hand-back is the ERROR_HOLD one, the error pose is what lies underneath.
    CALM_OVER_ERROR = "calm flash over ErrorFlash: " + ERROR_HOLD
    flash = Layer(
        "Flash", "3:6", FLASH_REST_NODE,
        any_transitions=[When(SUCCESS_NODE, (when_trigger(VM_SUCCESS), calm_off), cut=True, reason=FLASH_CUT),
                         When(ERROR_NODE, (when_trigger(VM_ERROR), calm_off), cut=True, reason=FLASH_CUT),
                         When(SUCCESS_CALM_NODE, (when_trigger(VM_SUCCESS), calm_on), cut=True, hold=True,
                              reason=CALM_OVER_ERROR),
                         When(ERROR_CALM_NODE, (when_trigger(VM_ERROR), calm_on), cut=True, hold=True,
                              reason=CALM_OVER_ERROR)],
        states=[State(FLASH_REST_ANIM, FLASH_REST_NODE, 0),
                State(SUCCESS_ANIM, SUCCESS_NODE, 1, reset=True,
                      transitions=[Exit(FLASH_REST_NODE, 120, SOFT_OUT)]),
                State(ERROR_ANIM, ERROR_NODE, 2, reset=True,
                      transitions=[Exit(FLASH_REST_NODE, 0, cut=True, hold=True,
                                        reason=ERROR_HOLD)]),
                State(SUCCESS_CALM_ANIM, SUCCESS_CALM_NODE, 3, reset=True,
                      transitions=[Exit(FLASH_REST_NODE, 0, cut=True, reason=CALM_END)]),
                State(ERROR_CALM_ANIM, ERROR_CALM_NODE, 4, reset=True,
                      transitions=[Exit(FLASH_REST_NODE, 0, cut=True, reason=CALM_END)])]).rml()
    drag = Layer(
        "Drag", "3:7", DRAG_REST_NODE,
        any_transitions=[OnBool(DRAG_NODE, VM_DRAGGED, "true", 80, SPRING),
                         OnBool(DRAG_REST_NODE, VM_DRAGGED, "false", 350, SOFT_OUT)],
        states=[State(DRAG_REST_ANIM, DRAG_REST_NODE, 0), State(DRAG_ANIM, DRAG_NODE, 1)]).rml()
    return breath, blink, hover, flash, drag


def _park():
    # Asleep, the character holds still: the waits divert to a parked state until it is awake
    # again. Transitions are ordered, so the park check comes first. Asleep is either RESTING
    # state (sleeping, or te3n1.5's dreaming), so parking is one transition per state and waking
    # needs both ruled out at once.
    PARK = "park: sleeping holds still, so the wait drops its beat where it stands"

    def to_park(node, dur=0):
        return [OnEnum(node, state_enum_ids[s], dur, None, cut=True, reason=PARK) for s in RESTING]

    def from_park(node):
        return When(node, tuple(when_enum(VM_STATE, state_enum_ids[s], "notEqual") for s in RESTING),
                    cut=True, reason=PARK)
    return to_park, from_park


def _hold(node, beat_ms, *also):
    """reduceMotion (and, for Wander, `working`) holds a layer still: the way into its hold state,
    written first so it beats the park and the pick. From a wait it cuts (a wait keys nothing);
    from a beat it blends out over `beat_ms`, like a beat's own way home."""
    calm = [OnBool(node, VM_REDUCE, "true", beat_ms, SOFT_OUT if beat_ms else None)]
    return calm + [OnEnum(node, state_enum_ids[s], beat_ms, SOFT_OUT if beat_ms else None) for s in also]


def _idle_layer(to_park, from_park):
    # Each wait ends by picking one beat at random (weights: the glance is still the most common,
    # the bounce the rarest); every beat returns to a random wait.
    # A beat blends in over 160 ms and out over 320 ms: its keys sit on top of Breath / the
    # host's turn on the same nodes, so a hard cut would snap those values at either end.
    beat_nodes = [(IDLE_GLANCE_NODE, 22), (IDLE_BEATS["tilt"].node, 14), (IDLE_BEATS["lookaround"].node, 13),
                  (IDLE_BEATS["shift"].node, 12), (IDLE_BEATS["stretch"].node, 10), (IDLE_BEATS["sigh"].node, 10),
                  (IDLE_BEATS["shiver"].node, 8), (IDLE_BEATS["wobble"].node, 6), (IDLE_BEATS["bounce"].node, 5)]
    pick_beat = [Exit(n, BLEND["beat_in"], SOFT_OUT, w) for n, w in beat_nodes]
    back_to_wait = [Exit(w.node, BLEND["beat_out"], SOFT_OUT, 25) for w in IDLE_WAITS]

    wait_out = _hold(IDLE_HOLD_NODE, 0) + to_park(IDLE_SLEEP_NODE)
    beat_out = _hold(IDLE_HOLD_NODE, BLEND["beat_out"]) + to_park(IDLE_SLEEP_NODE)
    states = [State(w.anim, w.node, k, random=True, transitions=wait_out + pick_beat)
              for k, w in enumerate(IDLE_WAITS)]
    states.append(State(IDLE_GLANCE_ANIM, IDLE_GLANCE_NODE, 4, reset=True, random=True,
                        transitions=beat_out + back_to_wait))
    states.append(State(IDLE_WAIT_A, IDLE_SLEEP_NODE, 14, transitions=[from_park(IDLE_A_NODE)]))
    states += [State(b.anim, b.node, 5 + i, reset=True, random=True,
                     transitions=beat_out + back_to_wait)
               for i, b in enumerate(IDLE_BEATS.values())]
    # reduceMotion: no idle beats at all until it is off again (te3n1.5).
    states.append(State(IDLE_WAIT_A, IDLE_HOLD_NODE, 15,
                        transitions=[OnBool(IDLE_A_NODE, VM_REDUCE, "false", 0, None)]))

    return Layer("IdleVariety", "3:8", IDLE_A_NODE, states=states).rml()


def _wander_layer(to_park, from_park):
    # Wander beats key the joystick the host also turns: blend in (200 ms) and out (320 ms).
    pick_wander = [Exit(WANDER_GLANCE_NODE, BLEND["wander_in"], SOFT_OUT, 55),
                   Exit(WANDER_PEEK_NODE, BLEND["wander_in"], SOFT_OUT, 35),
                   Exit(WANDER_SPIN_NODE, BLEND["wander_in"], SOFT_OUT, 10)]
    wander_home = [Exit(w.node, BLEND["wander_out"], SOFT_OUT, 25) for w in WANDER_WAITS]

    # Wander also holds while `working`: the head stays square-on to the work (te3n1.5).
    wait_out = _hold(WANDER_HOLD_NODE, 0, "working") + to_park(WANDER_SLEEP_WAIT_NODE)
    beat_out = _hold(WANDER_HOLD_NODE, BLEND["wander_out"], "working") + to_park(WANDER_SLEEP_WAIT_NODE, 400)
    states = [State(w.anim, w.node, k, random=True, transitions=wait_out + pick_wander)
              for k, w in enumerate(WANDER_WAITS)]
    states += [State(aid, n, i, reset=True, random=True, transitions=beat_out + wander_home)
               for aid, n, i in ((WANDER_GLANCE, WANDER_GLANCE_NODE, 4),
                                 (WANDER_PEEK, WANDER_PEEK_NODE, 5),
                                 (WANDER_SPIN, WANDER_SPIN_NODE, 6))]
    # Asleep with reduceMotion on, even the slow sleep shift stops.
    states.append(State(WANDER_SLEEP_WAIT, WANDER_SLEEP_WAIT_NODE, 7,
                        transitions=_hold(WANDER_HOLD_NODE, 0) + [from_park(WANDER_A_NODE), Exit(WANDER_SLEEP_SHIFT_NODE)]))
    states.append(State(WANDER_SLEEP_SHIFT, WANDER_SLEEP_SHIFT_NODE, 6, reset=True,
                        transitions=_hold(WANDER_HOLD_NODE, 400) + [from_park(WANDER_A_NODE),
                                     Exit(WANDER_SLEEP_WAIT_NODE, 200, SOFT_OUT)]))
    # Out of the hold only when neither reason holds; asleep, WanderA parks again at once.
    states.append(State(WANDER_WAIT_A, WANDER_HOLD_NODE, 8, transitions=[
        When(WANDER_A_NODE, (when_bool(VM_REDUCE, "false"),
                             when_enum(VM_STATE, state_enum_ids["working"], "notEqual")))]))

    return Layer("Wander", "3:10", WANDER_A_NODE, states=states).rml()


def _listeners():
    def bool_listener(name, kind, prop, value):
        b = bind(prop, BIND_BOOL).replace("/>", ' direction="true"/>')
        return (f'<StateMachineListenerSingle targetId="{HITBOX}" listenerTypeValue="{kind}" name="{name}">\n'
                f'    <ListenerViewModelChange>\n        <BindablePropertyBoolean propertyValue="{value}">\n'
                f'            {b}\n        </BindablePropertyBoolean>\n    </ListenerViewModelChange>\n</StateMachineListenerSingle>')
    listeners = "\n".join([
        bool_listener("HoverIn", "enter", VM_HOVER, "true"), bool_listener("HoverOut", "exit", VM_HOVER, "false"),
        bool_listener("DragStart", "dragStart", VM_DRAGGED, "true"), bool_listener("DragEnd", "dragEnd", VM_DRAGGED, "false"),
    ])
    return listeners


def _pet_layers():
    """Pet (the sway, blended) and PetFace (the content glyph, cut) - te3n1.5.

    Two layers on purpose. The sway sits on top of Breath and the host's turn, so it has to blend
    in and out; the glyph is the plate's `expr`, a nested number that a blend would sweep through
    every index between the two glyphs (a frame of listening, a frame of dragged, ...). So the glyph
    gets its own layer and cuts, hidden by the blink PettingFace fires on entry."""
    petting = when_bool(VM_PETTING, "true")
    pet = Layer("Pet", PET_LAYER, PET_REST_NODE, states=[
        State(PET_REST_ANIM, PET_REST_NODE, 0,
              transitions=[When(PET_NODE, (petting, when_bool(VM_REDUCE, "false")), BLEND["pet_in"], SOFT_OUT)]),
        State(PET_ANIM, PET_NODE, 1,
              transitions=[OnBool(PET_REST_NODE, VM_PETTING, "false", BLEND["pet_out"], SOFT_OUT),
                           OnBool(PET_REST_NODE, VM_REDUCE, "true", BLEND["pet_out"], SOFT_OUT)])]).rml()
    FACE_CUT = "the content glyph swaps under the blink PettingFace fires; `expr` cannot blend"
    face = Layer("PetFace", PET_FACE_LAYER, PET_FACE_REST_NODE, states=[
        State(PET_REST_ANIM, PET_FACE_REST_NODE, 0,
              transitions=[OnBool(PET_FACE_NODE, VM_PETTING, "true", 0, None, cut=True, reason=FACE_CUT)]),
        State(PET_FACE_ANIM, PET_FACE_NODE, 1,
              transitions=[OnBool(PET_FACE_REST_NODE, VM_PETTING, "false", 0, None, cut=True, reason=FACE_CUT)])]).rml()
    return pet, face


# (trigger, reaction) for the reactions one trigger plays outright.
_SIMPLE_REACTIONS = [("poke", "poke"), ("dizzy", "dizzy"), ("sent", "sent"), ("toolStart", "toolStart"),
                     ("toolDone", "toolDone"), ("wake", "wake"), ("yawn", "yawn")]


# `land` by impact band and `trick` by id band: (reaction, number property, lower, upper or None).
_LAND_TIERS = [("landSoft", None, 0.34), ("landMid", 0.34, 0.67), ("landHard", 0.67, None)]


def _band(prop, lo, hi):
    """Conditions for lo <= prop < hi (either end open)."""
    out = []
    if lo is not None:
        out.append(when_number(prop, "greaterThanOrEqual", lo))
    if hi is not None:
        out.append(when_number(prop, "lessThan", hi))
    return tuple(out)


def _react_layer():
    """React (te3n1.5): one one-shot per trigger, fired from wherever the character is.

    Like a flash, a reaction is fired, not requested: every trigger cuts into its one-shot from any
    state (a new poke restarts a poke), and each one-shot starts and ends at rest so the way home is
    a 0 ms cut too. With reduceMotion on, every trigger plays ReactCalm instead: one blink."""
    REACT_CUT = "trigger reaction, self-returning: it re-poses from its own frame 0"
    HOME = "the reaction ends at rest; only `expr` is left, and the state's glyph is back under the blink"
    full, calm = when_bool(VM_REDUCE, "false"), when_bool(VM_REDUCE, "true")
    fired = []
    for trig, r in _SIMPLE_REACTIONS:
        fired.append(When(react_node[r], (when_trigger(VM_NEW[trig]), full), cut=True, reason=REACT_CUT))
    for r, lo, hi in _LAND_TIERS:
        fired.append(When(react_node[r], (when_trigger(VM_NEW["land"]), full) + _band(VM_NEW["impact"], lo, hi),
                          cut=True, reason=REACT_CUT))
    for k in range(4):
        lo, hi = (None if k == 0 else k - 0.5), (None if k == 3 else k + 0.5)
        fired.append(When(react_node[f"trick{k}"], (when_trigger(VM_NEW["trick"]), full) + _band(VM_NEW["trickId"], lo, hi),
                          cut=True, reason=REACT_CUT))
    triggers = [t for t, _ in _SIMPLE_REACTIONS] + ["land", "trick"]
    fired += [When(REACT_CALM_NODE, (when_trigger(VM_NEW[t]), calm), cut=True, reason=REACT_CUT) for t in triggers]
    states = [State(REACT_REST_ANIM, REACT_REST_NODE, 0)]
    states += [State(react_anim[r], react_node[r], 1 + i, reset=True,
                     transitions=[Exit(REACT_REST_NODE, 0, cut=True, reason=HOME)])
               for i, r in enumerate(REACTIONS)]
    states.append(State(REACT_CALM_ANIM, REACT_CALM_NODE, 1 + len(REACTIONS), reset=True,
                        transitions=[Exit(REACT_REST_NODE, 0, cut=True, reason=HOME)]))
    return Layer("React", REACT_LAYER, REACT_REST_NODE, any_transitions=fired, states=states).rml()


def root_machine():
    """The whole `Avatar` state machine: twelve layers in order, plus the file's own listeners.

    te3n1.5's three layers (Pet, PetFace, React) go last: they key only nodes nobody else keys
    (Squash, Peek) plus `expr`, so they sit on top of everything without fighting it."""
    to_park, from_park = _park()
    breath, blink, hover, flash, drag = _static_layers()
    pet, pet_face = _pet_layers()
    layers = [_shape_layer(), _expression_layer(), breath, blink, hover, flash, drag,
              _idle_layer(to_park, from_park), _wander_layer(to_park, from_park),
              pet, pet_face, _react_layer(), _listeners()]
    return f'<StateMachine name="Avatar" id="{SM}">\n' + "\n".join(indent(layer, "    ") for layer in layers) + '\n</StateMachine>'
