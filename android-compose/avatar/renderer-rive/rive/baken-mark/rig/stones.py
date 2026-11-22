"""The drawing: two stones on a flat ground, and the two lights between them.

Geometry comes from `art/anchor.svg` and `art/pebble.svg` (the brand kit's 03 Basalt Planes
paths, unchanged) through the mascot's `svgpath.parse_path`, scaled by ART_SCALE and re-based on
each stone's ground contact, so a rotation keyed on any node of the chain pivots on the point the
stone stands on. Nothing here floats: both stones' lowest points sit on the ground line at rest,
and every light is painted on a stone or in the seam between them.

    Pebble Base (335, 420)                       placement only; never keyed
      PebbleLean                                 Hover layer: the lean perk
        PebbleEvent                              Event layer: boot, success (and their opacity)
          PebblePose                             Pose layer: the state's weight shift
            PebbleTone                           Light layer: the stone's brightness
              PebbleBloom, PebbleWarm, PebbleDim, PebbleStone     (first draws on top)
    Anchor Base (162, 420) > AnchorLean > AnchorEvent > AnchorPose > AnchorTone
              SheenClip > Sheen (clipped to the stone), AnchorBounce, AnchorStone
    Seam (240, 335), squeezed to the lab's ellipse > SeamPose > SeamGlow    (behind both)

One node per layer is the rule the mascot learned the hard way: placement and keyed motion never
share a node, and two layers never key the same property, so nothing a layer lets go of snaps.
"""
import os
from textwrap import indent

from rml import COLOR, bind  # noqa: F401 - COLOR documents the key the Theme layer keys
from svgpath import _vertex_xml, parse_path, read_svg

from rig.constants import (ART_SCALE, BOUNCE_CENTRE, BOUNCE_RADIUS, BOUNCE_STOPS, DEFAULT_THEME,
                           PALETTE, PIVOT, SEAM_CENTRE, SEAM_RADII, SEAM_STOPS, SHEEN_ALPHA,
                           SHEEN_ANGLE, SHEEN_BAND, SHEEN_Y, art, argb, rad)
from rig.ids import (ANCHOR_BOUNCE, ANCHOR_BOUNCE_GRAD, ANCHOR_BOUNCE_STOPS, ANCHOR_FILL,
                     ANCHOR_SHEEN, ANCHOR_SHEEN_GROUP, ANCHOR_STONE, NODES, PEBBLE_BLOOM,
                     PEBBLE_BLOOM_FILL, PEBBLE_DIM, PEBBLE_DIM_FILL, PEBBLE_FILL, PEBBLE_STONE,
                     PEBBLE_WARM, PEBBLE_WARM_FILL, SEAM_GLOW, SEAM_GRAD, SEAM_NODE, SEAM_POSE,
                     SEAM_STOPS as SEAM_STOP_IDS, SHEEN_GRAD)

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(HERE, "art")


def stone_vertices(stone):
    """The stone's path as (x, y, c_in, c_out) in artboard px, relative to its ground contact."""
    px, py = PIVOT[stone]
    subs = parse_path(read_svg(os.path.join(ART, f"{stone}.svg"))["d"])
    if len(subs) != 1 or not subs[0]["closed"]:
        raise ValueError(f"art/{stone}.svg: a stone is one closed path")

    def place(pt):
        return None if pt is None else (round((pt[0] - px) * ART_SCALE, 4), round((pt[1] - py) * ART_SCALE, 4))

    return [(*place((x, y)), place(cin), place(cout)) for x, y, cin, cout in subs[0]["verts"]]


def stone_path(stone, name="Path"):
    verts = "\n".join("    " + _vertex_xml(v) for v in stone_vertices(stone))
    return f'<PointsPath isClosed="true" name="{name}">\n{verts}\n</PointsPath>'


def _solid(name, sid, colour):
    return f'<Fill name="{name}">\n    <SolidColor colorValue="{colour}" name="Color" id="{sid}"/>\n</Fill>'


def _shape(stone, name, sid, paint, opacity=None):
    op = f' opacity="{opacity}"' if opacity is not None else ""
    return (f'<Shape{op} name="{name}" id="{sid}">\n{indent(stone_path(stone), "    ")}\n'
            f'{indent(paint, "    ")}\n</Shape>')


def _stops(stops, ids, rgb_of):
    return "\n".join(f'    <GradientStop colorValue="{argb(alpha, rgb_of(which))}" position="{pos}" id="{sid}"/>'
                     for (pos, alpha, which), sid in zip(stops, ids))


def _theme_light(which, theme=DEFAULT_THEME):
    return PALETTE[theme]["light"][which]


