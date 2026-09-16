"""The face assembly on the root artboard: the plate's placement, turn, arc, trails and remaps.

Owns the FacePlacement > HostTurn > Turn > Arc > Face chain that carries the nested Plate, the
TurnX / TurnY pose ranges the Facing joystick scrubs, and spin_keys() (the whip-around the
success flash and Wander reuse). gen_scene.py draws face(); rig/motion.py reads spin_keys.

Rive rules that bite here:
  - placement and keyed motion must live on different nodes - a keyed x=0 overwrites a
    placement. FacePlacement holds the position, Turn takes only the joystick's keys, Face takes
    the states'. Adding a key to the wrong one silently parks the plate at the origin.
  - `NestedRemapAnimation.time` is a 0..1 FRACTION, not frames: the look, mouth and tunable
    remaps scrub a 60-frame pose range through a range-mapper converter, never a frame number.
"""
from textwrap import indent

from rml import (LINEAR, OPACITY, REMAP_TIME, ROT, SINE, SX, SY, VM_LOOKX, VM_LOOKY, VM_MOUTH, X, Y,
                 animation, bind)
from rig.body import fill, rrect
from rig.chart import Chart, Pen
from rig.constants import JX, LEAN_BASE, PLATE_WHITE, TUNABLES, TURN_ARC, TURN_LEAN_DEG, rad
from rig.ids import (
    ARC_NODE, AUTO_X, AUTO_Y, BODY_NODE, CONV_LOOK, CONV_MOUTH, CONV_TURN_ROT, CONV_TURN_X,
    CONV_TURN_Y, FACE, HOST_TURN, JOYSTICK, LEAN_NODE, PLATE, PLATE_AB, PLATE_AUTO_X, PLATE_AUTO_Y,
    PLATE_BLINK, PLATE_EXPR, PLATE_IN_BLINK, PLATE_IN_EXPR, PLATE_LOOKX, PLATE_LOOKY, PLATE_OPEN,
    PLATE_SM, TRAIL1, TRAIL2, TURN_NODE, TURN_X_ANIM, TURN_Y_ANIM, VM_TURN_X, VM_TURN_Y,
)


# ================================================================================================
# Root
# ================================================================================================
TURN_PX, TURN_PY = 70, 24          # plate travel at facing +-1


TURN_SQUASH = 0.7                  # plate scaleX at the edges (foreshortening)


TURN_ROT = 14                      # plate roll (deg) at facing +-1; the body adds +-6 of its own


TURN_RECEDE = 0.84                 # plate scale at facing +-1: it is further from the viewer there


def trail(name, sid):
    """A ghost copy of the plate card, invisible until the spin keys its opacity."""
    return f'''<Shape x="0" y="0" opacity="0" name="{name}" id="{sid}">
    {rrect(120, 120, 27)}
    {fill(PLATE_WHITE)}
</Shape>'''


def face():
    """The face assembly: the placement chain, the nested Plate with its inputs and remaps, trails."""
    # FacePlacement > Turn (keyed only by the joystick's TurnX/TurnY) > Face (keyed by state motion).
    # Trails are declared after Turn so they draw underneath the plate.
    return f'''<Node x="0" y="{-8 - LEAN_BASE}" name="FacePlacement">
<Node x="0" y="0" name="HostTurn" id="{HOST_TURN}">
    {bind(VM_TURN_X, X, CONV_TURN_X)}
    {bind(VM_TURN_X, ROT, CONV_TURN_ROT)}
    {bind(VM_TURN_Y, Y, CONV_TURN_Y)}
<Node x="0" y="0" name="Turn" id="{TURN_NODE}">
<Node x="0" y="0" name="Arc" id="{ARC_NODE}">
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
{indent(chr(10).join(f'<NestedRemapAnimation animationId="{t.anim}" time="0.5" name="{n}">{chr(10)}    {bind(t.vm_id, REMAP_TIME)}{chr(10)}</NestedRemapAnimation>' for n, t in TUNABLES.items()), "        ")}
        <NestedRemapAnimation animationId="{PLATE_AUTO_X}" time="0.5" name="AutoLookX" id="{AUTO_X}"/>
        <NestedRemapAnimation animationId="{PLATE_AUTO_Y}" time="0.5" name="AutoLookY" id="{AUTO_Y}"/>
    </NestedArtboard>
</Node>
</Node>
</Node>
</Node>
{indent(trail("Trail1", TRAIL1), "")}
{indent(trail("Trail2", TRAIL2), "")}
</Node>'''


