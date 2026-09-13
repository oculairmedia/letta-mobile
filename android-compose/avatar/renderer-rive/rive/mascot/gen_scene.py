"""Generator for rive/mascot/scene.rml - the v3 "plate" rig (see MASCOT.md, ARTIST.md).

One character, one eye. A morphing soft body carries a white plate with a single glyph; the
glyph is the expression. Sparse on purpose: it reads at 22 dp in a rail, and an illustrator has
one thing per state to draw.

Structure (Rive conventions):
  Mascot (root, view-model driven)
    Body     - one PointsPath, vertices keyed per sustained state (circle / teardrop / blob),
               paints: halo, soft edge, bound fill, shade, gloss, tint, glass ring
    Plate    - NestedArtboard; `expr` picks the glyph, LookX/LookY scrub the glyph offset,
               Open scrubs the speaking arc, `blink` squashes the plate
    Layers   - Expression (sustained), Flash (success/error triggers, self-returning),
               Drag (boolean + drag listeners), IdleVariety, Breath, Blink, Hover
  Plate (component, input driven)

The project is pushed to the Rive workspace (rive.yaml carries the mapping). Regenerating and
pushing is fine while nobody has edited the file in the editor - ids are stable, so a push updates
objects in place. Once the artist has started, stop pushing from the CLI.

Conventions learned the hard way (all silent in the compiler):
  - rotations are radians; LinearAnimation.duration is frames; StateTransition.duration is ms
  - keyframes default to `hold`; motion needs interpolationType="cubic" + a nested interpolator
  - the first child of a node draws on TOP; within a shape the LATER paint draws on top
  - a view-model-driven machine inside a nested artboard never fires; components use inputs
  - NestedRemapAnimation.time is a 0..1 fraction of the timeline
  - Feather on a Fill renders nothing; feather strokes only
  - animations must key every mutable property in every pose, or states leak into each other
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
VX, VY, VROT, VDIST = 24, 25, 82, 83          # Vertex x/y, CubicMirroredVertex rotation/distance
NESTED_VALUE, NESTED_FIRE, REMAP_TIME = 239, 401, 202
BIND_ENUM, BIND_TRIGGER, BIND_BOOL = 637, 686, 634

# --- ids: "client:object". 0:* root scene, 1:* data, 2:* converters, 3:* root machine, 7:* Plate --
VM, VM_STATE, VM_MOUTH, VM_LOOKX, VM_LOOKY, VM_BLINK, VM_SHAPE, VM_COLOR, VM_HOVER = (
    "1:50", "1:1", "1:2", "1:3", "1:4", "1:5", "1:6", "1:7", "1:8")
VM_SUCCESS, VM_ERROR, VM_DRAGGED = "1:9", "1:10", "1:11"
VM_INSTANCE, ENUM_STATE, ENUM_SHAPE = "1:20", "1:100", "1:200"
state_enum_ids = {s: f"1:{101 + i}" for i, s in enumerate(STATES)}
SHAPES = ["circle", "blob", "roundedSquare", "pill", "triangle", "hexagon", "cloud", "drop"]
shape_enum_ids = {s: f"1:{201 + i}" for i, s in enumerate(SHAPES)}
CONV_LOOK, CONV_MOUTH = "2:1", "2:2"

ROOT, BODY_NODE, FACE, HITBOX, HALO, TINT = "0:2", "0:100", "0:200", "0:90", "0:91", "0:92"
PLATE, PLATE_EXPR, PLATE_BLINK = "0:210", "0:211", "0:212"
body_vertex_ids = [f"0:{300 + i}" for i in range(8)]
halo_vertex_ids = [f"0:{320 + i}" for i in range(8)]

SM = "3:5"
root_state_anim = {s: f"3:{100 + i}" for i, s in enumerate(STATES)}
root_state_node = {s: f"3:{130 + i}" for i, s in enumerate(STATES)}
BREATH_ANIM, BREATH_NODE = "3:160", "3:161"
BLINK_ANIM, BLINK_REST_ANIM, BLINK_NODE, BLINK_REST_NODE = "3:170", "3:171", "3:172", "3:173"
HOVER_ANIM, HOVER_REST_ANIM, HOVER_NODE, HOVER_REST_NODE = "3:180", "3:181", "3:182", "3:183"
FLASH_REST_ANIM, FLASH_REST_NODE, SUCCESS_ANIM, SUCCESS_NODE, ERROR_ANIM, ERROR_NODE = "3:190", "3:191", "3:192", "3:193", "3:194", "3:195"
DRAG_REST_ANIM, DRAG_REST_NODE, DRAG_ANIM, DRAG_NODE = "3:200", "3:201", "3:202", "3:203"
IDLE_WAIT_A, IDLE_WAIT_B, IDLE_GLANCE_ANIM, IDLE_A_NODE, IDLE_B_NODE, IDLE_GLANCE_NODE = "3:210", "3:211", "3:212", "3:213", "3:214", "3:215"

PLATE_AB, PLATE_SM, PLATE_IN_EXPR, PLATE_IN_BLINK = "7:2", "7:5", "7:6", "7:7"
PLATE_ROOT, PLATE_CARD, GLYPHS_NODE, TELL_DOT = "7:20", "7:21", "7:22", "7:23"
GLYPH = {name: f"7:{30 + i}" for i, name in enumerate(
    ["square", "ring", "dash", "ringSmall", "arcOpen", "smile", "diamond", "arch", "dot", "cross", "frownLine"])}
PLATE_LOOKX, PLATE_LOOKY, PLATE_OPEN, PLATE_BLINK_ANIM, PLATE_WAIT_A, PLATE_WAIT_B = "7:60", "7:61", "7:62", "7:63", "7:64", "7:65"
plate_expr_anim = {s: f"7:{100 + i}" for i, s in enumerate(STATES)}
plate_expr_node = {s: f"7:{130 + i}" for i, s in enumerate(STATES)}
PLATE_BLINK_NODE, PLATE_BLINK_REST_NODE = "7:160", "7:161"
PLATE_AUTO_A, PLATE_AUTO_B, PLATE_AUTO_BLINK = "7:162", "7:163", "7:164"

INK = "FF1A1A1A"
PLATE_WHITE = "FFF7F7F7"
EASE = '<CubicEaseInterpolator x1="0.25" y1="0.1" x2="0.25" y2="1"/>'


# --- small builders ----------------------------------------------------------------------------
def bind(source, key, converter=None):
    conv = f' converterId="{converter}"' if converter else ""
    return f'<DataBindContext sourcePathIds="{VM}-{source}" propertyKey="{key}"{conv}/>'


def kf(value, frame, ease=True):
    if isinstance(value, str):  # colour
        return f'<KeyFrameColor value="{value}" frame="{frame}"/>'
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


def stroke(color, thickness, feather=None, name="Stroke"):
    f = f'<Feather strength="{feather}" name="Feather"/>' if feather else ""
    return f'<Stroke thickness="{thickness}" cap="round" join="round" name="{name}"><SolidColor colorValue="{color}" name="Color"/>{f}</Stroke>'


def rrect(w, h, r, name="Path"):
    return (f'<Rectangle width="{w}" height="{h}" linkCornerRadius="true" cornerRadiusTL="{r}" '
            f'cornerRadiusTR="{r}" cornerRadiusBL="{r}" cornerRadiusBR="{r}" name="{name}"/>')


# ================================================================================================
# Body: one 8-vertex cubic path whose vertices are keyed per sustained state.
# ================================================================================================
R = 150.0
HANDLE = round(R * 4 / 3 * math.tan(math.pi / 16), 3)  # 8-point circle approximation


def body_points(radii=(1,) * 8, tip=None):
    """Eight vertices at 45-degree steps starting from the top, each scaled by `radii`.
    `tip` = (index, distance) sharpens one vertex's handles (the teardrop)."""
    pts = []
    for i in range(8):
        a = -math.pi / 2 + i * math.pi / 4
        r = R * radii[i]
        d = HANDLE * radii[i]
        if tip and tip[0] == i:
            d = tip[1]
        pts.append((round(r * math.cos(a), 3), round(r * math.sin(a), 3), round(a + math.pi / 2, 5), round(d, 3)))
    return pts


