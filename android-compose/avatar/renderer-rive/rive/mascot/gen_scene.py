"""Generator for rive/mascot/scene.rml - the v2 rig (see MASCOT.md).

One character, built the way the reference files are: the root artboard owns the body and the
view model; the face is three component artboards (Eye x2, Brow x2, Mouth) driven from the root
through nested inputs and scrubbed timelines. Everything the host writes stays on the root's
view model - the components are internal and never see it.

Why generated: twelve expressions x five components x every keyed property must be exhaustive or
states leak into each other. Regenerate with `python gen_scene.py`, then `rive . --verify`.

Conventions learned the hard way (all silent in the compiler):
  - rotations are radians; LinearAnimation.duration is frames; StateTransition.duration is ms
  - keyframes default to `hold`; motion needs interpolationType="cubic" + a nested interpolator
  - the first child of a node draws on TOP
  - a view-model-driven machine inside a nested artboard never fires; components use inputs
  - NestedRemapAnimation.time is a 0..1 fraction of the timeline
  - Feather on a Fill renders nothing; feather strokes, glow with gradients
"""
import math
from textwrap import indent


def rad(deg):
    return round(math.radians(deg), 5)


STATES = ["idle", "listening", "dragged", "thinking", "waitingInput", "speaking",
          "success", "error", "sleeping", "loading", "failed", "degraded"]
EXPR = {s: i for i, s in enumerate(STATES)}

# --- property keys (rive schema) --------------------------------------------------------------
X, Y, ROT, SX, SY, OPACITY = 13, 14, 15, 16, 17, 18
WIDTH, HEIGHT, COLOR = 20, 21, 37
NESTED_VALUE, NESTED_FIRE, REMAP_TIME = 239, 401, 202
GRADIENT_STOP_COLOR = 38
BIND_ENUM, BIND_TRIGGER, BIND_BOOL = 637, 686, 634

# --- ids: "client:object". 0:* root scene, 1:* data, 2:* converters, 3:* root machine,
#     4:* Eye, 5:* Brow, 6:* Mouth ----------------------------------------------------------------
VM, VM_STATE, VM_MOUTH, VM_LOOKX, VM_LOOKY, VM_BLINK, VM_SHAPE, VM_COLOR, VM_HOVER = (
    "1:50", "1:1", "1:2", "1:3", "1:4", "1:5", "1:6", "1:7", "1:8")
VM_INSTANCE, ENUM_STATE, ENUM_SHAPE = "1:20", "1:100", "1:200"
state_enum_ids = {s: f"1:{101 + i}" for i, s in enumerate(STATES)}
SHAPES = ["circle", "blob", "roundedSquare", "pill", "triangle", "hexagon", "cloud", "drop"]
shape_enum_ids = {s: f"1:{201 + i}" for i, s in enumerate(SHAPES)}

CONV_LOOK, CONV_MOUTH = "2:1", "2:2"

ROOT, BODY_NODE, FACE, HITBOX = "0:2", "0:100", "0:200", "0:90"
EYE_L, EYE_R, BROW_L, BROW_R, MOUTH = "0:210", "0:220", "0:230", "0:240", "0:250"
# nested-input / remap ids per placement
EYE_L_EXPR, EYE_R_EXPR, BROW_L_EXPR, BROW_R_EXPR, MOUTH_EXPR = "0:211", "0:221", "0:231", "0:241", "0:251"
EYE_L_BLINK, EYE_R_BLINK = "0:212", "0:222"

SM = "3:5"
root_state_anim = {s: f"3:{100 + i}" for i, s in enumerate(STATES)}
root_state_node = {s: f"3:{130 + i}" for i, s in enumerate(STATES)}
BREATH_ANIM, BREATH_NODE = "3:160", "3:161"
BLINK_ANIM, BLINK_REST_ANIM, BLINK_NODE, BLINK_REST_NODE = "3:170", "3:171", "3:172", "3:173"
HOVER_ANIM, HOVER_REST_ANIM, HOVER_NODE, HOVER_REST_NODE = "3:180", "3:181", "3:182", "3:183"

EYE_AB, EYE_SM, EYE_IN_EXPR, EYE_IN_BLINK = "4:2", "4:5", "4:6", "4:7"
EYE_PUPIL, EYE_SCLERA, EYE_ROOT, EYE_APERTURE, EYE_APERTURE_PATH = "4:20", "4:23", "4:24", "4:25", "4:26"
EYE_LOOKX, EYE_LOOKY, EYE_BLINK_ANIM = "4:30", "4:31", "4:32"
EYE_WAIT_A, EYE_WAIT_B = "4:33", "4:34"
eye_expr_anim = {s: f"4:{100 + i}" for i, s in enumerate(STATES)}
eye_expr_node = {s: f"4:{130 + i}" for i, s in enumerate(STATES)}
EYE_BLINK_NODE, EYE_BLINK_REST_NODE = "4:160", "4:161"
EYE_AUTO_A, EYE_AUTO_B, EYE_AUTO_BLINK = "4:162", "4:163", "4:164"

BROW_AB, BROW_SM, BROW_IN_EXPR, BROW_BAR = "5:2", "5:5", "5:6", "5:20"
brow_expr_anim = {s: f"5:{100 + i}" for i, s in enumerate(STATES)}
brow_expr_node = {s: f"5:{130 + i}" for i, s in enumerate(STATES)}

