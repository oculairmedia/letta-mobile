"""Every named Rive object id in the mark, plus the vocabulary the id ranges are indexed by.

Ids are `client:object`, numeric, unique, object 0 reserved - the same namespaces the mascot uses,
in a file of their own (the two `.riv` files never meet):

    0:*   artboard nodes and shapes          1:*   view model, its enums and instance
    3:*   animations, state nodes, layers

The view model is `1:50` and `state` is `1:1` on purpose: `rml.py` (shared with the mascot) binds
through `VM` and `OnEnum` defaults to `VM_STATE`, and both are those ids. Existing ids are frozen:
append, never renumber - a renumbered id is a different object to any tool that remembers it.
Uniqueness is checked at import.
"""
from rml import VM, VM_STATE

# --- vocabulary (order = enum order = the index Rive delivers; append only) ------------------
STATES = ("idle", "loading", "connecting", "degraded", "ambient")
THEMES = ("dark", "light", "monoDark", "monoLight")
STONES = ("anchor", "pebble")

def _run(client, start, names):
    """Consecutive ids from `client:start`, one per name, in vocabulary order."""
    return {n: f"{client}:{start + i}" for i, n in enumerate(names)}


# --- artboard -----------------------------------------------------------------------------------
ROOT = "0:1"
STYLE = "0:3"

# Per stone, outermost first: Base (placement at the ground contact) > Lean (hover) > Event (boot,
# success) > Pose (the state loops) > Tone (the state's brightness) > the shapes.
NODES = {
    "anchor": {"Base": "0:100", "Lean": "0:101", "Event": "0:102", "Pose": "0:103", "Tone": "0:104"},
    "pebble": {"Base": "0:200", "Lean": "0:201", "Event": "0:202", "Pose": "0:203", "Tone": "0:204"},
}
ANCHOR_STONE, ANCHOR_BOUNCE, ANCHOR_SHEEN_GROUP, ANCHOR_SHEEN = "0:110", "0:111", "0:112", "0:113"
ANCHOR_FILL, ANCHOR_BOUNCE_GRAD, SHEEN_GRAD = "0:120", "0:121", "0:122"
PEBBLE_STONE, PEBBLE_DIM, PEBBLE_WARM, PEBBLE_BLOOM = "0:210", "0:211", "0:212", "0:213"
PEBBLE_FILL, PEBBLE_DIM_FILL, PEBBLE_WARM_FILL, PEBBLE_BLOOM_FILL = "0:220", "0:221", "0:222", "0:223"
SEAM_NODE, SEAM_POSE, SEAM_GLOW, SEAM_GRAD = "0:300", "0:301", "0:302", "0:303"
# The bounce and seam light are warm by nature; in a mono theme they are white light instead, so
# the gradient stops are keyed per theme too.
ANCHOR_BOUNCE_STOPS = ("0:130", "0:131")
SEAM_STOPS = ("0:310", "0:311", "0:312")

# --- view model (1:*) -----------------------------------------------------------------------------
VM_THEME, VM_BOOT, VM_SUCCESS, VM_REDUCE, VM_HOVER = "1:2", "1:3", "1:4", "1:5", "1:6"
VM_INSTANCE = "1:20"
ENUM_STATE, ENUM_THEME = "1:100", "1:200"
state_enum_ids = _run("1", 101, STATES)
theme_enum_ids = _run("1", 201, THEMES)

# --- animations and state nodes (3:*) -----------------------------------------------------------
SM = "3:1"
LAYER_THEME, LAYER_POSE, LAYER_LIGHT, LAYER_EVENT, LAYER_HOVER = "3:2", "3:3", "3:4", "3:5", "3:6"

# Themes: one 1-frame pose each, 3:10.. animations, 3:20.. nodes.
theme_anim = _run("3", 10, THEMES)
theme_node = _run("3", 20, THEMES)

