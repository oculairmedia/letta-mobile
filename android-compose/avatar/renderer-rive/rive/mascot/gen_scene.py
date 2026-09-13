"""One-shot generator for rive/mascot/scene.rml (see MASCOT.md).

Twelve state animations must each key EVERY mutable face/body property, or states leak into
each other; that exhaustiveness is why this is generated rather than typed. The output is the
committed source of truth and is meant to be hand-edited afterwards.
"""
from textwrap import indent
import math


def rad(deg):
    return round(math.radians(deg), 5)


# Property keys, from rive-runtime's generated headers.
X, Y, ROT, SX, SY, OPACITY = 13, 14, 15, 16, 17, 18
WIDTH, HEIGHT, COLOR = 20, 21, 37

SHAPES = ["circle", "blob", "roundedSquare", "pill", "triangle", "hexagon", "cloud", "drop"]
STATES = ["idle", "listening", "dragged", "thinking", "waitingInput", "speaking",
          "success", "error", "sleeping", "loading", "failed", "degraded"]

# --- ids ------------------------------------------------------------------------------------
VM, VM_STATE, VM_MOUTH, VM_LOOKX, VM_LOOKY, VM_BLINK, VM_SHAPE, VM_COLOR = (
    "1:50", "1:1", "1:2", "1:3", "1:4", "1:5", "1:6", "1:7")
VM_INSTANCE = "1:20"
ENUM_STATE, ENUM_SHAPE = "1:100", "1:200"
CONV_GAZE_X, CONV_GAZE_Y, CONV_MOUTH = "2:1", "2:2", "2:3"
SM = "3:5"

state_enum_ids = {s: f"1:{101 + i}" for i, s in enumerate(STATES)}
shape_enum_ids = {s: f"1:{201 + i}" for i, s in enumerate(SHAPES)}
body_shape_ids = {s: f"0:{110 + i}" for i, s in enumerate(SHAPES)}

BODY_NODE = "0:100"
EYE_L, EYE_R = "0:21", "0:22"          # Shape (rotation, y)
EYE_L_PATH, EYE_R_PATH = "0:23", "0:24"  # Rectangle (width, height)
HAPPY_L, HAPPY_R = "0:25", "0:26"
CLOSED_L, CLOSED_R = "0:27", "0:28"
CROSS_L, CROSS_R = "0:29", "0:33"
CROSS_IDS = {"0:29": ("0:40", "0:41"), "0:33": ("0:42", "0:43")}
MOUTH = "0:30"

state_anim_ids = {s: f"3:{100 + i}" for i, s in enumerate(STATES)}
state_node_ids = {s: f"3:{130 + i}" for i, s in enumerate(STATES)}
shape_anim_ids = {s: f"3:{160 + i}" for i, s in enumerate(SHAPES)}
shape_node_ids = {s: f"3:{170 + i}" for i, s in enumerate(SHAPES)}


def bind(source, key, converter=None):
    conv = f' converterId="{converter}"' if converter else ""
    return f'<DataBindContext sourcePathIds="{VM}-{source}" propertyKey="{key}"{conv}/>'


def body(name, sid, inner):
    # Every body: same origin, fill bound to `color`, hidden until the Shape layer shows it.
    return f'''<Shape x="250" y="270" opacity="0" name="{name}" id="{sid}">
    {inner}
    <Fill name="Fill">
        <SolidColor colorValue="FF1E7BF0" name="Color">
            {bind(VM_COLOR, COLOR)}
        </SolidColor>
    </Fill>
</Shape>'''


def points(verts):
    return "\n        ".join(verts)


