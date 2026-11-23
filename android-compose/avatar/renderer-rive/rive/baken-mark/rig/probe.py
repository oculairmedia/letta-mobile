"""Pose telemetry for the mark: which node properties to read back, and how to inject the binds.

The mascot's method, unchanged (its `rig/probe.py` explains it): a two-way `DataBindContext`
inside a node writes that node's property into a view-model number every frame, and the CLI's
`--data-dump` prints it. `gen_scene.py --probe` calls `inject()` on the built document; the result
only ever lives in a temp project (probe.py), never in scene.rml.

Probe ids are `1:900+`, outside rig/ids.py on purpose (they must never ship); `_check_ids()`
asserts they collide with nothing the rig names.
"""
import re
from typing import NamedTuple

from rig import ids

X, Y, ROT, SX, SY, OPACITY = 13, 14, 15, 16, 17, 18
GRADIENT_OPACITY = 46

PROBE_ID_FLOOR = 900
VM_ID = "1:50"
VM_INSTANCE_ID = ids.VM_INSTANCE
PREFIX = "telemetry"


class Probe(NamedTuple):
    name: str
    object_id: str
    key: int
    unit: str          # px | rad | x | alpha

    @property
    def vm_name(self):
        return f"{PREFIX}_{self.name.replace('.', '_')}"

    @property
    def prop_id(self):
        return _PROP_ID[self.name]


_A, _P = ids.NODES["anchor"], ids.NODES["pebble"]

# Order is the exposure sheet's column order and the id order.
PROBES = (
    # Pose layer: the state's weight shift, about each ground contact.
    Probe("AnchorPose.x", _A["Pose"], X, "px"),
    Probe("AnchorPose.y", _A["Pose"], Y, "px"),
    Probe("AnchorPose.rotation", _A["Pose"], ROT, "rad"),
    Probe("AnchorPose.scaleY", _A["Pose"], SY, "x"),
    Probe("PebblePose.x", _P["Pose"], X, "px"),
    Probe("PebblePose.y", _P["Pose"], Y, "px"),
    Probe("PebblePose.rotation", _P["Pose"], ROT, "rad"),
    Probe("PebblePose.scaleX", _P["Pose"], SX, "x"),
    # Event layer: boot and success.
    Probe("AnchorEvent.y", _A["Event"], Y, "px"),
    Probe("AnchorEvent.rotation", _A["Event"], ROT, "rad"),
    Probe("AnchorEvent.scaleY", _A["Event"], SY, "x"),
    Probe("AnchorEvent.opacity", _A["Event"], OPACITY, "alpha"),
    Probe("PebbleEvent.x", _P["Event"], X, "px"),
    Probe("PebbleEvent.y", _P["Event"], Y, "px"),
    Probe("PebbleEvent.rotation", _P["Event"], ROT, "rad"),
    Probe("PebbleEvent.opacity", _P["Event"], OPACITY, "alpha"),
    Probe("PebbleBloom.opacity", ids.PEBBLE_BLOOM, OPACITY, "alpha"),
    # Hover layer.
    Probe("PebbleLean.rotation", _P["Lean"], ROT, "rad"),
    # Light layer: brightness, the overlays and the two lights.
    Probe("AnchorTone.opacity", _A["Tone"], OPACITY, "alpha"),
    Probe("PebbleTone.opacity", _P["Tone"], OPACITY, "alpha"),
    Probe("PebbleDim.opacity", ids.PEBBLE_DIM, OPACITY, "alpha"),
    Probe("PebbleWarm.opacity", ids.PEBBLE_WARM, OPACITY, "alpha"),
    Probe("Bounce.opacity", ids.ANCHOR_BOUNCE_GRAD, GRADIENT_OPACITY, "alpha"),
    Probe("Sheen.x", ids.ANCHOR_SHEEN, X, "px"),
    Probe("Sheen.opacity", ids.ANCHOR_SHEEN, OPACITY, "alpha"),
    Probe("Seam.opacity", ids.SEAM_POSE, OPACITY, "alpha"),
    Probe("Seam.scaleY", ids.SEAM_POSE, SY, "x"),
)

# The properties that are MOTION (a transform), as opposed to light. reduceMotion allows light.
MOTION = tuple(p.name for p in PROBES if p.unit in ("px", "rad", "x"))

_PROP_ID = {p.name: f"1:{PROBE_ID_FLOOR + i}" for i, p in enumerate(PROBES)}


def _check_ids():
    names = [p.name for p in PROBES]
    if len(set(names)) != len(names):
        raise AssertionError("duplicate probe name in PROBES")
    taken = set(ids.all_ids())
    for name, pid in _PROP_ID.items():
        if pid in taken:
            raise AssertionError(f"probe id {pid} ({name}) collides with a rig id")


_check_ids()


def bind_xml(probe):
    return (f'<DataBindContext sourcePathIds="{VM_ID}-{probe.prop_id}" propertyKey="{probe.key}"'
            f' direction="true" twoWay="true"/>')


def _insert_child(document, object_id, child):
    m = re.search(r'<(\w+)((?:"[^"]*"|[^>"])*?\bid="%s"(?:"[^"]*"|[^>"])*?)(/?)>' % re.escape(object_id), document)
    if not m:
        raise SystemExit(f"--probe: no element with id={object_id!r} in the document")
    tag, attrs, closed = m.group(1), m.group(2), m.group(3)
    replacement = f"<{tag}{attrs}>\n    {child}\n</{tag}>" if closed else f"<{tag}{attrs}>\n    {child}"
    return document[:m.start()] + replacement + document[m.end():]


def inject(document):
    """Add the probe properties, their default values and their binds to a built document."""
    props = "\n".join(f'    <ViewModelPropertyNumber name="{p.vm_name}" id="{p.prop_id}"/>' for p in PROBES)
    values = "\n".join(f'        <ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="{p.prop_id}"/>'
                       for p in PROBES)
    anchor = f'<ViewModelInstance exports="true" name="Default" id="{VM_INSTANCE_ID}">'
    if anchor not in document:
        raise SystemExit("--probe: the BrandMark default instance is not where expected")
    document = document.replace(anchor, f"{props}\n\n{' ' * 4}{anchor}\n{values}", 1)
    for p in PROBES:
        document = _insert_child(document, p.object_id, bind_xml(p))
    return document


if __name__ == "__main__":
    width = max(len(p.name) for p in PROBES)
    for p in PROBES:
        print(f"{p.name:<{width}}  {p.object_id:>6}  key {p.key:<4} {p.unit:<6} {p.prop_id}")
