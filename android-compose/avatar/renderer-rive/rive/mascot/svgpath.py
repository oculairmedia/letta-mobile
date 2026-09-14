"""SVG path -> RML geometry, for the art in ./art (see SPEC.md).

Accepts the subset the spec commits to: absolute M / L / C / Z, one <path> per file, a fill or a
stroke, optional fill-rule="evenodd". Coordinates are imported as authored (1 SVG unit = 1 px);
the viewBox is never used for fitting.

Two emitters:
  path_rml(svg, ...)     -> one <Shape> with a PointsPath per subpath and the paint, for glyphs
  body_vertices(svg)     -> the 8 mirrored vertices (x, y, rotation, distance) for the body path
"""
import math
import re

_NUM = r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"
_SUPPORTED = set("MLCZ")


def _tokens(d):
    for m in re.finditer(rf"([A-Za-z])|({_NUM})", d):
        letter, num = m.group(1), m.group(2)
        if letter:
            if letter not in _SUPPORTED:
                raise ValueError(f"unsupported path command {letter}")
            yield letter
        else:
            yield float(num)


def _close_sub(cur):
    cur["closed"] = True
    v0, vN = cur["verts"][0], cur["verts"][-1]
    if len(cur["verts"]) > 1 and abs(v0[0] - vN[0]) < 1e-6 and abs(v0[1] - vN[1]) < 1e-6:
        cur["verts"][0] = (v0[0], v0[1], vN[2], v0[3])
        cur["verts"].pop()
    return (v0[0], v0[1])


def _move_to(toks, i):
    return {"closed": False, "verts": [(toks[i], toks[i + 1], None, None)]}, (toks[i], toks[i + 1]), i + 2


def _line_to(cur, toks, i):
    cur["verts"].append((toks[i], toks[i + 1], None, None))
    return (toks[i], toks[i + 1]), i + 2


def _cubic_to(cur, toks, i):
    c1, c2, p = (toks[i], toks[i + 1]), (toks[i + 2], toks[i + 3]), (toks[i + 4], toks[i + 5])
    px, py, cin, _ = cur["verts"][-1]
    cur["verts"][-1] = (px, py, cin, c1)
    cur["verts"].append((p[0], p[1], c2, None))
    return p, i + 6


def parse_path(d):
    """-> list of subpaths; each {'closed': bool, 'verts': [(x, y, c_in, c_out)]} where c_in/c_out
    are absolute control points of the incoming / outgoing cubic, or None for straight segments."""
    subpaths, cur, pos = [], None, (0.0, 0.0)
    cmd = None
    toks = list(_tokens(d))
    i = 0

    def flush_sub():
        nonlocal cur
        if cur and cur["verts"]:
            subpaths.append(cur)
        cur = None

    while i < len(toks):
        t = toks[i]
        if isinstance(t, str):
            cmd = t
            if cmd == "Z":
                if cur:
                    pos = _close_sub(cur)
                flush_sub()
            i += 1
            continue
        if cmd == "M":
            flush_sub()
            cur, pos, i = _move_to(toks, i)
            cmd = "L"
        elif cmd == "L":
            pos, i = _line_to(cur, toks, i)
        elif cmd == "C":
            pos, i = _cubic_to(cur, toks, i)
        else:
            raise ValueError(f"unsupported path command {cmd}")
    flush_sub()
    return subpaths


def _handle(v, c):
    if c is None:
        return 0.0, 0.0
    dx, dy = c[0] - v[0], c[1] - v[1]
    return round(math.atan2(dy, dx), 5), round(math.hypot(dx, dy), 4)


def _vertex_xml(v, ids=None):
    x, y, cin, cout = v
    if cin is None and cout is None:
        return f'<StraightVertex x="{x:g}" y="{y:g}"/>'
    ir, idist = _handle((x, y), cin)
    orot, odist = _handle((x, y), cout)
    return (f'<CubicDetachedVertex x="{x:g}" y="{y:g}" inRotation="{ir}" inDistance="{idist}" '
            f'outRotation="{orot}" outDistance="{odist}"/>')


