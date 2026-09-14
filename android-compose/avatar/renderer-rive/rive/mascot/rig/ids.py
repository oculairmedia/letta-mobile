"""Every Rive object id in the rig, in one place, checked for uniqueness at import.

An id is "client:index". The generator names an object with a fixed id so that `rive push`
updates that object in place; the client half is the space it belongs to:

    0  artboard objects   the Mascot artboard: nodes, paths, vertices, bones
    1  view model         properties and enum values the host binds to
    2  converters         the little value converters between binding and property
    3  root animations / state-machine layers, states and transitions
    7  plate artboard     the nested Plate component: glyphs, mouth, saccade, its own machine

**Existing ids are frozen.** Changing one orphans the object in the workspace (the next push
creates a new one and the artist's edits stay on the old), so every value below is history, not
a choice. New ids come from `alloc(space, name)`, which records name -> id in ids.json so the
name keeps its id forever; the floors sit above everything already used in that space.

Two ids collided recently because nothing checked. `_check_unique()` runs at import and names
both constants; `all_ids()` exposes the whole set.

Read by every rig module and by gen_scene.py; it imports nothing from them. It also owns the
state / shape / glyph vocabulary, because the id tables below are ranges indexed by those orders.

Rive rules that bite here:
  - push before you commit. `rive push` writes an id onto every object the generator left
    unnamed and saves them back into scene.rml; regenerating throws those away. The ids in this
    module are the named ones - stable across pushes, and what an editor edit attaches to.
  - an id is resolved, never matched by name: a duplicate silently rebinds one object onto
    another rather than failing, which is why the uniqueness check runs at import.
"""
import json as _json
import os as _os
import re as _re
from typing import NamedTuple as _NamedTuple

_ID = _re.compile(r"^\d+:\d+$")

_TABLE = _os.path.join(_os.path.dirname(_os.path.abspath(__file__)), "ids.json")

# Ids owned by other modules (rml.py's view model, body.py's nodes, the layer and artboard ids
# written inline in machine.py / plate.py / gen_scene.py). Listed so the allocator and the
# uniqueness check see the whole file, not just this module.
_EXTERNAL = {
    "1:1", "1:2", "1:3", "1:4", "1:5", "1:6", "1:7", "1:8", "1:9", "1:10", "1:11", "1:50",  # rml.py
    "0:3", "0:233", "0:234",                                                    # gen_scene / body
    "3:1", "3:2", "3:3", "3:4", "3:6", "3:7", "3:8", "3:9", "3:10",             # machine.py layers
    "7:3", "7:10", "7:11", "7:12", "7:13",                                      # plate.py
    "3:900", "3:901", "3:902",                                  # gen_scene.py SOLO_SM / _LAYER / _STATE
}

# New ids start above everything already taken in the space: 0 runs to 0:327 (the vertex blocks),
# 3 to 3:475 (the beats migrated below), 7 to 7:204 (the wave vertices).
_FLOOR = {0: 400, 3: 460, 7: 300}


def _walk(value):
    """Every id string inside a constant, however it is nested."""
    if isinstance(value, str):
        if _ID.match(value):
            yield value
    elif isinstance(value, dict):
        for v in value.values():
            yield from _walk(v)
    elif isinstance(value, (list, tuple, set, frozenset)):
        for v in value:
            yield from _walk(v)


def _defined(namespace):
    """id -> the constant that defines it. Every id in this module is defined exactly once:
    tables that merely *reference* an id (TUNABLES, IDLE_WAITS, ...) stay in constants.py."""
    owner = {}
    for name, i in _public_ids(namespace):
        if owner.setdefault(i, name) != name:
            raise AssertionError(f"id collision: {i} is used by both {owner[i]} and {name}")
    return owner


def _public_ids(namespace):
    """(constant name, id) for every id inside every public constant of `namespace`."""
    return [(name, i) for name, value in namespace.items() if not name.startswith("_") for i in _walk(value)]


