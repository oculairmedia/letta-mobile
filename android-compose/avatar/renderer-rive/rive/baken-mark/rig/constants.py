"""The design numbers, as data. Almost nothing else in the rig should carry a number.

Sources, in order of authority:
  BAKEN_MARK_RIVE_SPEC.md   the state table (timings, amplitudes, colours) and the contract
  baken-motion-lab.html     the reference keyframes the spec's numbers come from
  PALETTE.md                01 North Sea Buoy

Geometry is the locked 03 Basalt Planes mark, authored at 200x200 and placed on a 500x500
artboard at ART_SCALE 2.5, so the ground (y=168) lands at y=420 and the two contact pivots at
(162, 420) and (335, 420) as the spec says. Every length below is in ART units (the 200 px
drawing), the unit the lab and the spec write them in; `art()` converts. Rotations are degrees
here and radians in the file (`rad()`); durations are ms here and frames in the file (`frames()`).

A keyframe track is a list of (percent, {property: value}) in the lab's own percentages, so a
reviewer can hold this file next to the lab's @keyframes and read them across. Properties:
x, y (art px), rot (deg), sx, sy (scale), op (opacity).
"""
import math

from rig.brand import theme_inks

ART_SCALE = 2.5                       # 200x200 art -> 500x500 artboard
ARTBOARD = 500
GROUND_Y = 168                        # art: the flush baseline both stones sit on
PIVOT = {"anchor": (65, 168), "pebble": (134, 168)}   # art: the bottom contact points (lab transform-origin)

# The shared layer builder (rig/layers.py, the mascot's) reads this from `rig.constants`: no
# transition shorter than this may move a pose. Same floor as the mascot.
MIN_TRANSITION_MS = 100


def art(v):
    """Art units (the 200 px drawing) -> artboard px."""
    return round(v * ART_SCALE, 4)


def rad(deg):
    return round(math.radians(deg), 6)


def frames(ms):
    """ms -> frames at 60 fps (LinearAnimation.duration and keyframe positions are frames)."""
    return round(ms * 60 / 1000)


def at(pct, ms):
    """A lab percentage of a `ms`-long animation -> its frame."""
    return frames(ms * pct / 100)


# --- easing (the lab's CSS timing functions, as rml.py bezier strings) --------------------------
EASE_IN_OUT = "0.42 0 0.58 1"         # CSS ease-in-out
EASE = "0.25 0.1 0.25 1"              # CSS ease
SOFT_OUT = "0.22 1 0.36 1"            # the lab's boot / success curve (rml.SOFT_OUT)
CRADLE = "0.45 0.05 0.55 0.95"        # loading's stone-cradle weight transfer

# --- palette, ARGB ------------------------------------------------------------------------------
# stone / accent / bg are the brand's, read from the app's own tokens (rig/brand.py: ThemeTokens.kt),
# never restated here. What only the animation needs is motion data from the brand kit's motion lab:
# warm, the ambient / success warming of the pebble (lab --stone2-warm); dim, degraded's muted
# slate-rust (lab --stone2-dim); light, the colour of the reflected bounce and the seam glow. In the
# mono themes there is no hue to warm, so every state reads through luminance only: warm lifts the
# ink, dim greys it, light is plain light.
MOTION_TONES = {
    "dark":      {"warm": "FFFF7858", "dim": "FF7C5C59", "light": ("FF7555", "FF7A59", "EE5A3F")},
    "light":     {"warm": "FFF4664A", "dim": "FF8D7C7B", "light": ("F4664A", "F4664A", "E04A32")},
    "monoDark":  {"warm": "FF2E4A60", "dim": "FF586E7F", "light": ("586E7F", "586E7F", "586E7F")},
    "monoLight": {"warm": "FFFFFFFF", "dim": "FF7F96A8", "light": ("FFFFFF", "FFFFFF", "FFFFFF")},
}
PALETTE = {theme: {"stone": stone, "accent": accent, "bg": bg, **MOTION_TONES[theme]}
           for theme, (stone, accent, bg) in theme_inks().items()}
DEFAULT_THEME = "dark"
DEFAULT_STATE = "idle"


def argb(alpha, rgb):
    """0..1 alpha and an RRGGBB string -> AARRGGBB."""
    return f"{round(alpha * 255):02X}{rgb}"


# Gradient stops (position, alpha) for the two lights - the lab's seamGlow / bounce gradients.
BOUNCE_STOPS = ((0.0, 0.45, 0), (1.0, 0.0, 2))           # (position, alpha, which `light` colour)
SEAM_STOPS = ((0.0, 0.72, 1), (0.55, 0.22, 2), (1.0, 0.0, 2))

