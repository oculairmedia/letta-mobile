"""The `Avatar` view-model contract as data: every property the file exposes, its type, its range.

This table is the documented contract. `native/rivdump/check_contract.py` reads the BUILT `.riv`
(both shipped copies, no Rive CLI needed) and fails unless the file exposes exactly these
properties with exactly these types, the `AvatarState` / `MascotShape` enums carry exactly the
keys below, and every name `RiveAvatarContract.kt` writes is one of them with a matching type.
`test_contract.py` runs that check in CI and also holds the input tables in `MASCOT.md` and
`ARTIST.md` to this list, so the docs cannot drift from the file either.

Order is the file's declaration order (gen_scene.py data()). Owners:

    host   the app writes it (the renderer, through `RiveInputSink`)
    both   the app writes it and the file's own listeners write it too (pointer fallback)
    bench  art-direction tunables the desktop bench writes; not part of the app contract

Imports nothing from the rest of the rig except the vocabulary, so the checker can load it
without building anything.
"""
from typing import NamedTuple

from rig.ids import SHAPES, SUSTAINED


class Prop(NamedTuple):
    """One view-model property: its name, its Rive type, its range and who writes it."""
    name: str
    type: str          # enum | number | boolean | trigger | color
    range: str         # human range ("0..1", "-1..1", the enum's name), "" for a trigger
    owner: str         # host | both | bench
    meaning: str


AVATAR = [
    Prop("state", "enum", "AvatarState", "host", "sustained state (keys below)"),
    Prop("mouthOpen", "number", "0..1", "host", "speech amplitude; the mouth morphs closed/half/open"),
    Prop("lookX", "number", "-1..1", "host", "horizontal gaze; the glyph moves inside the plate"),
    Prop("lookY", "number", "-1..1", "host", "vertical gaze"),
    Prop("blink", "trigger", "", "host", "one blink (the plate also blinks on its own)"),
    Prop("shape", "enum", "MascotShape", "host", "identity: one of eight bodies"),
    Prop("color", "color", "ARGB", "host", "identity: body, soft edge and halo are bound to it"),
    Prop("hovered", "boolean", "", "both", "pointer over the mascot: perk-up, attentive hold"),
    Prop("success", "trigger", "", "host", "happy flash (hop + spin), self-returning"),
    Prop("error", "trigger", "", "host", "sad flash (shake + drop); `state` then settles to error"),
    Prop("dragged", "boolean", "", "both", "held while dragging: the dragged glyph, compression"),
    Prop("tunePlate", "number", "0..1", "bench", "plate scale (0.5 = shipped)"),
    Prop("tuneGlyph", "number", "0..1", "bench", "glyph scale (0.5 = shipped)"),
    Prop("tuneMouth", "number", "0..1", "bench", "mouth scale (0.5 = shipped)"),
    Prop("tuneMouthY", "number", "0..1", "bench", "mouth height (0.5 = shipped)"),
    Prop("tuneScale", "number", "0..1", "bench", "whole-entity scale (0.5 = shipped)"),
    Prop("turnX", "number", "-1..1", "host", "head turn toward what the eyes look at"),
    Prop("turnY", "number", "-1..1", "host", "vertical head turn"),
    Prop("shapeRotation", "number", "0..360", "host", "identity: the body's turn in degrees"),
    # te3n1.5
    Prop("reduceMotion", "boolean", "", "host", "calm everything: no idle beats, wander or breath "
         "bob; flashes and reactions become a blink or a glyph change"),
    Prop("petting", "boolean", "", "host", "held while petted: content glyph, slow side sway, purr"),
    Prop("listenLevel", "number", "0..1", "host", "listening intensity (typing cadence): the face "
         "lifts up to 10 px and leans in up to 5 %"),
    Prop("moveX", "number", "-1..1", "host", "horizontal travel / drag velocity: the body leans, "
         "top trailing, up to 8 deg about the feet"),
    Prop("moveY", "number", "-1..1", "host", "vertical travel velocity, + is down: up stretches, "
         "down squashes, about the feet"),
    Prop("impact", "number", "0..1", "host", "how hard the next `land` is (soft < 0.34 <= mid < 0.67 <= hard)"),
    Prop("trickId", "number", "0..3", "host", "which trick the next `trick` plays: 0 spin-flip, "
         "1 jump-twirl, 2 peekaboo, 3 melt-and-reform"),
    Prop("poke", "trigger", "", "host", "420 ms squish, startled glyph, rebound"),
    Prop("dizzy", "trigger", "", "host", "1.5 s dizzy glyph and a decaying wobble on the base"),
    Prop("sent", "trigger", "", "host", "350 ms 'got it' catch-dip and lift"),
    Prop("toolStart", "trigger", "", "host", "300 ms small hop with a glance down"),
    Prop("toolDone", "trigger", "", "host", "250 ms nod"),
    Prop("land", "trigger", "", "host", "arrival squash about the feet, scaled by `impact`"),
    Prop("wake", "trigger", "", "host", "700 ms startle stretch out of sleeping/dreaming"),
    Prop("yawn", "trigger", "", "host", "1.2 s yawn (closed eye, open mouth) entering sleeping/dreaming"),
    Prop("trick", "trigger", "", "host", "plays trick `trickId`"),
]


# Rive type name of each contract type, as the built file's objects are called.
RIVE_TYPE = {"enum": "ViewModelPropertyEnumCustom", "number": "ViewModelPropertyNumber",
             "boolean": "ViewModelPropertyBoolean", "trigger": "ViewModelPropertyTrigger",
             "color": "ViewModelPropertyColor"}


ENUMS = {"AvatarState": list(SUSTAINED), "MascotShape": list(SHAPES)}


STATE_MACHINE = "Avatar"


VIEW_MODEL = "Avatar"


def by_name():
    return {p.name: p for p in AVATAR}
