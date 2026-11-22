"""The `BrandMark` view-model contract as data: every property the file exposes, its type, its range.

This table is the documented contract, in the file's declaration order (gen_scene.py `data()`
emits the view model from it, so the two cannot disagree in the source). `test_contract.py` reads
the BUILT `.riv` (both shipped copies, through `native/rivdump/rivfile.py`, no Rive CLI) and fails
unless the file exposes exactly these properties with these types, the enums carry exactly these
keys in this order, and the `BrandMark` state machine exists. The README's contract table is held
to this list too.

Shape-compatible with the mascot's `rig/contract.py` (`Prop`, `ENUMS`, `STATE_MACHINE`,
`VIEW_MODEL`, `RIVE_TYPE`, `by_name()`), so `native/rivdump/check_contract.py`'s file checks run on
it unchanged; `AVATAR` is the name those checks read the property list under.

Owners, as in the mascot: `host` the app writes it. Nothing here is written by the file itself.

Imports nothing from the rest of the rig except the vocabulary.
"""
from typing import NamedTuple

from rig.ids import STATES, THEMES


class Prop(NamedTuple):
    """One view-model property: its name, its Rive type, its range and who writes it."""
    name: str
    type: str          # enum | number | boolean | trigger | color
    range: str         # human range, or the enum's name; "" for a trigger or a boolean
    owner: str         # host
    meaning: str


BRAND_MARK = [
    Prop("state", "enum", "BakenMarkState", "host",
         "sustained app / connection state (keys below); never a turn activity - the mascots own those"),
    Prop("theme", "enum", "BakenMarkTheme", "host",
         "palette: dark / light (01 North Sea Buoy) or the one-ink monoDark / monoLight"),
    Prop("boot", "trigger", "", "host",
         "one-shot splash entrance: the anchor seats, the pebble rolls in and nests into the seam. "
         "Fire before the first advance to start from nothing"),
    Prop("success", "trigger", "", "host",
         "one-shot peer-lock / boot-ready settle: weighted dip, low lift-and-tuck, warm bloom"),
    Prop("reduceMotion", "boolean", "", "host",
         "no loops and no hops: each state is a static parting / brightness, boot is a fade, "
         "success a bloom (parity with Avatar.reduceMotion)"),
    Prop("hovered", "boolean", "", "host", "pointer over the mark (desktop): a 2 deg lean perk"),
]

# The name check_contract.py's file checks read the property list under.
AVATAR = BRAND_MARK


RIVE_TYPE = {"enum": "ViewModelPropertyEnumCustom", "number": "ViewModelPropertyNumber",
             "boolean": "ViewModelPropertyBoolean", "trigger": "ViewModelPropertyTrigger",
             "color": "ViewModelPropertyColor"}


ENUMS = {"BakenMarkState": list(STATES), "BakenMarkTheme": list(THEMES)}

# What each state means to the host (the spec's "Host Signal" column), for the README table.
STATE_SIGNAL = {
    "idle": "connected and healthy; also whenever a mascot is thinking or working",
    "loading": "app / surface hydration, only with no mascot on screen",
    "connecting": "Iroh reconnect / QR pairing; fire `success` on lock",
    "degraded": "host / Iroh connection lost",
    "ambient": "an unread background / ambient update",
}


STATE_MACHINE = "BrandMark"


VIEW_MODEL = "BrandMark"


ARTBOARD = "BakenMark"


def by_name():
    return {p.name: p for p in BRAND_MARK}
