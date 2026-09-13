"""Generator for rive/mascot/scene.rml - the v4 rig, built from SPEC.md and the SVGs in ./art.

One character, one eye. The body is an identity (the `shape` enum picks one of eight 8-cubic
bodies); the plate carries one glyph per state; the mouth sits below the plate. Numbers come
from SPEC.md and MOTION-REFERENCES.md; geometry comes from art/*.svg through svgpath.py.
Standard size profile only - the `-small` profile needs a host signal the contract does not
yet carry (see DEVIATIONS).

Structure (Rive conventions):
  Mascot (root, view-model driven)
    Body     - one path, vertices keyed per IDENTITY on the Shape layer, never per state
               paints: bound fill, shade, gloss (breathing opacity), tint (per state); soft edge
               and halo as faint feathered strokes behind it
    Plate    - NestedArtboard; `expr` picks the glyph and the mouth, LookX/LookY move the glyph,
               Open morphs the mouth, `blink` squashes the glyph
    Layers   - Shape (identity), Expression (sustained, per-pair transitions), Breath (gloss),
               Flash (success/error, self-returning), Drag, IdleVariety, Blink, Hover
  Plate (component, input driven)

DEVIATIONS from SPEC.md, all intentional and small:
  - no `-small` profile yet (no host signal); standard geometry everywhere
  - drag tilt needs pointer velocity the file cannot see: 0 degrees
  - the idle glance moves the plate 2 degrees / 2 px instead of the glyph (the root cannot key a
    nested artboard's node); the eye-only glance belongs in the editor pass
  - glyph "shutter" on change is a cross-fade over the transition, not a scaleY shutter
  - speaking mouth opacity is smoothstep-free: visible while expr is speaking/dragged
"""
import math
import os
from textwrap import indent

import svgpath

ART = os.path.join(os.path.dirname(os.path.abspath(__file__)), "art")


def art(name):
    return os.path.join(ART, name)


def rad(deg):
    return round(math.radians(deg), 5)


def frames(ms):
    return round(ms * 60 / 1000)


STATES = ["idle", "listening", "dragged", "thinking", "waitingInput", "speaking",
          "success", "error", "sleeping", "loading", "failed", "degraded"]
EXPR = {s: i for i, s in enumerate(STATES)}
MOMENTARY = ["success", "dragged"]
SUSTAINED = [s for s in STATES if s not in MOMENTARY]
SHAPES = ["circle", "blob", "roundedSquare", "pill", "triangle", "hexagon", "cloud", "drop"]
SHAPE_SVG = {"circle": "body-circle.svg", "blob": "body-blob.svg", "roundedSquare": "body-squircle.svg",
             "pill": "body-pill.svg", "triangle": "body-triangle.svg", "hexagon": "body-hexagon.svg",
             "cloud": "body-cloud.svg", "drop": "body-drop.svg"}
DEFAULT_SHAPE = "blob"

# --- property keys (rive schema) --------------------------------------------------------------
X, Y, ROT, SX, SY, OPACITY = 13, 14, 15, 16, 17, 18
COLOR, GRADIENT_OPACITY = 37, 46
VX, VY, VROT, VDIST = 24, 25, 82, 83                      # mirrored vertex
VIN_ROT, VIN_DIST, VOUT_ROT, VOUT_DIST = 84, 85, 86, 87   # detached vertex
NESTED_VALUE, NESTED_FIRE, REMAP_TIME = 239, 401, 202
BIND_ENUM, BIND_TRIGGER, BIND_BOOL = 637, 686, 634

# --- ids ----------------------------------------------------------------------------------------
VM, VM_STATE, VM_MOUTH, VM_LOOKX, VM_LOOKY, VM_BLINK, VM_SHAPE, VM_COLOR, VM_HOVER = (
    "1:50", "1:1", "1:2", "1:3", "1:4", "1:5", "1:6", "1:7", "1:8")
VM_SUCCESS, VM_ERROR, VM_DRAGGED = "1:9", "1:10", "1:11"
VM_INSTANCE, ENUM_STATE, ENUM_SHAPE = "1:20", "1:100", "1:200"
state_enum_ids = {s: f"1:{101 + i}" for i, s in enumerate(STATES)}
shape_enum_ids = {s: f"1:{201 + i}" for i, s in enumerate(SHAPES)}
CONV_LOOK, CONV_MOUTH = "2:1", "2:2"

ROOT, BODY_NODE, FACE, HITBOX, HALO, SOFT, TINT, GLOSS = "0:2", "0:100", "0:200", "0:90", "0:91", "0:93", "0:92", "0:94"
PLATE, PLATE_EXPR, PLATE_BLINK = "0:210", "0:211", "0:212"
body_vertex_ids = [f"0:{300 + i}" for i in range(8)]
soft_vertex_ids = [f"0:{310 + i}" for i in range(8)]
halo_vertex_ids = [f"0:{320 + i}" for i in range(8)]

