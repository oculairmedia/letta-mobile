"""Vocabulary and ids for the rig: states, shapes, tunables, every object id, the design numbers.
Pure data plus the unit helpers (art, rad, frames, beat). Star-imported by every rig module."""
import math
import os

import svgpath  # noqa: F401 - re-exported for modules that lift geometry
from rml import *  # noqa: F401,F403


# The art lives beside gen_scene.py, one level above this package.
ART = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "art")


def art(name):
    return os.path.join(ART, name)


def rad(deg):
    return round(math.radians(deg), 5)


def frames(ms):
    return round(ms * 60 / 1000)


# Art direction: every beat (idle, wander, hover, saccade holds) was read as too quick at product
# sizes. beat() stretches a beat's timing; waits, breath and state entries keep frames().
BEAT_TEMPO = 1.4


def beat(ms):
    return frames(ms * BEAT_TEMPO)


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


# Art-direction tunables (bench only, not in the app contract): 0..1 scrubbing a pose range, 0.5 = shipped.
TUNABLES = {  # name: (vm id, timeline id, node id, property keys, (value at 0, value at 1))
    "tunePlate": ("1:12", "7:66", "7:25", ("SX", "SY"), (0.6, 1.4)),
    "tuneGlyph": ("1:13", "7:67", "7:26", ("SX", "SY"), (0.4, 1.6)),
    "tuneMouth": ("1:14", "7:68", "7:27", ("SX", "SY"), (0.5, 1.5)),
    "tuneMouthY": ("1:15", "7:69", "7:27", ("Y",), (42, 122)),
}


PLATE_SCALE_NODE, GLYPH_SCALE_NODE, MOUTH_NODE, SACCADE_NODE = "7:25", "7:26", "7:27", "7:28"


# Pupil overlay (SPEC 10.2 / art/pupil/PUPIL-SPEC.md): one shared assembly drawn above the glyph
# Solo, visible only for idle/listening/speaking. Keyed four-target fallback for the wave.
PUPIL_OVERLAY, PUPIL_ROOT, IRIS, WAVE, CORE, CATCH = "7:29", "7:96", "7:95", "7:97", "7:98", "7:99"


wave_vertex_ids = [f"7:{200 + i}" for i in range(5)]


# Astra Max pupil overlay (SPEC 10.2): built, reviewed, and dropped - the pure glyph stays. The
# assembly remains in the file at opacity 0 so the art pass can revisit it; PUPIL is empty.
PUPIL = {}   # state: (period ms, amplitude px); empty = never shown


WAVE_STROKE = 5   # spec says 8; that reads as a bar at hero size


PUPIL_PARALLAX = (1.5, 1.0)


# Saccade layer: random waits, then a 60 ms hop to one of a few small fixations, a hold, a hop back.
SACCADE_WAITS = [("7:170", 3600), ("7:171", 7200), ("7:172", 10400)]          # (anim id, ms)


# (anim id, (dx, dy) px, weight %): Eyes Alive direction distribution - down 20, up 18, left 17,
# right 16, diagonals 6-8 - with cardinal hops larger than diagonal ones (magnitudes skew small).
SACCADE_FIX = [("7:173", (0, 5), 20), ("7:174", (0, -5), 18), ("7:175", (-6, 0), 17), ("7:176", (6, 0), 16),
               ("7:177", (-3, -3), 7), ("7:178", (3, -3), 6), ("7:190", (-3, 3), 8), ("7:191", (3, 3), 8)]


SACCADE_WAIT_NODES = ["7:180", "7:181", "7:182"]


SACCADE_FIX_NODES = ["7:183", "7:184", "7:185", "7:186", "7:187", "7:188", "7:192", "7:193"]


SACCADE_SLEEP_NODE = "7:189"


VM_TUNE_SCALE, CONV_SCALE, ENTITY = "1:16", "2:3", "0:230"      # whole-entity scale, 0..1 -> 0.5..1.5