def turn_animations():
    """Pose ranges the joystick scrubs: frame 0 = facing -1 (left), 60 = +1 (right).

    **One property, one axis.** The Facing joystick applies both pose ranges every frame, and the
    one applied last wins outright - there is no mixing between them. TurnX used to key Turn's
    scaleY as well as its scaleX, and TurnY the other way round, so whichever landed second
    flattened the other: `Turn.scaleX` read exactly 1.000 through every horizontal turn while
    `Turn.x` swung the full +-70 px, and the plate slid without ever foreshortening
    (letta-mobile-72r3a). So each axis now owns whole properties - TurnX: x, rotation, scaleX;
    TurnY: y, scaleY - and the recede is folded into whichever scale the owning axis carries.
    """
    # The plate slides, foreshortens AND rolls with the turn (+-TURN_ROT): a face turning on a
    # ball tilts its features; without the roll a turn reads as a flat slide.
    # TurnX owns scaleX: the horizontal foreshortening (TURN_SQUASH) and the recede together.
    tx = animation("TurnX", TURN_X_ANIM, 60, {
        TURN_NODE: {X: [(0, -TURN_PX, LINEAR), (60, TURN_PX)],
                    ROT: [(0, rad(-TURN_ROT), LINEAR), (60, rad(TURN_ROT))],
                    SX: [(0, TURN_SQUASH * TURN_RECEDE, LINEAR), (30, 1, LINEAR), (60, TURN_SQUASH * TURN_RECEDE)]},
        ARC_NODE: {Y: [(0, 0, SINE), (30, -TURN_ARC, SINE), (60, 0)]},
        LEAN_NODE: {ROT: [(0, rad(-TURN_LEAN_DEG), LINEAR), (60, rad(TURN_LEAN_DEG))]},
        BODY_NODE: {ROT: [(0, rad(-6), LINEAR), (60, rad(6))],
                    SX: [(0, 0.93, LINEAR), (30, 1, LINEAR), (60, 0.93)]}})
    # TurnY owns scaleY: the vertical foreshortening and the recede together.
    ty = animation("TurnY", TURN_Y_ANIM, 60, {
        TURN_NODE: {Y: [(0, -TURN_PY, LINEAR), (60, TURN_PY)],
                    SY: [(0, 0.9 * TURN_RECEDE, LINEAR), (30, 1, LINEAR), (60, 0.9 * TURN_RECEDE)]},
        BODY_NODE: {SY: [(0, 1.03, LINEAR), (30, 1, LINEAR), (60, 0.96)]}})
    return [tx, ty]


# The ticks of one swing, as distance fractions at evenly spaced frames: 18 / 32 / 32 / 18 % of
# the travel per quarter, so the swing eases out of one extreme and into the next without any
# quarter carrying more than a third. The in-betweens ARE the spacing, so the smoothing between
# them is linear - a bezier on every tick would re-ease each segment and put the snap back.
SPIN_SPACING = (0.18, 0.5, 0.82)


def spin_keys(start, dur, trails=True):
    """A whip-around: facing 0 -> +1 -> -1 -> 0 over `dur` frames from `start`, trails lagging.

    Authored as a timing chart, not as beziers. The old version leaned on BACK_IN into the first
    extreme and ELASTIC_OUT out of the last, and both are front-loaded to the point of a cut: the
    facing ran -1 -> -0.33 in a single frame, which is `Turn.x` moving 47 px and `Arc.y` 5.3 px
    between two frames - the largest delta anywhere in the probe set (letta-mobile-72r3a). Every
    swing now lays its in-betweens down as real keys (`rig/chart.py`: spacing IS the weight) with
    the travel spread evenly across the middle and eased only at the extremes, so the whip reads
    as a whip and no frame carries more than about a seventh of a swing.

    `dur` has to be long enough to hold that: the swing 1 -> -1 is two full units of facing, and
    at 70 px a unit a per-frame delta under 14 px needs roughly 16 frames for that swing alone.
    """
    a, b, c, d = start, start + round(dur * 0.26), start + round(dur * 0.68), start + dur
    swing = lambda f0, v0, f1, v1: Chart(extremes=[(f0, v0), (f1, v1)], spacing=list(SPIN_SPACING),
                                         pen=Pen(smooth=LINEAR)).keys()
    x = swing(a, 0, b, 1)[:-1] + swing(b, 1, c, -1)[:-1] + swing(c, -1, d, 0)
    roll = [(f, rad(TURN_ROT * v)) + tuple(rest) for (f, v, *rest) in x]
    keys = {JOYSTICK: {JX: x}, BODY_NODE: {ROT: roll}}
    if trails:
        def lagged(lag, scale):
            # the facing curve, delayed `lag` frames and mapped through `scale`, keeping each key's bezier
            return [(k[0] + lag, scale(k[1])) + tuple(k[2:]) for k in x]
        for tid, lag, alpha in ((TRAIL1, 2, 0.28), (TRAIL2, 4, 0.14)):
            keys[tid] = {
                X: lagged(lag, lambda v: v * TURN_PX),
                ROT: lagged(lag, lambda v: rad(v * TURN_ROT)),
                OPACITY: [(a, 0, LINEAR), (a + lag + 1, alpha, None), (c, alpha, LINEAR), (d, 0)],
            }
    return keys