SM = "3:5"
root_state_anim = {s: f"3:{100 + i}" for i, s in enumerate(STATES)}
root_state_node = {s: f"3:{130 + i}" for i, s in enumerate(STATES)}
shape_anim = {s: f"3:{60 + i}" for i, s in enumerate(SHAPES)}
shape_node = {s: f"3:{70 + i}" for i, s in enumerate(SHAPES)}
BREATH_ANIM, BREATH_NODE = "3:160", "3:161"
BLINK_ANIM, BLINK_REST_ANIM, BLINK_NODE, BLINK_REST_NODE = "3:170", "3:171", "3:172", "3:173"
HOVER_ANIM, HOVER_REST_ANIM, HOVER_NODE, HOVER_REST_NODE, HOVER_HELD_NODE = "3:180", "3:181", "3:182", "3:183", "3:184"
FLASH_REST_ANIM, FLASH_REST_NODE, SUCCESS_ANIM, SUCCESS_NODE, ERROR_ANIM, ERROR_NODE = "3:190", "3:191", "3:192", "3:193", "3:194", "3:195"
DRAG_REST_ANIM, DRAG_REST_NODE, DRAG_ANIM, DRAG_NODE = "3:200", "3:201", "3:202", "3:203"
IDLE_WAIT_A, IDLE_WAIT_B, IDLE_GLANCE_ANIM, IDLE_A_NODE, IDLE_B_NODE, IDLE_GLANCE_NODE = "3:210", "3:211", "3:212", "3:213", "3:214", "3:215"

PLATE_AB, PLATE_SM, PLATE_IN_EXPR, PLATE_IN_BLINK = "7:2", "7:5", "7:6", "7:7"
PLATE_ROOT, PLATE_CARD, GLYPHS_NODE, MOUTH_MORPH, PLATE_SHADOW = "7:20", "7:21", "7:22", "7:23", "7:24"
GLYPH_ORDER = ["idle", "listening", "thinking", "waitingInput", "speaking", "success", "error", "sleeping", "loading", "failed", "degraded"]
GLYPH = {name: f"7:{30 + i}" for i, name in enumerate(GLYPH_ORDER)}
FROWN, MOUTH_O = "7:42", "7:43"
mouth_vertex_ids = [f"7:{70 + i}" for i in range(4)]
PLATE_LOOKX, PLATE_LOOKY, PLATE_OPEN, PLATE_BLINK_ANIM, PLATE_WAIT_A, PLATE_WAIT_B = "7:60", "7:61", "7:62", "7:63", "7:64", "7:65"
plate_expr_anim = {s: f"7:{100 + i}" for i, s in enumerate(STATES)}
plate_expr_node = {s: f"7:{130 + i}" for i, s in enumerate(STATES)}
PLATE_BLINK_NODE, PLATE_BLINK_REST_NODE = "7:160", "7:161"
PLATE_AUTO_A, PLATE_AUTO_B, PLATE_AUTO_BLINK = "7:162", "7:163", "7:164"

INK = "FF111111"
PLATE_WHITE = "FFF7F7F7"
# SPEC / MOTION-REFERENCES beziers
EASE_OUT = "0 0 0.58 1"
SOFT_OUT = "0.22 1 0.36 1"
STANDARD = "0.4 0 0.2 1"
SPRING = "0.16 1 0.3 1"
ACCEL = "0.4 0 1 1"
SINE = "0.37 0 0.63 1"
LINEAR = "0 0 1 1"


# --- small builders ----------------------------------------------------------------------------
def bind(source, key, converter=None):
    conv = f' converterId="{converter}"' if converter else ""
    return f'<DataBindContext sourcePathIds="{VM}-{source}" propertyKey="{key}"{conv}/>'


def interp(bezier):
    x1, y1, x2, y2 = bezier.split()
    return f'<CubicEaseInterpolator x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}"/>'


def kf(value, frame, bezier=None):
    if isinstance(value, str):  # colour
        return f'<KeyFrameColor value="{value}" frame="{frame}"/>'
    if bezier:
        return f'<KeyFrameDouble value="{value}" frame="{frame}" interpolationType="cubic">{interp(bezier)}</KeyFrameDouble>'
    return f'<KeyFrameDouble value="{value}" frame="{frame}"/>'


def keyed(objects, default_bezier=SOFT_OUT):
    """objects: {objectId: {propertyKey: value | [(frame, value) | (frame, value, bezier)]}}.
    The bezier on a keyframe shapes the segment that LEAVES it (Rive semantics)."""
    out = []
    for obj, props in objects.items():
        kp = []
        for key, fr in props.items():
            if not isinstance(fr, list):
                fr = [(0, fr)]
            lines = []
            for item in fr:
                f, v = item[0], item[1]
                bez = item[2] if len(item) > 2 else (default_bezier if len(fr) > 1 else None)
                lines.append("        " + kf(v, f, bez))
            kp.append(f'    <KeyedProperty propertyKey="{key}">\n' + "\n".join(lines) + '\n    </KeyedProperty>')
        out.append(f'<KeyedObject objectId="{obj}">\n' + "\n".join(kp) + '\n</KeyedObject>')
    return "\n".join(out)