MOUTH_AB, MOUTH_SM, MOUTH_IN_EXPR = "6:2", "6:5", "6:6"
MOUTH_OPEN, MOUTH_OPEN_PATH, MOUTH_SMILE, MOUTH_FROWN, MOUTH_LINE, MOUTH_O = "6:20", "6:21", "6:22", "6:23", "6:24", "6:25"
MOUTH_OPEN_ANIM = "6:30"
mouth_expr_anim = {s: f"6:{100 + i}" for i, s in enumerate(STATES)}
mouth_expr_node = {s: f"6:{130 + i}" for i, s in enumerate(STATES)}

INK = "FF1A1A1A"
EASE = '<CubicEaseInterpolator x1="0.25" y1="0.1" x2="0.25" y2="1"/>'


# --- small builders ----------------------------------------------------------------------------
def bind(source, key, converter=None):
    conv = f' converterId="{converter}"' if converter else ""
    return f'<DataBindContext sourcePathIds="{VM}-{source}" propertyKey="{key}"{conv}/>'


def kf(value, frame, ease=True):
    if ease:
        return f'<KeyFrameDouble value="{value}" frame="{frame}" interpolationType="cubic">{EASE}</KeyFrameDouble>'
    return f'<KeyFrameDouble value="{value}" frame="{frame}"/>'


def keyed(objects):
    """objects: {objectId: {propertyKey: [(frame, value)] | value}} -> KeyedObject xml."""
    out = []
    for obj, props in objects.items():
        kp = []
        for key, frames in props.items():
            if not isinstance(frames, list):
                frames = [(0, frames)]
            body = "\n".join("        " + kf(v, f, ease=len(frames) > 1) for f, v in frames)
            kp.append(f'    <KeyedProperty propertyKey="{key}">\n{body}\n    </KeyedProperty>')
        out.append(f'<KeyedObject objectId="{obj}">\n' + "\n".join(kp) + '\n</KeyedObject>')
    return "\n".join(out)


def callback_keyed(objects, frame=0):
    return "\n".join(
        f'<KeyedObject objectId="{o}">\n    <KeyedProperty propertyKey="{NESTED_FIRE}">\n'
        f'        <KeyFrameCallback frame="{frame}"/>\n    </KeyedProperty>\n</KeyedObject>' for o in objects)


def animation(name, aid, duration, objects, loop="oneShot", callbacks=()):
    body = keyed(objects) + ("\n" + callback_keyed(callbacks) if callbacks else "")
    return (f'<LinearAnimation fps="60" duration="{duration}" loopValue="{loop}" name="{name}" id="{aid}">\n'
            + indent(body, "    ") + '\n</LinearAnimation>')


def anim_state(aid, nid, i, extra=""):
    return f'<AnimationState x="200" y="{40 + 60 * i}" animationId="{aid}"{extra} id="{nid}"/>'


def layer_frame(name, lid, entry_to, any_transitions, states):
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


def vm_enum_transition(to_id, enum_value_id, prop=VM_STATE):
    return f'''<StateTransition stateToId="{to_id}" duration="160">
    <TransitionViewModelCondition opValue="equal">
        <TransitionPropertyViewModelComparator>
            <BindablePropertyEnum>
                {bind(prop, BIND_ENUM)}
            </BindablePropertyEnum>
        </TransitionPropertyViewModelComparator>
        <TransitionValueEnumComparator value="{enum_value_id}"/>
    </TransitionViewModelCondition>
</StateTransition>'''


def vm_trigger_transition(to_id, prop):
    return f'''<StateTransition stateToId="{to_id}">
    <TransitionViewModelCondition opValue="equal">
        <TransitionPropertyViewModelComparator>
            <BindablePropertyTrigger>
                {bind(prop, BIND_TRIGGER)}
            </BindablePropertyTrigger>
        </TransitionPropertyViewModelComparator>
        <TransitionValueTriggerComparator/>
    </TransitionViewModelCondition>
</StateTransition>'''


def vm_bool_transition(to_id, prop, value, duration=120):
    return f'''<StateTransition stateToId="{to_id}" duration="{duration}">
    <TransitionViewModelCondition opValue="equal">
        <TransitionPropertyViewModelComparator>
            <BindablePropertyBoolean>
                {bind(prop, BIND_BOOL)}
            </BindablePropertyBoolean>
        </TransitionPropertyViewModelComparator>
        <TransitionValueBooleanComparator value="{value}"/>
    </TransitionViewModelCondition>
</StateTransition>'''


def input_transition(to_id, input_id, value, duration=160):
    return (f'<StateTransition stateToId="{to_id}" duration="{duration}">\n'
            f'    <TransitionNumberCondition inputId="{input_id}" opValue="equal" value="{value}"/>\n'
            f'</StateTransition>')


def exit_transition(to_id, duration=0):
    return (f'<StateTransition stateToId="{to_id}" duration="{duration}" enableExitTime="true" '
            f'exitTimeIsPercetange="true" exitTime="100"/>')


def weighted(transition, weight):
    return transition.replace("/>", f' randomWeight="{weight}"/>')


