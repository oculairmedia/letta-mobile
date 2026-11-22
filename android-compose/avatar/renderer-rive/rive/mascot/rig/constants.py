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
BLEND = {"beat_in": 160, "beat_out": 320, "wander_in": 200, "wander_out": 320, "hover_out": 260,
         # te3n1.5: a loop and its reduceMotion twin trade places over `calm`; the pet sway mixes
         # in over `pet_in` and lets go over `pet_out`.
         "calm": 300, "pet_in": 250, "pet_out": 320}


# reduceMotion: an entry hands off to the calm twin over at most this many ms (te3n1.5). It was
# 80; the minimum transition time (MIN_TRANSITION_MS, below) raised it.
CALM_HANDOFF = 100


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


# te3n1.5 host channels. listenLevel 0..1: the face lifts LISTEN_LIFT_PX and grows to LISTEN_SCALE.
LISTEN_LIFT_PX, LISTEN_SCALE = 10, 1.05


# moveX -1..1: the whole body leans about its feet, top trailing the travel, up to MOVE_LEAN_DEG.
# moveY -1..1 (+ is down): (scale at -1, scale at +1) - travelling up stretches, down squashes.
MOVE_LEAN_DEG = 8
MOVE_STRETCH, MOVE_SPREAD = (1.06, 0.95), (0.96, 1.04)


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


# The minimum transition time: no change of state may move a pose in less than this. At 0 ms a
# state change lands in ONE frame, and a pose that jumps in one frame always reads as a glitch -
# the loop being left mid-bob, mid-lean, mid-turn snaps to the next animation's frame 0. Every
# transition that moves a pose property blends for at least this long; rig/layers.py refuses one
# that does not, from any frame its source can be left at (rig/seams.py pose_moves), and no
# signature excuses it. Only discrete properties still cut - the glyph index `expr` (a blend would
# sweep through every glyph between) and a Solo reference (it has no in-between).
#
# 100 ms is six frames at 60 fps - long enough that the eye reads travel, not a jump, and short
# enough not to blur a request into lag. It is also the ceiling for the blend INTO an entry: the
# entry flips `expr` at BLINK_FLIP, and that flip must land after the blend has finished, or the
# blend mixes the old index with the new one and sweeps (rig/layers.py refuses that too).
MIN_TRANSITION_MS = 100


# An entry may be interrupted by the next request only after its glyph has flipped: before the
# flip, the entry still shows the old glyph, and a blend into the next entry (which starts on the
# NEW one) would mix the two indices. A request inside that window waits these few ms.
ENTRY_FLIP_MS = round(BLINK_FLIP * 1000 / 60)
assert MIN_TRANSITION_MS <= ENTRY_FLIP_MS, "the blend into an entry must end before its expr flip"


INK = "FF111111"


PLATE_WHITE = "FFF7F7F7"


def expression_layer(*parts):
    """The XML of an expression layer: one state per STATES entry, every other state one blend away.

    Explicit matrix: an AnyState fan-out would keep re-entering the current state (a self-blend
    that fades the glyph). Each swap blends over MIN_TRANSITION_MS: the glyph is a Solo reference,
    which has no in-between, so it swaps on the first frame of the blend - under the blink the
    root fires - while the mouth fades and the card eases to its scale for the new state, instead
    of popping in one frame (they are not under the shutter: it squashes the glyph only). The
    input itself never sweeps: the root keys `expr` only on layers that hold it still across a
    blend, or cut it (rig/layers.py refuses a blend that would sweep it).
    """
    name, lid, input_id, anim_ids, node_ids = parts
    states = [State(anim_ids[s], node_ids[s], i,
                    transitions=[OnInput(node_ids[t], input_id, EXPR[t], MIN_TRANSITION_MS)
                                 for t in STATES if t != s])
              for i, s in enumerate(STATES)]
    return Layer(name, lid, node_ids["idle"], states=states).rml()


# --- secondaries and sparkle (letta-mobile-su08n) ------------------------------------------------
# Emmanuel's pick: "moments + loops" secondaries and "star glints" sparkle.
#
# LOOP_LAG_FRAMES: in these sustained loops the face trails the body by this many frames - the
# plate's motion is the body's, phase-shifted, so it arrives a beat late at every extreme (overlap,
# MOTION-REFERENCES 3.3). Faint on purpose: at most ~2 px of separation on the 11 px breath bob.
# A state not listed moves face and body together, as before. reduceMotion holds the loop's frame 0.
LOOP_LAG_FRAMES = {"idle": 14, "listening": 14, "speaking": 14, "thinking": 12, "working": 8}