def callback_keyed(objects, frame=0):
    return "\n".join(
        f'<KeyedObject objectId="{o}">\n    <KeyedProperty propertyKey="{NESTED_FIRE}">\n'
        f'        <KeyFrameCallback frame="{frame}"/>\n    </KeyedProperty>\n</KeyedObject>' for o in objects)


def animation(name, aid, duration, objects, loop="oneShot", callbacks=(), bezier=SOFT_OUT):
    body = keyed(objects, bezier) + ("\n" + callback_keyed(callbacks) if callbacks else "")
    return (f'<LinearAnimation fps="60" duration="{max(1, duration)}" loopValue="{loop}" name="{name}" id="{aid}">\n'
            + indent(body, "    ") + '\n</LinearAnimation>')


def anim_state(aid, nid, i, extra="", children=""):
    if children:
        return f'<AnimationState x="200" y="{40 + 60 * i}" animationId="{aid}"{extra} id="{nid}">\n{indent(children, "    ")}\n</AnimationState>'
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


def vm_condition(kind, prop, literal):
    key = {"Enum": BIND_ENUM, "Trigger": BIND_TRIGGER, "Boolean": BIND_BOOL}[kind]
    return f'''<TransitionViewModelCondition opValue="equal">
    <TransitionPropertyViewModelComparator>
        <BindableProperty{kind}>
            {bind(prop, key)}
        </BindableProperty{kind}>
    </TransitionPropertyViewModelComparator>
    {literal}
</TransitionViewModelCondition>'''


def transition(to_id, duration_ms, bezier, condition=None, exit_time=False):
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


def enum_transition(to_id, enum_value_id, duration_ms=160, bezier=EASE_OUT, prop=VM_STATE):
    return transition(to_id, duration_ms, bezier, vm_condition("Enum", prop, f'<TransitionValueEnumComparator value="{enum_value_id}"/>'))


def trigger_transition(to_id, prop):
    return transition(to_id, 0, None, vm_condition("Trigger", prop, '<TransitionValueTriggerComparator/>'))


def bool_transition(to_id, prop, value, duration_ms, bezier):
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


def expression_layer(name, lid, input_id, anim_ids, node_ids):
    trans = "\n".join(input_transition(node_ids[s], input_id, EXPR[s]) for s in STATES)
    states = "\n".join(anim_state(anim_ids[s], node_ids[s], i) for i, s in enumerate(STATES))
    return layer_frame(name, lid, node_ids["idle"], trans, states)


def fill(color):
    return f'<Fill name="Fill"><SolidColor colorValue="{color}" name="Color"/></Fill>'


def rrect(w, h, r, name="Path"):
    return (f'<Rectangle width="{w}" height="{h}" linkCornerRadius="true" cornerRadiusTL="{r}" '
            f'cornerRadiusTR="{r}" cornerRadiusBL="{r}" cornerRadiusBR="{r}" name="{name}"/>')


# ================================================================================================
# Body: identity path (SPEC section 1, body import mechanics) + paint recipe (section 6).
# ================================================================================================
BODY = {s: svgpath.body_vertices(art(SHAPE_SVG[s])) for s in SHAPES}


def body_path(vertex_ids, name):
    verts = "\n".join(
        f'<CubicMirroredVertex x="{x}" y="{y}" rotation="{rot}" distance="{d}" name="V{i}" id="{vid}"/>'
        for i, ((x, y, rot, d), vid) in enumerate(zip(BODY[DEFAULT_SHAPE], vertex_ids)))
    return f'<PointsPath isClosed="true" name="{name}">\n{indent(verts, "    ")}\n</PointsPath>'


def shape_keys(shape):
    objs = {}
    for (x, y, rot, d), vb, vs, vh in zip(BODY[shape], body_vertex_ids, soft_vertex_ids, halo_vertex_ids):
        for vid in (vb, vs, vh):
            objs[vid] = {VX: x, VY: y, VROT: rot, VDIST: d}
    return objs


