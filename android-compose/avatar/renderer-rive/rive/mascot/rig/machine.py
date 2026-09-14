"""The root state machine: one function per layer, assembled by root_machine()."""
from textwrap import indent

from rml import *  # noqa: F401,F403
from rig.constants import *  # noqa: F401,F403
from rig.layers import Exit, Layer, OnBool, OnEnum, OnTrigger, State
from rig.motion import enter_duration


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
    states = []
    for i, st in enumerate(SUSTAINED):
        own = [OnEnum(enter_node[(st, to)], state_enum_ids[to], 0, None) for to in SUSTAINED if to != st]
        states.append(State(root_state_anim[st], root_state_node[st], i, transitions=own))
    for j, ((frm, to), nid) in enumerate(enter_node.items()):
        # An entry can be interrupted by any other request (through that state's own entry).
        hand_off = (0, None) if (frm, to) in DESIGNED_PAIRS else (min(120, enter_duration(frm, to)[0]), EASE_OUT)
        own = [Exit(root_state_node[to], *hand_off)]
        own += [OnEnum(enter_node[(to, other)], state_enum_ids[other], 0, None) for other in SUSTAINED if other != to]
        states.append(State(enter_anim[(frm, to)], nid, len(SUSTAINED) + j, reset=True, transitions=own))

    return Layer("Expression", "3:1", root_state_node["idle"], states=states).rml()


def _static_layers():
    """Breath, Blink, Hover, Flash, Drag: one animation each, driven by the view model."""
    breath = Layer("Breath", "3:2", BREATH_NODE, states=[State(BREATH_ANIM, BREATH_NODE, 0)]).rml()
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
                  transitions=[OnBool(HOVER_REST_NODE, VM_HOVER, "false", 260, SOFT_OUT)])]).rml()
    flash = Layer(
        "Flash", "3:6", FLASH_REST_NODE,
        any_transitions=[OnTrigger(SUCCESS_NODE, VM_SUCCESS), OnTrigger(ERROR_NODE, VM_ERROR)],
        states=[State(FLASH_REST_ANIM, FLASH_REST_NODE, 0),
                State(SUCCESS_ANIM, SUCCESS_NODE, 1, reset=True,
                      transitions=[Exit(FLASH_REST_NODE, 120, SOFT_OUT)]),
                State(ERROR_ANIM, ERROR_NODE, 2, reset=True,
                      transitions=[Exit(FLASH_REST_NODE, 0)])]).rml()
    drag = Layer(
        "Drag", "3:7", DRAG_REST_NODE,
        any_transitions=[OnBool(DRAG_NODE, VM_DRAGGED, "true", 80, SPRING),
                         OnBool(DRAG_REST_NODE, VM_DRAGGED, "false", 350, SOFT_OUT)],
        states=[State(DRAG_REST_ANIM, DRAG_REST_NODE, 0), State(DRAG_ANIM, DRAG_NODE, 1)]).rml()
    return breath, blink, hover, flash, drag


def _park():
    # Asleep, the character holds still: the waits divert to a parked state until it is awake
    # again. Transitions are ordered, so the park check comes first.
    sleeping = state_enum_ids["sleeping"]
    to_park = lambda node, dur=0: OnEnum(node, sleeping, dur, None)
    from_park = lambda node: OnEnum(node, sleeping, 0, None, op="notEqual")
    return to_park, from_park


def _idle_layer(to_park, from_park):
    # Each wait ends by picking one beat at random (weights: the glance is still the most common,
    # the bounce the rarest); every beat returns to a random wait.
    # A beat blends in over 160 ms and out over 320 ms: its keys sit on top of Breath / the
    # host's turn on the same nodes, so a hard cut would snap those values at either end.
    beat_nodes = [(IDLE_GLANCE_NODE, 22), (IDLE_BEATS["tilt"][1], 14), (IDLE_BEATS["lookaround"][1], 13),
                  (IDLE_BEATS["shift"][1], 12), (IDLE_BEATS["stretch"][1], 10), (IDLE_BEATS["sigh"][1], 10),
                  (IDLE_BEATS["shiver"][1], 8), (IDLE_BEATS["wobble"][1], 6), (IDLE_BEATS["bounce"][1], 5)]
    pick_beat = [Exit(n, 160, SOFT_OUT, w) for n, w in beat_nodes]
    back_to_wait = [Exit(n, 320, SOFT_OUT, 25) for _, n, _ in IDLE_WAITS]

    states = [State(aid, n, k, random=True, transitions=[to_park(IDLE_SLEEP_NODE)] + pick_beat)
              for k, (aid, n, _) in enumerate(IDLE_WAITS)]
    states.append(State(IDLE_GLANCE_ANIM, IDLE_GLANCE_NODE, 4, reset=True, random=True,
                        transitions=[to_park(IDLE_SLEEP_NODE)] + back_to_wait))
    states.append(State(IDLE_WAIT_A, IDLE_SLEEP_NODE, 14, transitions=[from_park(IDLE_A_NODE)]))
    states += [State(aid, n, 5 + i, reset=True, random=True,
                     transitions=[to_park(IDLE_SLEEP_NODE)] + back_to_wait)
               for i, (aid, n) in enumerate(IDLE_BEATS.values())]

    return Layer("IdleVariety", "3:8", IDLE_A_NODE, states=states).rml()


def _wander_layer(to_park, from_park):
    # Wander beats key the joystick the host also turns: blend in (200 ms) and out (320 ms).
    pick_wander = [Exit(WANDER_GLANCE_NODE, 200, SOFT_OUT, 55),
                   Exit(WANDER_PEEK_NODE, 200, SOFT_OUT, 35),
                   Exit(WANDER_SPIN_NODE, 200, SOFT_OUT, 10)]
    wander_home = [Exit(n, 320, SOFT_OUT, 25) for _, n, _ in WANDER_WAITS]

    states = [State(aid, n, k, random=True, transitions=[to_park(WANDER_SLEEP_WAIT_NODE)] + pick_wander)
              for k, (aid, n, _) in enumerate(WANDER_WAITS)]
    states += [State(aid, n, i, reset=True, random=True,
                     transitions=[to_park(WANDER_SLEEP_WAIT_NODE, 400)] + wander_home)
               for aid, n, i in ((WANDER_GLANCE, WANDER_GLANCE_NODE, 4),
                                 (WANDER_PEEK, WANDER_PEEK_NODE, 5),
                                 (WANDER_SPIN, WANDER_SPIN_NODE, 6))]
    states.append(State(WANDER_SLEEP_WAIT, WANDER_SLEEP_WAIT_NODE, 7,
                        transitions=[from_park(WANDER_A_NODE), Exit(WANDER_SLEEP_SHIFT_NODE)]))
    states.append(State(WANDER_SLEEP_SHIFT, WANDER_SLEEP_SHIFT_NODE, 6, reset=True,
                        transitions=[from_park(WANDER_A_NODE),
                                     Exit(WANDER_SLEEP_WAIT_NODE, 200, SOFT_OUT)]))

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


def root_machine():
    to_park, from_park = _park()
    breath, blink, hover, flash, drag = _static_layers()
    layers = [_shape_layer(), _expression_layer(), breath, blink, hover, flash, drag,
              _idle_layer(to_park, from_park), _wander_layer(to_park, from_park), _listeners()]
    return f'<StateMachine name="Avatar" id="{SM}">\n' + "\n".join(indent(layer, "    ") for layer in layers) + '\n</StateMachine>'
