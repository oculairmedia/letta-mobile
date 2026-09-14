"""The design numbers: state vocabulary lookups, tunables, timings, distances, tint colours.

Owns everything the rig can retune without moving an object - the waits, the saccade table, the
designed entry pairs, the turn and lean amplitudes - plus the unit helpers (art, rad, frames,
beat). Object ids live in rig/ids.py and are imported here only where a table names one. Read by
every other rig module and by gen_scene.py; it imports nothing from them.

Rive rules that bite here:
  - units are not interchangeable: `LinearAnimation.duration` is FRAMES (frames() converts ms at
    60 fps), `StateTransition.duration` is MS, and rotations are RADIANS (rad() converts degrees).
    A number that goes into a transition must stay ms; one that goes into an animation must not.
  - beat() stretches a beat by BEAT_TEMPO; waits, breath and state entries stay on plain frames().
"""
import math
import os
from typing import NamedTuple

from rig.ids import (
    GLYPH_SCALE_NODE, IDLE_A_NODE, IDLE_B_NODE, IDLE_C_NODE, IDLE_D_NODE, IDLE_WAIT_A, IDLE_WAIT_B,
    IDLE_WAIT_C, IDLE_WAIT_D, MOUTH_NODE, PLATE_SCALE_NODE, SACCADE_FIX_ANIMS, SACCADE_WAIT_ANIMS,
    STATES, TUNE_ANIM, VM_TUNE, WANDER_A_NODE, WANDER_B_NODE, WANDER_C_NODE, WANDER_D_NODE,
    WANDER_WAIT_A, WANDER_WAIT_B, WANDER_WAIT_C, WANDER_WAIT_D,
)
from rig.layers import Layer, OnInput, State


# The art lives beside gen_scene.py, one level above this package.
ART = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "art")


def art(name):
    """The absolute path of an art file in ./art."""
    return os.path.join(ART, name)


def rad(deg):
    """Degrees as the radians every Rive rotation property wants."""
    return round(math.radians(deg), 5)


def frames(ms):
    """Milliseconds as the whole 60 fps frames a LinearAnimation duration wants."""
    return round(ms * 60 / 1000)


# Art direction: every beat (idle, wander, hover, saccade holds) was read as too quick at product
# sizes. beat() stretches a beat's timing; waits, breath and state entries keep frames().
BEAT_TEMPO = 1.4


# The blend policy, in ms: how long a beat takes to mix in over the layers below it and how long
# it takes to let go again. A beat's keys sit on top of Breath and the host's turn on the same
# nodes, so both ends are blends - a cut would snap those values (see rig/seams.py). One table
# instead of the same numbers retyped per state in rig/machine.py.
BLEND = {"beat_in": 160, "beat_out": 320, "wander_in": 200, "wander_out": 320, "hover_out": 260}


def beat(ms):
    """Milliseconds as frames, stretched by BEAT_TEMPO - the timing for anything read as a beat."""
    return frames(ms * BEAT_TEMPO)


EXPR = {s: i for i, s in enumerate(STATES)}


SHAPE_SVG = {"circle": "body-circle.svg", "blob": "body-blob.svg", "roundedSquare": "body-squircle.svg",
             "pill": "body-pill.svg", "triangle": "body-triangle.svg", "hexagon": "body-hexagon.svg",
             "cloud": "body-cloud.svg", "drop": "body-drop.svg"}


DEFAULT_SHAPE = "blob"


class Tunable(NamedTuple):
    """One bench tunable: the view-model number, the pose-range timeline it scrubs, and its span."""
    vm_id: str
    anim: str
    node: str
    props: tuple
    span: tuple      # (value at 0, value at 1)


# Art-direction tunables (bench only, not in the app contract): 0..1 scrubbing a pose range, 0.5 = shipped.
TUNABLES = {
    "tunePlate": Tunable(VM_TUNE["tunePlate"], TUNE_ANIM["tunePlate"], PLATE_SCALE_NODE, ("SX", "SY"), (0.6, 1.4)),
    "tuneGlyph": Tunable(VM_TUNE["tuneGlyph"], TUNE_ANIM["tuneGlyph"], GLYPH_SCALE_NODE, ("SX", "SY"), (0.4, 1.6)),
    "tuneMouth": Tunable(VM_TUNE["tuneMouth"], TUNE_ANIM["tuneMouth"], MOUTH_NODE, ("SX", "SY"), (0.5, 1.5)),
    "tuneMouthY": Tunable(VM_TUNE["tuneMouthY"], TUNE_ANIM["tuneMouthY"], MOUTH_NODE, ("Y",), (42, 122)),
}


class PupilWave(NamedTuple):
    """The squiggle loop a state gives the pupil overlay: its period and its travel in px."""
    period_ms: int
    amplitude: float


# Astra Max pupil overlay (SPEC 10.2): built, reviewed, and dropped - the pure glyph stays. The
# assembly remains in the file at opacity 0 so the art pass can revisit it; PUPIL is empty.
PUPIL = {}   # state: PupilWave(period ms, amplitude px); empty = never shown


