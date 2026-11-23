"""The `BrandMark` state machine: five layers, each keying its own nodes and nothing else.

| Layer  | Keys                                   | Driven by                                   |
|--------|----------------------------------------|---------------------------------------------|
| Theme  | every colour                           | `theme` (280 ms blend)                      |
| Pose   | both Pose nodes (x y rot sx sy)        | `state`, `reduceMotion` (450 ms / 300 ms)   |
| Light  | tones, overlays, bounce, sheen, seam   | `state`, `reduceMotion` (320 ms / 300 ms)   |
| Event  | both Event nodes, the bloom            | triggers `boot`, `success`, `reduceMotion`  |
| Hover  | both Lean nodes                        | `hovered` (240 ms in, 320 ms out)           |

Layers are built with the mascot's typed builder (`rig.layers`), so the structural rules and the
blend policy are checked here in Python: every state change that moves a pose takes at least
MIN_TRANSITION_MS, no exit-time transition hangs off a loop, every target is in its own layer.

Entry is conditional on the Theme, Pose, Light and Event layers: the layer starts in the state the
view model already asks for (written before the first advance), instead of starting in the
default and blending over to it - so a mark that opens `connecting` is parted from frame 0, a
`light` mark is never dark for 280 ms, and a `boot` fired before the first advance starts the
entrance from nothing rather than from the resting mark. `rig.layers` knows one entry target;
`_conditional_entry()` replaces that line with the list after the layer has validated.
"""
from textwrap import indent

from rml import STANDARD, transition, when_bool, when_enum, when_trigger

from rig.constants import (CALM_BLEND_MS, HOVER_IN_MS, HOVER_OUT_MS, LIGHT_BLEND_MS, MIN_TRANSITION_MS,
                           SOFT_OUT, STATE_BLEND_MS, THEME_BLEND_MS)
from rig.ids import (BOOT_ANIM, BOOT_CALM_ANIM, BOOT_CALM_NODE, BOOT_NODE, EVENT_REST_ANIM,
                     EVENT_REST_NODE, HOVER_ANIM, HOVER_NODE, HOVER_REST_ANIM, HOVER_REST_NODE,
                     LAYER_EVENT, LAYER_HOVER, LAYER_LIGHT, LAYER_POSE, LAYER_THEME, SM, STATES,
                     SUCCESS_ANIM, SUCCESS_CALM_ANIM, SUCCESS_CALM_NODE, SUCCESS_NODE, THEMES,
                     VM_BOOT, VM_HOVER, VM_REDUCE, VM_STATE, VM_SUCCESS, VM_THEME, light_anim,
                     light_calm_anim, light_calm_node, light_node, pose_anim, pose_calm_anim,
                     pose_calm_node, pose_node, state_enum_ids, theme_anim, theme_enum_ids, theme_node)
from rig.layers import Exit, Layer, OnBool, OnEnum, State, When

STATE_MACHINE = "BrandMark"


def _conditional_entry(layer_xml, entry_to, branches):
    """Swap the layer's single entry transition for `branches` [(to, [conditions])], evaluated in
    order, with the original target as the unconditional fallback last."""
    plain = f'<StateTransition stateToId="{entry_to}"/>'
    if plain not in layer_xml:
        raise ValueError("entry transition not where rig.layers puts it")
    lines = [transition(to, 0, None, "\n".join(conds)) for to, conds in branches] + [plain]
    return layer_xml.replace(plain, "\n".join(lines).replace("\n", "\n        "), 1)


def _loops(*tables):
    return {aid: "loop" for table in tables for aid in table.values()}


# --- Theme --------------------------------------------------------------------------------------
def theme_layer():
    states = []
    for i, t in enumerate(THEMES):
        states.append(State(theme_anim[t], theme_node[t], i, transitions=[
            OnEnum(theme_node[o], theme_enum_ids[o], THEME_BLEND_MS, STANDARD, prop=VM_THEME)
            for o in THEMES if o != t]))
    layer = Layer("Theme", LAYER_THEME, theme_node[THEMES[0]], states=states)
    return _conditional_entry(layer.rml(), theme_node[THEMES[0]],
                              [(theme_node[t], [when_enum(VM_THEME, theme_enum_ids[t])]) for t in THEMES[1:]])


