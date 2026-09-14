"""RML primitives for the mascot generator: Rive property keys, view-model ids, easing tokens and
the small XML builders (keyframes, animations, states, transitions). No mascot knowledge lives
here; gen_scene.py composes these into the rig. Star-imported by gen_scene.py."""
from textwrap import indent

# --- property keys (rive schema) --------------------------------------------------------------
X, Y, ROT, SX, SY, OPACITY = 13, 14, 15, 16, 17, 18
COLOR, GRADIENT_OPACITY = 37, 46
VX, VY, VROT, VDIST = 24, 25, 82, 83                      # mirrored vertex
VIN_ROT, VIN_DIST, VOUT_ROT, VOUT_DIST = 84, 85, 86, 87   # detached vertex
NESTED_VALUE, NESTED_FIRE, REMAP_TIME = 239, 401, 202
ACTIVE_CHILD = 296  # Solo.activeComponentId
BIND_ENUM, BIND_TRIGGER, BIND_BOOL = 637, 686, 634

# --- ids ----------------------------------------------------------------------------------------
VM, VM_STATE, VM_MOUTH, VM_LOOKX, VM_LOOKY, VM_BLINK, VM_SHAPE, VM_COLOR, VM_HOVER = (
    "1:50", "1:1", "1:2", "1:3", "1:4", "1:5", "1:6", "1:7", "1:8")
VM_SUCCESS, VM_ERROR, VM_DRAGGED = "1:9", "1:10", "1:11"

# SPEC / MOTION-REFERENCES beziers
EASE_OUT = "0 0 0.58 1"
SOFT_OUT = "0.22 1 0.36 1"
STANDARD = "0.4 0 0.2 1"
SPRING = "0.16 1 0.3 1"
ACCEL = "0.4 0 1 1"
SINE = "0.37 0 0.63 1"
LINEAR = "0 0 1 1"
# SPEC section 9 (Material 3 motion tokens)
EMPH_ACCEL = "0.3 0 0.8 0.15"
# Mass. A cubic with y outside 0..1 pulls back before it goes (BACK_IN) or overshoots and
# settles (BACK_OUT); Elastic is a real damped spring on the landing.
BACK_IN = "0.36 0 0.66 -0.56"
BACK_OUT = "0.34 1.28 0.64 1"
BACK_IN_OUT = "0.68 -0.4 0.32 1.35"


class Elastic(str):
    """Marker: `Elastic(amplitude, period)` as a key's outgoing interpolation (spring on arrival)."""
    def __new__(cls, amplitude=1.0, period=0.4, easing="easeOut"):
        o = str.__new__(cls, f"elastic {amplitude} {period} {easing}")
        o.amplitude, o.period, o.easing = amplitude, period, easing
        return o


ELASTIC_OUT = Elastic(0.7, 0.75)     # a whip settles with one slow, heavy bounce
ELASTIC_SOFT = Elastic(0.35, 0.9)    # a glance settles with barely one
EMPH_DECEL = "0.05 0.7 0.1 1"
M3_STANDARD = "0.2 0 0 1"
STD_DECEL = "0 0 0 1"


# --- small builders ----------------------------------------------------------------------------
def bind(source, key, converter=None):
    conv = f' converterId="{converter}"' if converter else ""
    return f'<DataBindContext sourcePathIds="{VM}-{source}" propertyKey="{key}"{conv}/>'


def interp(bezier):
    x1, y1, x2, y2 = bezier.split()
    return f'<CubicEaseInterpolator x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}"/>'


class Id(str):
    """A keyed object reference (Solo.activeComponentId): KeyFrameId, always hold."""


def kf(value, frame, bezier=None):
    if isinstance(value, Id):
        return f'<KeyFrameId value="{value}" frame="{frame}"/>'
    if isinstance(value, str):  # colour
        return f'<KeyFrameColor value="{value}" frame="{frame}"/>'
    if isinstance(bezier, Elastic):
        return (f'<KeyFrameDouble value="{value}" frame="{frame}" interpolationType="elastic">'
                f'<ElasticInterpolator easingValue="{bezier.easing}" amplitude="{bezier.amplitude}" period="{bezier.period}"/></KeyFrameDouble>')
    if bezier:
        return f'<KeyFrameDouble value="{value}" frame="{frame}" interpolationType="cubic">{interp(bezier)}</KeyFrameDouble>'
    return f'<KeyFrameDouble value="{value}" frame="{frame}"/>'


def _frames(fr, default_bezier):
    if not isinstance(fr, list):
        fr = [(0, fr)]
    lines = []
    for item in fr:
        f, v = item[0], item[1]
        bez = item[2] if len(item) > 2 else (default_bezier if len(fr) > 1 else None)
        lines.append("        " + kf(v, f, bez))
    return lines


def _keyed_object(obj, props, default_bezier):
    kp = []
    for key, fr in props.items():
        lines = _frames(fr, default_bezier)
        kp.append(f'    <KeyedProperty propertyKey="{key}">\n' + "\n".join(lines) + '\n    </KeyedProperty>')
    return f'<KeyedObject objectId="{obj}">\n' + "\n".join(kp) + '\n</KeyedObject>'