BODIES = {
    "circle": '<Ellipse width="300" height="300" name="Path"/>',
    "roundedSquare": '<Rectangle width="280" height="280" linkCornerRadius="true" cornerRadiusTL="64" '
                     'cornerRadiusTR="64" cornerRadiusBL="64" cornerRadiusBR="64" name="Path"/>',
    "pill": '<Rectangle width="320" height="220" linkCornerRadius="true" cornerRadiusTL="110" '
            'cornerRadiusTR="110" cornerRadiusBL="110" cornerRadiusBR="110" name="Path"/>',
    "triangle": '<Polygon width="320" height="290" points="3" cornerRadius="40" name="Path"/>',
    "hexagon": '<Polygon width="310" height="310" points="6" cornerRadius="28" name="Path"/>',
    # Organic bodies: closed paths of mirrored cubics. Placeholder silhouettes for the editor pass.
    "blob": f'''<PointsPath isClosed="true" name="Path">
        {points([
            f'<CubicMirroredVertex x="0" y="-150" rotation="{rad(0)}" distance="85"/>',
            f'<CubicMirroredVertex x="150" y="-30" rotation="{rad(90)}" distance="70"/>',
            f'<CubicMirroredVertex x="95" y="135" rotation="{rad(165)}" distance="70"/>',
            f'<CubicMirroredVertex x="-105" y="125" rotation="{rad(195)}" distance="70"/>',
            f'<CubicMirroredVertex x="-150" y="-40" rotation="{rad(270)}" distance="70"/>',
        ])}
    </PointsPath>''',
    "cloud": f'''<PointsPath isClosed="true" name="Path">
        {points([
            f'<CubicMirroredVertex x="-60" y="-120" rotation="{rad(0)}" distance="60"/>',
            f'<CubicMirroredVertex x="70" y="-130" rotation="{rad(20)}" distance="55"/>',
            f'<CubicMirroredVertex x="160" y="-20" rotation="{rad(100)}" distance="60"/>',
            f'<CubicMirroredVertex x="110" y="120" rotation="{rad(175)}" distance="70"/>',
            f'<CubicMirroredVertex x="-110" y="120" rotation="{rad(185)}" distance="70"/>',
            f'<CubicMirroredVertex x="-160" y="-10" rotation="{rad(260)}" distance="60"/>',
        ])}
    </PointsPath>''',
    "drop": f'''<PointsPath isClosed="true" name="Path">
        {points([
            '<StraightVertex x="0" y="-165" radius="18"/>',
            f'<CubicMirroredVertex x="135" y="40" rotation="{rad(120)}" distance="90"/>',
            f'<CubicMirroredVertex x="0" y="150" rotation="{rad(180)}" distance="80"/>',
            f'<CubicMirroredVertex x="-135" y="40" rotation="{rad(240)}" distance="90"/>',
        ])}
    </PointsPath>''',
}

# --- the face ---------------------------------------------------------------------------------
INK = "FF0B0B0B"


def eye(name, sid, path_id, x):
    return f'''<Shape x="{x}" y="230" name="{name}" id="{sid}">
    <Rectangle width="14" height="44" linkCornerRadius="true" cornerRadiusTL="7" cornerRadiusTR="7"
               cornerRadiusBL="7" cornerRadiusBR="7" name="Path" id="{path_id}"/>
    <Fill name="Fill"><SolidColor colorValue="{INK}" name="Color"/></Fill>
</Shape>'''


def overlay_bar(name, sid, x, rotation=0, width=30, height=8):
    return f'''<Shape x="{x}" y="230" rotation="{rotation}" opacity="0" name="{name}" id="{sid}">
    <Rectangle width="{width}" height="{height}" linkCornerRadius="true" cornerRadiusTL="4" cornerRadiusTR="4"
               cornerRadiusBL="4" cornerRadiusBR="4" name="Path"/>
    <Fill name="Fill"><SolidColor colorValue="{INK}" name="Color"/></Fill>
</Shape>'''


def overlay_arc(name, sid, x):
    # A happy eye: an upward arc, stroked.
    return f'''<Shape x="{x}" y="232" opacity="0" name="{name}" id="{sid}">
    <PointsPath isClosed="false" name="Path">
        <StraightVertex x="-16" y="6"/>
        <CubicMirroredVertex x="0" y="-8" rotation="{rad(0)}" distance="12"/>
        <StraightVertex x="16" y="6"/>
    </PointsPath>
    <Stroke thickness="8" cap="round" join="round" name="Stroke"><SolidColor colorValue="{INK}" name="Color"/></Stroke>
</Shape>'''


def cross(name, sid, x):
    return f'''<Node x="{x}" y="230" opacity="0" name="{name}" id="{sid}">
    {overlay_bar(name + "A", CROSS_IDS[sid][0], 0, rotation=rad(45)).replace(' x="0" y="230"', ' x="0" y="0"').replace(' opacity="0"', '')}
    {overlay_bar(name + "B", CROSS_IDS[sid][1], 0, rotation=rad(-45)).replace(' x="0" y="230"', ' x="0" y="0"').replace(' opacity="0"', '')}
</Node>'''


