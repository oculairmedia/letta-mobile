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
    Turn     - keyed only by the Facing joystick (TurnX/TurnY): slides and foreshortens the plate,
               rotates and squeezes the body; state entries, Wander and the success spin key it
    Plate    - NestedArtboard; `expr` picks the glyph and the mouth, LookX/LookY move the glyph,
               Open morphs the mouth, `blink` squashes the glyph
    Layers   - Shape (identity), Expression (sustained loops + one Enter_<from>_<to> one-shot per
               pair: blink shutter, glyph flip under it, facing eased), Breath (gloss),
               Flash (success/error, self-returning), Drag, IdleVariety, Wander, Blink, Hover
  Plate (component, input driven; its Expression layer is an explicit matrix of instant cuts)

No AnyState anywhere: it is evaluated before a state's own transitions and re-enters the
current state while its condition holds (a self-blend that faded every glyph in).
See README.md for the loop, ids and the full gotcha list.

DEVIATIONS from SPEC.md, all intentional and small:
  - no `-small` profile yet (no host signal); standard geometry everywhere
  - drag tilt needs pointer velocity the file cannot see: 0 degrees
  - the idle glance moves the plate 2 degrees / 2 px instead of the glyph (the root cannot key a
    nested artboard's node); the eye-only glance belongs in the editor pass
  - the glyph shutter is the plate blink (scaleY on the Glyphs node), fired by every entry
  - speaking mouth opacity is smoothstep-free: visible while expr is speaking/dragged
"""
import os
import re
from textwrap import indent

from rml import (BACK_OUT, BACK_SOFT, ELASTIC_HEAVY, ELASTIC_SOFT, GRADIENT_OPACITY, ROT, SINE, SX, SY, VM, VM_BLINK, VM_COLOR,
                 VM_DRAGGED, VM_ERROR, VM_HOVER, VM_LOOKX, VM_LOOKY, VM_MOUTH, VM_SHAPE, VM_STATE,
                 VM_SUCCESS, Y, anim_state, animation, bind, layer_frame)
from rig.body import INFLATE_NODE, body, sine, squash
from rig.constants import (DEFAULT_SHAPE, HOST_BODY_DEG, HOST_BODY_PX, HOST_LEAN_DEG, HOST_TURN_DEG,
                           HOST_TURN_PX, HOST_TURN_PY, LEAN_BASE, TUNABLES, beat, frames, rad)
from rig.face import face, turn_animations
from rig.ids import (
    BLINK_ANIM, BLINK_REST_ANIM, BODY_NODE, BREATH_ANIM, CONV_BODY_ROT, CONV_BODY_X, CONV_DEGREES, CONV_LEAN,
    CONV_LOOK, CONV_MOUTH, CONV_SCALE, CONV_TURN_ROT, CONV_TURN_X, CONV_TURN_Y, ENTITY, ENUM_SHAPE,
    ENUM_STATE, FACE, GLOSS, HOVER_ANIM, HOVER_HELD_ANIM, HOVER_REST_ANIM, JOYSTICK, LEAN_NODE,
    PLATE_BLINK, ROOT, SHAPES, SM, SUSTAINED, TURN_X_ANIM, TURN_Y_ANIM, VM_INSTANCE, VM_TUNE_SCALE,
    VM_SHAPE_ROTATION, VM_TURN_X, VM_TURN_Y, shape_enum_ids, state_enum_ids,
)
from rig.layers import set_animation_index
from rig.machine import root_machine
from rig.seams import animation_index
from rig.motion import (enter_animations, idle_variety_animations, momentary_animations,
                        shape_animations, sustained_animations, wander_animations)
from rig.plate import plate_component


# Solo mode (onion.py --animation): a throwaway extra state machine that plays one named
# LinearAnimation outright, so an animation that normally sits behind a random wait can be
# screenshotted. `Avatar` stays in the file untouched; only defaultStateMachineId changes, and
# only when solo is asked for - the default output must stay byte-identical. The three ids are
# registered in rig/ids.py's _EXTERNAL so the allocator never hands them out.
SOLO_SM, SOLO_LAYER, SOLO_STATE = "3:900", "3:901", "3:902"


def _animation_id(document, name):
    m = re.search(r'<LinearAnimation[^>]*\bname="%s" id="([^"]+)"' % re.escape(name), document)
    if not m:
        raise SystemExit(f"no animation named {name!r} in the generated document")
    return m.group(1)


def _solo_machine(anim_id):
    layer = layer_frame("Solo", SOLO_LAYER, SOLO_STATE, "", anim_state(anim_id, SOLO_STATE, 0))
    return f'<StateMachine name="Solo" id="{SOLO_SM}">\n{indent(layer, "    ")}\n</StateMachine>'


def root_artboard(solo=None):
    """The Mascot artboard: the node tree, every root animation, and the `Avatar` state machine."""
    breath = animation("Breath", BREATH_ANIM, frames(4600), {GLOSS: {GRADIENT_OPACITY: sine(0.05, 4600, 1.0)}}, "loop")
    blink_rest = animation("BlinkRest", BLINK_REST_ANIM, 1, {})
    blink = animation("BlinkFire", BLINK_ANIM, 2, {}, callbacks=(PLATE_BLINK,))
    hover_rest = animation("HoverRest", HOVER_REST_ANIM, 1, {})
    # The perk takes 200 ms to arrive instead of 160 and its two rolls leave on BACK_SOFT and settle
    # on ELASTIC_HEAVY: BACK_OUT put a fifth of the whole roll into the second frame, which is what
    # the product owner was reading as too lively (letta-mobile-q55am). The lift and the inflate are
    # a translation and a scale and keep their own tokens; they are re-timed only to stay in step.
    d = beat(520)
    hover = animation("HoverPerk", HOVER_ANIM, d, dict(
        squash(INFLATE_NODE, [(0, 1, BACK_OUT), (beat(200), 0.92, None), (beat(320), 0.92, ELASTIC_SOFT), (d, 0.97)]),
        **{FACE: {Y: [(0, 0, BACK_OUT), (beat(200), -9, None), (beat(320), -9, ELASTIC_SOFT), (d, -5)],
                  ROT: [(0, 0, BACK_SOFT), (beat(200), rad(-3), None), (beat(320), rad(-3), ELASTIC_HEAVY), (d, rad(-2))]},
           BODY_NODE: {ROT: [(0, 0, BACK_SOFT), (beat(200), rad(-4), None), (beat(320), rad(-4), ELASTIC_HEAVY), (d, rad(-2))]}}))
    # Held while hovered: the perk's end pose, breathing a little faster in the face lift.
    hover_held = animation("HoverHeld", HOVER_HELD_ANIM, beat(2400), dict(
        squash(INFLATE_NODE, [(0, 0.97, SINE), (beat(1200), 0.95, SINE), (beat(2400), 0.97)]),
        **{FACE: {Y: [(0, -5, SINE), (beat(1200), -7, SINE), (beat(2400), -5)], ROT: [(0, rad(-2))]},
           BODY_NODE: {ROT: [(0, rad(-2))]}}), "loop")
    anims = (shape_animations() + sustained_animations() + enter_animations() + momentary_animations() + idle_variety_animations()
             + turn_animations() + wander_animations() + [breath, blink_rest, blink, hover_rest, hover, hover_held])
    # The blend policy (MOTION-PIPELINE step A): every animation this artboard plays, indexed by
    # its keyed ends, so root_machine()'s layers can refuse a 0 ms transition that tears a value.
    # The animations exist before the machine does, so the index is simply the XML just built.
    set_animation_index(animation_index("\n".join(anims)))
    board = f'''<Artboard defaultStateMachineId="{SM}" viewModelId="{VM}" viewModelInstanceId="{VM_INSTANCE}"
          x="0" y="0" styleId="0:3" clip="false" width="500" height="500" name="Mascot" id="{ROOT}">
    <LayoutComponentStyle name="Style" id="0:3"/>
    <!-- Facing: one 2-D value scrubbing TurnX/TurnY; keyed by state entries, Wander and flashes. -->
    <Joystick posX="250" posY="262" width="140" height="48" xId="{TURN_X_ANIM}" yId="{TURN_Y_ANIM}" x="-0.15" y="0" name="Facing" id="{JOYSTICK}"/>
    <!-- Entity: everything drawn, scaled as one about the body centre by the tuneScale number. -->
    <Node x="250" y="270" name="Entity" id="{ENTITY}">
        {bind(VM_TUNE_SCALE, SX, CONV_SCALE)}
        {bind(VM_TUNE_SCALE, SY, CONV_SCALE)}
        <!-- Lean: pivot at the base; children sit LEAN_BASE above it so the centre stays put. -->
        <Node x="0" y="{LEAN_BASE}" name="Lean" id="{LEAN_NODE}">
            {bind(VM_TURN_X, ROT, CONV_LEAN)}
{indent(face(), "            ")}
{indent(body(), "            ")}
        </Node>
    </Node>

{indent(chr(10).join(anims), "    ")}

{indent(root_machine(), "    ")}
</Artboard>'''
    if not solo:
        return board
    machine = indent(_solo_machine(_animation_id(board, solo)), "    ")
    return board.replace(f'defaultStateMachineId="{SM}"', f'defaultStateMachineId="{SOLO_SM}"', 1) \
                .replace("\n</Artboard>", f"\n\n{machine}\n</Artboard>")


def data():
    """The document's data section: the two enums, the converters and the Avatar view model."""
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
<DataConverterRangeMapper minInput="0" maxInput="1" minOutput="0.5" maxOutput="1.5"
                          clampLower="true" clampUpper="true" name="TuneToScale" id="{CONV_SCALE}"/>
<DataConverterRangeMapper minInput="-1" maxInput="1" minOutput="{-HOST_TURN_PX}" maxOutput="{HOST_TURN_PX}"
                          clampLower="true" clampUpper="true" name="TurnToX" id="{CONV_TURN_X}"/>
<DataConverterRangeMapper minInput="-1" maxInput="1" minOutput="{-HOST_TURN_PY}" maxOutput="{HOST_TURN_PY}"
                          clampLower="true" clampUpper="true" name="TurnToY" id="{CONV_TURN_Y}"/>
<DataConverterRangeMapper minInput="-1" maxInput="1" minOutput="{rad(-HOST_TURN_DEG)}" maxOutput="{rad(HOST_TURN_DEG)}"
                          clampLower="true" clampUpper="true" name="TurnToRoll" id="{CONV_TURN_ROT}"/>
<DataConverterRangeMapper minInput="-1" maxInput="1" minOutput="{rad(-HOST_BODY_DEG)}" maxOutput="{rad(HOST_BODY_DEG)}"
                          clampLower="true" clampUpper="true" name="TurnToBodyRoll" id="{CONV_BODY_ROT}"/>
<DataConverterRangeMapper minInput="-1" maxInput="1" minOutput="{-HOST_BODY_PX}" maxOutput="{HOST_BODY_PX}"
                          clampLower="true" clampUpper="true" name="TurnToBodyX" id="{CONV_BODY_X}"/>
<DataConverterRangeMapper minInput="-1" maxInput="1" minOutput="{rad(-HOST_LEAN_DEG)}" maxOutput="{rad(HOST_LEAN_DEG)}"
                          clampLower="true" clampUpper="true" name="TurnToLean" id="{CONV_LEAN}"/>
<DataConverterRangeMapper minInput="0" maxInput="360" minOutput="0" maxOutput="{rad(360)}"
                          clampLower="false" clampUpper="false" name="DegreesToRadians" id="{CONV_DEGREES}"/>

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
{indent(chr(10).join(f'<ViewModelPropertyNumber name="{n}" id="{t.vm_id}"/>' for n, t in TUNABLES.items()), "    ")}
    <ViewModelPropertyNumber name="tuneScale" id="{VM_TUNE_SCALE}"/>
    <ViewModelPropertyNumber name="turnX" id="{VM_TURN_X}"/>
    <ViewModelPropertyNumber name="turnY" id="{VM_TURN_Y}"/>
    <ViewModelPropertyNumber name="shapeRotation" id="{VM_SHAPE_ROTATION}"/>

    <ViewModelInstance exports="true" name="Default" id="{VM_INSTANCE}">
{indent(chr(10).join(f'<ViewModelInstanceNumber propertyValue="0.5" viewModelPropertyId="{t.vm_id}"/>' for t in TUNABLES.values()), "        ")}
        <ViewModelInstanceNumber propertyValue="0.5" viewModelPropertyId="{VM_TUNE_SCALE}"/>
        <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_TURN_X}"/>
        <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_TURN_Y}"/>
        <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{VM_SHAPE_ROTATION}"/>
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