# --- Pose and Light: a loop and its calm twin per state ------------------------------------------
def _sustained_layer(name, lid, loop_anim, loop_node, calm_anim, calm_node, blend_ms, bezier):
    states, i = [], 0
    for s in STATES:
        # The reduceMotion check comes first: Rive evaluates a state's transitions in order.
        states.append(State(loop_anim[s], loop_node[s], i, transitions=[
            OnBool(calm_node[s], VM_REDUCE, "true", CALM_BLEND_MS, STANDARD)] + [
            OnEnum(loop_node[o], state_enum_ids[o], blend_ms, bezier) for o in STATES if o != s]))
        states.append(State(calm_anim[s], calm_node[s], i + 1, transitions=[
            OnBool(loop_node[s], VM_REDUCE, "false", CALM_BLEND_MS, STANDARD)] + [
            OnEnum(calm_node[o], state_enum_ids[o], blend_ms, bezier) for o in STATES if o != s]))
        i += 2
    first = loop_node[STATES[0]]
    layer = Layer(name, lid, first, states=states, anim_loops=_loops(loop_anim))
    branches = []
    for s in STATES:
        branches.append((calm_node[s], [when_bool(VM_REDUCE, "true"), when_enum(VM_STATE, state_enum_ids[s])]))
        if s != STATES[0]:
            branches.append((loop_node[s], [when_enum(VM_STATE, state_enum_ids[s])]))
    return _conditional_entry(layer.rml(), first, branches)


def pose_layer():
    return _sustained_layer("Pose", LAYER_POSE, pose_anim, pose_node, pose_calm_anim, pose_calm_node,
                            STATE_BLEND_MS, STANDARD)


def light_layer():
    return _sustained_layer("Light", LAYER_LIGHT, light_anim, light_node, light_calm_anim, light_calm_node,
                            LIGHT_BLEND_MS, STANDARD)


# --- Event: boot and success ----------------------------------------------------------------------
def event_layer():
    motion, calm = when_bool(VM_REDUCE, "false"), when_bool(VM_REDUCE, "true")
    boot, success = when_trigger(VM_BOOT), when_trigger(VM_SUCCESS)
    back = "the one-shot ends on the identity it started from, and no lower layer keys an Event node"
    rest = State(EVENT_REST_ANIM, EVENT_REST_NODE, 0, transitions=[
        # A boot fired on a mark already showing hides it over the floor before the entrance; a
        # boot fired before the first advance takes the conditional entry instead and starts
        # from nothing (see _conditional_entry above).
        When(BOOT_NODE, (boot, motion), ms=MIN_TRANSITION_MS, bezier=STANDARD),
        When(BOOT_CALM_NODE, (boot, calm), ms=MIN_TRANSITION_MS, bezier=STANDARD),
        When(SUCCESS_NODE, (success, motion)),
        When(SUCCESS_CALM_NODE, (success, calm)),
    ])
    one_shots = [State(a, n, i + 1, transitions=[Exit(EVENT_REST_NODE, hold=True, reason=back)])
                 for i, (a, n) in enumerate([(BOOT_ANIM, BOOT_NODE), (SUCCESS_ANIM, SUCCESS_NODE),
                                             (BOOT_CALM_ANIM, BOOT_CALM_NODE),
                                             (SUCCESS_CALM_ANIM, SUCCESS_CALM_NODE)])]
    layer = Layer("Event", LAYER_EVENT, EVENT_REST_NODE, states=[rest] + one_shots)
    return _conditional_entry(layer.rml(), EVENT_REST_NODE,
                              [(BOOT_CALM_NODE, [boot, calm]), (BOOT_NODE, [boot])])


# --- Hover ----------------------------------------------------------------------------------------
def hover_layer():
    return Layer("Hover", LAYER_HOVER, HOVER_REST_NODE, states=[
        State(HOVER_REST_ANIM, HOVER_REST_NODE, 0,
              transitions=[OnBool(HOVER_NODE, VM_HOVER, "true", HOVER_IN_MS, SOFT_OUT)]),
        State(HOVER_ANIM, HOVER_NODE, 1,
              transitions=[OnBool(HOVER_REST_NODE, VM_HOVER, "false", HOVER_OUT_MS, STANDARD)]),
    ]).rml()


def root_machine():
    layers = [theme_layer(), pose_layer(), light_layer(), event_layer(), hover_layer()]
    return (f'<StateMachine name="{STATE_MACHINE}" id="{SM}">\n'
            + "\n".join(indent(layer, "    ") for layer in layers) + '\n</StateMachine>')
