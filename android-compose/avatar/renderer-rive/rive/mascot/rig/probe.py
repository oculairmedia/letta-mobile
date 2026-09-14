"""Pose telemetry: which node properties to expose, and how to inject them into a document.

The pencil test as numbers (MOTION-PIPELINE section 0). A `DataBindContext` with
`direction="true" twoWay="true"` placed *inside* a node writes that node's property back into a
view-model number every frame, so `rive <dir> --data-dump=- --data-dump-every=N
--data-dump-filter='telemetry*'` prints the real, mixed, post-state-machine value of that property
for every frame of a scenario. One headless run, no rendering, no screenshots.

This module owns two things:

  PROBES     the table of (name, objectId, propertyKey, unit) we want to see
  inject()   adds, to a built document string, one `ViewModelPropertyNumber` per probe, one
             `ViewModelInstanceNumber` on the default instance, and one two-way bind inside the
             probed object

`gen_scene.py --probe` calls `inject()` on the built document and writes a throwaway variant;
`probe.py` builds that into a temp project and runs the CLI against it. Probe documents never
ship: `scene.rml` and the pushed file carry no debug surface, so the byte-identity invariant holds.

Ids
---
The probe properties are numbers on the Avatar view model (client 1), which is where every
existing property lives (1:1..1:11 the contract, 1:12..1:18 the tunables and turn, 1:20 the
default instance, 1:50 the view model itself, 1:100/1:200 the enums). They are allocated from
`PROBE_ID_FLOOR = 900` upward - deliberately far above anything the rig uses - and they are NOT
in `rig/ids.py`'s registry, because `ids.alloc()` records a name -> id mapping in the committed
`ids.json` so that `rive push` keeps updating the same object forever. These objects must never
reach a push: they exist only inside a throwaway document. Recording them would be a lie about
what is in the file. `_check_ids()` instead asserts, at import, that no probe id collides with
`ids.all_ids()`, which is the guarantee the registry was there to give.
"""
import re
from typing import NamedTuple

from rig import ids

# Rive property keys (mirrors of rml.py's, kept here so the probe table reads as a table).
X, Y, ROT, SX, SY, OPACITY = 13, 14, 15, 16, 17, 18
GRADIENT_OPACITY = 46
NESTED_VALUE = 239
JOYSTICK_X, JOYSTICK_Y = 299, 300      # Joystick.x / .y, same keys as rig/constants.JX / JY

PROBE_ID_FLOOR = 900                   # 1:900+ - above the view model, never allocated, never pushed
VM_ID = "1:50"
VM_INSTANCE_ID = "1:20"
PREFIX = "telemetry"                   # --data-dump-filter='telemetry*'


class Probe(NamedTuple):
    """One node property to read back every frame."""
    name: str          # "Body.y" - what the sheets and signatures call it
    object_id: str     # the Rive object that owns the property
    key: int           # the Rive property key
    unit: str          # px | rad | x | alpha | unit

    @property
    def vm_name(self):
        """The view-model property name; the dump's JSON key."""
        return f"{PREFIX}_{self.name.replace('.', '_')}"

    @property
    def prop_id(self):
        return _PROP_ID[self.name]


# The probe list. Order is the column order of the exposure sheet and the id order.
PROBES = (
    Probe("Body.x", ids.BODY_NODE, X, "px"),
    Probe("Body.y", ids.BODY_NODE, Y, "px"),
    Probe("Face.x", ids.FACE, X, "px"),
    Probe("Face.y", ids.FACE, Y, "px"),
    Probe("Face.rotation", ids.FACE, ROT, "rad"),
    Probe("Placement.scaleX", "0:233", SX, "x"),          # BodyPlacement (INFLATE_NODE): the breath
    Probe("Placement.scaleY", "0:233", SY, "x"),
    Probe("Turn.x", ids.TURN_NODE, X, "px"),
    Probe("Turn.y", ids.TURN_NODE, Y, "px"),
    Probe("Turn.rotation", ids.TURN_NODE, ROT, "rad"),
    Probe("Turn.scaleX", ids.TURN_NODE, SX, "x"),
    Probe("Arc.y", ids.ARC_NODE, Y, "px"),
    Probe("Lean.rotation", ids.LEAN_NODE, ROT, "rad"),
    Probe("Trail1.opacity", ids.TRAIL1, OPACITY, "alpha"),
    Probe("Lumen.opacity", "0:234", GRADIENT_OPACITY, "alpha"),
    # The Facing joystick reads live on both axes (proven: `probe.py beat:WanderSpin` moves x,
    # `probe.py beat:WanderPeek` moves y). It is the only place the facing can be read at all -
    # the joystick is what scrubs TurnX/TurnY, so Turn.* is its effect, not its value.
    Probe("Joystick.x", ids.JOYSTICK, JOYSTICK_X, "unit"),
    Probe("Joystick.y", ids.JOYSTICK, JOYSTICK_Y, "unit"),
)