# Host facing: two -1..1 numbers the host writes (the head turning toward what it looks at),
# bound to a HostTurn node above the rig's own Turn so the two add. Body rolls with it too.
VM_TURN_X, VM_TURN_Y, HOST_TURN = "1:17", "1:18", "0:235"


ARC_NODE, TURN_ARC = "0:236", 7    # a turn is an arc, not a slide: the plate lifts TURN_ARC px through the centre


CONV_BODY_X, HOST_BODY_PX = "2:8", 6   # the body shifts toward what the head turns to (commit motion)


LEAN_NODE, CONV_LEAN, LEAN_BASE = "0:237", "2:9", 150   # pivot at the body's base, LEAN_BASE px below centre


# Vector deformation (SPEC 10.5 / art/mesh/DEFORMATION-SPEC.md): three bones - crown (0,-150),
# middle (0,0), base (0,+150) - skinned to every body path. A force pose scales each bone's X by
# S-L / S / S+L and Y by 1/S, and shears crown/base by -+150 H. Weights come from each vertex's
# rest y (wc = -y/150, wb = y/150, wm = the rest) on the default body; Rive weights are static per
# vertex, so the other identities share them (their vertex order matches, positions nearly do).
BONE_CROWN, BONE_MID, BONE_BASE = "0:240", "0:241", "0:242"


BONE_REACH = 150


HOST_LEAN_DEG, TURN_LEAN_DEG = 9, 5                      # lean at host turn +-1 / at state facing +-1


CONV_TURN_X, CONV_TURN_Y, CONV_TURN_ROT, CONV_BODY_ROT = "2:4", "2:5", "2:6", "2:7"


HOST_TURN_PX, HOST_TURN_PY, HOST_TURN_DEG, HOST_BODY_DEG = 48, 18, 10, 5


# Default gaze life: root-keyed remaps of two small plate ranges (+-6 / +-4 px on the glyph's
# wrapper), additive with the host's lookX/lookY on the glyph itself.
AUTO_X, AUTO_Y, PLATE_AUTO_X, PLATE_AUTO_Y = "0:231", "0:232", "7:80", "7:81"


VM_INSTANCE, ENUM_STATE, ENUM_SHAPE = "1:20", "1:100", "1:200"


state_enum_ids = {s: f"1:{101 + i}" for i, s in enumerate(STATES)}


shape_enum_ids = {s: f"1:{201 + i}" for i, s in enumerate(SHAPES)}


CONV_LOOK, CONV_MOUTH = "2:1", "2:2"


ROOT, BODY_NODE, FACE, HITBOX, HALO, SOFT, TINT, GLOSS = "0:2", "0:100", "0:200", "0:90", "0:91", "0:93", "0:92", "0:94"


PLATE, PLATE_EXPR, PLATE_BLINK = "0:210", "0:211", "0:212"


TURN_NODE, JOYSTICK, TRAIL1, TRAIL2 = "0:220", "0:221", "0:222", "0:223"


JX, JY = 299, 300  # Joystick x / y


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


# More idle beats (anim id, state node id): a glance is not enough for a character on screen all day.
IDLE_BEATS = {"stretch": ("3:222", "3:226"), "tilt": ("3:223", "3:227"), "bounce": ("3:224", "3:228"), "shiver": ("3:225", "3:229"),
              "sigh": ("3:468", "3:469"), "wobble": ("3:470", "3:471"), "shift": ("3:472", "3:473"), "lookaround": ("3:474", "3:475")}


HOVER_HELD_ANIM = "3:185"


TURN_X_ANIM, TURN_Y_ANIM = "3:220", "3:221"


DESIGNED_PAIRS = {("idle", "listening"): 300, ("listening", "thinking"): 300, ("thinking", "speaking"): 200,
                  ("speaking", "idle"): 240, ("error", "idle"): 300}  # SPEC section 3, ms


ENTER_PAIRS = [(a, b) for a in SUSTAINED for b in SUSTAINED if a != b]  # every change gets an entry


enter_anim = {pair: f"3:{250 + i}" for i, pair in enumerate(ENTER_PAIRS)}   # 3:250..3:339