def _check_unique():
    owner = _defined(dict(globals()))
    for i in sorted(owner):
        if i in _EXTERNAL:
            raise AssertionError(f"id collision: {i} is {owner[i]} here and is also used outside rig/ids.py")
    return owner


def _taken():
    return set(_walk(dict(globals()))) | set(_RECORD.values()) | _EXTERNAL


def all_ids():
    """Every id this rig owns - the constants below, the allocator's table, the external ones."""
    return _taken()


def alloc(space, name):
    """The id recorded for `name`, or the lowest free one in `space` above its floor.

    Names are permanent: ids.json is committed so a re-run of the generator emits the same id and
    `rive push` keeps updating the same object."""
    got = _RECORD.get(name)
    if got is not None:
        if not got.startswith(f"{space}:"):
            raise AssertionError(f"{name} is recorded as {got}, not in space {space}")
        return got
    if space not in _FLOOR:
        raise AssertionError(f"no allocation floor for space {space}")
    used = {int(i.split(":")[1]) for i in _taken() if i.startswith(f"{space}:")}
    index = _FLOOR[space]
    while index in used:
        index += 1
    _RECORD[name] = f"{space}:{index}"
    with open(_TABLE, "w", encoding="utf-8") as f:
        _json.dump(_RECORD, f, indent=2, sort_keys=True)
        f.write("\n")
    return _RECORD[name]


with open(_TABLE, encoding="utf-8") as _f:
    _RECORD = _json.load(_f)


# --- vocabulary --------------------------------------------------------------------------------
# The id tables below are computed ranges indexed by these orders, so the orders live with them:
# reorder a list and every id shifts, which is exactly the move the freeze forbids.
STATES = ["idle", "listening", "dragged", "thinking", "waitingInput", "speaking",
          "success", "error", "sleeping", "loading", "failed", "degraded"]


MOMENTARY = ["success", "dragged"]


SUSTAINED = [s for s in STATES if s not in MOMENTARY]


SHAPES = ["circle", "blob", "roundedSquare", "pill", "triangle", "hexagon", "cloud", "drop"]


GLYPH_ORDER = ["idle", "listening", "thinking", "waitingInput", "speaking", "success", "error", "sleeping", "loading", "failed", "degraded", "dragged"]  # 7:30..7:41


ENTER_PAIRS = [(a, b) for a in SUSTAINED for b in SUSTAINED if a != b]  # every change gets an entry


# --- 0: the mascot artboard --------------------------------------------------------------------
ROOT, BODY_NODE, FACE, HITBOX, HALO, SOFT, TINT, GLOSS = "0:2", "0:100", "0:200", "0:90", "0:91", "0:93", "0:92", "0:94"


PLATE, PLATE_EXPR, PLATE_BLINK = "0:210", "0:211", "0:212"


TURN_NODE, JOYSTICK, TRAIL1, TRAIL2 = "0:220", "0:221", "0:222", "0:223"


ENTITY = "0:230"                                   # whole-entity scale


AUTO_X, AUTO_Y = "0:231", "0:232"                  # root-keyed default gaze


HOST_TURN = "0:235"                                # the host's facing, above the rig's own Turn


ARC_NODE = "0:236"                                 # a turn is an arc, not a slide


LEAN_NODE = "0:237"                                # pivot at the body's base


BONE_CROWN, BONE_MID, BONE_BASE = "0:240", "0:241", "0:242"   # skinned to every body path


body_vertex_ids = [f"0:{300 + i}" for i in range(8)]


soft_vertex_ids = [f"0:{310 + i}" for i in range(8)]


halo_vertex_ids = [f"0:{320 + i}" for i in range(8)]


# --- 1: the view model -------------------------------------------------------------------------
VM_TUNE = {"tunePlate": "1:12", "tuneGlyph": "1:13", "tuneMouth": "1:14", "tuneMouthY": "1:15"}


VM_TUNE_SCALE = "1:16"


VM_TURN_X, VM_TURN_Y = "1:17", "1:18"


