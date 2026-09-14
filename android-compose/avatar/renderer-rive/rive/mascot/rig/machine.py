"""The root state machine: one function per layer, assembled by root_machine()."""
from textwrap import indent

from rml import *  # noqa: F401,F403
from rig.constants import *  # noqa: F401,F403
from rig.motion import enter_duration


def _shape_layer():
    shape_trans = "\n".join(enum_transition(shape_node[s], shape_enum_ids[s], 240, SOFT_OUT, VM_SHAPE) for s in SHAPES)
    shape_states = "\n".join(anim_state(shape_anim[s], shape_node[s], i) for i, s in enumerate(SHAPES))
    shape_layer = layer_frame("Shape", "3:9", shape_node[DEFAULT_SHAPE], shape_trans, shape_states)

    return shape_layer


def _expression_layer():
    # No AnyState fan-out: Rive evaluates AnyState before a state's own transitions, which would
    # swallow the entries. Every sustained state cuts (0 ms) into the entry for the requested
    # state; the entry hands off to the sustained loop at its end. Designed entries land on the
    # target's rest and cut; generic ones blend the hand-off so held face/body/tint ease in.
    states = []
    for i, st in enumerate(SUSTAINED):
        own = [enum_transition(enter_node[(st, to)], state_enum_ids[to], 0, None) for to in SUSTAINED if to != st]
        states.append(anim_state(root_state_anim[st], root_state_node[st], i, "", "\n".join(own)))
    for j, ((frm, to), nid) in enumerate(enter_node.items()):
        # An entry can be interrupted by any other request (through that state's own entry).
        hand_off = (0, None) if (frm, to) in DESIGNED_PAIRS else (min(120, enter_duration(frm, to)[0]), EASE_OUT)
        own = [exit_transition(root_state_node[to], *hand_off)]
        own += [enum_transition(enter_node[(to, other)], state_enum_ids[other], 0, None) for other in SUSTAINED if other != to]
        states.append(anim_state(enter_anim[(frm, to)], nid, len(SUSTAINED) + j, ' reset="true"', "\n".join(own)))
    expression = layer_frame("Expression", "3:1", root_state_node["idle"], "", "\n".join(states))

    return expression


def _static_layers():
    """Breath, Blink, Hover, Flash, Drag: one animation each, driven by the view model."""
    breath = layer_frame("Breath", "3:2", BREATH_NODE, "", anim_state(BREATH_ANIM, BREATH_NODE, 0))
    blink = layer_frame("Blink", "3:3", BLINK_REST_NODE, trigger_transition(BLINK_NODE, VM_BLINK),
                        f'<AnimationState x="200" y="40" animationId="{BLINK_REST_ANIM}" id="{BLINK_REST_NODE}"/>\n'
                        + anim_state(BLINK_ANIM, BLINK_NODE, 1, ' reset="true"', exit_transition(BLINK_REST_NODE)))
    hover = layer_frame(
        "Hover", "3:4", HOVER_REST_NODE, "",
        anim_state(HOVER_REST_ANIM, HOVER_REST_NODE, 0, "", bool_transition(HOVER_NODE, VM_HOVER, "true", 0, None)) + "\n"
        + anim_state(HOVER_ANIM, HOVER_NODE, 1, ' reset="true"', exit_transition(HOVER_HELD_NODE) + "\n" + bool_transition(HOVER_REST_NODE, VM_HOVER, "false", 220, SOFT_OUT)) + "\n"
        + anim_state(HOVER_HELD_ANIM, HOVER_HELD_NODE, 2, "", bool_transition(HOVER_REST_NODE, VM_HOVER, "false", 260, SOFT_OUT)))
    flash = layer_frame(
        "Flash", "3:6", FLASH_REST_NODE, trigger_transition(SUCCESS_NODE, VM_SUCCESS) + "\n" + trigger_transition(ERROR_NODE, VM_ERROR),
        f'<AnimationState x="200" y="40" animationId="{FLASH_REST_ANIM}" id="{FLASH_REST_NODE}"/>\n'
        + anim_state(SUCCESS_ANIM, SUCCESS_NODE, 1, ' reset="true"', exit_transition(FLASH_REST_NODE, 120, SOFT_OUT)) + "\n"
        + anim_state(ERROR_ANIM, ERROR_NODE, 2, ' reset="true"', exit_transition(FLASH_REST_NODE, 0)))
    drag = layer_frame(
        "Drag", "3:7", DRAG_REST_NODE,
        bool_transition(DRAG_NODE, VM_DRAGGED, "true", 80, SPRING) + "\n" + bool_transition(DRAG_REST_NODE, VM_DRAGGED, "false", 350, SOFT_OUT),
        f'<AnimationState x="200" y="40" animationId="{DRAG_REST_ANIM}" id="{DRAG_REST_NODE}"/>\n'
        f'<AnimationState x="200" y="100" animationId="{DRAG_ANIM}" id="{DRAG_NODE}"/>')
    return breath, blink, hover, flash, drag