def body():
    bound = f'<SolidColor colorValue="FF79B7DF" name="Color">\n            {bind(VM_COLOR, COLOR)}\n        </SolidColor>'
    return f'''<Node x="250" y="270" name="BodyPlacement">
<Node x="0" y="0" name="Body" id="{BODY_NODE}">
    <!-- Paints on one shape; the LATER paint draws on top. -->
    <Shape name="BodyShape" id="{HITBOX}">
{indent(body_path(body_vertex_ids, "Path"), "        ")}
        <Fill name="Fill">
            {bound}
        </Fill>
        <Fill name="Shade">
            <RadialGradient startX="-40" startY="-65" endX="130" endY="120" name="Gradient">
                <GradientStop colorValue="00000000" position="0"/>
                <GradientStop colorValue="08000000" position="0.55"/>
                <GradientStop colorValue="40000000" position="1"/>
            </RadialGradient>
        </Fill>
        <Fill name="Gloss">
            <RadialGradient startX="-65" startY="-85" endX="65" endY="45" name="Gradient" id="{GLOSS}">
                <GradientStop colorValue="52FFFFFF" position="0"/>
                <GradientStop colorValue="24FFFFFF" position="0.45"/>
                <GradientStop colorValue="00FFFFFF" position="1"/>
            </RadialGradient>
        </Fill>
        <Fill name="Tint">
            <SolidColor colorValue="00000000" name="TintColor" id="{TINT}"/>
        </Fill>
    </Shape>
    <Shape opacity="0.08" name="SoftEdge" id="{SOFT}">
{indent(body_path(soft_vertex_ids, "Path"), "        ")}
        <Stroke thickness="8" name="Stroke">
            {bound}
            <Feather strength="4" name="Feather"/>
        </Stroke>
    </Shape>
    <Shape scaleX="1.02" scaleY="1.02" opacity="0.04" name="Halo" id="{HALO}">
{indent(body_path(halo_vertex_ids, "Path"), "        ")}
        <Stroke thickness="12" name="Stroke">
            {bound}
            <Feather strength="10" name="Feather"/>
        </Stroke>
    </Shape>
</Node>
</Node>'''


# ================================================================================================
# Plate component (SPEC sections 1, 4, 7 standard profile).
# ================================================================================================
MOUTH_SAMPLES = [svgpath.mouth_vertices(art(f"glyph-mouth-{n}.svg")) for n in ("closed", "half", "open")]
STATE_GLYPH = {"idle": "idle", "listening": "listening", "dragged": "thinking", "thinking": "thinking",
               "waitingInput": "waitingInput", "speaking": "speaking", "success": "success", "error": "error",
               "sleeping": "sleeping", "loading": "loading", "failed": "failed", "degraded": "degraded"}
STATE_MOUTH = {"dragged": MOUTH_MORPH, "waitingInput": MOUTH_O, "speaking": MOUTH_MORPH, "error": FROWN}
STATE_PLATE_SCALE = {"listening": 1.04, "waitingInput": 1.06}