# --- per-state poses --------------------------------------------------------------------------
# Every state sets all of these (hold keys), so switching states never leaves residue.
BASE = {
    (EYE_L_PATH, WIDTH): 14, (EYE_L_PATH, HEIGHT): 44, (EYE_R_PATH, WIDTH): 14, (EYE_R_PATH, HEIGHT): 44,
    (EYE_L, ROT): 0, (EYE_R, ROT): 0, (EYE_L, Y): 230, (EYE_R, Y): 230,
    (EYE_L, OPACITY): 1, (EYE_R, OPACITY): 1,
    (HAPPY_L, OPACITY): 0, (HAPPY_R, OPACITY): 0, (CLOSED_L, OPACITY): 0, (CLOSED_R, OPACITY): 0,
    (CROSS_L, OPACITY): 0, (CROSS_R, OPACITY): 0,
    (MOUTH, OPACITY): 0,
    (BODY_NODE, OPACITY): 1, (BODY_NODE, SX): 1, (BODY_NODE, SY): 1, (BODY_NODE, Y): 0, (BODY_NODE, X): 0,
}

# state -> (overrides, motion) where motion is a list of (objectId, key, [(frame, value)...]) and loop.
POSES = {
    "idle": ({}, [(BODY_NODE, SX, [(0, 1.0), (90, 1.02), (180, 1.0)]),
                  (BODY_NODE, SY, [(0, 1.0), (90, 1.02), (180, 1.0)])], 180, True),
    "listening": ({(EYE_L_PATH, HEIGHT): 57, (EYE_R_PATH, HEIGHT): 57, (EYE_L, Y): 226, (EYE_R, Y): 226,
                   (BODY_NODE, SX): 1.04, (BODY_NODE, SY): 1.04}, [], 1, False),
    "dragged": ({(EYE_L_PATH, HEIGHT): 22, (EYE_R_PATH, HEIGHT): 22},
                [(BODY_NODE, SX, [(0, 1.08), (20, 1.05), (40, 1.08)]),
                 (BODY_NODE, SY, [(0, 0.92), (20, 0.95), (40, 0.92)])], 40, True),
    "thinking": ({(EYE_L, Y): 224, (EYE_R, Y): 224, (EYE_R_PATH, HEIGHT): 31},
                 [(BODY_NODE, X, [(0, -3), (60, 3), (120, -3)])], 120, True),
    "waitingInput": ({(EYE_L_PATH, WIDTH): 21, (EYE_R_PATH, WIDTH): 21,
                      (EYE_L_PATH, HEIGHT): 57, (EYE_R_PATH, HEIGHT): 57},
                     [(BODY_NODE, Y, [(0, 0), (24, -6), (48, 0)])], 48, True),
    "speaking": ({(MOUTH, OPACITY): 1}, [], 1, False),
    "success": ({(EYE_L, OPACITY): 0, (EYE_R, OPACITY): 0, (HAPPY_L, OPACITY): 1, (HAPPY_R, OPACITY): 1},
                [(BODY_NODE, Y, [(0, 0), (12, -14), (24, 0)])], 24, False),
    "error": ({(EYE_L, ROT): rad(-12), (EYE_R, ROT): rad(12)},
              [(BODY_NODE, Y, [(0, 0), (6, 6), (30, 6)]),
               (BODY_NODE, X, [(0, 0), (4, -4), (8, 4), (12, -4), (16, 0)])], 30, False),
    "sleeping": ({(EYE_L, OPACITY): 0, (EYE_R, OPACITY): 0, (CLOSED_L, OPACITY): 1, (CLOSED_R, OPACITY): 1,
                  (BODY_NODE, OPACITY): 0.85},
                 [(BODY_NODE, SX, [(0, 0.98), (150, 1.03), (300, 0.98)]),
                  (BODY_NODE, SY, [(0, 0.98), (150, 1.03), (300, 0.98)])], 300, True),
    "loading": ({(EYE_L_PATH, HEIGHT): 15, (EYE_R_PATH, HEIGHT): 15},
                [(BODY_NODE, OPACITY, [(0, 0.7), (36, 1.0), (72, 0.7)])], 72, True),
    "failed": ({(EYE_L, OPACITY): 0, (EYE_R, OPACITY): 0, (CROSS_L, OPACITY): 1, (CROSS_R, OPACITY): 1,
                (BODY_NODE, OPACITY): 0.6}, [], 1, False),
    "degraded": ({(EYE_L, OPACITY): 0, (CLOSED_L, OPACITY): 1, (EYE_R_PATH, HEIGHT): 26,
                  (BODY_NODE, OPACITY): 0.8}, [], 1, False),
}


