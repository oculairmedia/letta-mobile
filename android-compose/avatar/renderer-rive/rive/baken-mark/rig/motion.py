"""Every animation in the mark, built from the tables in rig/constants.py.

    Theme_<theme>            1 frame: every colour (fills, overlay fills, light stops)
    Pose_<state>             the state's loop on both Pose nodes            (x y rot sx sy)
    PoseCalm_<state>         its reduceMotion twin: one static pose          (same keys)
    Light_<state>            the state's light loop                          (tones, overlays, lights)
    LightCalm_<state>        its reduceMotion twin: static brightness        (same keys)
    EventRest                keys nothing - the Event nodes keep the identity a one-shot ends on
    Boot / Success           one-shots on both Event nodes (+ the bloom)
    BootCalm / SuccessCalm   their reduceMotion twins: opacity and bloom only, no transform
    HoverRest / HoverLean    the lean perk on both Lean nodes

Every animation of a layer keys exactly the same set of properties as its siblings ("every pose
must key every mutable property, or the last state's value leaks through" - mascot README), which
`test_rig.py` checks.

A track is the lab's own notation: [(percent, {prop: value}), ...]. A property a key leaves out
is interpolated between the keys that do name it, and a property the first or last key leaves
out is at rest there - the CSS @keyframes rules, so a track reads across from the lab unchanged.
"""
from typing import NamedTuple

from rml import COLOR, OPACITY, ROT, SX, SY, X, Y, animation

from rig.constants import (AMBIENT_MS, BOOT, BOOT_CALM_MS, BOOT_LAB_MS, BOOT_MS, BOUNCE_STOPS, EASE,
                           EASE_IN_OUT, HOVER_LEAN_DEG, LIGHT, LIGHT_CALM, LIGHT_REST, PALETTE, POSE,
                           POSE_CALM, SEAM_STOPS, SOFT_OUT, SUCCESS, SUCCESS_BLOOM, SUCCESS_CALM_BLOOM,
                           SUCCESS_CALM_MS, SUCCESS_MS, art, argb, at, frames, rad)
from rig.ids import (ANCHOR_BOUNCE_GRAD, ANCHOR_BOUNCE_STOPS, ANCHOR_FILL, ANCHOR_SHEEN, BOOT_ANIM,
                     BOOT_CALM_ANIM, EVENT_REST_ANIM, HOVER_ANIM, HOVER_REST_ANIM, NODES, PEBBLE_BLOOM,
                     PEBBLE_BLOOM_FILL, PEBBLE_DIM, PEBBLE_DIM_FILL, PEBBLE_FILL, PEBBLE_WARM,
                     PEBBLE_WARM_FILL, SEAM_POSE, SEAM_STOPS as SEAM_STOP_IDS, STATES, STONES,
                     SUCCESS_ANIM, SUCCESS_CALM_ANIM, THEMES, light_anim, light_calm_anim, pose_anim,
                     pose_calm_anim, theme_anim)

GRADIENT_OPACITY = 46
STOP_COLOR = 38

# Transform properties: (Rive key, rest value, unit conversion).
TRANSFORM = {"x": (X, 0, art), "y": (Y, 0, art), "rot": (ROT, 0, rad), "sx": (SX, 1, float),
             "sy": (SY, 1, float), "op": (OPACITY, 1, float)}
POSE_PROPS = ("x", "y", "rot", "sx", "sy")
EVENT_PROPS = ("x", "y", "rot", "sx", "sy", "op")


def _r(v):
    return round(v, 6)


class Clock(NamedTuple):
    """How a track plays: the animation's length, its curve, and - when it plays only part of the
    lab's (boot stops at 60 % of the lab's 2.2 s) - the length the lab's percentages are of."""
    ms: int
    bezier: str = None
    lab_ms: int = None

    @property
    def base(self):
        return self.lab_ms or self.ms


def track_keys(track, prop, rest, base):
    """One property's keys out of a lab track: [(frame, value)], CSS @keyframes semantics.
    `base` is the length (ms) the track's percentages are of."""
    named = [(pct, keys[prop]) for pct, keys in track if prop in keys]
    first, last = track[0][0], track[-1][0]
    if not named:
        return [(at(first, base), rest)]
    if named[0][0] != first:
        named.insert(0, (first, rest))
    if named[-1][0] != last:
        named.append((last, rest))
    return [(at(pct, base), v) for pct, v in named]


def _eased(keys, convert, bezier):
    """[(frame, value)] -> rml keys with the bezier on every segment-leaving key (a lone key is a hold)."""
    if len(keys) == 1:
        return [(keys[0][0], _r(convert(keys[0][1])))]
    return [(f, _r(convert(v)), bezier) for f, v in keys[:-1]] + [(keys[-1][0], _r(convert(keys[-1][1])))]


def _transform_objects(node_of, tracks, props, clock):
    """{node id: {key: keys}} for each stone's track on the given property set."""
    objs = {}
    for stone in STONES:
        node, track = node_of[stone], tracks[stone]
        objs[node] = {}
        for prop in props:
            key, rest, convert = TRANSFORM[prop]
            objs[node][key] = _eased(track_keys(track, prop, rest, clock.base), convert, clock.bezier)
    return objs


def _static(pose):
    """A static pose (a calm twin) as a one-key track."""
    return [(0, pose)]