BODY_SHAPES = {
    "circle": body_points(),
    # top vertex pulled up and sharpened, shoulders pulled in: the listening teardrop
    "teardrop": body_points(radii=(1.14, 0.92, 0.99, 1.02, 1.0, 1.02, 0.99, 0.92), tip=(0, 30)),
    # an uneven blob for degraded: soft irregularity, not a diamond
    "blob": body_points(radii=(1.04, 0.94, 1.07, 0.97, 1.05, 0.93, 1.02, 0.98)),
    # squashed for dragged (also scaled by the Drag layer)
    "squash": body_points(radii=(0.94, 1.04, 1.08, 1.04, 0.94, 1.04, 1.08, 1.04)),
}
STATE_BODY = {s: "circle" for s in STATES}
STATE_BODY.update({"listening": "teardrop", "degraded": "blob", "dragged": "squash"})
# Tint over the identity colour: sleeping darker, failed grey. Colour-agnostic (an overlay).
STATE_TINT = {s: "00000000" for s in STATES}
STATE_TINT.update({"sleeping": "5C0A1E3A", "failed": "D9888888", "loading": "26000000"})


def body_path(vertex_ids, name):
    verts = "\n".join(
        f'<CubicMirroredVertex x="{x}" y="{y}" rotation="{rot}" distance="{d}" name="V{i}" id="{vid}"/>'
        for i, ((x, y, rot, d), vid) in enumerate(zip(BODY_SHAPES["circle"], vertex_ids)))
    return f'<PointsPath isClosed="true" name="{name}">\n{indent(verts, "    ")}\n</PointsPath>'