def scene_document(solo=None):
    """The whole scene.rml document: the root artboard, the Plate component and the data section."""
    return f'''<Rive version="1" kind="fragment">
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
{indent(root_artboard(solo), "    ")}

{indent(plate_component(), "    ")}

{indent(data(), "    ")}
</Rive>
'''

if __name__ == "__main__":
    import sys
    # `python gen_scene.py [out.rml] [--solo <AnimationName>] [--probe]` - an explicit path lets a
    # check regenerate without touching scene.rml; --solo is onion.py's single-animation document
    # and --probe is probe.py's telemetry document. Both post-process the built document string
    # into a throwaway variant, so the default output stays byte-identical and neither debug
    # surface can ever reach scene.rml or a push.
    argv, solo = sys.argv[1:], None
    if "--solo" in argv:
        i = argv.index("--solo")
        if i + 1 >= len(argv):
            raise SystemExit("--solo needs an animation name")
        solo = argv[i + 1]
        del argv[i:i + 2]
    probe = "--probe" in argv
    if probe:
        argv.remove("--probe")
    if (solo or probe) and not argv:
        # A debug variant must never fall back onto the shipping document.
        raise SystemExit("--solo and --probe write a throwaway document: pass its output path "
                         "(python gen_scene.py <out.rml> --probe)")
    doc = scene_document(solo)
    if probe:
        from rig.probe import inject
        doc = inject(doc)
    out = argv[0] if argv else os.path.join(os.path.dirname(os.path.abspath(__file__)), "scene.rml")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(doc)
    print(f"wrote {out} ({doc.count(chr(10))} lines)")