def expression_layer(name, lid, input_id, anim_ids, node_ids):
    """The per-component expression layer: AnyState -> Expr_i on `expr == i`."""
    trans = "\n".join(input_transition(node_ids[s], input_id, EXPR[s]) for s in STATES)
    states = "\n".join(anim_state(anim_ids[s], node_ids[s], i) for i, s in enumerate(STATES))
    return layer_frame(name, lid, node_ids["idle"], trans, states)


def fill(color):
    return f'<Fill name="Fill"><SolidColor colorValue="{color}" name="Color"/></Fill>'


def rrect(w, h, r):
    return (f'<Rectangle width="{w}" height="{h}" linkCornerRadius="true" cornerRadiusTL="{r}" '
            f'cornerRadiusTR="{r}" cornerRadiusBL="{r}" cornerRadiusBR="{r}" name="Path"/>')


# ================================================================================================
# Eye component. 120x120, centred. Sclera, pupil (gaze), upper + lower lids (expression + blink).
# ================================================================================================
def eye_component():
    # The lids are a clip: sclera and pupil are masked by an unpainted "aperture" ellipse, so the
    # eye opening is its height and its vertical offset. Colour-agnostic, unlike drawn lids.
    # Poses: aperture height/y, pupil scale, sclera scale, eye rotation.
    A_H, A_Y = (EYE_APERTURE_PATH, HEIGHT), (EYE_APERTURE, Y)
    REST = {A_H: 70, A_Y: 0, (EYE_PUPIL, SX): 1, (EYE_PUPIL, SY): 1,
            (EYE_ROOT, ROT): 0, (EYE_SCLERA, SX): 1, (EYE_SCLERA, SY): 1}
    poses = {
        "idle": {},
        "listening": {(EYE_SCLERA, SX): 1.1, (EYE_SCLERA, SY): 1.15, A_H: 80},
        "dragged": {A_H: 34},                                              # squint
        "thinking": {A_H: 48, A_Y: -6, (EYE_ROOT, ROT): rad(-6)},          # half-lidded, glancing
        "waitingInput": {(EYE_SCLERA, SX): 1.2, (EYE_SCLERA, SY): 1.25, A_H: 84, (EYE_PUPIL, SX): 0.75, (EYE_PUPIL, SY): 0.75},
        "speaking": {},
        "success": {A_H: 26, A_Y: -18},                                     # happy: a raised crescent
        "error": {A_H: 44, A_Y: 4, (EYE_ROOT, ROT): rad(14)},              # sad slant; mirrored eye reads as brows-in
        "sleeping": {A_H: 0},                                              # closed
        "loading": {(EYE_PUPIL, SX): 0.5, (EYE_PUPIL, SY): 0.5},
        "failed": {A_H: 22, (EYE_PUPIL, SX): 0.6, (EYE_PUPIL, SY): 0.6},
        "degraded": {A_H: 30},
    }
    expr_anims = []
    for s in STATES:
        vals = dict(REST)
        vals.update(poses[s])
        objs = {}
        for (obj, key), v in vals.items():
            objs.setdefault(obj, {})[key] = v
        expr_anims.append(animation("Expr" + s[0].upper() + s[1:], eye_expr_anim[s], 1, objs))

    look_x = animation("LookX", EYE_LOOKX, 60, {EYE_PUPIL: {X: [(0, -16), (60, 16)]}})
    look_y = animation("LookY", EYE_LOOKY, 60, {EYE_PUPIL: {Y: [(0, -12), (60, 12)]}})
    blink = animation("Blink", EYE_BLINK_ANIM, 10, {EYE_APERTURE_PATH: {HEIGHT: [(0, 70), (4, 2), (10, 70)]}})
    # Two idle waits of different lengths, chosen at random, so blinks do not tick like a clock.
    wait_a = animation("WaitA", EYE_WAIT_A, 150, {})
    wait_b = animation("WaitB", EYE_WAIT_B, 270, {})

    expr_layer = expression_layer("Expression", "4:10", EYE_IN_EXPR, eye_expr_anim, eye_expr_node)
    trigger_layer = layer_frame(
        "Blink", "4:11", EYE_BLINK_REST_NODE,
        f'<StateTransition stateToId="{EYE_BLINK_NODE}">\n    <TransitionTriggerCondition inputId="{EYE_IN_BLINK}"/>\n</StateTransition>',
        f'<AnimationState x="200" y="40" animationId="{EYE_WAIT_A}" id="{EYE_BLINK_REST_NODE}"/>\n'
        f'<AnimationState x="200" y="100" animationId="{EYE_BLINK_ANIM}" reset="true" id="{EYE_BLINK_NODE}">\n'
        f'    {exit_transition(EYE_BLINK_REST_NODE)}\n</AnimationState>')
    auto_layer = layer_frame(
        "AutoBlink", "4:12", EYE_AUTO_A, "",
        f'<AnimationState x="200" y="40" animationId="{EYE_WAIT_A}" random="true" id="{EYE_AUTO_A}">\n'
        f'    {exit_transition(EYE_AUTO_BLINK)}\n</AnimationState>\n'
        f'<AnimationState x="200" y="100" animationId="{EYE_WAIT_B}" id="{EYE_AUTO_B}">\n'
        f'    {exit_transition(EYE_AUTO_BLINK)}\n</AnimationState>\n'
        f'<AnimationState x="200" y="160" animationId="{EYE_BLINK_ANIM}" reset="true" random="true" id="{EYE_AUTO_BLINK}">\n'
        f'    {weighted(exit_transition(EYE_AUTO_A), 60)}\n'
        f'    {weighted(exit_transition(EYE_AUTO_B), 40)}\n</AnimationState>')

    return f'''<Artboard isComponent="true" defaultStateMachineId="{EYE_SM}" x="700" y="0" styleId="4:3" clip="false" width="120" height="120" name="Eye" id="{EYE_AB}">
    <LayoutComponentStyle name="Style" id="4:3"/>
    <Node x="60" y="60" name="EyeRoot" id="{EYE_ROOT}">
        <!-- Pupil over sclera (first child on top); both clipped by the unpainted aperture. -->
        <Shape x="0" y="2" name="Pupil" id="{EYE_PUPIL}">
            <Ellipse width="30" height="34" name="Path"/>
            {fill(INK)}
            <ClippingShape sourceId="{EYE_APERTURE}" name="Lids"/>
        </Shape>
        <Shape x="0" y="0" name="Sclera" id="{EYE_SCLERA}">
            <Ellipse width="60" height="66" name="Path"/>
            {fill("FFFFFFFF")}
            <ClippingShape sourceId="{EYE_APERTURE}" name="Lids"/>
        </Shape>
        <Shape x="0" y="0" name="Aperture" id="{EYE_APERTURE}">
            <Ellipse width="64" height="70" name="Path" id="{EYE_APERTURE_PATH}"/>
        </Shape>
    </Node>

{indent(chr(10).join(expr_anims + [look_x, look_y, blink, wait_a, wait_b]), "    ")}

    <StateMachine name="Eye" id="{EYE_SM}">
        <StateMachineNumber name="expr" id="{EYE_IN_EXPR}"/>
        <StateMachineTrigger name="blink" id="{EYE_IN_BLINK}"/>
{indent(expr_layer, "        ")}
{indent(trigger_layer, "        ")}
{indent(auto_layer, "        ")}
    </StateMachine>
</Artboard>
<ComponentAsset artboardId="{EYE_AB}" name="Eye"/>'''


