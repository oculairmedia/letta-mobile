"""The face assembly on the root artboard: turn, arc, lean, trails, and the facing joystick."""
import math

from rml import *  # noqa: F401,F403
from rig.constants import *  # noqa: F401,F403
from rig.body import fill, rrect, squash


# ================================================================================================
# Root
# ================================================================================================
TURN_PX, TURN_PY = 70, 24          # plate travel at facing +-1


TURN_SQUASH = 0.7                  # plate scaleX at the edges (foreshortening)


TURN_ROT = 14                      # plate roll (deg) at facing +-1; the body adds +-6 of its own


TURN_RECEDE = 0.84                 # plate scale at facing +-1: it is further from the viewer there


def trail(name, sid):
    return f'''<Shape x="0" y="0" opacity="0" name="{name}" id="{sid}">
    {rrect(120, 120, 27)}
    {fill(PLATE_WHITE)}
</Shape>'''


def face():
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
{indent(chr(10).join(f'<NestedRemapAnimation animationId="{aid}" time="0.5" name="{n}">{chr(10)}    {bind(vid, REMAP_TIME)}{chr(10)}</NestedRemapAnimation>' for n, (vid, aid, *_) in TUNABLES.items()), "        ")}
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
    """Pose ranges the joystick scrubs: frame 0 = facing -1 (left), 60 = +1 (right)."""
    # The plate slides, foreshortens AND rolls with the turn (+-TURN_ROT): a face turning on a
    # ball tilts its features; without the roll a turn reads as a flat slide.
    tx = animation("TurnX", TURN_X_ANIM, 60, {
        TURN_NODE: {X: [(0, -TURN_PX, LINEAR), (60, TURN_PX)],
                    ROT: [(0, rad(-TURN_ROT), LINEAR), (60, rad(TURN_ROT))],
                    # foreshorten (SX) and recede (SY too): the feature moves away as it turns
                    SX: [(0, TURN_SQUASH * TURN_RECEDE, LINEAR), (30, 1, LINEAR), (60, TURN_SQUASH * TURN_RECEDE)],
                    SY: [(0, TURN_RECEDE, LINEAR), (30, 1, LINEAR), (60, TURN_RECEDE)]},
        ARC_NODE: {Y: [(0, 0, SINE), (30, -TURN_ARC, SINE), (60, 0)]},
        LEAN_NODE: {ROT: [(0, rad(-TURN_LEAN_DEG), LINEAR), (60, rad(TURN_LEAN_DEG))]},
        BODY_NODE: {ROT: [(0, rad(-6), LINEAR), (60, rad(6))],
                    SX: [(0, 0.93, LINEAR), (30, 1, LINEAR), (60, 0.93)]}})
    ty = animation("TurnY", TURN_Y_ANIM, 60, {
        TURN_NODE: {Y: [(0, -TURN_PY, LINEAR), (60, TURN_PY)],
                    SY: [(0, 0.9 * TURN_RECEDE, LINEAR), (30, 1, LINEAR), (60, 0.9 * TURN_RECEDE)],
                    SX: [(0, TURN_RECEDE, LINEAR), (30, 1, LINEAR), (60, TURN_RECEDE)]},
        BODY_NODE: {SY: [(0, 1.03, LINEAR), (30, 1, LINEAR), (60, 0.96)]}})
    return [tx, ty]


def spin_keys(start, dur, trails=True):
    """A whip-around: facing 0 -> +1 -> -1 -> 0 over `dur` frames from `start`, trails lagging."""
    a, b, c, d = start, start + round(dur * 0.25), start + round(dur * 0.62), start + dur
    x = [(a, 0, BACK_IN), (b, 1, STANDARD), (c, -1, ELASTIC_OUT), (d, 0)]
    keys = {JOYSTICK: {JX: x},
            BODY_NODE: {ROT: [(a, 0, BACK_IN), (b, rad(14), STANDARD), (c, rad(-14), ELASTIC_OUT), (d, 0)]}}
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