def keyed(objects, default_bezier=SOFT_OUT):
    """objects: {objectId: {propertyKey: value | [(frame, value) | (frame, value, bezier)]}}.
    The bezier on a keyframe shapes the segment that LEAVES it (Rive semantics)."""
    return "\n".join(_keyed_object(obj, props, default_bezier) for obj, props in objects.items())


def callback_keyed(objects, frame=0):
    return "\n".join(
        f'<KeyedObject objectId="{o}">\n    <KeyedProperty propertyKey="{NESTED_FIRE}">\n'
        f'        <KeyFrameCallback frame="{frame}"/>\n    </KeyedProperty>\n</KeyedObject>' for o in objects)


def animation(*parts, **opts):
    name, aid, duration, objects = parts[0], parts[1], parts[2], parts[3]
    loop = parts[4] if len(parts) > 4 else opts.get("loop", "oneShot")
    callbacks = opts.get("callbacks", ())
    bezier = opts.get("bezier", SOFT_OUT)
    body = keyed(objects, bezier) + ("\n" + callback_keyed(callbacks) if callbacks else "")
    return (f'<LinearAnimation fps="60" duration="{max(1, duration)}" loopValue="{loop}" name="{name}" id="{aid}">\n'
            + indent(body, "    ") + '\n</LinearAnimation>')


def anim_state(*parts, **opts):
    aid, nid, i = parts[0], parts[1], parts[2]
    extra = parts[3] if len(parts) > 3 else opts.get("extra", "")
    children = parts[4] if len(parts) > 4 else opts.get("children", "")
    if children:
        return f'<AnimationState x="200" y="{40 + 60 * i}" animationId="{aid}"{extra} id="{nid}">\n{indent(children, "    ")}\n</AnimationState>'
    return f'<AnimationState x="200" y="{40 + 60 * i}" animationId="{aid}"{extra} id="{nid}"/>'


def layer_frame(*parts):
    name, lid, entry_to, any_transitions, states = parts
    return f'''<StateMachineLayer name="{name}" id="{lid}">
    <EntryState>
        <StateTransition stateToId="{entry_to}"/>
    </EntryState>
    <AnyState x="220" y="-140">
{indent(any_transitions, "        ")}
    </AnyState>
    <ExitState x="430" y="-140"/>
{indent(states, "    ")}
</StateMachineLayer>'''


def vm_condition(kind, prop, literal, op="equal"):
    key = {"Enum": BIND_ENUM, "Trigger": BIND_TRIGGER, "Boolean": BIND_BOOL}[kind]
    return f'''<TransitionViewModelCondition opValue="{op}">
    <TransitionPropertyViewModelComparator>
        <BindableProperty{kind}>
            {bind(prop, key)}
        </BindableProperty{kind}>
    </TransitionPropertyViewModelComparator>
    {literal}
</TransitionViewModelCondition>'''


def transition(*parts, **opts):
    to_id, duration_ms, bezier = parts[0], parts[1], parts[2]
    condition = parts[3] if len(parts) > 3 else opts.get("condition")
    exit_time = opts.get("exit_time", False)
    attrs = f'stateToId="{to_id}" duration="{duration_ms}"'
    if exit_time:
        attrs += ' enableExitTime="true" exitTimeIsPercetange="true" exitTime="100"'
    eased = bool(bezier) and duration_ms > 0
    if eased:
        attrs += ' interpolationType="cubic"'
    inner = (interp(bezier) + "\n" if eased else "") + (condition or "")
    if inner.strip():
        return f'<StateTransition {attrs}>\n{indent(inner.rstrip(), "    ")}\n</StateTransition>'
    return f'<StateTransition {attrs}/>'


def enum_transition(*parts, **opts):
    to_id, enum_value_id = parts[0], parts[1]
    duration_ms = parts[2] if len(parts) > 2 else opts.get("duration_ms", 160)
    bezier = parts[3] if len(parts) > 3 else opts.get("bezier", EASE_OUT)
    prop = parts[4] if len(parts) > 4 else opts.get("prop", VM_STATE)
    op = opts.get("op", "equal")
    return transition(to_id, duration_ms, bezier, vm_condition("Enum", prop, f'<TransitionValueEnumComparator value="{enum_value_id}"/>', op))


def trigger_transition(to_id, prop):
    return transition(to_id, 0, None, vm_condition("Trigger", prop, '<TransitionValueTriggerComparator/>'))


def bool_transition(*parts):
    to_id, prop, value, duration_ms, bezier = parts
    return transition(to_id, duration_ms, bezier, vm_condition("Boolean", prop, f'<TransitionValueBooleanComparator value="{value}"/>'))


def input_transition(to_id, input_id, value, duration=160):
    # Banded, not exact: the root keys this number and a root cross-blend interpolates it, so an
    # exact match would only fire when the blend ends. The band switches at the blend midpoint.
    return (f'<StateTransition stateToId="{to_id}" duration="{duration}">\n'
            f'    <TransitionNumberCondition inputId="{input_id}" opValue="greaterThanOrEqual" value="{value - 0.5}"/>\n'
            f'    <TransitionNumberCondition inputId="{input_id}" opValue="lessThan" value="{value + 0.5}"/>\n'
            f'</StateTransition>')


def exit_transition(to_id, duration=0, bezier=None):
    return transition(to_id, duration, bezier, exit_time=True)


def weighted(t, weight):
    return t.replace("<StateTransition ", f'<StateTransition randomWeight="{weight}" ', 1)