# ================================================================================================
# Brow component. 80x40. One bar; expression keys y and rotation. Placed mirrored for the right.
# ================================================================================================
import os
import re

LIFTED = os.path.join(os.path.dirname(os.path.abspath(__file__)), "lifted")


def lifted(fragment, **first_line_attrs):
    """A fragment from native/rivdump/riv2rml.py, with its root element's attributes overridden.

    The fragments are rest-pose art lifted from the community expression-grid file
    (CC BY 4.0, erdemediz - credit it wherever the mascot ships). Colours are normalised to INK.
    """
    text = open(os.path.join(LIFTED, fragment), encoding="utf-8").read().strip()
    first, rest = text.split("\n", 1)
    for k, v in first_line_attrs.items():
        first = re.sub(rf'\s{k}="[^"]*"', "", first)
        first = first.replace(">", f' {k}="{v}">', 1) if not first.endswith("/>") else first[:-2] + f' {k}="{v}"/>'
    text = first + "\n" + rest
    return re.sub(r'colorValue="FF(440E1E|721733)"', f'colorValue="{INK}"', text)


def brow_component():
    # The lifted brow's neutral rises toward the centre ("worried"); the rest angle flattens it and
    # every pose's rotation is relative to that.
    BASE_ROT = rad(26)
    REST = {Y: 32, ROT: 0}
    poses = {
        "idle": {}, "listening": {Y: 22}, "dragged": {Y: 36, ROT: rad(-8)}, "thinking": {Y: 24, ROT: rad(-14)},
        "waitingInput": {Y: 20}, "speaking": {}, "success": {Y: 26, ROT: rad(-4)}, "error": {Y: 36, ROT: rad(-16)},
        "sleeping": {Y: 40}, "loading": {}, "failed": {Y: 38, ROT: rad(-14)}, "degraded": {Y: 34, ROT: rad(8)},
    }
    anims = []
    for s in STATES:
        vals = dict(REST)
        vals.update(poses[s])
        vals[ROT] = round(vals[ROT] + BASE_ROT, 5)
        anims.append(animation("Expr" + s[0].upper() + s[1:], brow_expr_anim[s], 1, {BROW_BAR: vals}))
    layer = expression_layer("Expression", "5:10", BROW_IN_EXPR, brow_expr_anim, brow_expr_node)
    # The lifted brow is ~83x46 at the board's centre; it keeps its own id so the poses key it.
    brow = lifted("brow.rml.txt", x="50", y="32", id=BROW_BAR, name="Brow")
    return f'''<Artboard isComponent="true" defaultStateMachineId="{BROW_SM}" x="900" y="0" styleId="5:3" clip="false" width="100" height="60" name="Brow" id="{BROW_AB}">
    <LayoutComponentStyle name="Style" id="5:3"/>
{indent(brow, "    ")}
{indent(chr(10).join(anims), "    ")}
    <StateMachine name="Brow" id="{BROW_SM}">
        <StateMachineNumber name="expr" id="{BROW_IN_EXPR}"/>
{indent(layer, "        ")}
    </StateMachine>
</Artboard>
<ComponentAsset artboardId="{BROW_AB}" name="Brow"/>'''