def plate_component():
    expr_anims = []
    for st in STATES:
        objs = {gid: {OPACITY: 0} for gid in GLYPH.values()}
        objs.update({MOUTH_MORPH: {OPACITY: 0}, MOUTH_O: {OPACITY: 0}, FROWN: {OPACITY: 0}})
        objs[GLYPH[STATE_GLYPH[st]]] = {OPACITY: 1}
        if st in STATE_MOUTH:
            objs[STATE_MOUTH[st]] = {OPACITY: 1}
        sc = STATE_PLATE_SCALE.get(st, 1)
        objs[PLATE_CARD] = {SX: sc, SY: sc}
        objs[PLATE_SHADOW] = {SX: sc, SY: sc}
        expr_anims.append(animation("Expr" + st[0].upper() + st[1:], plate_expr_anim[st], 1, objs))

    look_x = animation("LookX", PLATE_LOOKX, 60, {GLYPHS_NODE: {X: [(0, -7, LINEAR), (60, 7)]}})
    look_y = animation("LookY", PLATE_LOOKY, 60, {GLYPHS_NODE: {Y: [(0, -5, LINEAR), (60, 5)]}})
    # Open: the mouth morphs closed -> half -> open through the three SVG samples (linear).
    mouth_keys = {}
    for vid, samples in zip(mouth_vertex_ids, zip(*MOUTH_SAMPLES)):
        mouth_keys[vid] = {}
        for key, idx in ((VX, 0), (VY, 1), (VIN_ROT, 2), (VIN_DIST, 3), (VOUT_ROT, 4), (VOUT_DIST, 5)):
            mouth_keys[vid][key] = [(f, s[idx], LINEAR) for f, s in zip((0, 30, 60), samples)]
    open_anim = animation("Open", PLATE_OPEN, 60, mouth_keys)
    # Blink: close 55 ms, hold 25 ms, open 90 ms (3 / 2 / 5 frames), squash the glyph only.
    blink = animation("Blink", PLATE_BLINK_ANIM, 10, {GLYPHS_NODE: {SY: [(0, 1, ACCEL), (3, 0, None), (5, 0, SPRING), (10, 1)]}})
    wait_a = animation("WaitA", PLATE_WAIT_A, frames(2500), {})
    wait_b = animation("WaitB", PLATE_WAIT_B, frames(4500), {})

    expr_layer = expression_layer("Expression", "7:10", PLATE_IN_EXPR, plate_expr_anim, plate_expr_node)
    trigger_layer = layer_frame(
        "Blink", "7:11", PLATE_BLINK_REST_NODE,
        f'<StateTransition stateToId="{PLATE_BLINK_NODE}">\n    <TransitionTriggerCondition inputId="{PLATE_IN_BLINK}"/>\n</StateTransition>',
        f'<AnimationState x="200" y="40" animationId="{PLATE_WAIT_A}" id="{PLATE_BLINK_REST_NODE}"/>\n'
        + anim_state(PLATE_BLINK_ANIM, PLATE_BLINK_NODE, 1, ' reset="true"', exit_transition(PLATE_BLINK_REST_NODE)))
    auto_layer = layer_frame(
        "AutoBlink", "7:12", PLATE_AUTO_A, "",
        anim_state(PLATE_WAIT_A, PLATE_AUTO_A, 0, ' random="true"', exit_transition(PLATE_AUTO_BLINK)) + "\n"
        + anim_state(PLATE_WAIT_B, PLATE_AUTO_B, 1, "", exit_transition(PLATE_AUTO_BLINK)) + "\n"
        + anim_state(PLATE_BLINK_ANIM, PLATE_AUTO_BLINK, 2, ' reset="true" random="true"',
                     weighted(exit_transition(PLATE_AUTO_A), 50) + "\n" + weighted(exit_transition(PLATE_AUTO_B), 50)))

    glyphs = "\n".join(svgpath.path_rml(art(f"glyph-{n}.svg"), n[0].upper() + n[1:], GLYPH[n], INK, opacity=0) for n in GLYPH_ORDER)
    mv = "\n".join(
        f'<CubicDetachedVertex x="{s[0]}" y="{s[1]}" inRotation="{s[2]}" inDistance="{s[3]}" outRotation="{s[4]}" outDistance="{s[5]}" name="M{i}" id="{vid}"/>'
        for i, (s, vid) in enumerate(zip(MOUTH_SAMPLES[0], mouth_vertex_ids)))
    mouth_morph = f'''<Shape x="0" y="82" opacity="0" name="Mouth" id="{MOUTH_MORPH}">
    <PointsPath isClosed="true" name="Path">
{indent(mv, "        ")}
    </PointsPath>
    {fill(INK)}
</Shape>'''
    mouth_o = svgpath.path_rml(art("glyph-mouth-o.svg"), "MouthO", MOUTH_O, INK, opacity=0, y=82)
    frown = svgpath.path_rml(art("glyph-mouth-frown.svg"), "FrownLine", FROWN, INK, opacity=0, y=82)

    return f'''<Artboard isComponent="true" defaultStateMachineId="{PLATE_SM}" x="700" y="0" styleId="7:3" clip="false" width="200" height="200" name="Plate" id="{PLATE_AB}">
    <LayoutComponentStyle name="Style" id="7:3"/>
    <Node x="100" y="100" name="PlateRoot" id="{PLATE_ROOT}">
{indent(mouth_morph, "        ")}
{indent(mouth_o, "        ")}
{indent(frown, "        ")}
        <Node x="0" y="0" name="Glyphs" id="{GLYPHS_NODE}">
{indent(glyphs, "            ")}
        </Node>
        <Shape x="0" y="0" name="Card" id="{PLATE_CARD}">
            {rrect(120, 120, 27)}
            {fill(PLATE_WHITE)}
        </Shape>
        <Shape x="0" y="2" name="Shadow" id="{PLATE_SHADOW}">
            {rrect(120, 120, 27)}
            <Stroke thickness="4" name="Stroke">
                <SolidColor colorValue="14000000" name="Color"/>
                <Feather strength="3" name="Feather"/>
            </Stroke>
        </Shape>
    </Node>

{indent(chr(10).join(expr_anims + [look_x, look_y, open_anim, blink, wait_a, wait_b]), "    ")}

    <StateMachine name="Plate" id="{PLATE_SM}">
        <StateMachineNumber name="expr" id="{PLATE_IN_EXPR}"/>
        <StateMachineTrigger name="blink" id="{PLATE_IN_BLINK}"/>
{indent(expr_layer, "        ")}
{indent(trigger_layer, "        ")}
{indent(auto_layer, "        ")}
    </StateMachine>
</Artboard>
<ComponentAsset artboardId="{PLATE_AB}" name="Plate"/>'''


# ================================================================================================
# Root
# ================================================================================================
def face():
    return f'''<Node x="250" y="262" name="FacePlacement">
<Node x="0" y="0" name="Face" id="{FACE}">
    <NestedArtboard artboardId="{PLATE_AB}" x="-100" y="-108" name="Plate" id="{PLATE}">
        <NestedStateMachine animationId="{PLATE_SM}" name="SM">
            <NestedNumber inputId="{PLATE_IN_EXPR}" nestedValue="0" name="expr" id="{PLATE_EXPR}"/>
            <NestedTrigger inputId="{PLATE_IN_BLINK}" name="blink" id="{PLATE_BLINK}"/>
        </NestedStateMachine>
        <NestedRemapAnimation animationId="{PLATE_LOOKX}" time="0.5" name="LookX">
            {bind(VM_LOOKX, REMAP_TIME, CONV_LOOK)}
        </NestedRemapAnimation>
        <NestedRemapAnimation animationId="{PLATE_LOOKY}" time="0.5" name="LookY">
            {bind(VM_LOOKY, REMAP_TIME, CONV_LOOK)}
        </NestedRemapAnimation>
        <NestedRemapAnimation animationId="{PLATE_OPEN}" time="0" name="Open">
            {bind(VM_MOUTH, REMAP_TIME, CONV_MOUTH)}
        </NestedRemapAnimation>
    </NestedArtboard>
</Node>
</Node>'''