enter_node = {pair: f"3:{350 + i}" for i, pair in enumerate(ENTER_PAIRS)}   # 3:350..3:439


WANDER_WAIT_A, WANDER_WAIT_B, WANDER_GLANCE, WANDER_PEEK, WANDER_SPIN = "3:230", "3:231", "3:232", "3:233", "3:234"


WANDER_SLEEP_WAIT, WANDER_SLEEP_SHIFT, WANDER_SLEEP_WAIT_NODE, WANDER_SLEEP_SHIFT_NODE = "3:235", "3:236", "3:245", "3:246"


IDLE_SLEEP_NODE = "3:217"


WANDER_A_NODE, WANDER_B_NODE, WANDER_GLANCE_NODE, WANDER_PEEK_NODE, WANDER_SPIN_NODE = "3:240", "3:241", "3:242", "3:243", "3:244"


# Waits of unequal, non-multiple lengths picked at random: several mascots on one screen must not
# fire their beats in lockstep (they all start at the same instant), so the cycle never repeats.
IDLE_WAITS = [(IDLE_WAIT_A, IDLE_A_NODE, 8000), (IDLE_WAIT_B, IDLE_B_NODE, 14000), ("3:460", "3:462", 6100), ("3:461", "3:463", 10700)]


WANDER_WAITS = [(WANDER_WAIT_A, WANDER_A_NODE, 12000), (WANDER_WAIT_B, WANDER_B_NODE, 24000), ("3:464", "3:466", 9300), ("3:465", "3:467", 17500)]


PLATE_AB, PLATE_SM, PLATE_IN_EXPR, PLATE_IN_BLINK = "7:2", "7:5", "7:6", "7:7"


PLATE_ROOT, PLATE_CARD, GLYPHS_NODE, MOUTH_MORPH, PLATE_SHADOW = "7:20", "7:21", "7:22", "7:23", "7:24"


GLYPH_ORDER = ["idle", "listening", "thinking", "waitingInput", "speaking", "success", "error", "sleeping", "loading", "failed", "degraded", "dragged"]  # 7:30..7:41


GLYPH = {name: f"7:{30 + i}" for i, name in enumerate(GLYPH_ORDER)}


FROWN, MOUTH_O = "7:42", "7:43"


mouth_vertex_ids = [f"7:{70 + i}" for i in range(4)]


PLATE_LOOKX, PLATE_LOOKY, PLATE_OPEN, PLATE_BLINK_ANIM, PLATE_WAIT_A, PLATE_WAIT_B = "7:60", "7:61", "7:62", "7:63", "7:64", "7:65"


plate_expr_anim = {s: f"7:{100 + i}" for i, s in enumerate(STATES)}


plate_expr_node = {s: f"7:{130 + i}" for i, s in enumerate(STATES)}


PLATE_BLINK_NODE, PLATE_BLINK_REST_NODE = "7:160", "7:161"


PLATE_AUTO_A, PLATE_AUTO_B, PLATE_AUTO_BLINK, PLATE_AUTO_SLEEP = "7:162", "7:163", "7:164", "7:165"


BLINK_SHUT, BLINK_FRAMES = 4, 15   # frames to fully shut / total


BLINK_FLIP = 6                     # entries flip the glyph here: the plate sees the trigger a frame or two late


INK = "FF111111"


PLATE_WHITE = "FFF7F7F7"


def expression_layer(*parts):
    # Explicit matrix, instant cuts: the root hides every swap inside a blink shutter, and an
    # AnyState fan-out would keep re-entering the current state (a self-blend that fades the glyph).
    name, lid, input_id, anim_ids, node_ids = parts
    states = []
    for i, s in enumerate(STATES):
        own = "\n".join(input_transition(node_ids[t], input_id, EXPR[t], 0) for t in STATES if t != s)
        states.append(anim_state(anim_ids[s], node_ids[s], i, "", own))
    return layer_frame(name, lid, node_ids["idle"], "", "\n".join(states))