# ================================================================================================
# Mouth component. 160x80. Static glyphs picked by expression; an open mouth scrubbed by data.
# ================================================================================================
def mouth_component():
    GLYPHS = [MOUTH_SMILE, MOUTH_FROWN, MOUTH_LINE, MOUTH_O]
    REST = {g: {OPACITY: 0} for g in GLYPHS}
    REST[MOUTH_OPEN] = {OPACITY: 0}
    show = {
        "idle": [], "listening": [MOUTH_LINE], "dragged": [MOUTH_O], "thinking": [MOUTH_LINE],
        "waitingInput": [MOUTH_O], "speaking": [MOUTH_OPEN], "success": [MOUTH_SMILE], "error": [MOUTH_FROWN],
        "sleeping": [], "loading": [], "failed": [MOUTH_FROWN], "degraded": [MOUTH_LINE],
    }
    anims = []
    for s in STATES:
        objs = {o: dict(p) for o, p in REST.items()}
        for g in show[s]:
            objs[g] = {OPACITY: 1}
        anims.append(animation("Expr" + s[0].upper() + s[1:], mouth_expr_anim[s], 1, objs))
    # Open: scrubbed 0..1 by mouthOpen. The lifted mouth (lips, teeth, tongue) sits in a node whose
    # scale is the opening; keying scale rather than a path keeps the lifted art untouched.
    open_anim = animation("Open", MOUTH_OPEN_ANIM, 60, {MOUTH_OPEN: {SY: [(0, 0.08), (60, 0.42)], SX: [(0, 0.34), (60, 0.42)]}})
    layer = expression_layer("Expression", "6:10", MOUTH_IN_EXPR, mouth_expr_anim, mouth_expr_node)
    arc = lambda name, sid, flip: f'''<Shape x="80" y="{36 if not flip else 44}" opacity="0" name="{name}" id="{sid}">
        <PointsPath isClosed="false" name="Path">
            <StraightVertex x="-26" y="{-8 if not flip else 8}"/>
            <CubicMirroredVertex x="0" y="{10 if not flip else -10}" rotation="0" distance="16"/>
            <StraightVertex x="26" y="{-8 if not flip else 8}"/>
        </PointsPath>
        <Stroke thickness="9" cap="round" join="round" name="Stroke"><SolidColor colorValue="{INK}" name="Color"/></Stroke>
    </Shape>'''
    return f'''<Artboard isComponent="true" defaultStateMachineId="{MOUTH_SM}" x="1100" y="0" styleId="6:3" clip="false" width="160" height="80" name="Mouth" id="{MOUTH_AB}">
    <LayoutComponentStyle name="Style" id="6:3"/>
    <Node x="80" y="40" scaleX="0.34" scaleY="0.08" opacity="0" name="Open" id="{MOUTH_OPEN}">
{indent(lifted("mouth.rml.txt", x="0", y="0", name="LiftedMouth"), "        ")}
    </Node>
    {arc("Smile", MOUTH_SMILE, False)}
    {arc("Frown", MOUTH_FROWN, True)}
    <Shape x="80" y="40" opacity="0" name="Line" id="{MOUTH_LINE}">
        {rrect(44, 8, 4)}
        {fill(INK)}
    </Shape>
    <Shape x="80" y="40" opacity="0" name="O" id="{MOUTH_O}">
        <Ellipse width="26" height="30" name="Path"/>
        {fill(INK)}
    </Shape>
{indent(chr(10).join(anims + [open_anim]), "    ")}
    <StateMachine name="Mouth" id="{MOUTH_SM}">
        <StateMachineNumber name="expr" id="{MOUTH_IN_EXPR}"/>
{indent(layer, "        ")}
    </StateMachine>
</Artboard>
<ComponentAsset artboardId="{MOUTH_AB}" name="Mouth"/>'''


