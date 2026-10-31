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
from rig.constants import (BLEND, CALM_HANDOFF, DEFAULT_SHAPE, DESIGNED_PAIRS, ENTRY_FLIP_MS, IDLE_WAITS,
                           MIN_TRANSITION_MS, WANDER_WAITS)
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
    DRAG_FACE_ANIM, DRAG_FACE_NODE, DRAG_FACE_REST_NODE, ERROR_FACE_ANIM, ERROR_FACE_NODE,
    FLASH_FACE_REST_NODE, REACT_EXPR, REACT_FACE_REST_NODE, SUCCESS_FACE_ANIM, SUCCESS_FACE_NODE,
    react_face_anim, react_face_node,
)
from rig.layers import Exit, Layer, OnBool, OnEnum, OnTrigger, State, When
from rig.motion import enter_duration

VM_REDUCE, VM_PETTING = VM_NEW["reduceMotion"], VM_NEW["petting"]

# Layer ids for te3n1.5's three layers (the original ten are 3:1..3:10, written inline).
PET_LAYER, PET_FACE_LAYER, REACT_LAYER = "3:11", "3:12", "3:13"


# The face halves of Flash, Drag and React (the minimum transition time): the glyph that cuts.
FLASH_FACE_LAYER, DRAG_FACE_LAYER, REACT_FACE_LAYER = "3:14", "3:15", "3:16"


# A blend at the floor (rig/constants.py MIN_TRANSITION_MS), for the state changes that used to cut.
FLOOR = (MIN_TRANSITION_MS, EASE_OUT)


# A glyph swap: the only thing still allowed to change in one frame (rig/seams.py SWEEP_KEYS).
GLYPH_CUT = "the glyph index `expr` cannot blend (it would sweep every glyph between); nothing else is keyed"


def _shape_layer():
    return Layer(
        "Shape", "3:9", shape_node[DEFAULT_SHAPE],
        any_transitions=[OnEnum(shape_node[s], shape_enum_ids[s], 240, SOFT_OUT, VM_SHAPE) for s in SHAPES],
        states=[State(shape_anim[s], shape_node[s], i) for i, s in enumerate(SHAPES)]).rml()