def anchor():
    """The anchor stone: base fill, the reflected bounce light, the loading sheen clipped to it."""
    p = PALETTE[DEFAULT_THEME]
    bx, by = art(BOUNCE_CENTRE[0]), art(BOUNCE_CENTRE[1])
    bounce = (f'<Fill name="Bounce">\n'
              f'    <RadialGradient startX="{bx}" startY="{by}" endX="{bx + art(BOUNCE_RADIUS)}" endY="{by}" '
              f'opacity="0.14" name="Gradient" id="{ANCHOR_BOUNCE_GRAD}">\n'
              f'{indent(_stops(BOUNCE_STOPS, ANCHOR_BOUNCE_STOPS, _theme_light), "    ")}\n'
              f'    </RadialGradient>\n</Fill>')
    w, h = art(SHEEN_BAND[0]), art(SHEEN_BAND[1])
    sheen_paint = (f'<Fill name="Fill">\n'
                   f'    <LinearGradient startX="{-w / 2}" startY="0" endX="{w / 2}" endY="0" name="Gradient" id="{SHEEN_GRAD}">\n'
                   f'        <GradientStop colorValue="00FFFFFF" position="0"/>\n'
                   f'        <GradientStop colorValue="{argb(SHEEN_ALPHA, "FFFFFF")}" position="0.5"/>\n'
                   f'        <GradientStop colorValue="00FFFFFF" position="1"/>\n'
                   f'    </LinearGradient>\n</Fill>')
    sheen = (f'<Node x="0" y="0" name="SheenClip" id="{ANCHOR_SHEEN_GROUP}">\n'
             f'    <ClippingShape sourceId="{ANCHOR_STONE}" name="StoneClip"/>\n'
             f'    <Shape x="{art(-45)}" y="{art(SHEEN_Y)}" rotation="{rad(SHEEN_ANGLE)}" opacity="0" name="Sheen" id="{ANCHOR_SHEEN}">\n'
             f'        <Rectangle width="{w}" height="{h}" name="Band"/>\n'
             f'{indent(sheen_paint, "        ")}\n'
             f'    </Shape>\n</Node>')
    return "\n".join([
        sheen,
        _shape("anchor", "AnchorBounce", ANCHOR_BOUNCE, bounce),
        _shape("anchor", "AnchorStone", ANCHOR_STONE, _solid("Fill", ANCHOR_FILL, p["stone"])),
    ])


def pebble():
    """The pebble (the beacon): base fill, then dim, warm and bloom overlays of the same outline."""
    p = PALETTE[DEFAULT_THEME]
    return "\n".join([
        _shape("pebble", "PebbleBloom", PEBBLE_BLOOM, _solid("Fill", PEBBLE_BLOOM_FILL, p["warm"]), 0),
        _shape("pebble", "PebbleWarm", PEBBLE_WARM, _solid("Fill", PEBBLE_WARM_FILL, p["warm"]), 0),
        _shape("pebble", "PebbleDim", PEBBLE_DIM, _solid("Fill", PEBBLE_DIM_FILL, p["dim"]), 0),
        _shape("pebble", "PebbleStone", PEBBLE_STONE, _solid("Fill", PEBBLE_FILL, p["accent"])),
    ])


def _chain(stone, title, content):
    """Base > Lean > Event > Pose > Tone > content, Base placed at the stone's ground contact."""
    n = NODES[stone]
    x, y = art(PIVOT[stone][0]), art(PIVOT[stone][1])
    out = indent(content, "    ")
    for level in ("Tone", "Pose", "Event", "Lean"):
        out = f'<Node x="0" y="0" name="{title}{level}" id="{n[level]}">\n{out}\n</Node>'
        out = indent(out, "    ")
    return f'<Node x="{x}" y="{y}" name="{title}" id="{n["Base"]}">\n{out}\n</Node>'


def seam():
    """The light inside the channel seam: a soft radial behind both stones, squeezed to the lab's
    28 x 38 ellipse by its placement node (so the gradient is elliptical too)."""
    r = art(SEAM_RADII[1])
    squeeze = round(SEAM_RADII[0] / SEAM_RADII[1], 6)
    return (f'<Node x="{art(SEAM_CENTRE[0])}" y="{art(SEAM_CENTRE[1])}" scaleX="{squeeze}" name="Seam" id="{SEAM_NODE}">\n'
            f'    <Node x="0" y="0" opacity="0" name="SeamPose" id="{SEAM_POSE}">\n'
            f'        <Shape name="SeamGlow" id="{SEAM_GLOW}">\n'
            f'            <Ellipse width="{2 * r}" height="{2 * r}" name="Glow"/>\n'
            f'            <Fill name="Fill">\n'
            f'                <RadialGradient startX="0" startY="0" endX="{r}" endY="0" name="Gradient" id="{SEAM_GRAD}">\n'
            f'{indent(_stops(SEAM_STOPS, SEAM_STOP_IDS, _theme_light), "                ")}\n'
            f'                </RadialGradient>\n'
            f'            </Fill>\n'
            f'        </Shape>\n'
            f'    </Node>\n</Node>')


def drawing():
    """Everything drawn, in draw order (first on top): pebble, anchor, the seam light behind."""
    return "\n".join([_chain("pebble", "Pebble", pebble()), _chain("anchor", "Anchor", anchor()), seam()])
