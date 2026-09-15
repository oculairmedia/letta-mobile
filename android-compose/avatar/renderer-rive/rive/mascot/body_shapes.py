"""Draw the polygon bodies (roundedSquare, triangle, hexagon) from their corners and a corner radius.

    python body_shapes.py                 # rewrites art/body-{squircle,triangle,hexagon}.svg
    python body_shapes.py --radius 45     # softer corners

A body is the rig's fixed contract (SPEC section 1): one closed path of 8 mirrored cubic vertices,
V0 at the top then clockwise. Mirrored tangents cannot hold a sharp corner, so a polygon body is a
rounded polygon: its true outline - straight sides, circular arcs of `radius` at the corners - is
traced, a vertex is placed where each slot's ray from the centre meets it (slots sit on the corners
where the V0..V7 order allows), the tangent there is the outline's, and each vertex's handle length
is fitted so the eight cubics stay within a pixel or two of the outline.

The other five bodies (circle, blob, pill, cloud, drop) are drawn by hand and are not touched.
Standard library only. Regenerate scene.rml and rebuild the .riv after running this.
"""
import argparse
import math
import os
from typing import NamedTuple

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_RADIUS = 25.0
DEG = math.pi / 180


class Body(NamedTuple):
    """A polygon body: its corners (screen coordinates, clockwise) and the angle of each vertex slot."""
    svg: str
    corners: list
    slot_angles: list


BODIES = [
    Body("body-squircle.svg", [(-150, -150), (150, -150), (150, 150), (-150, 150)],
         [-90, -45, 0, 45, 90, 135, 180, 225]),
    # Equilateral and centred on the face: its inscribed circle (radius 87.5) holds the face plate
    # whichever way the identity turns the body, which a base-heavy triangle could not.
    Body("body-triangle.svg", [(0, -175), (151.5544, 87.5), (-151.5544, 87.5)],
         [-90, -50, -10, 30, 90, 150, 190, 230]),
    Body("body-hexagon.svg", [(-80, -140), (80, -140), (158, 0), (80, 140), (-80, 140), (-158, 0)],
         [-90, -60, 0, 60, 90, 120, 180, 240]),
]


# --- the true outline -----------------------------------------------------------------------------
def unit(v):
    n = math.hypot(*v)
    return v[0] / n, v[1] / n


ARC_SAMPLES = 24


def corner_arc(neighbourhood, radius):
    """The arc of `radius` rounding the middle point of (prev, corner, next), from the side toward
    prev to the side toward next."""
    prev, corner, nxt = neighbourhood
    samples = ARC_SAMPLES
    a = unit((prev[0] - corner[0], prev[1] - corner[1]))
    b = unit((nxt[0] - corner[0], nxt[1] - corner[1]))
    theta = math.acos(max(-1.0, min(1.0, a[0] * b[0] + a[1] * b[1])))
    inward = unit((a[0] + b[0], a[1] + b[1]))
    reach = radius / math.sin(theta / 2)
    centre = (corner[0] + inward[0] * reach, corner[1] + inward[1] * reach)
    tangent = radius / math.tan(theta / 2)
    start = math.atan2(corner[1] + a[1] * tangent - centre[1], corner[0] + a[0] * tangent - centre[0])
    end = math.atan2(corner[1] + b[1] * tangent - centre[1], corner[0] + b[0] * tangent - centre[0])
    sweep = (end - start + math.pi) % (2 * math.pi) - math.pi
    return [(centre[0] + radius * math.cos(start + sweep * k / samples),
             centre[1] + radius * math.sin(start + sweep * k / samples)) for k in range(samples + 1)]


def rounded_outline(corners, radius):
    """The closed polyline of the rounded polygon: consecutive corner arcs, joined by the straight sides."""
    n = len(corners)
    return [p for i in range(n) for p in corner_arc((corners[i - 1], corners[i], corners[(i + 1) % n]), radius)]


def edges(polyline):
    return [(polyline[i], polyline[(i + 1) % len(polyline)]) for i in range(len(polyline))]


def cross(a, b):
    return a[0] * b[1] - a[1] * b[0]


def ray_hit(outline, angle):
    """(point, unit tangent) where the ray from the centre at `angle` leaves the outline."""
    direction = (math.cos(angle), math.sin(angle))
    hits = []
    for p, q in edges(outline):
        e = (q[0] - p[0], q[1] - p[1])
        den = cross(direction, e)
        if abs(den) < 1e-12:
            continue
        along, on_edge = cross(p, e) / den, cross(p, direction) / den
        if along > 0 and -1e-9 <= on_edge <= 1 + 1e-9:
            hits.append((along, unit(e)))
    along, tangent = min(hits)
    return (direction[0] * along, direction[1] * along), tangent