def _expression_layer():
    # No AnyState fan-out: Rive evaluates AnyState before a state's own transitions, which would
    # swallow the entries. Every sustained state blends (MIN_TRANSITION_MS) into the entry for the
    # requested state; the entry hands off to the sustained loop at its end.
    #
    # Nothing here cuts any more. A sustained loop is left at whatever frame the request arrives -
    # mid-bob, mid-lean, mid-turn - and the entry starts from the state's REST pose, so a 0 ms cut
    # snapped that pose in one frame (the blink only ever hid the glyph swap, never the body). The
    # glyph swap itself stays instant, inside the entry: `expr` holds the old index until
    # BLINK_FLIP and flips under the shut eye, and the blend into the entry is over by then
    # (MIN_TRANSITION_MS <= ENTRY_FLIP_MS), so the blend never mixes two indices.
    #
    # An entry interrupted by the next request blends too, but only once its own glyph has
    # flipped (`after_ms=ENTRY_FLIP_MS`): until then it still shows the old glyph while the next
    # entry starts on the new one. A request inside those first 100 ms waits for the flip.
    #
    # reduceMotion (te3n1.5): every sustained loop has a calm twin - the same pose held at the
    # loop's frame 0 - and `reduceMotion` blends between the two. An entry hands off to the twin
    # instead of the loop while it is on, over CALM_HANDOFF ms.
    def entries(st, gated=False):
        if gated:
            # Linear, not eased: there are len(SUSTAINED)**2 of these, every eased transition
            # carries its own interpolator object in the .riv, and over 100 ms the curve is moot.
            return [When(enter_node[(st, to)], (when_enum(VM_STATE, state_enum_ids[to]),), MIN_TRANSITION_MS,
                         after_ms=ENTRY_FLIP_MS) for to in SUSTAINED if to != st]
        return [OnEnum(enter_node[(st, to)], state_enum_ids[to], *FLOOR) for to in SUSTAINED if to != st]
    states = []
    for i, st in enumerate(SUSTAINED):
        own = entries(st) + [OnBool(calm_state_node[st], VM_REDUCE, "true", BLEND["calm"], SOFT_OUT)]
        states.append(State(root_state_anim[st], root_state_node[st], i, transitions=own))
    for j, ((frm, to), nid) in enumerate(enter_node.items()):
        # A designed entry lands on the target's rest and hands off at the floor; a generic one
        # blends its hand-off over up to 120 ms so held face/body/tint ease in.
        designed = (frm, to) in DESIGNED_PAIRS
        hand_off = FLOOR if designed else (max(MIN_TRANSITION_MS, min(120, enter_duration(frm, to).ms)), EASE_OUT)
        calm_off = (max(MIN_TRANSITION_MS, min(CALM_HANDOFF, hand_off[0])), EASE_OUT)
        own = [When(calm_state_node[to], (when_bool(VM_REDUCE, "true"),), *calm_off, exit=True),
               Exit(root_state_node[to], *hand_off)]
        own += entries(to, gated=True)
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
            # The perk starts at rest, but the layers below do not hold rest (breath, the state's
            # pose), so it blends in at the floor rather than taking those values in one frame.
            State(HOVER_REST_ANIM, HOVER_REST_NODE, 0,
                  transitions=[OnBool(HOVER_NODE, VM_HOVER, "true", MIN_TRANSITION_MS, SOFT_OUT)]),
            # Perk hands off to the attentive hold at its end; leaving hover blends out (220/260 ms)
            # because these keys sit on top of Breath and the host's turn.
            State(HOVER_ANIM, HOVER_NODE, 1, reset=True,
                  transitions=[Exit(HOVER_HELD_NODE),
                               OnBool(HOVER_REST_NODE, VM_HOVER, "false", 220, SOFT_OUT)]),
            State(HOVER_HELD_ANIM, HOVER_HELD_NODE, 2,
                  transitions=[OnBool(HOVER_REST_NODE, VM_HOVER, "false", BLEND["hover_out"], SOFT_OUT)])]).rml()
    # A flash is fired, not requested: it starts from wherever the character is - mid-bob,
    # mid-turn, mid-drop - so it blends in at the floor rather than cutting to its own frame 0.
    # Interrupting one flash with the other blends across the error drop the same way.
    #
    # Its glyph is the FlashFace layer's (below), which cuts: a blended `expr` would sweep through
    # every glyph index between the state's and the flash's.
    calm_off = when_bool(VM_REDUCE, "false")
    calm_on = when_bool(VM_REDUCE, "true")
    # Under reduceMotion the two flashes keep their meaning and lose their motion: the glyph under
    # a blink, no hop, no spin, no shake (te3n1.5) - FlashFace plays SuccessCalm / ErrorCalm. A
    # calm trigger fired over a running flash (reduceMotion switched on mid-shake) no longer cuts
    # the motion off: letting go mid-shake would hand the body back to the layers below in one
    # frame, however long the blend, so the running flash finishes instead.
    ERROR_HOLD = ("error flash holds its drop; `state` settles to error behind it, so the "
                  "Expression layer is already holding that pose when Flash lets go")
    flash = Layer(
        "Flash", "3:6", FLASH_REST_NODE,
        any_transitions=[When(SUCCESS_NODE, (when_trigger(VM_SUCCESS), calm_off), MIN_TRANSITION_MS, SOFT_OUT),
                         When(ERROR_NODE, (when_trigger(VM_ERROR), calm_off), MIN_TRANSITION_MS, SOFT_OUT)],
        states=[State(FLASH_REST_ANIM, FLASH_REST_NODE, 0),
                State(SUCCESS_ANIM, SUCCESS_NODE, 1, reset=True,
                      transitions=[Exit(FLASH_REST_NODE, 120, SOFT_OUT)]),
                # A hand-back, and a duration would not soften it (Rive holds the outgoing pose at
                # full weight until a blend ends): it is pose-neutral only because the host sets
                # `state` to error with the trigger.
                State(ERROR_ANIM, ERROR_NODE, 2, reset=True,
                      transitions=[Exit(FLASH_REST_NODE, 0, hold=True, reason=ERROR_HOLD)])]).rml()
    flash_face = Layer(
        "FlashFace", FLASH_FACE_LAYER, FLASH_FACE_REST_NODE,
        any_transitions=[When(SUCCESS_FACE_NODE, (when_trigger(VM_SUCCESS), calm_off), cut=True, reason=GLYPH_CUT),
                         When(ERROR_FACE_NODE, (when_trigger(VM_ERROR), calm_off), cut=True, reason=GLYPH_CUT),
                         When(SUCCESS_CALM_NODE, (when_trigger(VM_SUCCESS), calm_on), cut=True, reason=GLYPH_CUT),
                         When(ERROR_CALM_NODE, (when_trigger(VM_ERROR), calm_on), cut=True, reason=GLYPH_CUT)],
        states=[State(FLASH_REST_ANIM, FLASH_FACE_REST_NODE, 0),
                State(SUCCESS_FACE_ANIM, SUCCESS_FACE_NODE, 1, reset=True,
                      transitions=[Exit(FLASH_FACE_REST_NODE, 0, cut=True, reason=GLYPH_CUT)]),
                State(ERROR_FACE_ANIM, ERROR_FACE_NODE, 2, reset=True,
                      transitions=[Exit(FLASH_FACE_REST_NODE, 0, cut=True, reason=GLYPH_CUT)]),
                State(SUCCESS_CALM_ANIM, SUCCESS_CALM_NODE, 3, reset=True,
                      transitions=[Exit(FLASH_FACE_REST_NODE, 0, cut=True, reason=GLYPH_CUT)]),
                State(ERROR_CALM_ANIM, ERROR_CALM_NODE, 4, reset=True,
                      transitions=[Exit(FLASH_FACE_REST_NODE, 0, cut=True, reason=GLYPH_CUT)])]).rml()
    # The grab squash springs in at the floor (it was 80 ms); the dragged glyph is DragFace's.
    drag = Layer(
        "Drag", "3:7", DRAG_REST_NODE,
        any_transitions=[OnBool(DRAG_NODE, VM_DRAGGED, "true", MIN_TRANSITION_MS, SPRING),
                         OnBool(DRAG_REST_NODE, VM_DRAGGED, "false", 350, SOFT_OUT)],
        states=[State(DRAG_REST_ANIM, DRAG_REST_NODE, 0), State(DRAG_ANIM, DRAG_NODE, 1)]).rml()
    drag_face = Layer(
        "DragFace", DRAG_FACE_LAYER, DRAG_FACE_REST_NODE,
        states=[State(DRAG_REST_ANIM, DRAG_FACE_REST_NODE, 0,
                      transitions=[OnBool(DRAG_FACE_NODE, VM_DRAGGED, "true", 0, None, cut=True, reason=GLYPH_CUT)]),
                State(DRAG_FACE_ANIM, DRAG_FACE_NODE, 1,
                      transitions=[OnBool(DRAG_FACE_REST_NODE, VM_DRAGGED, "false", 0, None, cut=True,
                                          reason=GLYPH_CUT)])]).rml()
    return breath, blink, hover, flash, flash_face, drag, drag_face


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
    """React (te3n1.5) and ReactFace: one one-shot per trigger, fired from wherever the character is.

    A reaction is fired, not requested: every trigger blends into its one-shot from any state at
    the floor (a new poke restarts a poke), because the character it lands on is mid-sway or
    mid-reaction, not at rest. Each one-shot ends at rest, so the way home moves nothing.

    The glyph a reaction shows is ReactFace's, which cuts: a blended `expr` would sweep through
    every glyph between. A reaction without a glyph sends ReactFace home, so a glyph never outlives
    the reaction that showed it. With reduceMotion on, every trigger plays ReactCalm there instead:
    one blink, no motion."""
    full, calm = when_bool(VM_REDUCE, "false"), when_bool(VM_REDUCE, "true")
    fires = [(r, (when_trigger(VM_NEW[trig]),)) for trig, r in _SIMPLE_REACTIONS]
    fires += [(r, (when_trigger(VM_NEW["land"]),) + _band(VM_NEW["impact"], lo, hi)) for r, lo, hi in _LAND_TIERS]
    for k in range(4):
        lo, hi = (None if k == 0 else k - 0.5), (None if k == 3 else k + 0.5)
        fires.append((f"trick{k}", (when_trigger(VM_NEW["trick"]),) + _band(VM_NEW["trickId"], lo, hi)))
    triggers = [t for t, _ in _SIMPLE_REACTIONS] + ["land", "trick"]

    # The motion: in at the floor. Under reduceMotion a trigger leaves a running reaction to
    # finish (it ends at rest); cutting it off mid-move would hand Squash back in one frame.
    moved = [When(react_node[r], (c[0], full) + c[1:], MIN_TRANSITION_MS, SOFT_OUT) for r, c in fires]
    states = [State(REACT_REST_ANIM, REACT_REST_NODE, 0)]
    states += [State(react_anim[r], react_node[r], 1 + i, reset=True, transitions=[Exit(REACT_REST_NODE)])
               for i, r in enumerate(REACTIONS)]
    react = Layer("React", REACT_LAYER, REACT_REST_NODE, any_transitions=moved, states=states).rml()

    # The glyph: cut, always.
    shown = [When(react_face_node[r] if r in REACT_EXPR else REACT_FACE_REST_NODE, (c[0], full) + c[1:],
                  cut=True, reason=GLYPH_CUT) for r, c in fires]
    shown += [When(REACT_CALM_NODE, (when_trigger(VM_NEW[t]), calm), cut=True, reason=GLYPH_CUT) for t in triggers]
    home = lambda: [Exit(REACT_FACE_REST_NODE, 0, cut=True, reason=GLYPH_CUT)]
    face_states = [State(REACT_REST_ANIM, REACT_FACE_REST_NODE, 0)]
    face_states += [State(react_face_anim[r], react_face_node[r], 1 + i, reset=True, transitions=home())
                    for i, r in enumerate(r for r in REACTIONS if r in REACT_EXPR)]
    face_states.append(State(REACT_CALM_ANIM, REACT_CALM_NODE, 1 + len(REACT_EXPR), reset=True, transitions=home()))
    face = Layer("ReactFace", REACT_FACE_LAYER, REACT_FACE_REST_NODE, any_transitions=shown,
                 states=face_states).rml()
    return react, face


def root_machine():
    """The whole `Avatar` state machine: twelve layers in order, plus the file's own listeners.

    te3n1.5's three layers (Pet, PetFace, React) go last: they key only nodes nobody else keys
    (Squash, Peek) plus `expr`, so they sit on top of everything without fighting it."""
    to_park, from_park = _park()
    breath, blink, hover, flash, flash_face, drag, drag_face = _static_layers()
    pet, pet_face = _pet_layers()
    react, react_face = _react_layer()
    # Each face layer sits right above its motion half, so `expr` keeps the priority it had when
    # the two were one layer: React > PetFace > Drag > Flash > Expression.
    layers = [_shape_layer(), _expression_layer(), breath, blink, hover, flash, flash_face, drag, drag_face,
              _idle_layer(to_park, from_park), _wander_layer(to_park, from_park),
              pet, pet_face, react, react_face, _listeners()]
    return f'<StateMachine name="Avatar" id="{SM}">\n' + "\n".join(indent(layer, "    ") for layer in layers) + '\n</StateMachine>'
