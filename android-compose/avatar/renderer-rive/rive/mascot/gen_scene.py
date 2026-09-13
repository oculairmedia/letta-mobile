"""Generator for rive/mascot/scene.rml - the v2 rig (see MASCOT.md).

One character, built the way the reference files are: the root artboard owns the body and the
view model; the face is three component artboards (Eye x2, Brow x2, Mouth) driven from the root
through nested inputs and scrubbed timelines. Everything the host writes stays on the root's
view model - the components are internal and never see it.

Why generated: twelve expressions x five components x every keyed property must be exhaustive or
states leak into each other.

The project is pushed to the Rive workspace (rive.yaml carries the mapping; see ARTIST.md).
Regenerating and pushing again is fine while nobody has edited the file in the editor - ids here
are stable, so a push updates objects in place. Once the artist has started, stop pushing from
the CLI: the editor file is the source of truth and a push would overwrite their work.

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
# Rive convention: the enum carries only SUSTAINED states. success is a trigger the machine plays
# and returns from; dragged is a boolean the drag listeners (and the host) write.
MOMENTARY = ["success", "dragged"]
SUSTAINED = [s for s in STATES if s not in MOMENTARY]

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
VM_SUCCESS, VM_ERROR, VM_DRAGGED = "1:9", "1:10", "1:11"
VM_INSTANCE, ENUM_STATE, ENUM_SHAPE = "1:20", "1:100", "1:200"
state_enum_ids = {s: f"1:{101 + i}" for i, s in enumerate(STATES)}
SHAPES = ["circle", "blob", "roundedSquare", "pill", "triangle", "hexagon", "cloud", "drop"]
shape_enum_ids = {s: f"1:{201 + i}" for i, s in enumerate(SHAPES)}

CONV_LOOK, CONV_MOUTH = "2:1", "2:2"

ROOT, BODY_NODE, FACE, HITBOX = "0:2", "0:100", "0:200", "0:90"
EYES, BROWS, MOUTH = "0:210", "0:230", "0:250"
# nested-input / remap ids per placement
EYES_EXPR, BROWS_EXPR, MOUTH_EXPR = "0:211", "0:231", "0:251"
EYES_BLINK = "0:212"

SM = "3:5"
root_state_anim = {s: f"3:{100 + i}" for i, s in enumerate(STATES)}
root_state_node = {s: f"3:{130 + i}" for i, s in enumerate(STATES)}
BREATH_ANIM, BREATH_NODE = "3:160", "3:161"
FLASH_REST_ANIM, FLASH_REST_NODE, SUCCESS_ANIM, SUCCESS_NODE, ERROR_ANIM, ERROR_NODE = "3:190", "3:191", "3:192", "3:193", "3:194", "3:195"
DRAG_REST_ANIM, DRAG_REST_NODE, DRAG_ANIM, DRAG_NODE = "3:200", "3:201", "3:202", "3:203"
IDLE_WAIT_A, IDLE_WAIT_B, IDLE_GLANCE_ANIM, IDLE_A_NODE, IDLE_B_NODE, IDLE_GLANCE_NODE = "3:210", "3:211", "3:212", "3:213", "3:214", "3:215"
BLINK_ANIM, BLINK_REST_ANIM, BLINK_NODE, BLINK_REST_NODE = "3:170", "3:171", "3:172", "3:173"
HOVER_ANIM, HOVER_REST_ANIM, HOVER_NODE, HOVER_REST_NODE = "3:180", "3:181", "3:182", "3:183"

EYE_AB, EYE_SM, EYE_IN_EXPR, EYE_IN_BLINK = "4:2", "4:5", "4:6", "4:7"
# Both eyes live in ONE component so blink and gaze are shared; L/R ids per side.
EYE_L_PUPIL, EYE_L_SCLERA, EYE_L_ROOT, EYE_L_APERTURE, EYE_L_APERTURE_PATH = "4:20", "4:23", "4:24", "4:25", "4:26"
EYE_R_PUPIL, EYE_R_SCLERA, EYE_R_ROOT, EYE_R_APERTURE, EYE_R_APERTURE_PATH = "4:40", "4:43", "4:44", "4:45", "4:46"
EYE_LOOKX, EYE_LOOKY, EYE_BLINK_ANIM = "4:30", "4:31", "4:32"
EYE_WAIT_A, EYE_WAIT_B = "4:33", "4:34"
eye_expr_anim = {s: f"4:{100 + i}" for i, s in enumerate(STATES)}
eye_expr_node = {s: f"4:{130 + i}" for i, s in enumerate(STATES)}
EYE_BLINK_NODE, EYE_BLINK_REST_NODE = "4:160", "4:161"
EYE_AUTO_A, EYE_AUTO_B, EYE_AUTO_BLINK = "4:162", "4:163", "4:164"

BROW_AB, BROW_SM, BROW_IN_EXPR, BROW_L_SHAPE, BROW_R_SHAPE = "5:2", "5:5", "5:6", "5:20", "5:21"
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
def eyes_component():
    """Both eyes in one component: one expression input, one blink trigger, one auto-blink,
    one pair of gaze timelines. Two nested single eyes blinked out of sync - each ran its own
    random waits - which is the tell of a rig that was assembled, not designed."""
    # The lids are a clip: sclera and pupil are masked by an unpainted "aperture" ellipse, so the
    # eye opening is its height and its vertical offset. Colour-agnostic, unlike drawn lids.
    SIDES = {
        "L": (EYE_L_PUPIL, EYE_L_SCLERA, EYE_L_ROOT, EYE_L_APERTURE, EYE_L_APERTURE_PATH, -50),
        "R": (EYE_R_PUPIL, EYE_R_SCLERA, EYE_R_ROOT, EYE_R_APERTURE, EYE_R_APERTURE_PATH, 50),
    }
    # Pose fields: aperture height/y, pupil scale, sclera scale, eye rotation (mirrored on R).
    REST = {"ah": 70, "ay": 0, "ps": 1, "ss": (1, 1), "rot": 0}
    poses = {
        "idle": {},
        "listening": {"ss": (1.1, 1.15), "ah": 80},
        "dragged": {"ah": 34},
        "thinking": {"ah": 48, "ay": -6, "rot": rad(-6)},
        "waitingInput": {"ss": (1.2, 1.25), "ah": 84, "ps": 0.75},
        "speaking": {},
        "success": {"ah": 26, "ay": -18},
        "error": {"ah": 44, "ay": 4, "rot": rad(14)},
        "sleeping": {"ah": 0},
        "loading": {"ps": 0.5},
        "failed": {"ah": 22, "ps": 0.6},
        "degraded": {"ah": 30},
    }
    # Per-side overrides for the asymmetric looks.
    side_overrides = {"thinking": {"R": {"ah": 36}}, "degraded": {"L": {"ah": 2}}}

    expr_anims = []
    for st in STATES:
        objs = {}
        for side, (pupil, sclera, root, aperture, path, _) in SIDES.items():
            vals = dict(REST); vals.update(poses[st]); vals.update(side_overrides.get(st, {}).get(side, {}))
            sign = 1 if side == "L" else -1
            objs[path] = {HEIGHT: vals["ah"]}
            objs[aperture] = {Y: vals["ay"]}
            objs[pupil] = {SX: vals["ps"], SY: vals["ps"]}
            objs[sclera] = {SX: vals["ss"][0], SY: vals["ss"][1]}
            objs[root] = {ROT: round(vals["rot"] * sign, 5)}
        expr_anims.append(animation("Expr" + st[0].upper() + st[1:], eye_expr_anim[st], 1, objs))

    look_x = animation("LookX", EYE_LOOKX, 60, {EYE_L_PUPIL: {X: [(0, -16), (60, 16)]}, EYE_R_PUPIL: {X: [(0, -16), (60, 16)]}})
    look_y = animation("LookY", EYE_LOOKY, 60, {EYE_L_PUPIL: {Y: [(0, -12), (60, 12)]}, EYE_R_PUPIL: {Y: [(0, -12), (60, 12)]}})
    blink = animation("Blink", EYE_BLINK_ANIM, 10, {EYE_L_APERTURE_PATH: {HEIGHT: [(0, 70), (4, 2), (10, 70)]},
                                                     EYE_R_APERTURE_PATH: {HEIGHT: [(0, 70), (4, 2), (10, 70)]}})
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

    def eye(name, pupil, sclera, root, aperture, path, x):
        return f'''<Node x="{x}" y="0" name="{name}" id="{root}">
    <!-- Pupil over sclera (first child on top); both clipped by the unpainted aperture. -->
    <Shape x="0" y="2" name="Pupil" id="{pupil}">
        <Ellipse width="30" height="34" name="Path"/>
        {fill(INK)}
        <ClippingShape sourceId="{aperture}" name="Lids"/>
    </Shape>
    <Shape x="0" y="0" name="Sclera" id="{sclera}">
        <Ellipse width="60" height="66" name="Path"/>
        {fill("FFFFFFFF")}
        <ClippingShape sourceId="{aperture}" name="Lids"/>
    </Shape>
    <Shape x="0" y="0" name="Aperture" id="{aperture}">
        <Ellipse width="64" height="70" name="Path" id="{path}"/>
    </Shape>
</Node>'''

    return f'''<Artboard isComponent="true" defaultStateMachineId="{EYE_SM}" x="700" y="0" styleId="4:3" clip="false" width="220" height="120" name="Eyes" id="{EYE_AB}">
    <LayoutComponentStyle name="Style" id="4:3"/>
    <Node x="110" y="60" name="EyesRoot">
{indent(eye("EyeLeft", *SIDES["L"]), "        ")}
{indent(eye("EyeRight", *SIDES["R"]), "        ")}
    </Node>

{indent(chr(10).join(expr_anims + [look_x, look_y, blink, wait_a, wait_b]), "    ")}

    <StateMachine name="Eyes" id="{EYE_SM}">
        <StateMachineNumber name="expr" id="{EYE_IN_EXPR}"/>
        <StateMachineTrigger name="blink" id="{EYE_IN_BLINK}"/>
{indent(expr_layer, "        ")}
{indent(trigger_layer, "        ")}
{indent(auto_layer, "        ")}
    </StateMachine>
</Artboard>
<ComponentAsset artboardId="{EYE_AB}" name="Eyes"/>'''


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


def brows_component():
    """Both brows in one component. The right brow is the same lifted shape under a mirrored node,
    so one pose table drives both and rotations mirror for free."""
    # The lifted brow's neutral rises toward the centre ("worried"); the rest angle flattens it and
    # every pose's rotation is relative to that.
    BASE_ROT = rad(26)
    REST = {Y: 0, ROT: 0}
    poses = {
        "idle": {}, "listening": {Y: -10}, "dragged": {Y: 4, ROT: rad(-8)}, "thinking": {Y: -8, ROT: rad(-14)},
        "waitingInput": {Y: -12}, "speaking": {}, "success": {Y: -6, ROT: rad(-4)}, "error": {Y: 4, ROT: rad(-16)},
        "sleeping": {Y: 8}, "loading": {}, "failed": {Y: 6, ROT: rad(-14)}, "degraded": {Y: 2, ROT: rad(8)},
    }
    anims = []
    for st in STATES:
        vals = dict(REST)
        vals.update(poses[st])
        vals[ROT] = round(vals[ROT] + BASE_ROT, 5)
        anims.append(animation("Expr" + st[0].upper() + st[1:], brow_expr_anim[st], 1, {BROW_L_SHAPE: dict(vals), BROW_R_SHAPE: dict(vals)}))
    layer = expression_layer("Expression", "5:10", BROW_IN_EXPR, brow_expr_anim, brow_expr_node)
    left = lifted("brow.rml.txt", x="0", y="0", id=BROW_L_SHAPE, name="BrowLeft")
    right = re.sub(r'id="7:(\d+)"', r'id="8:\1"', lifted("brow.rml.txt", x="0", y="0", id=BROW_R_SHAPE, name="BrowRight"))
    return f'''<Artboard isComponent="true" defaultStateMachineId="{BROW_SM}" x="1000" y="0" styleId="5:3" clip="false" width="220" height="80" name="Brows" id="{BROW_AB}">
    <LayoutComponentStyle name="Style" id="5:3"/>
    <Node x="110" y="40" name="BrowsRoot">
        <Node x="-48" y="0" name="Left">
{indent(left, "            ")}
        </Node>
        <Node x="48" y="0" scaleX="-1" name="Right">
{indent(right, "            ")}
        </Node>
    </Node>
{indent(chr(10).join(anims), "    ")}
    <StateMachine name="Brows" id="{BROW_SM}">
        <StateMachineNumber name="expr" id="{BROW_IN_EXPR}"/>
{indent(layer, "        ")}
    </StateMachine>
</Artboard>
<ComponentAsset artboardId="{BROW_AB}" name="Brows"/>'''


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
    return f'''<Artboard isComponent="true" defaultStateMachineId="{MOUTH_SM}" x="1300" y="0" styleId="6:3" clip="false" width="160" height="80" name="Mouth" id="{MOUTH_AB}">
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
    # NestedArtboard x/y place the child's top-left. Eyes is 220x120 at scale 1.2 (264x144): centre
    # offset (132,72), eye centres at +-60. Brows is 220x80 at scale 1: centre offset (110,40).
    return f'''<Node x="250" y="262" name="FacePlacement">
<Node x="0" y="0" name="Face" id="{FACE}">
    <NestedArtboard artboardId="{EYE_AB}" x="-132" y="-102" scaleX="1.2" scaleY="1.2" name="Eyes" id="{EYES}">
        <NestedStateMachine animationId="{EYE_SM}" name="SM">
            <NestedNumber inputId="{EYE_IN_EXPR}" nestedValue="0" name="expr" id="{EYES_EXPR}"/>
            <NestedTrigger inputId="{EYE_IN_BLINK}" name="blink" id="{EYES_BLINK}"/>
        </NestedStateMachine>
        <NestedRemapAnimation animationId="{EYE_LOOKX}" time="0.5" name="LookX">
            {bind(VM_LOOKX, REMAP_TIME, CONV_LOOK)}
        </NestedRemapAnimation>
        <NestedRemapAnimation animationId="{EYE_LOOKY}" time="0.5" name="LookY">
            {bind(VM_LOOKY, REMAP_TIME, CONV_LOOK)}
        </NestedRemapAnimation>
    </NestedArtboard>
    <NestedArtboard artboardId="{BROW_AB}" x="-110" y="-142" name="Brows" id="{BROWS}">
        <NestedStateMachine animationId="{BROW_SM}" name="SM">
            <NestedNumber inputId="{BROW_IN_EXPR}" nestedValue="0" name="expr" id="{BROWS_EXPR}"/>
        </NestedStateMachine>
    </NestedArtboard>
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
    """Per sustained state: forward the expression index to every component, and pose the body."""
    BODY_REST = {BODY_NODE: {SX: 1, SY: 1, X: 0, Y: 0, OPACITY: 1}, FACE: {X: 0, Y: 0}}
    motion = {
        "idle": ({}, 1, "oneShot"),
        "listening": ({BODY_NODE: {SX: 1.04, SY: 1.04}, FACE: {Y: -6}}, 1, "oneShot"),
        "thinking": ({BODY_NODE: {X: [(0, -4), (60, 4), (120, -4)]}, FACE: {X: [(0, 6), (60, 10), (120, 6)], Y: -4}}, 120, "loop"),
        "waitingInput": ({BODY_NODE: {Y: [(0, 0), (24, -8), (48, 0)]}}, 48, "loop"),
        "speaking": ({}, 1, "oneShot"),
        "error": ({BODY_NODE: {Y: 6}, FACE: {Y: 4}}, 1, "oneShot"),          # the settle; the flash is a trigger
        "sleeping": ({BODY_NODE: {OPACITY: 0.85}, FACE: {Y: 8}}, 1, "oneShot"),
        "loading": ({BODY_NODE: {OPACITY: [(0, 0.7), (36, 1.0), (72, 0.7)]}}, 72, "loop"),
        "failed": ({BODY_NODE: {OPACITY: 0.6}}, 1, "oneShot"),
        "degraded": ({BODY_NODE: {OPACITY: 0.8}}, 1, "oneShot"),
    }
    out = []
    for st in SUSTAINED:
        overrides, duration, loop = motion[st]
        objs = {o: dict(p) for o, p in BODY_REST.items()}
        for o, p in overrides.items():
            objs.setdefault(o, {}).update(p)
        for nested in (EYES_EXPR, BROWS_EXPR, MOUTH_EXPR):
            objs[nested] = {NESTED_VALUE: EXPR[st]}
        out.append(animation("State" + st[0].upper() + st[1:], root_state_anim[st], duration, objs, loop))
    return out


def momentary_animations():
    """Flashes and drag. These layers sit AFTER Expression, so while they are in their active state
    they win the body pose and the components' expression; their Rest animations key nothing, so
    the sustained layer shows through the moment they return."""
    exprs = lambda st: {n: {NESTED_VALUE: EXPR[st]} for n in (EYES_EXPR, BROWS_EXPR, MOUTH_EXPR)}
    success = animation("SuccessFlash", SUCCESS_ANIM, 48,
                        {BODY_NODE: {Y: [(0, 0), (10, -18), (24, 0), (48, 0)], SX: [(0, 1), (10, 1.06), (24, 1), (48, 1)], SY: [(0, 1), (10, 1.06), (24, 1), (48, 1)]},
                         **exprs("success")})
    error = animation("ErrorFlash", ERROR_ANIM, 36,
                      {BODY_NODE: {Y: [(0, 0), (8, 6), (36, 6)], X: [(0, 0), (4, -5), (8, 5), (12, -4), (16, 0), (36, 0)]}, FACE: {Y: [(0, 0), (8, 4), (36, 4)]},
                       **exprs("error")})
    drag = animation("Dragged", DRAG_ANIM, 40,
                     {BODY_NODE: {SX: [(0, 1.08), (20, 1.05), (40, 1.08)], SY: [(0, 0.92), (20, 0.95), (40, 0.92)]}, **exprs("dragged")}, "loop")
    return [animation("FlashRest", FLASH_REST_ANIM, 1, {}), success, error,
            animation("DragRest", DRAG_REST_ANIM, 1, {}), drag]


def idle_variety_animations():
    """Idle is not one loop: random-length waits, then a small glance/tilt, then back."""
    glance = animation("IdleGlance", IDLE_GLANCE_ANIM, 90,
                       {FACE: {ROT: [(0, 0), (30, rad(3)), (60, rad(-2)), (90, 0)], X: [(0, 0), (30, 5), (60, -3), (90, 0)]}})
    return [animation("IdleWaitA", IDLE_WAIT_A, 240, {}), animation("IdleWaitB", IDLE_WAIT_B, 420, {}), glance]


def root_machine():
    expr_trans = "\n".join(vm_enum_transition(root_state_node[st], state_enum_ids[st]) for st in SUSTAINED)
    expr_states = "\n".join(anim_state(root_state_anim[st], root_state_node[st], i) for i, st in enumerate(SUSTAINED))
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

    flash = layer_frame(
        "Flash", "3:6", FLASH_REST_NODE,
        vm_trigger_transition(SUCCESS_NODE, VM_SUCCESS) + "\n" + vm_trigger_transition(ERROR_NODE, VM_ERROR),
        f'<AnimationState x="200" y="40" animationId="{FLASH_REST_ANIM}" id="{FLASH_REST_NODE}"/>\n'
        f'<AnimationState x="200" y="100" animationId="{SUCCESS_ANIM}" reset="true" id="{SUCCESS_NODE}">\n'
        f'    {exit_transition(FLASH_REST_NODE, 120)}\n</AnimationState>\n'
        f'<AnimationState x="200" y="160" animationId="{ERROR_ANIM}" reset="true" id="{ERROR_NODE}">\n'
        f'    {exit_transition(FLASH_REST_NODE, 120)}\n</AnimationState>')

    drag = layer_frame(
        "Drag", "3:7", DRAG_REST_NODE,
        vm_bool_transition(DRAG_NODE, VM_DRAGGED, "true") + "\n" + vm_bool_transition(DRAG_REST_NODE, VM_DRAGGED, "false"),
        f'<AnimationState x="200" y="40" animationId="{DRAG_REST_ANIM}" id="{DRAG_REST_NODE}"/>\n'
        f'<AnimationState x="200" y="100" animationId="{DRAG_ANIM}" id="{DRAG_NODE}"/>')

    idle = layer_frame(
        "IdleVariety", "3:8", IDLE_A_NODE, "",
        f'<AnimationState x="200" y="40" animationId="{IDLE_WAIT_A}" id="{IDLE_A_NODE}">\n    {exit_transition(IDLE_GLANCE_NODE)}\n</AnimationState>\n'
        f'<AnimationState x="200" y="100" animationId="{IDLE_WAIT_B}" id="{IDLE_B_NODE}">\n    {exit_transition(IDLE_GLANCE_NODE)}\n</AnimationState>\n'
        f'<AnimationState x="200" y="160" animationId="{IDLE_GLANCE_ANIM}" reset="true" random="true" id="{IDLE_GLANCE_NODE}">\n'
        f'    {weighted(exit_transition(IDLE_A_NODE), 50)}\n    {weighted(exit_transition(IDLE_B_NODE), 50)}\n</AnimationState>')

    def bool_listener(name, kind, prop, value):
        return (f'<StateMachineListenerSingle targetId="{HITBOX}" listenerTypeValue="{kind}" name="{name}">\n'
                f'    <ListenerViewModelChange>\n        <BindablePropertyBoolean propertyValue="{value}">\n'
                f'            {bind(prop, BIND_BOOL).replace("/>", chr(32) + "direction=" + chr(34) + "true" + chr(34) + "/>")}\n'
                f'        </BindablePropertyBoolean>\n    </ListenerViewModelChange>\n</StateMachineListenerSingle>')
    drag_listeners = bool_listener("DragStart", "dragStart", VM_DRAGGED, "true") + "\n" + bool_listener("DragEnd", "dragEnd", VM_DRAGGED, "false")

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
{indent(flash, "    ")}
{indent(drag, "    ")}
{indent(idle, "    ")}
{indent(listeners, "    ")}
{indent(drag_listeners, "    ")}
</StateMachine>'''


def root_artboard():
    state_anims = root_state_animations()
    # Breath keys only the Body node's scale; it is on its own layer, so the later layer wins where
    # a state also keys scale (dragged, success), which is the intent.
    breath = animation("Breath", BREATH_ANIM, 180, {BODY_NODE: {SX: [(0, 1.0), (180, 1.02)], SY: [(0, 1.0), (180, 1.02)]}}, "pingPong")
    blink_rest = animation("BlinkRest", BLINK_REST_ANIM, 1, {})
    blink = animation("BlinkFire", BLINK_ANIM, 2, {}, callbacks=(EYES_BLINK,))
    hover_rest = animation("HoverRest", HOVER_REST_ANIM, 1, {FACE: {ROT: 0}})
    hover = animation("HoverWiggle", HOVER_ANIM, 40, {FACE: {ROT: [(0, 0), (10, rad(6)), (30, rad(-6)), (40, 0)]}}, "loop")

    return f'''<Artboard defaultStateMachineId="{SM}" viewModelId="{VM}" viewModelInstanceId="{VM_INSTANCE}"
          x="0" y="0" styleId="0:3" clip="false" width="500" height="500" name="Mascot" id="{ROOT}">
    <LayoutComponentStyle name="Style" id="0:3"/>
{indent(face(), "    ")}
{indent(body(), "    ")}

{indent(chr(10).join(state_anims + momentary_animations() + idle_variety_animations() + [breath, blink_rest, blink, hover_rest, hover]), "    ")}

{indent(root_machine(), "    ")}
</Artboard>'''


def data():
    states = "\n".join(f'    <DataEnumValue key="{s}" value="{s[0].upper() + s[1:]}" id="{state_enum_ids[s]}"/>' for s in SUSTAINED)
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
    <ViewModelPropertyTrigger name="success" id="{VM_SUCCESS}"/>
    <ViewModelPropertyTrigger name="error" id="{VM_ERROR}"/>
    <ViewModelPropertyBoolean name="dragged" id="{VM_DRAGGED}"/>

    <ViewModelInstance exports="true" name="Default" id="{VM_INSTANCE}">
        <ViewModelInstanceEnum propertyValue="{state_enum_ids['idle']}" viewModelPropertyId="{VM_STATE}"/>
        <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_MOUTH}"/>
        <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_LOOKX}"/>
        <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_LOOKY}"/>
        <ViewModelInstanceTrigger viewModelPropertyId="{VM_BLINK}"/>
        <ViewModelInstanceEnum propertyValue="{shape_enum_ids['circle']}" viewModelPropertyId="{VM_SHAPE}"/>
        <ViewModelInstanceColor propertyValue="FF1E7BF0" viewModelPropertyId="{VM_COLOR}"/>
        <ViewModelInstanceBoolean propertyValue="false" viewModelPropertyId="{VM_HOVER}"/>
        <ViewModelInstanceTrigger viewModelPropertyId="{VM_SUCCESS}"/>
        <ViewModelInstanceTrigger viewModelPropertyId="{VM_ERROR}"/>
        <ViewModelInstanceBoolean propertyValue="false" viewModelPropertyId="{VM_DRAGGED}"/>
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

{indent(eyes_component(), "    ")}

{indent(brows_component(), "    ")}

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