WAVE_STROKE = 5   # spec says 8; that reads as a bar at hero size


PUPIL_PARALLAX = (1.5, 1.0)


class SaccadeWait(NamedTuple):
    """One wait the plate's Saccade layer sits in before it re-fixates: its animation and length."""
    anim: str
    ms: int


class SaccadeFix(NamedTuple):
    """One fixation the Saccade layer can hop to: its animation, its offset in px, its pick weight."""
    anim: str
    offset: tuple
    weight: int


# Saccade layer: random waits, then a 60 ms hop to one of a few small fixations, a hold, a hop back.
SACCADE_WAITS = [SaccadeWait(aid, ms) for aid, ms in zip(SACCADE_WAIT_ANIMS, (3600, 7200, 10400))]


# Eyes Alive direction distribution - down 20, up 18, left 17, right 16, diagonals 6-8 - with
# cardinal hops larger than diagonal ones (magnitudes skew small).
SACCADE_FIX = [SaccadeFix(aid, offset, weight) for aid, (offset, weight) in zip(
    SACCADE_FIX_ANIMS, [((0, 5), 20), ((0, -5), 18), ((-6, 0), 17), ((6, 0), 16),
                        ((-3, -3), 7), ((3, -3), 6), ((-3, 3), 8), ((3, 3), 8)])]


TURN_ARC = 7       # a turn is an arc, not a slide: the plate lifts TURN_ARC px through the centre


HOST_BODY_PX = 6   # the body shifts toward what the head turns to (commit motion)


LEAN_BASE = 150    # the lean pivots LEAN_BASE px below centre, at the body's base


# Vector deformation (SPEC 10.5 / art/mesh/DEFORMATION-SPEC.md): three bones - crown (0,-150),
# middle (0,0), base (0,+150) - skinned to every body path. A force pose scales each bone's X by
# S-L / S / S+L and Y by 1/S, and shears crown/base by -+150 H. Weights come from each vertex's
# rest y (wc = -y/150, wb = y/150, wm = the rest) on the default body; Rive weights are static per
# vertex, so the other identities share them (their vertex order matches, positions nearly do).
BONE_REACH = 150


HOST_LEAN_DEG, TURN_LEAN_DEG = 9, 5                      # lean at host turn +-1 / at state facing +-1


HOST_TURN_PX, HOST_TURN_PY, HOST_TURN_DEG, HOST_BODY_DEG = 48, 18, 10, 5


JX, JY = 299, 300  # Joystick x / y


DESIGNED_PAIRS = {("idle", "listening"): 300, ("listening", "thinking"): 300, ("thinking", "speaking"): 200,
                  ("speaking", "idle"): 240, ("error", "idle"): 300}  # SPEC section 3, ms


class Wait(NamedTuple):
    """One do-nothing wait state on the IdleVariety or Wander layer: animation, state node, length."""
    anim: str
    node: str
    ms: int


# Waits of unequal, non-multiple lengths picked at random: several mascots on one screen must not
# fire their beats in lockstep (they all start at the same instant), so the cycle never repeats.
IDLE_WAITS = [Wait(IDLE_WAIT_A, IDLE_A_NODE, 8000), Wait(IDLE_WAIT_B, IDLE_B_NODE, 14000),
              Wait(IDLE_WAIT_C, IDLE_C_NODE, 6100), Wait(IDLE_WAIT_D, IDLE_D_NODE, 10700)]


WANDER_WAITS = [Wait(WANDER_WAIT_A, WANDER_A_NODE, 12000), Wait(WANDER_WAIT_B, WANDER_B_NODE, 24000),
                Wait(WANDER_WAIT_C, WANDER_C_NODE, 9300), Wait(WANDER_WAIT_D, WANDER_D_NODE, 17500)]


BLINK_SHUT, BLINK_FRAMES = 4, 15   # frames to fully shut / total


BLINK_FLIP = 6                     # entries flip the glyph here: the plate sees the trigger a frame or two late


INK = "FF111111"


PLATE_WHITE = "FFF7F7F7"


EXPRESSION_CUT = "the plate's expression matrix: the swap is instant under the blink shutter"


def expression_layer(*parts):
    """The XML of an expression layer: one state per STATES entry, every other state one cut away.

    Explicit matrix, instant cuts: the root hides every swap inside a blink shutter, and an
    AnyState fan-out would keep re-entering the current state (a self-blend that fades the glyph).
    Every state is signed `cut=True` once, which is what lets the blend policy in rig/layers.py
    accept a matrix of 0 ms transitions that swap a glyph and a mouth outright.
    """
    name, lid, input_id, anim_ids, node_ids = parts
    states = [State(anim_ids[s], node_ids[s], i, cut=True, reason=EXPRESSION_CUT,
                    transitions=[OnInput(node_ids[t], input_id, EXPR[t], 0)
                                 for t in STATES if t != s])
              for i, s in enumerate(STATES)]
    return Layer(name, lid, node_ids["idle"], states=states).rml()