# ================================================================================================
# Root: body (glossy), face placements, view model, layers.
# ================================================================================================
def body():
    inner = '<Ellipse width="300" height="300" name="Path"/>'
    # The outer node places the body; the inner one is what animations key, so its rest is the
    # origin and scale pivots on the body centre.
    bound = lambda: f'<SolidColor colorValue="FF1E7BF0" name="Color">\n                {bind(VM_COLOR, COLOR)}\n            </SolidColor>'
    # After the mood-orb reference: a soft body (feathered edge stroke in the body colour), a
    # thin glass ring just outside it, and a wide soft halo. Feather only works on strokes, so
    # every soft element here is a stroke; the fill stays crisp underneath its feathered edge.
    return f'''<Node x="250" y="270" name="BodyPlacement">
<Node x="0" y="0" name="Body" id="{BODY_NODE}">
    <Shape scaleX="1.09" scaleY="1.09" name="GlassRing">
        {inner}
        <Stroke thickness="3" name="Stroke">
            <SolidColor colorValue="8CFFFFFF" name="Color"/>
            <Feather strength="4" name="Feather"/>
        </Stroke>
    </Shape>
    <Shape name="SoftEdge">
        {inner}
        <Stroke thickness="26" name="Stroke">
            {bound()}
            <Feather strength="22" name="Feather"/>
        </Stroke>
    </Shape>
    <Shape name="Gloss">
        {inner}
        <Fill name="Fill">
            <RadialGradient startX="-55" startY="-75" endX="60" endY="30" name="Gradient">
                <GradientStop colorValue="B8FFFFFF" position="0"/>
                <GradientStop colorValue="00FFFFFF" position="1"/>
            </RadialGradient>
        </Fill>
    </Shape>
    <Shape name="Shade">
        {inner}
        <Fill name="Fill">
            <RadialGradient startX="-30" startY="-50" endX="150" endY="130" name="Gradient">
                <GradientStop colorValue="00000000" position="0.45"/>
                <GradientStop colorValue="55000000" position="1"/>
            </RadialGradient>
        </Fill>
    </Shape>
    <Shape name="Fill" id="{HITBOX}">
        {inner}
        <Fill name="Fill">
            <SolidColor colorValue="FF1E7BF0" name="Color">
                {bind(VM_COLOR, COLOR)}
            </SolidColor>
        </Fill>
    </Shape>
    <Shape scaleX="1.12" scaleY="1.12" opacity="0.45" name="Halo">
        {inner}
        <Stroke thickness="70" name="Stroke">
            {bound()}
            <Feather strength="60" name="Feather"/>
        </Stroke>
    </Shape>
</Node>
</Node>'''


def face():
    # NestedArtboard x/y place the child's top-left. Eye is 120 square at scale 1.3 (156 px), so
    # its centre sits 78 px in; the brow bar is at (40,20) of an 80x40 board.
    def eye(name, sid, expr_id, blink_id, x):
        return f'''<NestedArtboard artboardId="{EYE_AB}" x="{x}" y="-102" scaleX="1.2" scaleY="1.2" name="{name}" id="{sid}">
    <NestedStateMachine animationId="{EYE_SM}" name="SM">
        <NestedNumber inputId="{EYE_IN_EXPR}" nestedValue="0" name="expr" id="{expr_id}"/>
        <NestedTrigger inputId="{EYE_IN_BLINK}" name="blink" id="{blink_id}"/>
    </NestedStateMachine>
    <NestedRemapAnimation animationId="{EYE_LOOKX}" time="0.5" name="LookX">
        {bind(VM_LOOKX, REMAP_TIME, CONV_LOOK)}
    </NestedRemapAnimation>
    <NestedRemapAnimation animationId="{EYE_LOOKY}" time="0.5" name="LookY">
        {bind(VM_LOOKY, REMAP_TIME, CONV_LOOK)}
    </NestedRemapAnimation>
</NestedArtboard>'''

    def brow(name, sid, expr_id, x, mirror):
        return f'''<NestedArtboard artboardId="{BROW_AB}" x="{x}" y="-134" scaleX="{-1 if mirror else 1}" name="{name}" id="{sid}">
    <NestedStateMachine animationId="{BROW_SM}" name="SM">
        <NestedNumber inputId="{BROW_IN_EXPR}" nestedValue="0" name="expr" id="{expr_id}"/>
    </NestedStateMachine>
</NestedArtboard>'''

    return f'''<Node x="250" y="262" name="FacePlacement">
<Node x="0" y="0" name="Face" id="{FACE}">
{indent(eye("EyeLeft", EYE_L, EYE_L_EXPR, EYE_L_BLINK, -122), "    ")}
{indent(eye("EyeRight", EYE_R, EYE_R_EXPR, EYE_R_BLINK, -22), "    ")}
{indent(brow("BrowLeft", BROW_L, BROW_L_EXPR, -94, False), "    ")}
{indent(brow("BrowRight", BROW_R, BROW_R_EXPR, 94, True), "    ")}
    <NestedArtboard artboardId="{MOUTH_AB}" x="-80" y="8" name="Mouth" id="{MOUTH}">
        <NestedStateMachine animationId="{MOUTH_SM}" name="SM">
            <NestedNumber inputId="{MOUTH_IN_EXPR}" nestedValue="0" name="expr" id="{MOUTH_EXPR}"/>
        </NestedStateMachine>
        <NestedRemapAnimation animationId="{MOUTH_OPEN_ANIM}" time="0" name="Open">
            {bind(VM_MOUTH, REMAP_TIME, CONV_MOUTH)}
        </NestedRemapAnimation>
    </NestedArtboard>
</Node>
</Node>'''


