"""The body: one 8-vertex path per identity, its three bones, the breath and the rising light.

Owns the BodyPlacement / Body node tree (paths, skin, weights, the paint stack) and the key
recipes that shape it - breath(), breath_scale(), lumen_keys(), bone_pose(), squash(), sine().
rig/motion.py reads those recipes into the state loops, flashes and idle beats; gen_scene.py
draws body() into the artboard. Geometry comes from art/*.svg through svgpath.py.

Rive rules that bite here:
  - `Feather` on a Fill renders nothing through the CLI - SoftEdge and Halo are feathered
    STROKES for that reason. Within one shape the LATER paint draws on top.
  - placement and keyed motion must live on different nodes, or a keyed x=0 overwrites the
    placement: BodyPlacement (INFLATE_NODE) holds the position and the breath's inflate, the
    Body node underneath takes the turn's and the states' keys.
"""
from textwrap import indent
from typing import NamedTuple

import svgpath
from rml import COLOR, GRADIENT_OPACITY, ROT, SINE, SX, SY, VDIST, VM_COLOR, VROT, VX, VY, X, bind
from rig.constants import BONE_REACH, DEFAULT_SHAPE, LEAN_BASE, SHAPE_SVG, art, frames
from rig.ids import (
    BODY_NODE, BONE_BASE, BONE_CROWN, BONE_MID, CONV_BODY_ROT, CONV_BODY_X, GLOSS, HALO, HITBOX,
    SHAPES, SOFT, TINT, VM_TURN_X, body_vertex_ids, halo_vertex_ids, soft_vertex_ids,
)


def fill(color):
    """A solid-colour Fill element."""
    return f'<Fill name="Fill"><SolidColor colorValue="{color}" name="Color"/></Fill>'


def rrect(w, h, r, name="Path"):
    """A rounded Rectangle path, all four corners linked to the same radius."""
    return (f'<Rectangle width="{w}" height="{h}" linkCornerRadius="true" cornerRadiusTL="{r}" '
            f'cornerRadiusTR="{r}" cornerRadiusBL="{r}" cornerRadiusBR="{r}" name="{name}"/>')


# ================================================================================================
# Body: identity path (SPEC section 1, body import mechanics) + paint recipe (section 6).
# ================================================================================================
BODY = {s: svgpath.body_vertices(art(SHAPE_SVG[s])) for s in SHAPES}


def bone_weight(y):
    """Packed CubicWeight attributes for a vertex at rest y: three tendons (1, 2, 3), 0..255 each."""
    wc = min(max(-y / BONE_REACH, 0.0), 1.0)
    wb = min(max(y / BONE_REACH, 0.0), 1.0)
    wm = 1.0 - wc - wb
    vc, vb = round(255 * wc), round(255 * wb)
    vm = 255 - vc - vb
    indices = 1 | (2 << 8) | (3 << 16)
    values = vc | (vm << 8) | (vb << 16)
    return (f'<CubicWeight values="{values}" indices="{indices}" inValues="{values}" inIndices="{indices}" '
            f'outValues="{values}" outIndices="{indices}"/>')


def body_skin():
    """The Skin binding a body path to the crown / middle / base bones."""
    return (f'<Skin tx="0" ty="0" name="Skin">\n'
            f'    <Tendon boneId="{BONE_CROWN}" tx="0" ty="{-BONE_REACH}" name="Crown"/>\n'
            f'    <Tendon boneId="{BONE_MID}" tx="0" ty="0" name="Middle"/>\n'
            f'    <Tendon boneId="{BONE_BASE}" tx="0" ty="{BONE_REACH}" name="Base"/>\n'
            f'</Skin>')


def body_path(vertex_ids, name):
    """The default identity's closed 8-vertex path, each vertex weighted to the three bones."""
    verts = "\n".join(
        f'<CubicMirroredVertex x="{x}" y="{y}" rotation="{rot}" distance="{d}" name="V{i}" id="{vid}">\n    {bone_weight(y)}\n</CubicMirroredVertex>'
        for i, ((x, y, rot, d), vid) in enumerate(zip(BODY[DEFAULT_SHAPE], vertex_ids)))
    verts += "\n" + body_skin()
    return f'<PointsPath isClosed="true" name="{name}">\n{indent(verts, "    ")}\n</PointsPath>'