def sine(amplitude, period_ms, base=0.0):
    """0,+a,0,-a,0 over one period with sine-ish easing per quarter; loops seamlessly."""
    q = frames(period_ms) // 4
    return [(0, base, SINE), (q, base + amplitude, SINE), (2 * q, base, SINE), (3 * q, base - amplitude, SINE), (4 * q, base)]


def sustained_animations():
    """SPEC section 1: root motion (Body and Face move together), plate rotation/offset, tint,
    gloss pulse, and the glyph index. Nothing keys body scale or body vertices."""
    ROW = {  # key: (root motion, period ms, plate rot deg, face offset, tint, gloss pulse)
        "idle": ({"y": sine(1, 4600)}, 4600, 0, (0, 0), "00000000", None),
        "listening": ({"y": sine(1, 4600)}, 4600, -2, (0, -3), "00000000", None),
        "thinking": ({"x": sine(2, 3200)}, 3200, -6, (0, 0), "00000000", None),
        "waitingInput": ({"y": sine(2, 1200)}, 1200, 0, (0, -2), "00000000", None),
        "speaking": ({"y": sine(1, 4600)}, 4600, 0, (0, 0), "00000000", None),
        "error": ({"y": 5}, 0, 5, (0, 4), "14000000", None),
        "sleeping": ({"y": sine(1, 6800)}, 6800, 3, (0, 4), "38000000", None),
        "loading": ({}, 1400, 0, (0, 0), "10000000", [(0, 0.8, SINE), (frames(700), 1.0, SINE), (frames(1400), 0.8)]),
        "failed": ({}, 0, 0, (0, 0), "66808080", None),
        "degraded": ({}, 0, 4, (0, 0), "00000000", None),
    }
    out = []
    for st in SUSTAINED:
        motion, period, rot, (fx, fy), tint, gloss = ROW[st]

        def shifted(keys, offset):
            if isinstance(keys, list):
                return [(f, v + offset) + tuple(k[2:]) for k, (f, v) in zip(keys, [(k[0], k[1]) for k in keys])]
            return keys + offset

        mx, my = motion.get("x", 0), motion.get("y", 0)
        body_keys = {X: mx, Y: my}
        face_keys = {X: shifted(mx, fx), Y: shifted(my, fy), ROT: rad(rot)}
        objs = {BODY_NODE: body_keys, FACE: face_keys, TINT: {COLOR: tint}, PLATE_EXPR: {NESTED_VALUE: EXPR[st]},
                GLOSS: {GRADIENT_OPACITY: gloss if gloss else 1}}
        duration = frames(period) if period else 1
        out.append(animation("State" + st[0].upper() + st[1:], root_state_anim[st], duration, objs, "loop" if duration > 1 else "oneShot"))
    return out


def shape_animations():
    return [animation("Shape" + s[0].upper() + s[1:], shape_anim[s], 1, shape_keys(s)) for s in SHAPES]


def momentary_animations():
    """SPEC section 2. Root delta on Body and Face together; plate rotation on Face."""
    S = frames(800)
    f = lambda pct: round(S * pct / 100)
    hop = [(0, 0, ACCEL), (f(10), 2, SPRING), (f(37.5), -10, ACCEL), (f(70), 1, SOFT_OUT), (S, 0)]
    success = animation("SuccessFlash", SUCCESS_ANIM, S, {
        BODY_NODE: {Y: hop},
        FACE: {Y: hop, ROT: [(0, 0, ACCEL), (f(10), rad(-2), SPRING), (f(37.5), rad(2), ACCEL), (f(70), rad(-1), SOFT_OUT), (S, 0)]},
        PLATE_EXPR: {NESTED_VALUE: EXPR["success"]}})
    E = frames(600)
    g = lambda pct: round(E * pct / 100)
    ex = [(0, 0, STANDARD), (g(16.6667), -4, STANDARD), (g(33.3333), 4, STANDARD), (g(50), -2, STANDARD), (g(66.6667), 0, None), (E, 0)]
    ey = [(0, 0, STANDARD), (g(16.6667), 5, STANDARD), (g(33.3333), 5, STANDARD), (g(50), 5, STANDARD), (g(66.6667), 5, None), (E, 5)]
    er = [(0, 0, STANDARD), (g(16.6667), rad(-7), STANDARD), (g(33.3333), rad(7), STANDARD), (g(50), rad(-3), STANDARD), (g(66.6667), rad(5), None), (E, rad(5))]
    error = animation("ErrorFlash", ERROR_ANIM, E, {BODY_NODE: {X: ex, Y: ey}, FACE: {X: ex, Y: ey, ROT: er},
                                                   PLATE_EXPR: {NESTED_VALUE: EXPR["error"]}})
    drag = animation("Dragged", DRAG_ANIM, 1, {PLATE_EXPR: {NESTED_VALUE: EXPR["dragged"]}})
    return [animation("FlashRest", FLASH_REST_ANIM, 1, {}), success, error, animation("DragRest", DRAG_REST_ANIM, 1, {}), drag]