# --- fitting the cubics ---------------------------------------------------------------------------
def cubic_point(cubic, t):
    """The point at `t` on a cubic given as (start, control, control, end)."""
    p0, c0, c1, p1 = cubic
    mt = 1 - t
    return tuple(mt ** 3 * p0[i] + 3 * mt * mt * t * c0[i] + 3 * mt * t * t * c1[i] + t ** 3 * p1[i] for i in range(2))


def distance_to_segment(q, a, b):
    ab = (b[0] - a[0], b[1] - a[1])
    t = max(0.0, min(1.0, ((q[0] - a[0]) * ab[0] + (q[1] - a[1]) * ab[1]) / (ab[0] ** 2 + ab[1] ** 2 or 1e-12)))
    return math.hypot(q[0] - a[0] - t * ab[0], q[1] - a[1] - t * ab[1])


class Fit:
    """Eight outline vertices and a handle length each; `error()` is the worst gap to the outline."""

    def __init__(self, body, radius):
        self.outline = rounded_outline(body.corners, radius)
        self.segments = edges(self.outline)
        self.vertices = [ray_hit(self.outline, a * DEG) for a in body.slot_angles]
        n = len(self.vertices)
        self.gaps = [min(math.dist(self.vertices[i][0], self.vertices[(i + 1) % n][0]),
                         math.dist(self.vertices[i][0], self.vertices[i - 1][0])) for i in range(n)]
        self.on_corner = [min(math.dist(v[0], c) for c in body.corners) < radius * 1.5 for v in self.vertices]
        self.lengths = [0.0] * n

    def handles(self, i):
        """(outgoing, incoming) control points of vertex i: mirrored along its tangent."""
        (p, t), length = self.vertices[i], self.lengths[i]
        return (p[0] + t[0] * length, p[1] + t[1] * length), (p[0] - t[0] * length, p[1] - t[1] * length)

    def cubics(self):
        n = len(self.vertices)
        return [(self.vertices[i][0], self.handles(i)[0], self.handles((i + 1) % n)[1], self.vertices[(i + 1) % n][0])
                for i in range(n)]

    def error(self):
        return max(min(distance_to_segment(cubic_point(c, k / 12), a, b) for a, b in self.segments)
                   for c in self.cubics() for k in range(1, 12))

    def search_ratios(self):
        """Global pass: one handle ratio for side vertices, one for corner vertices, of the shorter gap."""
        best = None
        for side in (x / 20 for x in range(4, 16)):
            for corner in (x / 40 for x in range(1, 20)):
                self.lengths = [g * (corner if c else side) for g, c in zip(self.gaps, self.on_corner)]
                best = min(best or (math.inf, None), (self.error(), list(self.lengths)))
        self.lengths = best[1]

    def refine(self):
        """Per-vertex pass: nudge each handle while that lowers the error, halving the nudge."""
        step = 4.0
        while step >= 0.25:
            if not any(self.nudge(i, step) for i in range(len(self.lengths))):
                step /= 2

    def nudge(self, i, step):
        base = self.error()
        for delta in (step, -step):
            old = self.lengths[i]
            self.lengths[i] = max(0.5, old + delta)
            if self.error() < base - 1e-6:
                return True
            self.lengths[i] = old
        return False

    def path(self):
        start = self.vertices[0][0]
        parts = [f"C {c0[0]:.4f} {c0[1]:.4f} {c1[0]:.4f} {c1[1]:.4f} {p1[0]:.4f} {p1[1]:.4f}" for _p0, c0, c1, p1 in self.cubics()]
        return f"M {start[0]:.4f} {start[1]:.4f} " + " ".join(parts) + " Z"


def svg_document(path):
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="-180 -180 360 360">\n'
            f'  <path d="{path}" fill="#000000"/>\n</svg>\n')


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--radius", type=float, default=DEFAULT_RADIUS, help="corner radius in px (default 25)")
    args = p.parse_args(argv)
    for body in BODIES:
        fit = Fit(body, args.radius)
        fit.search_ratios()
        fit.refine()
        # Always this rig's own art/ - the file names are fixed above, never taken from the command line.
        with open(os.path.join(HERE, "art", body.svg), "w", encoding="utf-8", newline="\n") as f:
            f.write(svg_document(fit.path()))
        print(f"{body.svg}: corner radius {args.radius:g}px, max deviation {fit.error():.2f}px")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
