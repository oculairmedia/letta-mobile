#!/usr/bin/env python3
"""Generate/check consultant vector-deformation proofs. Never calls Rive or writes RML.

Run: python art/mesh/validate_deformation.py [--check]
Requires Python 3, numpy, Pillow, CairoSVG. Inputs are the eight existing body SVGs.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import io
import math
import re
import xml.etree.ElementTree as ET

import cairosvg
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ART = HERE.parent
spec = importlib.util.spec_from_file_location("mascot_svgpath", ART.parent / "svgpath.py")
svgpath = importlib.util.module_from_spec(spec)
spec.loader.exec_module(svgpath)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
GRID = (-150, -90, -30, 30, 90, 150)
# name, primary X scale, local band delta, shear, independent radial inflate, root Y
POSES = {
    "rest": (1, 0, 0, 1, 0),
    "crouch": (1.06, .008, 0, 1, 6),
    "ascent": (.93, -.008, 0, 1, -24),
    "apex": (1, 0, 0, 1, -48),
    "fall": (.96, -.006, 0, 1, -20),
    "land": (1.10, .012, 0, 1, 4),
    "settle": (1.02, .003, 0, 1, -1),
    "drag-left": (1 / .92, -.008, -.015, 1, 0),
    "drag-right": (1 / .92, -.008, .015, 1, 0),
    "inflate": (1, 0, 0, 1.03, -11),
    "sleep-rest": (1, 0, 0, .985, 0),
    "sleep-inhale": (1, 0, 0, 1.015, -11),
    "error-settle": (1, 0, 0, 1, 24),
}


def weights(y):
    crown = min(1., max(0., -y / 150.))
    base = min(1., max(0., y / 150.))
    return np.array([crown, 1. - crown - base, base])


def read_body(path):
    svgpath.body_vertices(path)  # Existing Fable converter validates eight mirrored vertices.
    root = ET.parse(path).getroot()
    assert root.attrib["viewBox"] == "-180 -180 360 360"
    assert len(root) == 1 and root[0].tag.endswith("path")
    d = root[0].attrib["d"]
    assert re.findall(r"[A-Za-z]", d) == ["M"] + ["C"] * 8 + ["Z"]
    vs = svgpath.parse_path(d)[0]["verts"]
    return np.array([[[v[0], v[1]], v[2], v[3]] for v in vs], dtype=float)


def segments(v):
    return [np.array([v[i, 0], v[i, 2], v[(i + 1) % 8, 1], v[(i + 1) % 8, 0]]) for i in range(8)]


def area(v):
    """Exact signed area of the authored cubic boundary, via polynomial integration."""
    total = 0.
    for p0, p1, p2, p3 in segments(v):
        c = np.array([p0, 3 * (p1 - p0), 3 * (p0 - 2*p1 + p2), -p0 + 3*p1 - 3*p2 + p3])
        x, y = c[:, 0], c[:, 1]
        cross = np.polynomial.polynomial.polysub(
            np.polynomial.polynomial.polymul(x, np.polynomial.polynomial.polyder(y)),
            np.polynomial.polynomial.polymul(y, np.polynomial.polynomial.polyder(x)))
        total += sum(value / (i + 1) for i, value in enumerate(cross)) / 2
    return total


def deform(*args, **opts):
    v = args[0]
    sx = args[1] if len(args) > 1 else opts.get('sx', 1)
    local = args[2] if len(args) > 2 else opts.get('local', 0)
    shear = args[3] if len(args) > 3 else opts.get('shear', 0)
    inflate = args[4] if len(args) > 4 else opts.get('inflate', 1)
    return _deform(v, sx, local, shear, inflate)


def _deform(v, sx, local, shear, inflate):
    """Weights belong to anchors; each anchor and its two handles share one affine map."""
    out = v.copy()
    for i, points in enumerate(v):
        w = weights(points[0, 1])
        ax = float(w @ np.array([sx-local, sx, sx+local]))
        # Shear-like lean from bone translations, not an assumed native skew property.
        tx = float(w @ np.array([-150*shear, 0, 150*shear]))
        out[i, :, 0] = ax * points[:, 0] + tx
        out[i, :, 1] = points[:, 1] / sx
    q = area(v) / area(out)
    out[:, :, 1] *= q  # One common Y correction: exact planar area before deliberate inflate.
    out *= inflate
    return out, q


def path_d(v):
    f = lambda xy: f"{xy[0]:.6f} {xy[1]:.6f}"
    d = "M " + f(v[0, 0])
    for i in range(8):
        j = (i + 1) % 8
        d += " C " + " ".join(f(p) for p in (v[i, 2], v[j, 1], v[j, 0]))
    return d + " Z"


def sample(v, count=96):
    t = np.arange(count, dtype=float)[:, None] / count
    return np.concatenate([(1-t)**3*p[0] + 3*(1-t)**2*t*p[1] + 3*(1-t)*t*t*p[2] + t**3*p[3]
                           for p in segments(v)])


def has_crossing(p):
    """Strict non-adjacent segment intersections on a 768-point boundary sample."""
    a, b = p, np.roll(p, -1, axis=0)
    for i in range(len(p) - 2):
        js = np.arange(i + 2, len(p) - (i == 0))
        if not len(js):
            continue
        c, d = a[js], b[js]
        ab, cd = b[i] - a[i], d - c
        cross = lambda u, v: u[..., 0]*v[..., 1] - u[..., 1]*v[..., 0]
        if np.any((cross(ab, c-a[i])*cross(ab, d-a[i]) < -1e-9) &
                  (cross(cd, a[i]-c)*cross(cd, b[i]-c) < -1e-9)):
            return True
    return False


def text(*args, **opts):
    draw, pos, value = args[0], args[1], args[2]
    size = args[3] if len(args) > 3 else opts.get('size', 18)
    fill = args[4] if len(args) > 4 else opts.get('fill', "#202933")
    draw.text(pos, value, font=ImageFont.truetype(FONT, size), fill=fill)


def orb(v, pose, background="#f4f6f8", ghost=True):
    values = POSES[pose]
    out, _ = deform(v, *values)
    root_y = values[-1]
    # A proof raster only. This temporary SVG is neither saved nor an ingest asset.
    old = f'<path d="{path_d(v)}" fill="none" stroke="#77838f" stroke-width="1.2" stroke-dasharray="4 4"/>' if ghost else ""
    eye_d = ET.parse(ART / "glyph-idle.svg").getroot()[0].attrib["d"]
    # Gradient shading is a validation approximation; vectors and material source remain unchanged.
    body_svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="500" height="500">'
                f'<path d="{path_d(out)}" transform="translate(250 {270+1.25*root_y}) scale(1.25)" fill="white"/></svg>')
    mask = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=body_svg.encode()))).convert("RGBA").getchannel("A")
    yy, xx = np.mgrid[0:500, 0:500]
    highlight = np.exp(-(((xx-160)/160)**2 + ((yy-120)/180)**2)) * .52
    shade = np.clip((yy-240)/450, 0, .27)
    base = np.array([121,183,223], dtype=float)
    rgb = base[None,None,:]*(1-highlight[:,:,None]-shade[:,:,None]) + 255*highlight[:,:,None]
    paint = Image.fromarray(np.uint8(np.clip(rgb, 0, 255)), "RGB").convert("RGBA")
    paint.putalpha(mask)
    result = Image.new("RGBA", (500,500), background)
    result.alpha_composite(paint)
    # Keep the card/glyph rigid; copy their original pixels over the painted body.
    face_svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="500" height="500">'
                f'<g transform="translate(250 {270+1.25*root_y}) scale(1.25)">'
                f'<rect x="-60" y="-76" width="120" height="120" rx="27" fill="#F7F7F7"/>'
                f'<path d="{eye_d}" transform="translate(0 -16)" fill="#111111"/></g></svg>')
    result.alpha_composite(Image.open(io.BytesIO(cairosvg.svg2png(bytestring=face_svg.encode()))).convert("RGBA"))
    if ghost:
        ghost_svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="500" height="500">'
                     f'<path d="{path_d(v)}" transform="translate(250 {270+1.25*root_y}) scale(1.25)" '
                     f'fill="none" stroke="#687785" stroke-opacity=".65" stroke-width="1" stroke-dasharray="4 4"/></svg>')
        result.alpha_composite(Image.open(io.BytesIO(cairosvg.svg2png(bytestring=ghost_svg.encode()))).convert("RGBA"))
    return result.convert("RGB")


def lattice(v):
    lines = []
    for x in GRID:
        lines.append(f"M {x} -150 L {x} 150")
    for y in GRID:
        lines.append(f"M -150 {y} L 150 {y}")
    # One colour, no SVG text or transforms. Number labels live in the PNG and table.
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="-180 -180 360 360">\n'
            f'  <path d="{path_d(v)}" fill="none" stroke="#111111" stroke-width="2"/>\n'
            f'  <path d="{" ".join(lines)}" fill="none" stroke="#111111" stroke-width="0.6"/>\n</svg>\n').encode()


def proof_png(bodies, metrics):
    image = Image.new("RGB", (1600, 1810), "#f4f6f8")
    draw = ImageDraw.Draw(image)
    text(draw, (35, 22), "LOCAL DEFORMATION / ONE IDENTITY", 28)
    text(draw, (35, 64), "500 x 500 artboard; PROPOSED display scale 1.25. Grey = rest outline at the same root position.", 17)
    text(draw, (35, 91), "Vector sampling proof. Native bones, feather, facing and trails require Fable runtime validation.", 17)
    blob = bodies["body-blob"]
    for i, pose in enumerate(("land", "drag-right", "inflate")):
        x = 25 + i * 525
        image.paste(orb(blob, pose), (x, 132))
        text(draw, (x+8, 637), {"land":"LAND / +10% primary X", "drag-right":"DRAG / vertical compression", "inflate":"INHALE / +3% radial"}[pose], 20)
        m = metrics[("body-blob", pose)]
        text(draw, (x+8, 669), f"Area {m['area_change_pct']:+.4f}%  |  local q {m['q']:.7f}", 16)
        for j, size in enumerate((44,72)):
            tiny = orb(blob, pose, ghost=False).resize((size,size), Image.Resampling.LANCZOS)
            image.paste(tiny, (x+25+j*135,715))
            text(draw, (x+20+j*135, 795), f"{size} px / 1:1", 15)
        text(draw, (x+8, 830), "Plate 120 x 120, r27. Eye/card remain rigid.", 15)
    text(draw, (35, 891), "6 x 6 WEIGHT FIELD", 23)
    text(draw, (565, 891), "EIGHT IDENTITIES / SAME LAND DEFORMER", 23)
    grid_svg = lattice(blob)
    grid_img = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=grid_svg, output_width=480, output_height=480))).convert("RGBA")
    image.paste(grid_img, (35,935), grid_img)
    for r,y in enumerate(GRID):
        for c,x in enumerate(GRID):
            px = 35 + (x+180)*480/360
            py = 935 + (y+180)*480/360
            draw.ellipse((px-3,py-3,px+3,py+3),fill="#111111")
            text(draw, (px+5,py-20), str(r*6+c), 15)
    for i,(name,v) in enumerate(bodies.items()):
        x = 560 + (i%4)*250
        y = 950 + (i//4)*235
        render = orb(v, "land", ghost=True).resize((205,205), Image.Resampling.LANCZOS)
        image.paste(render,(x,y))
        text(draw, (x+10,y+205),name.replace("body-",""),16)
    text(draw, (35,1460), "Measured gates", 24)
    text(draw, (35,1503), "8 families x 13 endpoints + 41 samples/success interval; boundary samples 768 endpoints / 384 in-between.",17)
    text(draw, (35,1537), "Area uses exact cubic integration; intersections use sampled line segments. No body SVGs changed.",17)
    text(draw, (35,1571), "The 36 grid points are a design sampling field; the imported body remains 8 cubic vertices.",17)
    text(draw, (35,1605), "Every anchor and both handles share weights. The same deformation applies to fill/shade/gloss/edge/halo.",17)
    text(draw, (35,1648), "Force poses replace the existing squash scale. Breath inflate is suspended during force poses.",17)
    text(draw, (35,1682), "No limbs. One plate-eye. Shape family retained. This proof is geometry, not a recognition study.",17)
    text(draw, (35,1735), "Run: python art/mesh/validate_deformation.py --check",16)
    data=io.BytesIO();image.save(data,format="PNG",optimize=True)
    return data.getvalue()


def _pose_margin(p, root_y):
    margin = 500.
    a = 0
    R = np.array([[math.cos(a), -math.sin(a)], [math.sin(a), math.cos(a)]])
    for rx in (-24, 24):
        pp = 1.25 * (p @ R.T + np.array([rx, root_y])) + np.array([250, 270])
        margin = min(margin, float(np.min(pp)), float(np.min(500 - pp)))
    return margin


def _measure_pose(name, v, pose, values):
    out, q = deform(v, *values)
    assert np.max(np.abs(out[:, 1] + out[:, 2] - 2 * out[:, 0])) < .00022
    p = sample(out)
    assert not has_crossing(p), (name, pose, "crossing")
    area_change = 100 * (area(out) / area(v) - 1)
    expected = 100 * (values[3] ** 2 - 1)
    error = abs(area_change - expected)
    assert error < 1e-9
    delta = float(np.max(np.linalg.norm(out[:, 0] - v[:, 0], axis=1)) / 150)
    assert delta <= .12, (name, pose, delta)
    margin = _pose_margin(p, values[-1])
    assert margin >= 0, (name, pose, margin)
    row = {
        "q": q,
        "area_change_pct": area_change,
        "delta_pct": 100 * delta,
        "margin": margin,
        "bounds": (float(p[:, 0].min()), float(p[:, 1].min()), float(p[:, 0].max()), float(p[:, 1].max())),
    }
    return row, margin, error, delta


def _assert_success_blend(name, v):
    names = ("rest", "crouch", "ascent", "apex", "fall", "land", "settle", "rest")
    count = 0
    for left, right in zip(names, names[1:]):
        for u in np.linspace(0, 1, 41):
            values = np.array(POSES[left]) * (1 - u) + np.array(POSES[right]) * u
            out, q = deform(v, *values)
            p = sample(out, 48)
            assert not has_crossing(p), (name, left, right, u)
            assert abs(area(out) / area(v) - 1) < 1e-11
            assert np.max(np.linalg.norm(out[:, 0] - v[:, 0], axis=1)) / 150 <= .12
            count += 1
    return count


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--check",action="store_true");args=parser.parse_args()
    paths=sorted(ART.glob("body-*.svg"))
    assert len(paths)==8
    hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    bodies={p.stem:read_body(p) for p in paths}
    metrics={}
    min_margin=500.; max_delta=0.; max_area_error=0.; all_points=0
    for name,v in bodies.items():
        for pose,values in POSES.items():
            row, margin, error, delta = _measure_pose(name, v, pose, values)
            metrics[(name,pose)] = row
            min_margin=min(min_margin,margin);max_delta=max(max_delta,delta);max_area_error=max(max_area_error,error)
            all_points+=1
        all_points += _assert_success_blend(name, v)
    assert hashes=={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    overlay=lattice(bodies["body-blob"])
    root=ET.fromstring(overlay)
    assert root.attrib=={"viewBox":"-180 -180 360 360"}
    assert all(n.tag.endswith("path") and not (set(n.attrib)&{"transform","filter","mask"}) for n in root)
    weights_table="\n".join(f"| {r*6+c} | {r} | {c} | {x} | {y} | {weights(y)[0]:.6f} | {weights(y)[1]:.6f} | {weights(y)[2]:.6f} |"
                             for r,y in enumerate(GRID) for c,x in enumerate(GRID))
    anchor_table="\n".join(f"| {name} | {i} | {v[i,0,0]:.4f} | {v[i,0,1]:.4f} | {weights(v[i,0,1])[0]:.9f} | {weights(v[i,0,1])[1]:.9f} | {weights(v[i,0,1])[2]:.9f} |"
                            for name,v in bodies.items() for i in range(8))
    metric_table="\n".join(f"| {name} | {pose} | {m['q']:.9f} | {m['area_change_pct']:+.6f} | {m['delta_pct']:.4f} | {m['margin']:.3f} |"
                            for (name,pose),m in metrics.items() if pose in ("land","drag-right","inflate"))
    hashes_table="\n".join(f"| {name} | `{value}` |" for name,value in hashes.items())
    appendix=f'''<!-- BEGIN GENERATED METRICS -->

### Numbered lattice / exact row-major mapping

`index = row*6 + column`. Local x/y in artboard px; origin is the unchanged body origin. Coordinates are a sampling field, not 36 imported body vertices. Rounded printed weights are for review; the formula and Python double precision values are authoritative.

| index | row | column | x | y | crown | middle | base |
| --- | --- | --- | --- | --- | --- | --- | --- |
{weights_table}

### Weights of the actual eight vertices, every identity

Index is the existing SVG M/start vertex followed by its seven next cubic endpoints. Both tangent handles use exactly the owning anchor's weights, including handles outside ±150. No nearest-grid interpolation is performed.

| identity SVG stem | vertex | x | y | crown | middle | base |
| --- | --- | --- | --- | --- | --- | --- |
{anchor_table}

### Measured extremes

Artboard 500²; **proposed** display scale 1.25 about (250,270), replacing the reviewed implementation's 1.0. Filled boundary is checked at neutral facing, zero rotation and root x ±24 px. Root y uses the named pose. Margin excludes halo, feather, plate, facing foreshortening, base-pivot lean, composed rotations and ghost trails. Those compositions need a native runtime check. Deformation displacement is the maximum anchor displacement divided by reference radius 150, **not** a claim about the largest local differential strain.

| identity | pose | area correction q | area change % | max anchor displacement /150 % | minimum frame margin px |
| --- | --- | --- | --- | --- | --- |
{metric_table}

| gate | result | value |
| --- | --- | --- |
| Existing body inputs unchanged | PASS | 8 of 8 hashes below |
| Same one closed, 8-cubic topology and mirrored handles | PASS | 104 endpoint cases |
| No sampled self-intersections | PASS | {all_points} poses including success interpolation |
| Force-pose area preserved; deliberate inflate area reported | PASS | maximum integration residual {max_area_error:.3e} percentage points |
| Max anchor displacement ≤12% of 150 px | PASS | {max_delta*100:.4f}% over endpoint poses |
| Filled endpoint boundaries inside frame under stated sweep | PASS | minimum margin {min_margin:.3f} px |
| 44/72 exact raster dimensions | PASS | three named extremes at both sizes in deformation-extremes.png |
| Native vector skinning, painted edge, trails and combined facing | NOT RUN | Fable implementation/runtime gate |

| unchanged body file | SHA256 |
| --- | --- |
{hashes_table}

<!-- END GENERATED METRICS -->
'''
    doc=HERE/"DEFORMATION-SPEC.md"
    before=doc.read_text().split("<!-- BEGIN GENERATED METRICS -->")[0].rstrip()+"\n"
    outputs={HERE/"body-lattice-overlay.svg":overlay,HERE/"deformation-extremes.png":proof_png(bodies,metrics),doc:(before+appendix).encode()}
    for path,data in outputs.items():
        if args.check:
            assert path.read_bytes()==data,f"stale generated proof: {path.name}"
        else:
            path.write_bytes(data)
    print(f"PASS: 8 families, {all_points} poses, max anchor displacement {max_delta*100:.4f}%, min filled margin {min_margin:.3f}px; native Rive NOT RUN")


if __name__=="__main__":
    main()
