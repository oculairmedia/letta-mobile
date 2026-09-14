"""Lifts rest-pose vector geometry out of a rivdump JSON into RML fragments.

usage: python riv2rml.py <dump.json> <artboard name> [--id-client N] [--standalone out.rml]

Emits the drawable tree of one artboard - Node, Shape, PointsPath/Ellipse/Rectangle/Polygon/Star,
vertices, Fill/Stroke, SolidColor/gradients, clipping - with property names as RML attributes
(rivdump already uses the runtime's own names, which are RML's). Bones, skins, joysticks,
constraints and animations are dropped on purpose: this lifts art, not rigs. Rotations stay in
radians. Ids are "<client>:<index>" so a fragment can be pasted into another file without clashes.

--standalone wraps the fragment in an Artboard so `rive . --screenshot` can check the lift.
"""
import json
import sys
from pathlib import Path
from xml.sax.saxutils import escape


def _cli_path(arg: str) -> Path:
    if "\x00" in arg or ".." in Path(arg).parts:
        raise SystemExit(f"invalid path: {arg}")
    return Path(arg).expanduser().resolve()

DRAWABLE = {
    "Node", "Shape", "PointsPath", "Ellipse", "Rectangle", "Polygon", "Star", "Triangle",
    "StraightVertex", "CubicMirroredVertex", "CubicDetachedVertex", "CubicAsymmetricVertex",
    "Fill", "Stroke", "SolidColor", "LinearGradient", "RadialGradient", "GradientStop",
    "ClippingShape", "Feather", "TrimPath", "Solo",
}
# Attributes that are references into the object table, remapped to the new ids.
ID_ATTRS = {"sourceId", "activeComponentId"}
SKIP_ATTRS = {"parentId", "name", "drawableFlags", "blendModeValue", "pathFlags", "flags",
              "computedLocalX", "computedLocalY", "computedWorldX", "computedWorldY",
              "computedRootX", "computedRootY", "computedWidth", "computedHeight", "length",
              "xArtboard", "yArtboard", "isHole", "linkCornerRadius"}
# The runtime defaults; attributes at their default are omitted to keep fragments readable.
DEFAULTS = {"x": 0, "y": 0, "rotation": 0, "scaleX": 1, "scaleY": 1, "opacity": 1, "radius": 0,
            "originX": 0.5, "originY": 0.5, "position": 0, "isVisible": True, "isClosed": False,
            "fillRule": 0, "thickness": 1, "cap": 0, "join": 0, "transformAffectsStroke": True,
            "cornerRadiusTL": 0, "cornerRadiusTR": 0, "cornerRadiusBL": 0, "cornerRadiusBR": 0,
            "startX": 0, "startY": 0, "endX": 0, "endY": 0}
ENUMS = {"cap": ["butt", "round", "square"], "join": ["miter", "round", "bevel"], "fillRule": ["nonZero", "evenOdd", "clockwise"]}


def fmt(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return f"{round(v, 3):g}"
    return str(v)


def xml_attr(v):
    return escape(fmt(v), {'"': "&quot;"})


def _remap(k, v, new_id):
    if k in ID_ATTRS:
        return f'{k}="{new_id(v)}"'
    if k in ENUMS and isinstance(v, int) and v < len(ENUMS[k]):
        v = ENUMS[k][v]
    if k in DEFAULTS and v == DEFAULTS[k]:
        return None
    return f'{k}="{xml_attr(v)}"'


def _attrs_for(o, new_id):
    attrs = []
    for k, v in o.items():
        if k in ("index", "typeKey", "type") or k in SKIP_ATTRS:
            continue
        mapped = _remap(k, v, new_id)
        if mapped:
            attrs.append(mapped)
    t = o["type"]
    if t in ("Node", "Shape", "PointsPath", "Ellipse", "Rectangle", "Polygon", "Star", "Solo"):
        attrs.append(f'name="{t}{o["index"]}" id="{new_id(o["index"])}"')
    else:
        attrs.append(f'name="{t}"')
    return attrs


def _emit(o, depth, kids, new_id):
    t = o["type"]
    if t not in DRAWABLE:
        return []
    children = [line for c in kids.get(o["index"], []) for line in _emit(c, depth + 1, kids, new_id)]
    pad = "    " * depth
    head = f'{pad}<{t} {" ".join(_attrs_for(o, new_id))}'
    if not children:
        return [head + "/>"]
    return [head + ">"] + children + [f"{pad}</{t}>"]


def _kid_map(objs):
    kids = {}
    for o in objs.values():
        if o["index"] != 0 and "parentId" in o:
            kids.setdefault(o["parentId"], []).append(o)
    return kids


def lift(dump, artboard_name, client=9, subtree=None):
    ab = next(a for a in dump["artboards"] if a["name"] == artboard_name)
    objs = {o["index"]: o for o in ab["objects"] if o.get("type")}
    kids = _kid_map(objs)
    new_id = lambda i: f"{client}:{i + 1}"  # object 0 is reserved in RML ids
    roots = kids.get(subtree, []) if subtree is not None else kids.get(0, [])
    lines = []
    for c in roots:
        lines += _emit(c, 1, kids, new_id)
    if subtree is not None and subtree in objs:
        lines = _emit(objs[subtree], 1, kids, new_id)
    return objs[0], lines


def _flag_value(argv, flag, default=None, cast=int):
    if flag not in argv:
        return default
    return cast(argv[argv.index(flag) + 1])


def _write_standalone(out, root, name, client, body, lines):
    w, h = root.get("width", 500), root.get("height", 500)
    doc = (f'<Rive version="1" kind="fragment">\n<Artboard clip="false" width="{xml_attr(w)}" height="{xml_attr(h)}" '
           f'name="{xml_attr(name)}" id="{client}:1">\n{body}\n</Artboard>\n</Rive>\n')
    out.write_text(doc, encoding="utf-8", newline="\n")
    print(f"wrote {out}: {len(lines)} lines, artboard {w}x{h}", file=sys.stderr)


def main(argv=None):
    argv = sys.argv if argv is None else argv
    if len(argv) < 3:
        raise SystemExit("usage: python riv2rml.py <dump.json> <artboard name> [--id-client N] [--standalone out.rml]")
    dump = json.loads(_cli_path(argv[1]).read_text(encoding="utf-8"))
    name = argv[2]
    client = _flag_value(argv, "--id-client", 9)
    subtree = _flag_value(argv, "--subtree")
    root, lines = lift(dump, name, client, subtree)
    body = "\n".join(lines)
    if "--standalone" in argv:
        _write_standalone(_cli_path(argv[argv.index("--standalone") + 1]), root, name, client, body, lines)
    else:
        print(body)


if __name__ == "__main__":
    main()