def root_state_animations():
    """Per state: forward the expression index to every component, and move the body/face."""
    BODY_REST = {BODY_NODE: {SX: 1, SY: 1, X: 0, Y: 0, OPACITY: 1}, FACE: {X: 0, Y: 0}}
    motion = {
        "idle": ({}, 1, "oneShot"),
        "listening": ({BODY_NODE: {SX: 1.04, SY: 1.04}, FACE: {Y: -6}}, 1, "oneShot"),
        "dragged": ({BODY_NODE: {SX: [(0, 1.08), (20, 1.05), (40, 1.08)], SY: [(0, 0.92), (20, 0.95), (40, 0.92)]}}, 40, "loop"),
        "thinking": ({BODY_NODE: {X: [(0, -4), (60, 4), (120, -4)]}, FACE: {X: [(0, 6), (60, 10), (120, 6)], Y: -4}}, 120, "loop"),
        "waitingInput": ({BODY_NODE: {Y: [(0, 0), (24, -8), (48, 0)]}}, 48, "loop"),
        "speaking": ({}, 1, "oneShot"),
        "success": ({BODY_NODE: {Y: [(0, 0), (10, -18), (24, 0)], SX: [(0, 1), (10, 1.06), (24, 1)], SY: [(0, 1), (10, 1.06), (24, 1)]}}, 24, "oneShot"),
        "error": ({BODY_NODE: {Y: [(0, 0), (8, 6), (30, 6)], X: [(0, 0), (4, -5), (8, 5), (12, -4), (16, 0)]}, FACE: {Y: 4}}, 30, "oneShot"),
        "sleeping": ({BODY_NODE: {OPACITY: 0.85}, FACE: {Y: 8}}, 1, "oneShot"),
        "loading": ({BODY_NODE: {OPACITY: [(0, 0.7), (36, 1.0), (72, 0.7)]}}, 72, "loop"),
        "failed": ({BODY_NODE: {OPACITY: 0.6}}, 1, "oneShot"),
        "degraded": ({BODY_NODE: {OPACITY: 0.8}}, 1, "oneShot"),
    }
    out = []
    for s in STATES:
        overrides, duration, loop = motion[s]
        objs = {o: dict(p) for o, p in BODY_REST.items()}
        for o, p in overrides.items():
            objs.setdefault(o, {}).update(p)
        for nested in (EYE_L_EXPR, EYE_R_EXPR, BROW_L_EXPR, BROW_R_EXPR, MOUTH_EXPR):
            objs[nested] = {NESTED_VALUE: EXPR[s]}
        out.append(animation("State" + s[0].upper() + s[1:], root_state_anim[s], duration, objs, loop))
    return out


def root_machine():
    expr_trans = "\n".join(vm_enum_transition(root_state_node[s], state_enum_ids[s]) for s in STATES)
    expr_states = "\n".join(anim_state(root_state_anim[s], root_state_node[s], i) for i, s in enumerate(STATES))
    expression = layer_frame("Expression", "3:1", root_state_node["idle"], expr_trans, expr_states)

    breath = layer_frame("Breath", "3:2", BREATH_NODE, "", anim_state(BREATH_ANIM, BREATH_NODE, 0))

    blink = layer_frame(
        "Blink", "3:3", BLINK_REST_NODE, vm_trigger_transition(BLINK_NODE, VM_BLINK),
        f'<AnimationState x="200" y="40" animationId="{BLINK_REST_ANIM}" id="{BLINK_REST_NODE}"/>\n'
        f'<AnimationState x="200" y="100" animationId="{BLINK_ANIM}" reset="true" id="{BLINK_NODE}">\n'
        f'    {exit_transition(BLINK_REST_NODE)}\n</AnimationState>')

    hover = layer_frame(
        "Hover", "3:4", HOVER_REST_NODE,
        vm_bool_transition(HOVER_NODE, VM_HOVER, "true") + "\n" + vm_bool_transition(HOVER_REST_NODE, VM_HOVER, "false"),
        f'<AnimationState x="200" y="40" animationId="{HOVER_REST_ANIM}" id="{HOVER_REST_NODE}"/>\n'
        f'<AnimationState x="200" y="100" animationId="{HOVER_ANIM}" id="{HOVER_NODE}"/>')

    listeners = f'''<StateMachineListenerSingle targetId="{HITBOX}" listenerTypeValue="enter" name="HoverIn">
    <ListenerViewModelChange>
        <BindablePropertyBoolean propertyValue="true">
            {bind(VM_HOVER, BIND_BOOL).replace("/>", ' direction="true"/>')}
        </BindablePropertyBoolean>
    </ListenerViewModelChange>
</StateMachineListenerSingle>
<StateMachineListenerSingle targetId="{HITBOX}" listenerTypeValue="exit" name="HoverOut">
    <ListenerViewModelChange>
        <BindablePropertyBoolean propertyValue="false">
            {bind(VM_HOVER, BIND_BOOL).replace("/>", ' direction="true"/>')}
        </BindablePropertyBoolean>
    </ListenerViewModelChange>
</StateMachineListenerSingle>'''

    return f'''<StateMachine name="Avatar" id="{SM}">
{indent(expression, "    ")}
{indent(breath, "    ")}
{indent(blink, "    ")}
{indent(hover, "    ")}
{indent(listeners, "    ")}
</StateMachine>'''