def shape_keys(shape):
    """Vertex keys that morph body, SoftEdge and Halo onto one identity (the Shape layer)."""
    objs = {}
    for (x, y, rot, d), vb, vs, vh in zip(BODY[shape], body_vertex_ids, soft_vertex_ids, halo_vertex_ids):
        for vid in (vb, vs, vh):
            objs[vid] = {VX: x, VY: y, VROT: rot, VDIST: d}
    return objs


def body():
    """The whole body assembly: placement, bones, the painted path, soft edge and halo."""
    bound = f'<SolidColor colorValue="FF79B7DF" name="Color">\n            {bind(VM_COLOR, COLOR)}\n        </SolidColor>'
    return f'''<Node x="0" y="{-LEAN_BASE}" name="BodyPlacement" id="{INFLATE_NODE}">
    {bind(VM_TURN_X, ROT, CONV_BODY_ROT)}
    {bind(VM_TURN_X, X, CONV_BODY_X)}
<Node x="0" y="0" name="Body" id="{BODY_NODE}">
    <RootBone x="0" y="{-BONE_REACH}" length="1" rotation="0" name="Crown" id="{BONE_CROWN}"/>
    <RootBone x="0" y="0" length="1" rotation="0" name="Middle" id="{BONE_MID}"/>
    <RootBone x="0" y="{BONE_REACH}" length="1" rotation="0" name="Base" id="{BONE_BASE}"/>
    <!-- Paints on one shape; the LATER paint draws on top. -->
    <Shape name="BodyShape" id="{HITBOX}">
{indent(body_path(body_vertex_ids, "Path"), "        ")}
        <Fill name="Fill">
            {bound}
        </Fill>
        <Fill name="Shade">
            <RadialGradient startX="-40" startY="-65" endX="130" endY="120" name="Gradient">
                <GradientStop colorValue="00000000" position="0"/>
                <GradientStop colorValue="08000000" position="0.55"/>
                <GradientStop colorValue="40000000" position="1"/>
            </RadialGradient>
        </Fill>
        <Fill name="Gloss">
            <RadialGradient startX="-65" startY="-85" endX="65" endY="45" name="Gradient" id="{GLOSS}">
                <GradientStop colorValue="52FFFFFF" position="0"/>
                <GradientStop colorValue="24FFFFFF" position="0.45"/>
                <GradientStop colorValue="00FFFFFF" position="1"/>
            </RadialGradient>
        </Fill>
        <Fill name="Lumen">
            <!-- The breath's light: a very low white radial that rises through the body with the
                 inhale and fades out on the exhale. Opacity and centre are keyed per state. -->
            <RadialGradient startX="0" startY="{LUMEN_Y0}" endX="{LUMEN_R}" endY="{LUMEN_Y0}" opacity="0" name="Gradient" id="{LUMEN}">
                <GradientStop colorValue="FFFFFFFF" position="0"/>
                <GradientStop colorValue="66FFFFFF" position="0.45"/>
                <GradientStop colorValue="00FFFFFF" position="1"/>
            </RadialGradient>
        </Fill>
        <Fill name="Tint">
            <SolidColor colorValue="00000000" name="TintColor" id="{TINT}"/>
        </Fill>
    </Shape>
    <Shape opacity="0.14" name="SoftEdge" id="{SOFT}">
{indent(body_path(soft_vertex_ids, "Path"), "        ")}
        <Stroke thickness="8" name="Stroke">
            {bound}
            <Feather strength="4" name="Feather"/>
        </Stroke>
    </Shape>
    <Shape scaleX="1.02" scaleY="1.02" opacity="{HALO_OPACITY}" name="Halo" id="{HALO}">
{indent(body_path(halo_vertex_ids, "Path"), "        ")}
        <Stroke thickness="12" name="Stroke">
            {bound}
            <Feather strength="10" name="Feather"/>
        </Stroke>
    </Shape>
</Node>
</Node>'''


def sine(amplitude, period_ms, base=0.0):
    """0,+a,0,-a,0 over one period with sine-ish easing per quarter; loops seamlessly."""
    q = frames(period_ms) // 4
    return [(0, base, SINE), (q, base + amplitude, SINE), (2 * q, base, SINE), (3 * q, base - amplitude, SINE), (4 * q, base)]


BREATH_MS, BREATH_PX, BREATH_SCALE = 6500, 11, 1.06   # ~9 breaths a minute (SPEC 4600 felt hurried)


INFLATE_NODE = "0:233"  # BodyPlacement: the breath's inflate lives here, away from the turn's body keys