VM_INSTANCE, ENUM_STATE, ENUM_SHAPE = "1:20", "1:100", "1:200"


state_enum_ids = {s: f"1:{101 + i}" for i, s in enumerate(STATES)}


shape_enum_ids = {s: f"1:{201 + i}" for i, s in enumerate(SHAPES)}


# --- 2: converters -----------------------------------------------------------------------------
CONV_LOOK, CONV_MOUTH, CONV_SCALE = "2:1", "2:2", "2:3"


CONV_TURN_X, CONV_TURN_Y, CONV_TURN_ROT, CONV_BODY_ROT = "2:4", "2:5", "2:6", "2:7"


CONV_BODY_X, CONV_LEAN = "2:8", "2:9"


# --- 3: root animations and state-machine states -----------------------------------------------
SM = "3:5"


shape_anim = {s: f"3:{60 + i}" for i, s in enumerate(SHAPES)}


shape_node = {s: f"3:{70 + i}" for i, s in enumerate(SHAPES)}


root_state_anim = {s: f"3:{100 + i}" for i, s in enumerate(STATES)}


root_state_node = {s: f"3:{130 + i}" for i, s in enumerate(STATES)}


BREATH_ANIM, BREATH_NODE = "3:160", "3:161"


BLINK_ANIM, BLINK_REST_ANIM, BLINK_NODE, BLINK_REST_NODE = "3:170", "3:171", "3:172", "3:173"


HOVER_ANIM, HOVER_REST_ANIM, HOVER_NODE, HOVER_REST_NODE, HOVER_HELD_NODE = "3:180", "3:181", "3:182", "3:183", "3:184"


HOVER_HELD_ANIM = "3:185"


FLASH_REST_ANIM, FLASH_REST_NODE, SUCCESS_ANIM, SUCCESS_NODE, ERROR_ANIM, ERROR_NODE = "3:190", "3:191", "3:192", "3:193", "3:194", "3:195"


DRAG_REST_ANIM, DRAG_REST_NODE, DRAG_ANIM, DRAG_NODE = "3:200", "3:201", "3:202", "3:203"


IDLE_WAIT_A, IDLE_WAIT_B, IDLE_GLANCE_ANIM, IDLE_A_NODE, IDLE_B_NODE, IDLE_GLANCE_NODE = "3:210", "3:211", "3:212", "3:213", "3:214", "3:215"


IDLE_SLEEP_NODE = "3:217"


TURN_X_ANIM, TURN_Y_ANIM = "3:220", "3:221"


class Beat(_NamedTuple):
    """One idle beat: the LinearAnimation that plays it and the state node that holds it."""
    anim: str
    node: str


# More idle beats: a glance is not enough for a character on screen all day.
IDLE_BEATS = {"stretch": Beat("3:222", "3:226"), "tilt": Beat("3:223", "3:227"),
              "bounce": Beat("3:224", "3:228"), "shiver": Beat("3:225", "3:229"),
              "sigh": Beat(alloc(3, "IdleBeatSigh"), alloc(3, "IdleBeatSighNode")),
              "wobble": Beat(alloc(3, "IdleBeatWobble"), alloc(3, "IdleBeatWobbleNode")),
              "shift": Beat(alloc(3, "IdleBeatShift"), alloc(3, "IdleBeatShiftNode")),
              "lookaround": Beat(alloc(3, "IdleBeatLookAround"), alloc(3, "IdleBeatLookAroundNode"))}


WANDER_WAIT_A, WANDER_WAIT_B, WANDER_GLANCE, WANDER_PEEK, WANDER_SPIN = "3:230", "3:231", "3:232", "3:233", "3:234"


WANDER_SLEEP_WAIT, WANDER_SLEEP_SHIFT, WANDER_SLEEP_WAIT_NODE, WANDER_SLEEP_SHIFT_NODE = "3:235", "3:236", "3:245", "3:246"