def root_artboard():
    state_anims = root_state_animations()
    # Breath keys only the Body node's scale; it is on its own layer, so the later layer wins where
    # a state also keys scale (dragged, success), which is the intent.
    breath = animation("Breath", BREATH_ANIM, 180, {BODY_NODE: {SX: [(0, 1.0), (180, 1.02)], SY: [(0, 1.0), (180, 1.02)]}}, "pingPong")
    blink_rest = animation("BlinkRest", BLINK_REST_ANIM, 1, {})
    blink = animation("BlinkFire", BLINK_ANIM, 2, {}, callbacks=(EYE_L_BLINK, EYE_R_BLINK))
    hover_rest = animation("HoverRest", HOVER_REST_ANIM, 1, {FACE: {ROT: 0}})
    hover = animation("HoverWiggle", HOVER_ANIM, 40, {FACE: {ROT: [(0, 0), (10, rad(6)), (30, rad(-6)), (40, 0)]}}, "loop")

    return f'''<Artboard defaultStateMachineId="{SM}" viewModelId="{VM}" viewModelInstanceId="{VM_INSTANCE}"
          x="0" y="0" styleId="0:3" clip="false" width="500" height="500" name="Mascot" id="{ROOT}">
    <LayoutComponentStyle name="Style" id="0:3"/>
{indent(face(), "    ")}
{indent(body(), "    ")}

{indent(chr(10).join(state_anims + [breath, blink_rest, blink, hover_rest, hover]), "    ")}

{indent(root_machine(), "    ")}
</Artboard>'''


def data():
    states = "\n".join(f'    <DataEnumValue key="{s}" value="{s[0].upper() + s[1:]}" id="{state_enum_ids[s]}"/>' for s in STATES)
    shapes = "\n".join(f'    <DataEnumValue key="{s}" value="{s[0].upper() + s[1:]}" id="{shape_enum_ids[s]}"/>' for s in SHAPES)
    return f'''<DataEnumCustom name="AvatarState" id="{ENUM_STATE}">
{states}
</DataEnumCustom>
<!-- Declared so the host's identity write stays legal; v2 has one body and ignores it for now. -->
<DataEnumCustom name="MascotShape" id="{ENUM_SHAPE}">
{shapes}
</DataEnumCustom>

<!-- -1..1 gaze -> 0..1 timeline fraction; 0..1 amplitude -> 0..1 fraction, clamped. -->
<DataConverterRangeMapper minInput="-1" maxInput="1" minOutput="0" maxOutput="1"
                          clampLower="true" clampUpper="true" name="LookToTime" id="{CONV_LOOK}"/>
<DataConverterRangeMapper minInput="0" maxInput="1" minOutput="0" maxOutput="1"
                          clampLower="true" clampUpper="true" name="MouthToTime" id="{CONV_MOUTH}"/>

<ViewModel defaultInstanceId="{VM_INSTANCE}" name="Avatar" id="{VM}">
    <ViewModelPropertyEnumCustom enumId="{ENUM_STATE}" name="state" id="{VM_STATE}"/>
    <ViewModelPropertyNumber name="mouthOpen" id="{VM_MOUTH}"/>
    <ViewModelPropertyNumber name="lookX" id="{VM_LOOKX}"/>
    <ViewModelPropertyNumber name="lookY" id="{VM_LOOKY}"/>
    <ViewModelPropertyTrigger name="blink" id="{VM_BLINK}"/>
    <ViewModelPropertyEnumCustom enumId="{ENUM_SHAPE}" name="shape" id="{VM_SHAPE}"/>
    <ViewModelPropertyColor name="color" id="{VM_COLOR}"/>
    <ViewModelPropertyBoolean name="hovered" id="{VM_HOVER}"/>

    <ViewModelInstance exports="true" name="Default" id="{VM_INSTANCE}">
        <ViewModelInstanceEnum propertyValue="{state_enum_ids['idle']}" viewModelPropertyId="{VM_STATE}"/>
        <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_MOUTH}"/>
        <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_LOOKX}"/>
        <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_LOOKY}"/>
        <ViewModelInstanceTrigger viewModelPropertyId="{VM_BLINK}"/>
        <ViewModelInstanceEnum propertyValue="{shape_enum_ids['circle']}" viewModelPropertyId="{VM_SHAPE}"/>
        <ViewModelInstanceColor propertyValue="FF1E7BF0" viewModelPropertyId="{VM_COLOR}"/>
        <ViewModelInstanceBoolean propertyValue="false" viewModelPropertyId="{VM_HOVER}"/>
    </ViewModelInstance>
</ViewModel>'''


doc = f'''<Rive version="1" kind="fragment">
    <!--
        The Letta agent mascot, v2. GENERATED by gen_scene.py from the spec in MASCOT.md; regenerate
        rather than hand-edit the exhaustive parts, hand-edit the art freely.

        Inputs the host writes on the Avatar view model (the contract with RiveAvatarContract.kt):
          state     - the director's arbitrated AvatarState, as an enum key
          mouthOpen - 0..1 speech amplitude -> scrubs the Mouth component's Open timeline
          lookX/Y   - -1..1 gaze -> scrubs each Eye component's LookX/LookY timelines
          blink     - fire-once -> forwarded to both eyes; the eyes also blink on their own
          color     - identity; body and glow are bound to it
          shape     - identity; accepted, not yet drawn (one body in v2)
          hovered   - written by the file's own pointer listeners, read by the Hover layer

        The face is three component artboards. Components are driven by inputs, never by the view
        model: a view-model-driven machine inside a nested artboard silently never fires.
    -->
{indent(root_artboard(), "    ")}

{indent(eye_component(), "    ")}

{indent(brow_component(), "    ")}

{indent(mouth_component(), "    ")}

{indent(data(), "    ")}
</Rive>
'''

if __name__ == "__main__":
    import os
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scene.rml")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(doc)
    print(f"wrote {out} ({doc.count(chr(10))} lines)")