# NOT PROBED, and why. A probe that reads a constant for ever is worse than no probe: it makes an
# X-sheet column that says "nothing happened" about something that did.
#
#   Plate.expr (NestedNumber 0:211, propertyKey 239). The bind is accepted - the CLI verifies, and
#   the document renders pixel-identical - but the value never moves: it reports the authored
#   `nestedValue="0"` for every frame of every scenario, including `--data=state=speaking`, where
#   the Expression layer certainly keys it. Reading a nested state machine's input back out is not
#   what propertyKey 239 does. The glyph a state selects has to be read from the authored keys, or
#   from a pixel.
UNPROBED = {"Plate.expr": (ids.PLATE_EXPR, NESTED_VALUE,
                           "binds and verifies, but always reports the authored nestedValue")}


_PROP_ID = {p.name: f"1:{PROBE_ID_FLOOR + i}" for i, p in enumerate(PROBES)}


def _check_ids():
    """Probe ids are outside rig/ids.py, so the uniqueness guarantee is made here instead."""
    names = [p.name for p in PROBES]
    if len(set(names)) != len(names):
        raise AssertionError("duplicate probe name in PROBES")
    taken = ids.all_ids()
    for name, pid in _PROP_ID.items():
        if pid in taken:
            raise AssertionError(f"probe id {pid} ({name}) collides with a rig id")
    return True


_check_ids()


def by_name(name):
    for p in PROBES:
        if p.name == name:
            return p
    raise KeyError(name)


def bind_xml(probe):
    """The two-way bind that writes the node property back into the view-model number."""
    return (f'<DataBindContext sourcePathIds="{VM_ID}-{probe.prop_id}" propertyKey="{probe.key}"'
            f' direction="true" twoWay="true"/>')


def _insert_child(document, object_id, child):
    """Put `child` inside the element carrying id="object_id", opening a self-closing tag if needed."""
    m = re.search(r'<(\w+)((?:"[^"]*"|[^>"])*?\bid="%s"(?:"[^"]*"|[^>"])*?)(/?)>' % re.escape(object_id),
                  document)
    if not m:
        raise SystemExit(f"--probe: no element with id={object_id!r} in the document")
    tag, attrs, closed = m.group(1), m.group(2), m.group(3)
    if closed:
        replacement = f"<{tag}{attrs}>\n    {child}\n</{tag}>"
    else:
        replacement = f"<{tag}{attrs}>\n    {child}"
    return document[:m.start()] + replacement + document[m.end():]


def inject(document):
    """Add the probe properties, their instance values and their binds to a built document string.

    Pure string work on the output of `gen_scene.scene_document()`, exactly like `--solo`: the
    generator itself is untouched, so the default output stays byte-identical.
    """
    props = "\n".join(f'    <ViewModelPropertyNumber name="{p.vm_name}" id="{p.prop_id}"/>'
                      for p in PROBES)
    values = "\n".join(f'        <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{p.prop_id}"/>'
                       for p in PROBES)

    anchor = f'<ViewModelInstance exports="true" name="Default" id="{VM_INSTANCE_ID}">'
    if anchor not in document:
        raise SystemExit("--probe: the Avatar view model's default instance is not where expected")
    document = document.replace(anchor, f"{props}\n\n{' ' * 4}{anchor}\n{values}", 1)

    for p in PROBES:
        document = _insert_child(document, p.object_id, bind_xml(p))
    return document


if __name__ == "__main__":
    width = max(len(p.name) for p in PROBES)
    for p in PROBES:
        print(f"{p.name:<{width}}  {p.object_id:>7}  key {p.key:<4} {p.unit:<6} {p.prop_id}")
    for name, (oid, key, why) in UNPROBED.items():
        print(f"{name:<{width}}  {oid:>7}  key {key:<4} {'-':<6} not probed: {why}")