def body_keys(shape_name):
    """Vertex keys for both the body and the halo path."""
    objs = {}
    for (x, y, rot, d), vb, vh in zip(BODY_SHAPES[shape_name], body_vertex_ids, halo_vertex_ids):
        objs[vb] = {VX: x, VY: y, VDIST: d}
        objs[vh] = {VX: x, VY: y, VDIST: d}
    return objs


def body():
    bound = f'<SolidColor colorValue="FF5FA8FF" name="Color">\n            {bind(VM_COLOR, COLOR)}\n        </SolidColor>'
    return f'''<Node x="250" y="270" name="BodyPlacement">
<Node x="0" y="0" name="Body" id="{BODY_NODE}">
    <!-- Paints on one shape: the LATER paint draws on top. Halo is a separate, enlarged shape. -->
    <Shape name="BodyShape" id="{HITBOX}">
{indent(body_path(body_vertex_ids, "Path"), "        ")}
        <Stroke thickness="26" name="SoftEdge">
            {bound}
            <Feather strength="22" name="Feather"/>
        </Stroke>
        <Fill name="Fill">
            {bound}
        </Fill>
        <Fill name="Shade">
            <RadialGradient startX="-30" startY="-50" endX="150" endY="130" name="Gradient">
                <GradientStop colorValue="00000000" position="0.4"/>
                <GradientStop colorValue="66000000" position="1"/>
            </RadialGradient>
        </Fill>
        <Fill name="Gloss">
            <RadialGradient startX="-55" startY="-75" endX="60" endY="30" name="Gradient">
                <GradientStop colorValue="D0FFFFFF" position="0"/>
                <GradientStop colorValue="00FFFFFF" position="1"/>
            </RadialGradient>
        </Fill>
        <Fill name="Tint">
            <SolidColor colorValue="00000000" name="TintColor" id="{TINT}"/>
        </Fill>
        <Stroke thickness="2" name="GlassRing">
            <SolidColor colorValue="4DFFFFFF" name="Color"/>
            <Feather strength="3" name="Feather"/>
        </Stroke>
    </Shape>
    <Shape scaleX="1.06" scaleY="1.06" opacity="0.22" name="Halo" id="{HALO}">
{indent(body_path(halo_vertex_ids, "Path"), "        ")}
        <Stroke thickness="44" name="Stroke">
            {bound}
            <Feather strength="40" name="Feather"/>
        </Stroke>
    </Shape>
</Node>
</Node>'''