# --- the lights' geometry (art units, relative to the owning stone's pivot unless noted) --------
BOUNCE_CENTRE = (21, -39)             # lab bounce-hdr cx=100% cy=70% of the anchor's box
BOUNCE_RADIUS = 45
SEAM_CENTRE = (96, 134)               # absolute art: the lab's seam-glow ellipse
SEAM_RADII = (28, 38)
SHEEN_BAND = (60, 260)                # width, height of the travelling band
SHEEN_ANGLE = 18                      # deg
SHEEN_Y = -68                         # its centre, above the anchor's pivot
SHEEN_ALPHA = 0.35                    # peak white in the band's middle

# --- transitions (ms) ---------------------------------------------------------------------------
STATE_BLEND_MS = 450                  # Pose: heavy stones change stance slowly
LIGHT_BLEND_MS = 320                  # Light: the lab's fill / opacity transition
THEME_BLEND_MS = 280                  # Theme: the lab's background transition
CALM_BLEND_MS = 300                   # into / out of a reduceMotion twin (the mascot's figure)
HOVER_IN_MS, HOVER_OUT_MS = 240, 320

# --- sustained states (spec state table; lab @keyframes) ---------------------------------------
# Each entry: duration ms, curve, and per stone a keyframe track. A track's first and last keys are
# the same pose (they loop). Pose tracks key x, y, rot, sx, sy; anything left out is rest.
IDLE_MS = 5200
LOADING_MS = 1500
CONNECTING_MS = 2200
DEGRADED_MS = 3200
AMBIENT_MS = 3400                     # the ambient LIGHT loop; its stones keep idle's 5200

POSE = {
    "idle": (IDLE_MS, EASE_IN_OUT, {
        "anchor": [(0, {}), (50, {"rot": -0.85}), (100, {})],
        "pebble": [(0, {}), (50, {"rot": 1.35, "x": 0.6}), (100, {})],
    }),
    "loading": (LOADING_MS, CRADLE, {
        "anchor": [(0, {}), (26, {"rot": -4.2, "x": -1.5, "sy": 1.015}),
                   (55, {"rot": 1.6, "x": 1, "sy": 0.985}), (78, {"rot": -0.6}), (100, {})],
        "pebble": [(0, {}), (22, {"rot": -2, "x": -1, "sx": 1.03, "sy": 0.97}),
                   (58, {"rot": 11, "x": 4.5, "y": -3}),
                   (84, {"rot": -1.5, "x": -0.5, "sx": 1.02, "sy": 0.98}), (100, {})],
    }),
    "connecting": (CONNECTING_MS, EASE_IN_OUT, {
        "anchor": [(0, {"x": -5.5, "rot": -2.5}), (50, {"x": -1.5, "rot": 1.2}), (100, {"x": -5.5, "rot": -2.5})],
        "pebble": [(0, {"x": 8, "rot": 6}), (50, {"x": 2, "rot": -2.5}), (100, {"x": 8, "rot": 6})],
    }),
    "degraded": (DEGRADED_MS, EASE_IN_OUT, {
        "anchor": [(0, {}), (100, {})],
        "pebble": [(0, {"x": 10, "rot": 10}), (50, {"x": 12, "rot": 12.5}), (100, {"x": 10, "rot": 10})],
    }),
    "ambient": (IDLE_MS, EASE_IN_OUT, {
        "anchor": [(0, {}), (50, {"rot": -0.85}), (100, {})],
        "pebble": [(0, {}), (50, {"rot": 1.35, "x": 0.6}), (100, {})],
    }),
}

# reduceMotion: one static pose per state - parting only, no loop (spec: "states express via
# static parting / luminance").
POSE_CALM = {
    "idle": {"anchor": {}, "pebble": {}},
    "loading": {"anchor": {}, "pebble": {}},
    "connecting": {"anchor": {"x": -5.5}, "pebble": {"x": 8}},
    "degraded": {"anchor": {}, "pebble": {"x": 10, "rot": 10}},
    "ambient": {"anchor": {}, "pebble": {}},
}

# Light tracks: every light property, keyed per state. Properties:
#   anchorTone, pebbleTone   opacity of the whole stone (degraded dims both)
#   dim, warm                opacity of the pebble's dim / warm overlays (degraded / ambient)
#   bounce                   the reflected light on the anchor's right flank
#   sheen, sheenX            the loading sheen's opacity and travel (art px from the anchor pivot)
#   seam, seamScale          the light inside the channel seam
LIGHT_REST = {"anchorTone": 1, "pebbleTone": 1, "dim": 0, "warm": 0, "bounce": 0.14,
              "sheen": 0, "sheenX": -45, "seam": 0, "seamScale": 1}