def state_animation(state):
    overrides, motion, duration, loop = POSES[state]
    values = dict(BASE)
    values.update(overrides)
    animated = {(o, k): frames for o, k, frames in motion}
    by_object = {}
    for (obj, key), value in values.items():
        frames = animated.get((obj, key), [(0, value)])
        by_object.setdefault(obj, []).append((key, frames))
    keyed = []
    for obj, props in by_object.items():
        kp = "\n".join(
            f'    <KeyedProperty propertyKey="{key}">\n' +
            "".join(f'        <KeyFrameDouble value="{v}" frame="{f}"/>\n' for f, v in frames) +
            '    </KeyedProperty>'
            for key, frames in props)
        keyed.append(f'<KeyedObject objectId="{obj}">\n{kp}\n</KeyedObject>')
    loop_attr = ' loopValue="loop"' if loop else ' loopValue="oneShot"'
    return (f'<LinearAnimation fps="60" duration="{duration}"{loop_attr} name="{state[0].upper() + state[1:]}" '
            f'id="{state_anim_ids[state]}">\n' + indent("\n".join(keyed), "    ") + '\n</LinearAnimation>')


def shape_animation(shape):
    keyed = "\n".join(
        f'<KeyedObject objectId="{body_shape_ids[s]}">\n    <KeyedProperty propertyKey="{OPACITY}">\n'
        f'        <KeyFrameDouble value="{1 if s == shape else 0}" frame="0"/>\n    </KeyedProperty>\n</KeyedObject>'
        for s in SHAPES)
    return (f'<LinearAnimation fps="60" duration="1" loopValue="oneShot" name="Shape{shape[0].upper() + shape[1:]}" '
            f'id="{shape_anim_ids[shape]}">\n' + indent(keyed, "    ") + '\n</LinearAnimation>')


def transition(to_id, vm_prop, enum_value_id):
    return f'''<StateTransition stateToId="{to_id}" duration="160">
    <TransitionViewModelCondition opValue="equal">
        <TransitionPropertyViewModelComparator>
            <BindablePropertyEnum>
                {bind(vm_prop, 637)}
            </BindablePropertyEnum>
        </TransitionPropertyViewModelComparator>
        <TransitionValueEnumComparator value="{enum_value_id}"/>
    </TransitionViewModelCondition>
</StateTransition>'''


def layer(name, lid, entries, node_ids, anim_ids, enum_ids, vm_prop, default):
    transitions = "\n".join(transition(node_ids[e], vm_prop, enum_ids[e]) for e in entries)
    states = "\n".join(f'<AnimationState x="200" y="{40 + 60 * i}" animationId="{anim_ids[e]}" id="{node_ids[e]}"/>'
                       for i, e in enumerate(entries))
    return f'''<StateMachineLayer name="{name}" id="{lid}">
    <EntryState>
        <StateTransition stateToId="{node_ids[default]}"/>
    </EntryState>
    <AnyState x="220" y="-140">
{indent(transitions, "        ")}
    </AnyState>
    <ExitState x="430" y="-140"/>
{indent(states, "    ")}
</StateMachineLayer>'''


def enum_block(name, eid, entries, ids):
    values = "\n".join(f'    <DataEnumValue key="{e}" value="{e[0].upper() + e[1:]}" id="{ids[e]}"/>' for e in entries)
    return f'<DataEnumCustom name="{name}" id="{eid}">\n{values}\n</DataEnumCustom>'