WANDER_A_NODE, WANDER_B_NODE, WANDER_GLANCE_NODE, WANDER_PEEK_NODE, WANDER_SPIN_NODE = "3:240", "3:241", "3:242", "3:243", "3:244"


enter_anim = {pair: f"3:{250 + i}" for i, pair in enumerate(ENTER_PAIRS)}   # 3:250..3:339


enter_node = {pair: f"3:{350 + i}" for i, pair in enumerate(ENTER_PAIRS)}   # 3:350..3:439


# The extra waits that break the lockstep (see IDLE_WAITS / WANDER_WAITS in constants.py).
IDLE_WAIT_C, IDLE_WAIT_D = alloc(3, "IdleWaitC"), alloc(3, "IdleWaitD")


WANDER_WAIT_C, WANDER_WAIT_D = alloc(3, "WanderWaitC"), alloc(3, "WanderWaitD")


IDLE_C_NODE, IDLE_D_NODE = alloc(3, "IdleWaitCNode"), alloc(3, "IdleWaitDNode")


WANDER_C_NODE, WANDER_D_NODE = alloc(3, "WanderWaitCNode"), alloc(3, "WanderWaitDNode")


# --- 7: the plate artboard ---------------------------------------------------------------------
PLATE_AB, PLATE_SM, PLATE_IN_EXPR, PLATE_IN_BLINK = "7:2", "7:5", "7:6", "7:7"


PLATE_ROOT, PLATE_CARD, GLYPHS_NODE, MOUTH_MORPH, PLATE_SHADOW = "7:20", "7:21", "7:22", "7:23", "7:24"


PLATE_SCALE_NODE, GLYPH_SCALE_NODE, MOUTH_NODE, SACCADE_NODE = "7:25", "7:26", "7:27", "7:28"


# Pupil overlay (SPEC 10.2 / art/pupil/PUPIL-SPEC.md): one shared assembly drawn above the glyph.
PUPIL_OVERLAY, PUPIL_ROOT, IRIS, WAVE, CORE, CATCH = "7:29", "7:96", "7:95", "7:97", "7:98", "7:99"


GLYPH = {name: f"7:{30 + i}" for i, name in enumerate(GLYPH_ORDER)}


FROWN, MOUTH_O = "7:42", "7:43"


PLATE_LOOKX, PLATE_LOOKY, PLATE_OPEN, PLATE_BLINK_ANIM, PLATE_WAIT_A, PLATE_WAIT_B = "7:60", "7:61", "7:62", "7:63", "7:64", "7:65"


TUNE_ANIM = {"tunePlate": "7:66", "tuneGlyph": "7:67", "tuneMouth": "7:68", "tuneMouthY": "7:69"}


mouth_vertex_ids = [f"7:{70 + i}" for i in range(4)]


PLATE_AUTO_X, PLATE_AUTO_Y = "7:80", "7:81"


plate_expr_anim = {s: f"7:{100 + i}" for i, s in enumerate(STATES)}


plate_expr_node = {s: f"7:{130 + i}" for i, s in enumerate(STATES)}


PLATE_BLINK_NODE, PLATE_BLINK_REST_NODE = "7:160", "7:161"


PLATE_AUTO_A, PLATE_AUTO_B, PLATE_AUTO_BLINK, PLATE_AUTO_SLEEP = "7:162", "7:163", "7:164", "7:165"


# Saccade layer (waits and fixations; the timings and offsets live in constants.py).
SACCADE_WAIT_ANIMS = ["7:170", "7:171", "7:172"]


SACCADE_FIX_ANIMS = ["7:173", "7:174", "7:175", "7:176", "7:177", "7:178", "7:190", "7:191"]


SACCADE_WAIT_NODES = ["7:180", "7:181", "7:182"]


SACCADE_FIX_NODES = ["7:183", "7:184", "7:185", "7:186", "7:187", "7:188", "7:192", "7:193"]


SACCADE_SLEEP_NODE = "7:189"


wave_vertex_ids = [f"7:{200 + i}" for i in range(5)]


_OWNER = _check_unique()