def idle_variety_animations():
    m, h, r = frames(250), frames(650), frames(300)
    glance = animation("IdleGlance", IDLE_GLANCE_ANIM, m + h + r, {
        FACE: {ROT: [(0, 0, SOFT_OUT), (m, rad(2), None), (m + h, rad(2), SOFT_OUT), (m + h + r, 0)],
               X: [(0, 0, SOFT_OUT), (m, 2, None), (m + h, 2, SOFT_OUT), (m + h + r, 0)]}})
    return [animation("IdleWaitA", IDLE_WAIT_A, frames(4000), {}), animation("IdleWaitB", IDLE_WAIT_B, frames(7000), {}), glance]


def root_machine():
    shape_trans = "\n".join(enum_transition(shape_node[s], shape_enum_ids[s], 240, SOFT_OUT, VM_SHAPE) for s in SHAPES)
    shape_states = "\n".join(anim_state(shape_anim[s], shape_node[s], i) for i, s in enumerate(SHAPES))
    shape_layer = layer_frame("Shape", "3:9", shape_node[DEFAULT_SHAPE], shape_trans, shape_states)

    target_dur = {"sleeping": (600, STANDARD), "waitingInput": (160, SPRING)}
    expr_trans = "\n".join(enum_transition(root_state_node[st], state_enum_ids[st], *target_dur.get(st, (160, EASE_OUT))) for st in SUSTAINED)
    pairs = {("idle", "listening"): (200, SOFT_OUT), ("listening", "thinking"): (300, STANDARD),
             ("thinking", "speaking"): (200, SOFT_OUT), ("speaking", "idle"): (240, SOFT_OUT), ("error", "idle"): (300, SOFT_OUT)}
    states = []
    for i, st in enumerate(SUSTAINED):
        own = [enum_transition(root_state_node[to], state_enum_ids[to], d, b) for (frm, to), (d, b) in pairs.items() if frm == st]
        if st == "sleeping":
            own += [enum_transition(root_state_node[to], state_enum_ids[to], 600, STANDARD) for to in SUSTAINED if to != "sleeping"]
        states.append(anim_state(root_state_anim[st], root_state_node[st], i, "", "\n".join(own)))
    expression = layer_frame("Expression", "3:1", root_state_node["idle"], expr_trans, "\n".join(states))

    breath = layer_frame("Breath", "3:2", BREATH_NODE, "", anim_state(BREATH_ANIM, BREATH_NODE, 0))
    blink = layer_frame("Blink", "3:3", BLINK_REST_NODE, trigger_transition(BLINK_NODE, VM_BLINK),
                        f'<AnimationState x="200" y="40" animationId="{BLINK_REST_ANIM}" id="{BLINK_REST_NODE}"/>\n'
                        + anim_state(BLINK_ANIM, BLINK_NODE, 1, ' reset="true"', exit_transition(BLINK_REST_NODE)))
    hover = layer_frame(
        "Hover", "3:4", HOVER_REST_NODE, "",
        anim_state(HOVER_REST_ANIM, HOVER_REST_NODE, 0, "", bool_transition(HOVER_NODE, VM_HOVER, "true", 0, None)) + "\n"
        + anim_state(HOVER_ANIM, HOVER_NODE, 1, ' reset="true"', exit_transition(HOVER_HELD_NODE)) + "\n"
        + anim_state(HOVER_REST_ANIM, HOVER_HELD_NODE, 2, "", bool_transition(HOVER_REST_NODE, VM_HOVER, "false", 0, None)))
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
    idle = layer_frame(
        "IdleVariety", "3:8", IDLE_A_NODE, "",
        anim_state(IDLE_WAIT_A, IDLE_A_NODE, 0, "", exit_transition(IDLE_GLANCE_NODE)) + "\n"
        + anim_state(IDLE_WAIT_B, IDLE_B_NODE, 1, "", exit_transition(IDLE_GLANCE_NODE)) + "\n"
        + anim_state(IDLE_GLANCE_ANIM, IDLE_GLANCE_NODE, 2, ' reset="true" random="true"',
                     weighted(exit_transition(IDLE_A_NODE), 50) + "\n" + weighted(exit_transition(IDLE_B_NODE), 50)))

    def bool_listener(name, kind, prop, value):
        b = bind(prop, BIND_BOOL).replace("/>", ' direction="true"/>')
        return (f'<StateMachineListenerSingle targetId="{HITBOX}" listenerTypeValue="{kind}" name="{name}">\n'
                f'    <ListenerViewModelChange>\n        <BindablePropertyBoolean propertyValue="{value}">\n'
                f'            {b}\n        </BindablePropertyBoolean>\n    </ListenerViewModelChange>\n</StateMachineListenerSingle>')
    listeners = "\n".join([
        bool_listener("HoverIn", "enter", VM_HOVER, "true"), bool_listener("HoverOut", "exit", VM_HOVER, "false"),
        bool_listener("DragStart", "dragStart", VM_DRAGGED, "true"), bool_listener("DragEnd", "dragEnd", VM_DRAGGED, "false"),
    ])
    return f'''<StateMachine name="Avatar" id="{SM}">
{indent(shape_layer, "    ")}
{indent(expression, "    ")}
{indent(breath, "    ")}
{indent(blink, "    ")}
{indent(hover, "    ")}
{indent(flash, "    ")}
{indent(drag, "    ")}
{indent(idle, "    ")}
{indent(listeners, "    ")}
</StateMachine>'''