# --- Theme ------------------------------------------------------------------------------------
def theme_animations():
    out = []
    for t in THEMES:
        p = PALETTE[t]
        light = p["light"]
        objs = {ANCHOR_FILL: {COLOR: p["stone"]}, PEBBLE_FILL: {COLOR: p["accent"]},
                PEBBLE_DIM_FILL: {COLOR: p["dim"]}, PEBBLE_WARM_FILL: {COLOR: p["warm"]},
                PEBBLE_BLOOM_FILL: {COLOR: p["warm"]}}
        for (pos, alpha, which), sid in zip(BOUNCE_STOPS, ANCHOR_BOUNCE_STOPS):
            objs[sid] = {STOP_COLOR: argb(alpha, light[which])}
        for (pos, alpha, which), sid in zip(SEAM_STOPS, SEAM_STOP_IDS):
            objs[sid] = {STOP_COLOR: argb(alpha, light[which])}
        out.append(animation(f"Theme_{t}", theme_anim[t], 1, objs))
    return out


# --- Pose -------------------------------------------------------------------------------------
def _pose_nodes():
    return {s: NODES[s]["Pose"] for s in STONES}


def pose_animations():
    out = []
    for s in STATES:
        ms, bezier, tracks = POSE[s]
        out.append(animation(f"Pose_{s}", pose_anim[s], frames(ms),
                             _transform_objects(_pose_nodes(), tracks, POSE_PROPS, Clock(ms, bezier)), "loop"))
    return out


def pose_calm_animations():
    out = []
    for s in STATES:
        tracks = {stone: _static(POSE_CALM[s][stone]) for stone in STONES}
        out.append(animation(f"PoseCalm_{s}", pose_calm_anim[s], 1,
                             _transform_objects(_pose_nodes(), tracks, POSE_PROPS, Clock(1))))
    return out


# --- Light ------------------------------------------------------------------------------------
# Light property -> (object id, Rive key, unit conversion).
LIGHT_TARGET = {
    "anchorTone": (NODES["anchor"]["Tone"], OPACITY, float),
    "pebbleTone": (NODES["pebble"]["Tone"], OPACITY, float),
    "dim": (PEBBLE_DIM, OPACITY, float),
    "warm": (PEBBLE_WARM, OPACITY, float),
    "bounce": (ANCHOR_BOUNCE_GRAD, GRADIENT_OPACITY, float),
    "sheen": (ANCHOR_SHEEN, OPACITY, float),
    "sheenX": (ANCHOR_SHEEN, X, art),
    "seam": (SEAM_POSE, OPACITY, float),
    "seamScale": (SEAM_POSE, None, float),          # both scaleX and scaleY
}


def _light_objects(track, ms, bezier):
    objs = {}
    for prop, rest in LIGHT_REST.items():
        obj, key, convert = LIGHT_TARGET[prop]
        keys = _eased(track_keys(track, prop, rest, ms), convert, bezier)
        for k in ((SX, SY) if key is None else (key,)):
            objs.setdefault(obj, {})[k] = keys
    return objs


def light_animations():
    out = []
    for s in STATES:
        ms, bezier, track = LIGHT[s]
        out.append(animation(f"Light_{s}", light_anim[s], frames(ms), _light_objects(track, ms, bezier), "loop"))
    return out


def light_calm_animations():
    return [animation(f"LightCalm_{s}", light_calm_anim[s], 1, _light_objects(_static(LIGHT_CALM[s]), 1, None))
            for s in STATES]


# --- Event ------------------------------------------------------------------------------------
def _event_nodes():
    return {s: NODES[s]["Event"] for s in STONES}


def _bloom(points, ms, bezier):
    keys = [(at(pct, ms), v) for pct, v in points]
    return {PEBBLE_BLOOM: {OPACITY: _eased(keys, float, bezier)}}


def event_animations():
    rest = animation("EventRest", EVENT_REST_ANIM, 1, {})
    boot = _transform_objects(_event_nodes(), BOOT, EVENT_PROPS, Clock(BOOT_MS, SOFT_OUT, BOOT_LAB_MS))
    boot.update(_bloom([(0, 0)], BOOT_MS, None))
    success = _transform_objects(_event_nodes(), SUCCESS, EVENT_PROPS, Clock(SUCCESS_MS, SOFT_OUT))
    success.update(_bloom(SUCCESS_BLOOM, SUCCESS_MS, EASE))
    # Calm twins: the same property set, transforms held at rest, light only.
    fade = {s: [(0, {"op": 0}), (100, {"op": 1})] for s in STONES}
    boot_calm = _transform_objects(_event_nodes(), fade, EVENT_PROPS, Clock(BOOT_CALM_MS, EASE_IN_OUT))
    boot_calm.update(_bloom([(0, 0)], BOOT_CALM_MS, None))
    still = {s: [(0, {}), (100, {})] for s in STONES}
    success_calm = _transform_objects(_event_nodes(), still, EVENT_PROPS, Clock(SUCCESS_CALM_MS))
    success_calm.update(_bloom(SUCCESS_CALM_BLOOM, SUCCESS_CALM_MS, EASE))
    return [rest,
            animation("Boot", BOOT_ANIM, frames(BOOT_MS), boot),
            animation("Success", SUCCESS_ANIM, frames(SUCCESS_MS), success),
            animation("BootCalm", BOOT_CALM_ANIM, frames(BOOT_CALM_MS), boot_calm),
            animation("SuccessCalm", SUCCESS_CALM_ANIM, frames(SUCCESS_CALM_MS), success_calm)]


# --- Hover ------------------------------------------------------------------------------------
def hover_animations():
    lean = {NODES[s]["Lean"]: {ROT: rad(HOVER_LEAN_DEG)} for s in STONES}
    rest = {NODES[s]["Lean"]: {ROT: 0} for s in STONES}
    return [animation("HoverRest", HOVER_REST_ANIM, 1, rest),
            animation("HoverLean", HOVER_ANIM, 1, lean)]


def all_animations():
    return (theme_animations() + pose_animations() + pose_calm_animations() + light_animations()
            + light_calm_animations() + event_animations() + hover_animations())


AMBIENT_LIGHT_MS = AMBIENT_MS  # re-exported for the README's table