def read_svg(path):
    src = open(path, encoding="utf-8").read()
    m = re.search(r'<path\s+([^>]*)/?>', src, re.S)
    attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
    return attrs


def path_rml(*parts, **opts):
    """A <Shape> holding every subpath of the SVG and its paint. Ink is normalised to `color`."""
    svg_file, name, sid = parts[0], parts[1], parts[2]
    color = parts[3] if len(parts) > 3 else opts.get("color", "FF111111")
    opacity = opts.get("opacity")
    x = opts.get("x", 0)
    y = opts.get("y", 0)
    extra_attrs = opts.get("extra_attrs", "")
    a = read_svg(svg_file)
    subs = parse_path(a["d"])
    paths = []
    for k, sp in enumerate(subs):
        verts = "\n".join("    " + _vertex_xml(v) for v in sp["verts"])
        paths.append(f'<PointsPath isClosed="{"true" if sp["closed"] else "false"}" name="Path{k}">\n{verts}\n</PointsPath>')
    if a.get("stroke", "none") != "none" and a.get("fill", "none") == "none":
        w = float(a.get("stroke-width", 1))
        paint = (f'<Stroke thickness="{w:g}" cap="round" join="round" name="Stroke">'
                 f'<SolidColor colorValue="{color}" name="Color"/></Stroke>')
    else:
        rule = ' fillRule="evenOdd"' if a.get("fill-rule") == "evenodd" else ""
        paint = f'<Fill{rule} name="Fill"><SolidColor colorValue="{color}" name="Color"/></Fill>'
    op = f' opacity="{opacity}"' if opacity is not None else ""
    body = "\n".join(paths + [paint])
    return f'<Shape x="{x}" y="{y}"{op} {extra_attrs} name="{name}" id="{sid}">\n' + "\n".join("    " + l for l in body.split("\n")) + "\n</Shape>"


def body_vertices(svg_file):
    """The body contract: one closed subpath, 8 vertices, mirrored tangents.
    -> [(x, y, rotation, distance)] with rotation/distance of the OUTGOING handle (spec §1)."""
    subs = parse_path(read_svg(svg_file)["d"])
    assert len(subs) == 1 and subs[0]["closed"], f"{svg_file}: body must be one closed path"
    verts = subs[0]["verts"]
    assert len(verts) == 8, f"{svg_file}: body must have 8 vertices, has {len(verts)}"
    out = []
    for x, y, cin, cout in verts:
        rot, dist = _handle((x, y), cout)
        # Mirrored means the incoming handle is the outgoing one reflected; check within rounding.
        if cin is not None:
            irot, idist = _handle((x, y), cin)
            # Mirrored: the incoming handle points the opposite way, so irot - rot is +-pi.
            assert abs(idist - dist) < 0.05 and abs(((irot - rot) % (2 * math.pi)) - math.pi) < 0.01, \
                f"{svg_file}: vertex ({x},{y}) tangents are not mirrored"
        out.append((round(x, 4), round(y, 4), rot, dist))
    return out


def mouth_vertices(svg_file):
    """The three mouth samples share topology: one closed subpath of 4 detached-cubic vertices."""
    subs = parse_path(read_svg(svg_file)["d"])
    assert len(subs) == 1 and len(subs[0]["verts"]) == 4, f"{svg_file}: mouth must be 4 vertices"
    out = []
    for x, y, cin, cout in subs[0]["verts"]:
        ir, idist = _handle((x, y), cin)
        orot, odist = _handle((x, y), cout)
        out.append((round(x, 4), round(y, 4), ir, idist, orot, odist))
    return out


if __name__ == "__main__":
    import glob, os, sys
    for f in sorted(glob.glob(os.path.join(os.path.dirname(__file__), "art", "*.svg"))):
        subs = parse_path(read_svg(f)["d"])
        print(os.path.basename(f), [(len(s["verts"]), s["closed"]) for s in subs])