HALO_OPACITY, HALO_SLEEP, HALO_FAILED = 0.10, 0.06, 0


def breath(period_ms=BREATH_MS):
    """SPEC section 9.3: root y 0 -> -11 -> 0, inhale 55 % / exhale 45 %."""
    return [(0, 0, SINE), (frames(period_ms * 0.55), -BREATH_PX, SINE), (frames(period_ms), 0)]


def breath2(period_ms=BREATH_MS):
    """Two bobs per inflate: the rise/fall at its own period, twice, so the loop can carry a
    slower inflate (the user: 'the bob speed is right, the breathing is twice too fast')."""
    n = frames(period_ms)
    first = breath(period_ms)
    second = [(f + n, v) + tuple(rest) for (f, v, *rest) in first[1:]]
    return first + second


def breath_scale(period_ms=BREATH_MS, lo=1.0, hi=BREATH_SCALE):
    """The volume of the breath: the body inflates on the inhale, same phase as the rise."""
    return [(0, lo, SINE), (frames(period_ms * 0.55), hi, SINE), (frames(period_ms), lo)]


class Breathing(NamedTuple):
    """A state's inflate: the period it breathes over and the scale it travels between."""
    period_ms: int
    lo: float
    hi: float


# Inflate (and the light) run at twice the bob's period: one slow breath per two bobs.
BREATHING = {"idle": Breathing(2 * BREATH_MS, 1.0, BREATH_SCALE),
             "listening": Breathing(2 * BREATH_MS, 1.0, BREATH_SCALE),
             "speaking": Breathing(2 * BREATH_MS, 1.0, BREATH_SCALE),
             "sleeping": Breathing(18000, 0.985, 1.045)}


# Lumen: the breath's light (a white radial fill on the body, gradient opacity 0 at rest).
LUMEN, LUMEN_R, LUMEN_Y0, LUMEN_Y1, LUMEN_PEAK = "0:234", 150, 70, -70, 0.16


G_START_X, G_START_Y, G_END_X, G_END_Y = 42, 33, 34, 35   # LinearGradient property keys


def lumen_keys(period_ms=BREATH_MS, peak=LUMEN_PEAK):
    """Rises from low in the body to high with the inhale, brightening on the way; gone at rest."""
    top = frames(period_ms * 0.55)
    end = frames(period_ms)
    return {GRADIENT_OPACITY: [(0, 0, SINE), (top, peak, SINE), (end, 0)],
            G_START_Y: [(0, LUMEN_Y0, SINE), (top, LUMEN_Y1, SINE), (end, LUMEN_Y0)],
            G_END_Y: [(0, LUMEN_Y0, SINE), (top, LUMEN_Y1, SINE), (end, LUMEN_Y0)],
            G_START_X: 0, G_END_X: LUMEN_R}


LUMEN_REST = {GRADIENT_OPACITY: 0, G_START_Y: LUMEN_Y0, G_END_Y: LUMEN_Y0, G_START_X: 0, G_END_X: LUMEN_R}


def bone_pose(keys):
    """keys: (frame, S, L, H[, bezier]) -> bone transforms for the three-band deformation."""
    out = {BONE_CROWN: {SX: [], SY: [], X: []}, BONE_MID: {SX: [], SY: []}, BONE_BASE: {SX: [], SY: [], X: []}}
    for k in keys:
        f, S, L, H = k[0], k[1], k[2], k[3]
        bez = tuple(k[4:])
        out[BONE_CROWN][SX].append((f, round(S - L, 4)) + bez)
        out[BONE_MID][SX].append((f, round(S, 4)) + bez)
        out[BONE_BASE][SX].append((f, round(S + L, 4)) + bez)
        for b in (BONE_CROWN, BONE_MID, BONE_BASE):
            out[b][SY].append((f, round(1 / S, 4)) + bez)
        out[BONE_CROWN][X].append((f, round(-BONE_REACH * H, 3)) + bez)
        out[BONE_BASE][X].append((f, round(BONE_REACH * H, 3)) + bez)
    return out


def squash(node, keys):
    """Volume-preserving squash and stretch: keys of (frame, scaleX[, bezier]); scaleY = 1/scaleX."""
    sx = [(k[0], k[1]) + tuple(k[2:]) for k in keys]
    sy = [(k[0], round(1 / k[1], 4)) + tuple(k[2:]) for k in keys]
    return {node: {SX: sx, SY: sy}}
