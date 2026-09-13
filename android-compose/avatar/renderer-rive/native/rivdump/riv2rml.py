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


def lift(dump, artboard_name, client=9):
    ab = next(a for a in dump["artboards"] if a["name"] == artboard_name)
    objs = {o["index"]: o for o in ab["objects"] if o.get("type")}
    kids = {}
    for o in objs.values():
        if o["index"] != 0 and "parentId" in o:
            kids.setdefault(o["parentId"], []).append(o)
    new_id = lambda i: f"{client}:{i + 1}"  # object 0 is reserved in RML ids

    def emit(o, depth):
        t = o["type"]
        if t not in DRAWABLE:
            return []
        attrs = []
        for k, v in o.items():
            if k in ("index", "typeKey", "type") or k in SKIP_ATTRS:
                continue
            if k in ID_ATTRS:
                attrs.append(f'{k}="{new_id(v)}"')
                continue
            if k in ENUMS and isinstance(v, int) and v < len(ENUMS[k]):
                v = ENUMS[k][v]
            if k in DEFAULTS and v == DEFAULTS[k]:
                continue
            attrs.append(f'{k}="{fmt(v)}"')
        if t in ("Node", "Shape", "PointsPath", "Ellipse", "Rectangle", "Polygon", "Star", "Solo"):
            attrs.append(f'name="{t}{o["index"]}" id="{new_id(o["index"])}"')
        else:
            attrs.append(f'name="{t}"')
        children = [line for c in kids.get(o["index"], []) for line in emit(c, depth + 1)]
        pad = "    " * depth
        head = f'{pad}<{t} {" ".join(attrs)}'
        if not children:
            return [head + "/>"]
        return [head + ">"] + children + [f"{pad}</{t}>"]

    lines = []
    for c in kids.get(0, []):
        lines += emit(c, 1)
    root = objs[0]
    return root, lines


if __name__ == "__main__":
    dump = json.load(open(sys.argv[1], encoding="utf-8"))
    name = sys.argv[2]
    client = int(sys.argv[sys.argv.index("--id-client") + 1]) if "--id-client" in sys.argv else 9
    root, lines = lift(dump, name, client)
    body = "\n".join(lines)
    if "--standalone" in sys.argv:
        out = sys.argv[sys.argv.index("--standalone") + 1]
        w, h = root.get("width", 500), root.get("height", 500)
        doc = (f'<Rive version="1" kind="fragment">\n<Artboard clip="false" width="{fmt(w)}" height="{fmt(h)}" '
               f'name="{name}" id="{client}:1">\n{body}\n</Artboard>\n</Rive>\n')
        open(out, "w", encoding="utf-8", newline="\n").write(doc)
        print(f"wrote {out}: {len(lines)} lines, artboard {w}x{h}", file=sys.stderr)
    else:
        print(body)