# OVERLAP: per moment, the secondary action on top of the main beat, as keyframe tracks
# [(frame, value[, bezier])]. Frames are counted from the trigger, on the main beat's own clock
# (rig/motion.py react_animations / momentary_animations), so every extreme lands a few frames
# AFTER the primary one it follows through. Properties:
#   lag_x, lag_y      px    the face trailing the body (Lag node, inside the face chain)
#   follow            x     the body's follow-through squash about the feet (Follow; scaleY,
#                           scaleX = 1/scaleY so the volume holds)
#   blink             -     frames at which the plate blinks off the beat (a squint at the apex,
#                           a blink after a landing)
# Every track starts and ends at rest, so the layer hands nothing back.
OVERLAP = {
    # success hop: crouch @5, apex @18, land @34 (800 ms, 48 frames).
    "success": {"n": 52,
                "lag_y": [(0, 0), (5, 0), (10, 6), (18, 0), (22, -4), (34, 0), (38, 5), (44, -2), (52, 0)],
                "follow": [(0, 1), (34, 1), (38, 0.965), (43, 1.015), (48, 0.995), (52, 1)],
                "blink": [19]},
    # poke: squish @4, rebound @12 (420 ms).
    "poke": {"n": 30,
             "lag_x": [(0, 0), (4, 0), (8, -5), (14, 3), (20, -1), (30, 0)],
             "follow": [(0, 1), (12, 1), (16, 1.02), (22, 0.99), (30, 1)]},
    # sent: dip @5, lift @13 (350 ms).
    "sent": {"n": 26,
             "lag_y": [(0, 0), (5, 0), (8, 3), (15, -3), (20, 1), (26, 0)]},
    # toolStart: hop @9 (300 ms).
    "toolStart": {"n": 24,
                  "lag_y": [(0, 0), (6, 0), (10, 4), (15, -2), (24, 0)],
                  "follow": [(0, 1), (14, 1), (17, 0.98), (21, 1.01), (24, 1)]},
    # toolDone: the nod @5 (250 ms) - the face nods once more, smaller, after the head.
    "toolDone": {"n": 22,
                 "lag_y": [(0, 0), (5, 0), (9, 2.5), (14, -1), (22, 0)]},
    # land: the arrival squash @2 and its settle (300-500 ms by impact).
    "land": {"n": 32,
             "lag_y": [(0, 0), (2, 0), (5, 5), (12, -2), (20, 0.5), (32, 0)],
             "follow": [(0, 1), (14, 1), (18, 1.02), (24, 0.99), (32, 1)],
             "blink": [6]},
    # wake: the tall stretch peaks @15 (700 ms).
    "wake": {"n": 46,
             "lag_y": [(0, 0), (12, 0), (18, 4), (27, -3), (35, 1), (46, 0)],
             "follow": [(0, 1), (28, 1), (32, 1.015), (38, 0.995), (46, 1)]},
}

# The turn's follow-through: a turning entry (rig/motion.py TURNING) rolls the body this far past
# the direction of the turn and settles by the entry's end, while the plate leads.
TURN_FOLLOW_DEG = 1.5

# SPARKLE: star glints at the moments that earn them, and nowhere else (never in a loop).
# Three small four-point stars by the plate's top-right corner (positions in Face px; radius is
# the star's scale against art/sparkle-star.svg's 10 px), popping in a stagger and twinkling out.
SPARKLE_STARS = [(74, -80, 1.1), (96, -52, 0.7), (50, -100, 0.55)]
SPARKLE_STAGGER = 3                # frames between one star and the next
SPARKLE_FRAMES = 24                # one star's life: pop, twinkle, gone (400 ms)
SPARKLE_SPIN_DEG = 45              # a quarter turn of the four points over its life
SPARKLE_COLOR = "FFFFFFFF"
# The frame of the main beat each sparkle lands on: the hop's apex, the nod's bottom, the
# stretch's peak.
SPARKLE_AT = {"success": 18, "toolDone": 5, "wake": 15}