# ================================================================================================
# Plate component: a white card with one glyph. 200x200, centred.
# ================================================================================================
STATE_GLYPH = {
    "idle": "square", "listening": "ring", "dragged": "dash", "thinking": "dash", "waitingInput": "ringSmall",
    "speaking": "arcOpen", "success": "smile", "error": "diamond", "sleeping": "arch", "loading": "dot",
    "failed": "cross", "degraded": "smile",
}
# Extra shapes shown alongside the glyph.
STATE_EXTRA = {"waitingInput": [TELL_DOT], "error": [GLYPH["frownLine"]]}


def plate_component():
    G = GLYPH
    expr_anims = []
    for st in STATES:
        objs = {gid: {OPACITY: 0} for gid in G.values()}
        objs[TELL_DOT] = {OPACITY: 0}
        objs[G[STATE_GLYPH[st]]] = {OPACITY: 1}
        for extra in STATE_EXTRA.get(st, []):
            objs[extra] = {OPACITY: 1}
        # The plate itself: a little bigger when attentive, a little smaller when asleep.
        card_scale = {"listening": 1.06, "waitingInput": 1.08, "sleeping": 0.94, "failed": 0.96}.get(st, 1)
        objs[PLATE_CARD] = {SX: card_scale, SY: card_scale}
        expr_anims.append(animation("Expr" + st[0].upper() + st[1:], plate_expr_anim[st], 1, objs))

    look_x = animation("LookX", PLATE_LOOKX, 60, {GLYPHS_NODE: {X: [(0, -12), (60, 12)]}, PLATE_CARD: {X: [(0, -4), (60, 4)]}})
    look_y = animation("LookY", PLATE_LOOKY, 60, {GLYPHS_NODE: {Y: [(0, -10), (60, 10)]}, PLATE_CARD: {Y: [(0, -3), (60, 3)]}})
    # Speaking: the arc opens with the amplitude. Keyed on the glyph's own scale so the art stays.
    open_anim = animation("Open", PLATE_OPEN, 60, {G["arcOpen"]: {SY: [(0, 0.55), (60, 1.6)], SX: [(0, 0.95), (60, 1.1)]}})
    # Blink: the whole plate squashes to a line and back.
    blink = animation("Blink", PLATE_BLINK_ANIM, 10, {PLATE_ROOT: {SY: [(0, 1), (4, 0.06), (10, 1)]}})
    wait_a = animation("WaitA", PLATE_WAIT_A, 150, {})
    wait_b = animation("WaitB", PLATE_WAIT_B, 270, {})

    expr_layer = expression_layer("Expression", "7:10", PLATE_IN_EXPR, plate_expr_anim, plate_expr_node)
    trigger_layer = layer_frame(
        "Blink", "7:11", PLATE_BLINK_REST_NODE,
        f'<StateTransition stateToId="{PLATE_BLINK_NODE}">\n    <TransitionTriggerCondition inputId="{PLATE_IN_BLINK}"/>\n</StateTransition>',
        f'<AnimationState x="200" y="40" animationId="{PLATE_WAIT_A}" id="{PLATE_BLINK_REST_NODE}"/>\n'
        f'<AnimationState x="200" y="100" animationId="{PLATE_BLINK_ANIM}" reset="true" id="{PLATE_BLINK_NODE}">\n'
        f'    {exit_transition(PLATE_BLINK_REST_NODE)}\n</AnimationState>')
    auto_layer = layer_frame(
        "AutoBlink", "7:12", PLATE_AUTO_A, "",
        f'<AnimationState x="200" y="40" animationId="{PLATE_WAIT_A}" random="true" id="{PLATE_AUTO_A}">\n'
        f'    {exit_transition(PLATE_AUTO_BLINK)}\n</AnimationState>\n'
        f'<AnimationState x="200" y="100" animationId="{PLATE_WAIT_B}" id="{PLATE_AUTO_B}">\n'
        f'    {exit_transition(PLATE_AUTO_BLINK)}\n</AnimationState>\n'
        f'<AnimationState x="200" y="160" animationId="{PLATE_BLINK_ANIM}" reset="true" random="true" id="{PLATE_AUTO_BLINK}">\n'
        f'    {weighted(exit_transition(PLATE_AUTO_A), 60)}\n'
        f'    {weighted(exit_transition(PLATE_AUTO_B), 40)}\n</AnimationState>')

    def glyph(name, sid, inner):
        return f'<Shape x="0" y="0" opacity="0" name="{name}" id="{sid}">\n{indent(inner, "    ")}\n</Shape>'

    def arc(down, size=1.0):
        """down=True is the smile (ends up, middle down); down=False the arch - a closed eye."""
        s = -1 if down else 1  # y grows downward
        k = size
        return (f'<PointsPath isClosed="false" name="Path">\n'
                f'    <StraightVertex x="{-22 * k}" y="{8 * s * k}"/>\n'
                f'    <CubicMirroredVertex x="0" y="{-12 * s * k}" rotation="0" distance="{15 * k}"/>\n'
                f'    <StraightVertex x="{22 * k}" y="{8 * s * k}"/>\n'
                f'</PointsPath>\n{stroke(INK, round(12 * k, 1))}')

    glyphs = "\n".join([
        glyph("Square", G["square"], rrect(42, 42, 8) + fill(INK)),
        glyph("Ring", G["ring"], f'<Ellipse width="40" height="40" name="Path"/>\n{stroke(INK, 12)}'),
        glyph("Dash", G["dash"], rrect(52, 13, 6) + fill(INK)),
        glyph("RingSmall", G["ringSmall"], f'<Ellipse width="34" height="34" name="Path"/>\n{stroke(INK, 11)}'),
        glyph("ArcOpen", G["arcOpen"], arc(down=False, size=1.3)),
        glyph("Smile", G["smile"], arc(down=True)),
        glyph("Diamond", G["diamond"], f'<Shape rotation="{rad(45)}" name="Rot">\n    {rrect(34, 34, 5)}\n    {fill(INK)}\n</Shape>'),
        glyph("Arch", G["arch"], arc(down=False)),
        glyph("Dot", G["dot"], f'<Ellipse width="22" height="22" name="Path"/>\n{fill(INK)}'),
        glyph("Cross", G["cross"], f'<Shape rotation="{rad(45)}" name="A">\n    {rrect(46, 12, 5)}\n    {fill(INK)}\n</Shape>\n'
                                    f'<Shape rotation="{rad(-45)}" name="B">\n    {rrect(46, 12, 5)}\n    {fill(INK)}\n</Shape>'),
        # The error frown sits on the body below the plate, like the sheet.
        f'<Shape x="0" y="100" opacity="0" name="FrownLine" id="{G["frownLine"]}">\n'
        f'    <PointsPath isClosed="false" name="Path">\n        <StraightVertex x="-16" y="4"/>\n'
        f'        <CubicMirroredVertex x="0" y="-6" rotation="0" distance="10"/>\n        <StraightVertex x="16" y="4"/>\n'
        f'    </PointsPath>\n    {stroke(INK, 7)}\n</Shape>',
    ])

    return f'''<Artboard isComponent="true" defaultStateMachineId="{PLATE_SM}" x="700" y="0" styleId="7:3" clip="false" width="200" height="200" name="Plate" id="{PLATE_AB}">
    <LayoutComponentStyle name="Style" id="7:3"/>
    <Node x="100" y="100" name="PlateRoot" id="{PLATE_ROOT}">
        <!-- The "asking you" tell: a small dot below the plate, on the body. -->
        <Shape x="0" y="82" opacity="0" name="TellDot" id="{TELL_DOT}">
            <Ellipse width="12" height="12" name="Path"/>
            {fill(INK)}
        </Shape>
        <Node x="0" y="0" name="Glyphs" id="{GLYPHS_NODE}">
{indent(glyphs, "            ")}
        </Node>
        <Shape x="0" y="0" name="Card" id="{PLATE_CARD}">
            {rrect(112, 112, 26)}
            <Stroke thickness="14" name="Shadow">
                <SolidColor colorValue="33000000" name="Color"/>
                <Feather strength="14" name="Feather"/>
            </Stroke>
            {fill(PLATE_WHITE)}
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
# Root: face placement, animations, machine, data.
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


def root_state_animations():
    """Per sustained state: body morph + tint + body motion, and the glyph index for the plate."""
    BODY_REST = {BODY_NODE: {SX: 1, SY: 1, X: 0, Y: 0, OPACITY: 1}, FACE: {X: 0, Y: 0}}
    motion = {
        "idle": ({}, 1, "oneShot"),
        "listening": ({BODY_NODE: {SX: 1.03, SY: 1.03}, FACE: {Y: -8}}, 1, "oneShot"),
        "thinking": ({BODY_NODE: {X: [(0, -4), (60, 4), (120, -4)]}, FACE: {X: [(0, 5), (60, 9), (120, 5)]}}, 120, "loop"),
        "waitingInput": ({BODY_NODE: {Y: [(0, 0), (24, -8), (48, 0)]}}, 48, "loop"),
        "speaking": ({}, 1, "oneShot"),
        "error": ({BODY_NODE: {Y: 6}, FACE: {Y: 4}}, 1, "oneShot"),
        "sleeping": ({FACE: {Y: 6}}, 1, "oneShot"),
        "loading": ({BODY_NODE: {OPACITY: [(0, 0.75), (36, 1.0), (72, 0.75)]}}, 72, "loop"),
        "failed": ({}, 1, "oneShot"),
        "degraded": ({FACE: {X: 8, Y: -4}}, 1, "oneShot"),
    }
    out = []
    for st in SUSTAINED:
        overrides, duration, loop = motion[st]
        objs = {o: dict(p) for o, p in BODY_REST.items()}
        for o, p in overrides.items():
            objs.setdefault(o, {}).update(p)
        objs.update(body_keys(STATE_BODY[st]))
        objs[TINT] = {COLOR: STATE_TINT[st]}
        objs[PLATE_EXPR] = {NESTED_VALUE: EXPR[st]}
        out.append(animation("State" + st[0].upper() + st[1:], root_state_anim[st], duration, objs, loop))
    return out


def momentary_animations():
    """Flashes and drag. These layers sit AFTER Expression, so while active they win the body pose
    and the plate's glyph; their Rest animations key nothing, so the sustained layer shows through
    the moment they return."""
    success = animation("SuccessFlash", SUCCESS_ANIM, 48,
                        {BODY_NODE: {Y: [(0, 0), (10, -20), (24, 0), (48, 0)], SX: [(0, 1), (10, 1.06), (24, 1), (48, 1)], SY: [(0, 1), (10, 1.06), (24, 1), (48, 1)]},
                         PLATE_EXPR: {NESTED_VALUE: EXPR["success"]}})
    error = animation("ErrorFlash", ERROR_ANIM, 36,
                      {BODY_NODE: {Y: [(0, 0), (8, 6), (36, 6)], X: [(0, 0), (4, -5), (8, 5), (12, -4), (16, 0), (36, 0)]},
                       PLATE_EXPR: {NESTED_VALUE: EXPR["error"]}})
    drag = animation("Dragged", DRAG_ANIM, 40,
                     {BODY_NODE: {SX: [(0, 1.08), (20, 1.05), (40, 1.08)], SY: [(0, 0.92), (20, 0.95), (40, 0.92)]},
                      PLATE_EXPR: {NESTED_VALUE: EXPR["dragged"]}, **body_keys("squash")}, "loop")
    return [animation("FlashRest", FLASH_REST_ANIM, 1, {}), success, error,
            animation("DragRest", DRAG_REST_ANIM, 1, {}), drag]


def idle_variety_animations():
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
        b = bind(prop, BIND_BOOL).replace("/>", ' direction="true"/>')
        return (f'<StateMachineListenerSingle targetId="{HITBOX}" listenerTypeValue="{kind}" name="{name}">\n'
                f'    <ListenerViewModelChange>\n        <BindablePropertyBoolean propertyValue="{value}">\n'
                f'            {b}\n        </BindablePropertyBoolean>\n    </ListenerViewModelChange>\n</StateMachineListenerSingle>')
    listeners = "\n".join([
        bool_listener("HoverIn", "enter", VM_HOVER, "true"), bool_listener("HoverOut", "exit", VM_HOVER, "false"),
        bool_listener("DragStart", "dragStart", VM_DRAGGED, "true"), bool_listener("DragEnd", "dragEnd", VM_DRAGGED, "false"),
    ])
    return f'''<StateMachine name="Avatar" id="{SM}">
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
    breath = animation("Breath", BREATH_ANIM, 180, {BODY_NODE: {SX: [(0, 1.0), (180, 1.02)], SY: [(0, 1.0), (180, 1.02)]}}, "pingPong")
    blink_rest = animation("BlinkRest", BLINK_REST_ANIM, 1, {})
    blink = animation("BlinkFire", BLINK_ANIM, 2, {}, callbacks=(PLATE_BLINK,))
    hover_rest = animation("HoverRest", HOVER_REST_ANIM, 1, {FACE: {ROT: 0}})
    hover = animation("HoverWiggle", HOVER_ANIM, 40, {FACE: {ROT: [(0, 0), (10, rad(6)), (30, rad(-6)), (40, 0)]}}, "loop")
    anims = root_state_animations() + momentary_animations() + idle_variety_animations() + [breath, blink_rest, blink, hover_rest, hover]
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
<!-- Declared so the host's identity write stays legal; the body morphs per state instead. -->
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
        <ViewModelInstanceEnum propertyValue="{shape_enum_ids['circle']}" viewModelPropertyId="{VM_SHAPE}"/>
        <ViewModelInstanceColor propertyValue="FF5FA8FF" viewModelPropertyId="{VM_COLOR}"/>
        <ViewModelInstanceBoolean propertyValue="false" viewModelPropertyId="{VM_HOVER}"/>
        <ViewModelInstanceTrigger viewModelPropertyId="{VM_SUCCESS}"/>
        <ViewModelInstanceTrigger viewModelPropertyId="{VM_ERROR}"/>
        <ViewModelInstanceBoolean propertyValue="false" viewModelPropertyId="{VM_DRAGGED}"/>
    </ViewModelInstance>
</ViewModel>'''


doc = f'''<Rive version="1" kind="fragment">
    <!--
        The Letta agent mascot, v3 "plate": a soft morphing body carrying one glyph on a white
        plate. GENERATED by gen_scene.py; the exhaustive parts (every pose keys every property)
        are why. The art is a placeholder for the illustrator's pass (see ARTIST.md).

        The host writes, on the Avatar view model (the contract with RiveAvatarContract.kt):
          state     - sustained AvatarState enum key
          success   - trigger: happy flash, self-returning
          error     - trigger: sad flash; `state` then settles to error
          dragged   - boolean: held while dragging (the file's drag listeners write it too)
          mouthOpen - 0..1 speech amplitude -> the speaking arc opens
          lookX/Y   - -1..1 gaze -> the glyph shifts inside the plate
          blink     - trigger -> the plate squashes shut; it also blinks on its own
          color     - identity; body and halo are bound to it
          shape     - accepted, unused: the body morphs per state instead
    -->
{indent(root_artboard(), "    ")}

{indent(plate_component(), "    ")}

{indent(data(), "    ")}
</Rive>
'''

if __name__ == "__main__":
    import os
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scene.rml")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(doc)
    print(f"wrote {out} ({doc.count(chr(10))} lines)")