doc = f'''<Rive version="1" kind="fragment">
    <!--
        The Letta agent mascot. GENERATED by scratch gen_mascot_rml.py from the spec in MASCOT.md,
        then hand-edited: treat this file as the source of truth.

        Inputs the host writes on the Avatar view model (names are the contract with
        RiveAvatarContract.kt; renaming one breaks the app silently):
          shape     - identity, MascotShape enum key; written once on load
          color     - identity, ARGB; every body fill is bound to it
          state     - the director's arbitrated AvatarState, as an enum key
          mouthOpen - 0..1 speech amplitude
          lookX/Y   - -1..1 gaze
          blink     - fire-once

        Two state-machine layers, both keyed from AnyState by view-model enum: `Shape` shows one
        of eight bodies by opacity, `State` poses the face and moves the body. State animations
        never key body colour - colour is identity.
    -->
    <Artboard defaultStateMachineId="{SM}" viewModelId="{VM}" viewModelInstanceId="{VM_INSTANCE}"
              clip="true" width="500" height="500" name="Mascot" id="0:2">

        <!-- Face first: the first sibling draws on top. -->
        <Node x="0" y="0" name="Gaze" id="0:20">
            {bind(VM_LOOKX, X, CONV_GAZE_X)}
            {bind(VM_LOOKY, Y, CONV_GAZE_Y)}

{indent(eye("EyeLeft", EYE_L, EYE_L_PATH, 205), "            ")}
{indent(eye("EyeRight", EYE_R, EYE_R_PATH, 295), "            ")}
{indent(overlay_arc("HappyLeft", HAPPY_L, 205), "            ")}
{indent(overlay_arc("HappyRight", HAPPY_R, 295), "            ")}
{indent(overlay_bar("ClosedLeft", CLOSED_L, 205), "            ")}
{indent(overlay_bar("ClosedRight", CLOSED_R, 295), "            ")}
{indent(cross("CrossLeft", CROSS_L, 205), "            ")}
{indent(cross("CrossRight", CROSS_R, 295), "            ")}
        </Node>

        <!-- Mouth: hidden except while speaking; height grows from the closed size with mouthOpen. -->
        <Shape x="250" y="330" opacity="0" name="Mouth" id="{MOUTH}">
            <Ellipse width="90" height="10" name="Path" id="0:31">
                {bind(VM_MOUTH, HEIGHT, CONV_MOUTH)}
            </Ellipse>
            <Fill name="Fill"><SolidColor colorValue="{INK}" name="Color"/></Fill>
        </Shape>

        <!-- Bodies, all at one origin; the Shape layer shows exactly one. Declared last so they draw under the face. -->
        <Node x="0" y="0" name="Body" id="{BODY_NODE}">
{indent(chr(10).join(body(s[0].upper() + s[1:], body_shape_ids[s], BODIES[s]) for s in SHAPES), "            ")}
        </Node>

{indent(chr(10).join(state_animation(s) for s in STATES), "        ")}

{indent(chr(10).join(shape_animation(s) for s in SHAPES), "        ")}

        <StateMachine name="Avatar" id="{SM}">
{indent(layer("Shape", "3:2", SHAPES, shape_node_ids, shape_anim_ids, shape_enum_ids, VM_SHAPE, "circle"), "            ")}
{indent(layer("State", "3:1", STATES, state_node_ids, state_anim_ids, state_enum_ids, VM_STATE, "idle"), "            ")}
        </StateMachine>
    </Artboard>

{indent(enum_block("AvatarState", ENUM_STATE, STATES, state_enum_ids), "    ")}
{indent(enum_block("MascotShape", ENUM_SHAPE, SHAPES, shape_enum_ids), "    ")}

    <!-- -1..1 gaze into pixels of eye travel, clamped so a bad value cannot fling the eyes off. -->
    <DataConverterRangeMapper minInput="-1" maxInput="1" minOutput="-22" maxOutput="22"
                              clampLower="true" clampUpper="true" name="GazeX" id="{CONV_GAZE_X}"/>
    <DataConverterRangeMapper minInput="-1" maxInput="1" minOutput="-14" maxOutput="14"
                              clampLower="true" clampUpper="true" name="GazeY" id="{CONV_GAZE_Y}"/>
    <!-- 0..1 amplitude into mouth height. The floor is the closed mouth, not zero. -->
    <DataConverterRangeMapper minInput="0" maxInput="1" minOutput="10" maxOutput="86"
                              clampLower="true" clampUpper="true" name="MouthHeight" id="{CONV_MOUTH}"/>

    <ViewModel defaultInstanceId="{VM_INSTANCE}" name="Avatar" id="{VM}">
        <ViewModelPropertyEnumCustom enumId="{ENUM_STATE}" name="state" id="{VM_STATE}"/>
        <ViewModelPropertyNumber name="mouthOpen" id="{VM_MOUTH}"/>
        <ViewModelPropertyNumber name="lookX" id="{VM_LOOKX}"/>
        <ViewModelPropertyNumber name="lookY" id="{VM_LOOKY}"/>
        <ViewModelPropertyTrigger name="blink" id="{VM_BLINK}"/>
        <ViewModelPropertyEnumCustom enumId="{ENUM_SHAPE}" name="shape" id="{VM_SHAPE}"/>
        <ViewModelPropertyColor name="color" id="{VM_COLOR}"/>

        <ViewModelInstance exports="true" name="Default" id="{VM_INSTANCE}">
            <ViewModelInstanceEnum propertyValue="{state_enum_ids['idle']}" viewModelPropertyId="{VM_STATE}"/>
            <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_MOUTH}"/>
            <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_LOOKX}"/>
            <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_LOOKY}"/>
            <ViewModelInstanceTrigger viewModelPropertyId="{VM_BLINK}"/>
            <ViewModelInstanceEnum propertyValue="{shape_enum_ids['circle']}" viewModelPropertyId="{VM_SHAPE}"/>
            <ViewModelInstanceColor propertyValue="FF1E7BF0" viewModelPropertyId="{VM_COLOR}"/>
        </ViewModelInstance>
    </ViewModel>
</Rive>
'''

import sys
out = sys.argv[1]
with open(out, "w", encoding="utf-8", newline="\n") as f:
    f.write(doc)
print(f"wrote {out} ({doc.count(chr(10))} lines)")