# Pose and Light: a loop and its reduceMotion twin per state.
pose_anim = _run("3", 100, STATES)
pose_node = _run("3", 110, STATES)
pose_calm_anim = _run("3", 120, STATES)
pose_calm_node = _run("3", 130, STATES)
light_anim = _run("3", 140, STATES)
light_node = _run("3", 150, STATES)
light_calm_anim = _run("3", 160, STATES)
light_calm_node = _run("3", 170, STATES)

# Event: a rest that keys nothing, and the two one-shots with their calm twins.
EVENT_REST_ANIM, EVENT_REST_NODE = "3:200", "3:201"
BOOT_ANIM, BOOT_NODE = "3:202", "3:203"
SUCCESS_ANIM, SUCCESS_NODE = "3:204", "3:205"
BOOT_CALM_ANIM, BOOT_CALM_NODE = "3:206", "3:207"
SUCCESS_CALM_ANIM, SUCCESS_CALM_NODE = "3:208", "3:209"

# Hover: rest, lean, held.
HOVER_REST_ANIM, HOVER_REST_NODE = "3:220", "3:221"
HOVER_ANIM, HOVER_NODE = "3:222", "3:223"

# Solo mode (review.py --animation, probe.py beat:<Name>): never in the shipped document.
SOLO_SM, SOLO_LAYER, SOLO_STATE = "3:900", "3:901", "3:902"
REVIEW_BACKDROP = "0:999"                               # gen_scene.py --backdrop (review.py) only


def all_ids():
    """Every id this module names, as a list (duplicates included, so the check can see them)."""
    out = [VM, VM_STATE, ROOT, STYLE, ANCHOR_STONE, ANCHOR_BOUNCE, ANCHOR_SHEEN_GROUP, ANCHOR_SHEEN,
           ANCHOR_FILL, ANCHOR_BOUNCE_GRAD, SHEEN_GRAD, PEBBLE_STONE, PEBBLE_DIM, PEBBLE_WARM,
           PEBBLE_BLOOM, PEBBLE_FILL, PEBBLE_DIM_FILL, PEBBLE_WARM_FILL, PEBBLE_BLOOM_FILL,
           SEAM_NODE, SEAM_POSE, SEAM_GLOW, SEAM_GRAD, *ANCHOR_BOUNCE_STOPS, *SEAM_STOPS,
           VM_THEME, VM_BOOT, VM_SUCCESS, VM_REDUCE, VM_HOVER, VM_INSTANCE, ENUM_STATE, ENUM_THEME,
           SM, LAYER_THEME, LAYER_POSE, LAYER_LIGHT, LAYER_EVENT, LAYER_HOVER,
           EVENT_REST_ANIM, EVENT_REST_NODE, BOOT_ANIM, BOOT_NODE, SUCCESS_ANIM, SUCCESS_NODE,
           BOOT_CALM_ANIM, BOOT_CALM_NODE, SUCCESS_CALM_ANIM, SUCCESS_CALM_NODE,
           HOVER_REST_ANIM, HOVER_REST_NODE, HOVER_ANIM, HOVER_NODE, SOLO_SM, SOLO_LAYER, SOLO_STATE,
           REVIEW_BACKDROP]
    for table in (state_enum_ids, theme_enum_ids, theme_anim, theme_node, pose_anim, pose_node,
                  pose_calm_anim, pose_calm_node, light_anim, light_node, light_calm_anim,
                  light_calm_node):
        out += list(table.values())
    for nodes in NODES.values():
        out += list(nodes.values())
    return out


def _check_unique():
    seen, dupes = set(), set()
    for i in all_ids():
        if i in seen:
            dupes.add(i)
        seen.add(i)
        client, obj = i.split(":")
        if int(obj) == 0:
            raise AssertionError(f"id {i}: object 0 is reserved")
    if dupes:
        raise AssertionError(f"duplicate ids: {sorted(dupes)}")


_check_unique()