def _park():
    # Asleep, the character holds still: the waits divert to a parked state until it is awake
    # again. Transitions are ordered, so the park check comes first.
    sleeping = state_enum_ids["sleeping"]
    to_park = lambda node, dur=0: enum_transition(node, sleeping, dur, None)
    from_park = lambda node: enum_transition(node, sleeping, 0, None, op="notEqual")
    return to_park, from_park


def _idle_layer(to_park, from_park):
    # Each wait ends by picking one beat at random (weights: the glance is still the most common,
    # the bounce the rarest); every beat returns to a random wait.
    # A beat blends in over 160 ms and out over 320 ms: its keys sit on top of Breath / the
    # host's turn on the same nodes, so a hard cut would snap those values at either end.
    beat_nodes = [(IDLE_GLANCE_NODE, 22), (IDLE_BEATS["tilt"][1], 14), (IDLE_BEATS["lookaround"][1], 13),
                  (IDLE_BEATS["shift"][1], 12), (IDLE_BEATS["stretch"][1], 10), (IDLE_BEATS["sigh"][1], 10),
                  (IDLE_BEATS["shiver"][1], 8), (IDLE_BEATS["wobble"][1], 6), (IDLE_BEATS["bounce"][1], 5)]
    pick_beat = "\n".join(weighted(exit_transition(n, 160, SOFT_OUT), w) for n, w in beat_nodes)
    back_to_wait = "\n".join(weighted(exit_transition(n, 320, SOFT_OUT), 25) for _, n, _ in IDLE_WAITS)
    wait_states = "\n".join(
        anim_state(aid, n, k, ' random="true"', to_park(IDLE_SLEEP_NODE) + "\n" + pick_beat)
        for k, (aid, n, _) in enumerate(IDLE_WAITS))
    beat_states = "\n".join(
        anim_state(aid, n, 5 + i, ' reset="true" random="true"', to_park(IDLE_SLEEP_NODE) + "\n" + back_to_wait)
        for i, (aid, n) in enumerate(IDLE_BEATS.values()))
    idle = layer_frame(
        "IdleVariety", "3:8", IDLE_A_NODE, "",
        wait_states + "\n"
        + anim_state(IDLE_GLANCE_ANIM, IDLE_GLANCE_NODE, 4, ' reset="true" random="true"', to_park(IDLE_SLEEP_NODE) + "\n" + back_to_wait) + "\n"
        + anim_state(IDLE_WAIT_A, IDLE_SLEEP_NODE, 14, "", from_park(IDLE_A_NODE)) + "\n"
        + beat_states)

    return idle


def _wander_layer(to_park, from_park):
    # Wander beats key the joystick the host also turns: blend in (200 ms) and out (320 ms).
    pick_wander = (weighted(exit_transition(WANDER_GLANCE_NODE, 200, SOFT_OUT), 55) + "\n"
                   + weighted(exit_transition(WANDER_PEEK_NODE, 200, SOFT_OUT), 35) + "\n"
                   + weighted(exit_transition(WANDER_SPIN_NODE, 200, SOFT_OUT), 10))
    wander_home = "\n".join(weighted(exit_transition(n, 320, SOFT_OUT), 25) for _, n, _ in WANDER_WAITS)
    wander_waits = "\n".join(
        anim_state(aid, n, k, ' random="true"', to_park(WANDER_SLEEP_WAIT_NODE) + "\n" + pick_wander)
        for k, (aid, n, _) in enumerate(WANDER_WAITS))
    wander = layer_frame(
        "Wander", "3:10", WANDER_A_NODE, "",
        wander_waits + "\n"
        + anim_state(WANDER_GLANCE, WANDER_GLANCE_NODE, 4, ' reset="true" random="true"',
                     to_park(WANDER_SLEEP_WAIT_NODE, 400) + "\n" + wander_home) + "\n"
        + anim_state(WANDER_PEEK, WANDER_PEEK_NODE, 5, ' reset="true" random="true"',
                     to_park(WANDER_SLEEP_WAIT_NODE, 400) + "\n" + wander_home) + "\n"
        + anim_state(WANDER_SPIN, WANDER_SPIN_NODE, 6, ' reset="true" random="true"',
                     to_park(WANDER_SLEEP_WAIT_NODE, 400) + "\n" + wander_home) + "\n"
        + anim_state(WANDER_SLEEP_WAIT, WANDER_SLEEP_WAIT_NODE, 7, "",
                     from_park(WANDER_A_NODE) + "\n" + exit_transition(WANDER_SLEEP_SHIFT_NODE)) + "\n"
        + anim_state(WANDER_SLEEP_SHIFT, WANDER_SLEEP_SHIFT_NODE, 6, ' reset="true"',
                     from_park(WANDER_A_NODE) + "\n" + exit_transition(WANDER_SLEEP_WAIT_NODE, 200, SOFT_OUT)))

    return wander


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