LIGHT = {
    "idle": (IDLE_MS, EASE_IN_OUT, [(0, {}), (100, {})]),
    "loading": (LOADING_MS, EASE_IN_OUT, [
        (0, {"sheen": 0, "sheenX": -45, "bounce": 0.14}),
        (25, {"sheen": 0.9, "sheenX": -10}),
        (50, {"bounce": 0.45}),
        (65, {"sheen": 0, "sheenX": 45}),
        (100, {"sheen": 0, "sheenX": 45, "bounce": 0.14})]),
    "connecting": (CONNECTING_MS, EASE_IN_OUT, [
        (0, {"seam": 0.18, "seamScale": 0.82, "bounce": 0.12}),
        (50, {"seam": 0.92, "seamScale": 1.12, "bounce": 0.68}),
        (100, {"seam": 0.18, "seamScale": 0.82, "bounce": 0.12})]),
    "degraded": (DEGRADED_MS, EASE_IN_OUT, [
        (0, {"anchorTone": 0.62, "pebbleTone": 0.78, "dim": 1, "bounce": 0}),
        (100, {"anchorTone": 0.62, "pebbleTone": 0.78, "dim": 1, "bounce": 0})]),
    "ambient": (AMBIENT_MS, EASE_IN_OUT, [
        (0, {"warm": 0, "bounce": 0.18, "seam": 0.08, "seamScale": 0.92}),
        (50, {"warm": 1, "bounce": 0.85, "seam": 0.55, "seamScale": 1.08}),
        (100, {"warm": 0, "bounce": 0.18, "seam": 0.08, "seamScale": 0.92})]),
}

# A light property that may jump at a loop's seam, and the opacity that hides the jump: the sheen
# travels left to right and wraps back while it is invisible (opacity 0 at both ends of the loop).
LOOP_WRAPS_HIDDEN = {"sheenX": "sheen"}

LIGHT_CALM = {
    "idle": {},
    "loading": {"bounce": 0.3},
    "connecting": {"seam": 0.55, "bounce": 0.4},
    "degraded": {"anchorTone": 0.62, "pebbleTone": 0.78, "dim": 1, "bounce": 0},
    "ambient": {"warm": 0.5, "bounce": 0.5, "seam": 0.3},
}

# --- one-shots (lab bBoot* / bSucc*) ----------------------------------------------------------
# The lab loops boot over 2.2 s with the motion done by 60 %; the rig plays it once and stops at
# 60 % (1320 ms). Success is the lab's 1.15 s. Event tracks key x, y, rot, sx, sy, op on each
# stone's Event node, and `bloom` (the pebble's warm flash) for success.
BOOT_LAB_MS = 2200
BOOT_MS = 1320
BOOT = {
    "anchor": [(0, {"y": -28, "sx": 0.97, "sy": 1.03, "op": 0}),
               (18, {"y": 1, "sx": 1.04, "sy": 0.96, "op": 1}),
               (30, {"op": 1}), (60, {"op": 1})],
    "pebble": [(0, {"x": 26, "y": -6, "rot": 18, "sx": 0.92, "sy": 0.92, "op": 0}),
               (18, {"x": 26, "y": -6, "rot": 18, "sx": 0.92, "sy": 0.92, "op": 0}),
               (38, {"x": -2, "y": 1, "rot": -3, "sx": 1.05, "sy": 0.95, "op": 1}),
               (50, {"x": 0.5, "rot": 1, "op": 1}),
               (60, {"op": 1})],
}
SUCCESS_MS = 1150
SUCCESS = {
    "anchor": [(0, {}), (14, {"y": 2, "sx": 1.04, "sy": 0.95}),
               (38, {"y": -9, "rot": -2.5, "sx": 0.98, "sy": 1.02}),
               (62, {"y": 1, "sx": 1.02, "sy": 0.98}), (100, {})],
    "pebble": [(0, {}), (14, {"y": 2.5, "sx": 1.06, "sy": 0.93}),
               (42, {"y": -14, "rot": -7, "x": -1.5}),
               (66, {"y": 1, "rot": 1.5}), (100, {})],
}
SUCCESS_BLOOM = [(0, 0), (40, 1), (100, 0)]          # the lab's bSuccFlash, CSS `ease`

# reduceMotion twins: brightness only, no transform.
BOOT_CALM_MS = 400                                     # a fade in
SUCCESS_CALM_MS = 700                                  # a warm bloom, no dip or lift
SUCCESS_CALM_BLOOM = [(0, 0), (40, 0.8), (100, 0)]

# --- hover (spec: a subtle 2 deg lean perk) ----------------------------------------------------
HOVER_LEAN_DEG = -2                                    # both stones lean left about their feet