def root_artboard():
    breath = animation("Breath", BREATH_ANIM, frames(4600), {GLOSS: {GRADIENT_OPACITY: sine(0.05, 4600, 1.0)}}, "loop")
    blink_rest = animation("BlinkRest", BLINK_REST_ANIM, 1, {})
    blink = animation("BlinkFire", BLINK_ANIM, 2, {}, callbacks=(PLATE_BLINK,))
    hover_rest = animation("HoverRest", HOVER_REST_ANIM, 1, {})
    hover = animation("HoverWiggle", HOVER_ANIM, frames(240), {FACE: {ROT: [(0, 0, STANDARD), (frames(60), rad(2), STANDARD), (frames(150), rad(-2), STANDARD), (frames(240), 0)]}})
    anims = shape_animations() + sustained_animations() + momentary_animations() + idle_variety_animations() + [breath, blink_rest, blink, hover_rest, hover]
    return f'''<Artboard defaultStateMachineId="{SM}" viewModelId="{VM}" viewModelInstanceId="{VM_INSTANCE}"
          x="0" y="0" styleId="0:3" clip="false" width="500" height="500" name="Mascot" id="{ROOT}">
    <LayoutComponentStyle name="Style" id="0:3"/>
{indent(face(), "    ")}
{indent(body(), "    ")}

{indent(chr(10).join(anims), "    ")}

{indent(root_machine(), "    ")}
</Artboard>'''


def data():
    states = "\n".join(f'    <DataEnumValue key="{s}" value="{s[0].upper() + s[1:]}" id="{state_enum_ids[s]}"/>' for s in SUSTAINED)
    shapes = "\n".join(f'    <DataEnumValue key="{s}" value="{s[0].upper() + s[1:]}" id="{shape_enum_ids[s]}"/>' for s in SHAPES)
    return f'''<DataEnumCustom name="AvatarState" id="{ENUM_STATE}">
{states}
</DataEnumCustom>
<DataEnumCustom name="MascotShape" id="{ENUM_SHAPE}">
{shapes}
</DataEnumCustom>

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
        <ViewModelInstanceEnum propertyValue="{shape_enum_ids[DEFAULT_SHAPE]}" viewModelPropertyId="{VM_SHAPE}"/>
        <ViewModelInstanceColor propertyValue="FF79B7DF" viewModelPropertyId="{VM_COLOR}"/>
        <ViewModelInstanceBoolean propertyValue="false" viewModelPropertyId="{VM_HOVER}"/>
        <ViewModelInstanceTrigger viewModelPropertyId="{VM_SUCCESS}"/>
        <ViewModelInstanceTrigger viewModelPropertyId="{VM_ERROR}"/>
        <ViewModelInstanceBoolean propertyValue="false" viewModelPropertyId="{VM_DRAGGED}"/>
    </ViewModelInstance>
</ViewModel>'''


doc = f'''<Rive version="1" kind="fragment">
    <!--
        The Letta agent mascot, v4: SPEC.md + art/*.svg through gen_scene.py. Regenerate rather
        than hand-edit the exhaustive parts; the art is the illustrator's.

        The host writes, on the Avatar view model (the contract with RiveAvatarContract.kt):
          state     - sustained AvatarState enum key
          success   - trigger: happy flash, self-returning
          error     - trigger: sad flash; `state` then settles to error
          dragged   - boolean: held while dragging (the file's drag listeners write it too)
          mouthOpen - 0..1 speech amplitude -> the mouth below the plate morphs closed/half/open
          lookX/Y   - -1..1 gaze -> the glyph moves +-7 / +-5 px inside the plate
          blink     - trigger -> the glyph squashes shut; it also blinks on its own
          color     - identity; body, soft edge and halo are bound to it
          shape     - identity; picks one of eight bodies on the Shape layer
    -->
{indent(root_artboard(), "    ")}

{indent(plate_component(), "    ")}

{indent(data(), "    ")}
</Rive>
'''

if __name__ == "__main__":
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scene.rml")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(doc)
    print(f"wrote {out} ({doc.count(chr(10))} lines)")
