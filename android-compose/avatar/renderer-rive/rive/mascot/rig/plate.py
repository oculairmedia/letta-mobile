"""The plate: the nested face artboard - glyphs per state, mouth morph, pupil overlay, saccades.

Owns everything inside the Plate component - its node tree, its animations, and its own six-layer
state machine (Expression, Blink, AutoBlink, Saccade, and te3n1.5's Squint and Rem). rig/face.py mounts it as a NestedArtboard
and drives it; gen_scene.py writes plate_component() into the document. The glyph per state comes
from STATE_GLYPH here, the ids from rig/ids.py, the timings from rig/constants.py.

Rive rules that bite here:
  - a view-model-driven state machine inside a nested artboard NEVER fires. The plate is driven by
    inputs instead (`expr` NestedNumber, `blink` NestedTrigger) that the root's animations key.
  - `expr` is keyed by two root states at once, so it INTERPOLATES through a root cross-blend: a
    condition on it must be a band (>= v-0.5 && < v+0.5), never an exact match. "Anything but
    sleeping" cannot be one band, hence the pairs of open-ended conditions in the park checks.
"""
import itertools
import math
from textwrap import indent

import svgpath
from rml import (ACTIVE_CHILD, BACK_OUT, EMPH_DECEL, Id, LINEAR, OPACITY, SINE, SOFT_OUT, STD_DECEL,
                 SX, SY, VIN_DIST, VIN_ROT, VOUT_DIST, VOUT_ROT, VX, VY, X, Y, animation, when_input)
from rig.body import fill, rrect
from rig.constants import (BLINK_FRAMES, BLINK_SHUT, EXPR, INK, PLATE_WHITE, PUPIL, PUPIL_PARALLAX,
                           SACCADE_FIX, SACCADE_WAITS, TUNABLES, WAVE_STROKE, art, beat,
                           expression_layer, frames)
from rig.ids import (
    CATCH, CORE, FROWN, GLYPH, GLYPHS_NODE, GLYPH_ORDER, GLYPH_SCALE_NODE, IRIS, MOUTH_MORPH,
    MOUTH_NODE, MOUTH_O, NEW_GLYPHS, PLATE_AB, PLATE_AUTO_A, PLATE_AUTO_B, PLATE_AUTO_BLINK,
    PLATE_AUTO_SLEEP, PLATE_AUTO_X, PLATE_AUTO_Y, PLATE_BLINK_ANIM, PLATE_BLINK_NODE,
    PLATE_BLINK_REST_NODE, PLATE_CARD, PLATE_IN_BLINK, PLATE_IN_EXPR, PLATE_LOOKX, PLATE_LOOKY,
    PLATE_OPEN, PLATE_REM_ANIM, PLATE_REM_NODE, PLATE_REM_OFF_NODE, PLATE_REM_WAIT_NODES,
    PLATE_REM_WAITS, PLATE_ROOT, PLATE_SCALE_NODE, PLATE_SHADOW, PLATE_SM, PLATE_SQUINT_ANIM,
    PLATE_SQUINT_NODE, PLATE_SQUINT_OFF_ANIM, PLATE_SQUINT_OFF_NODE, PLATE_SQUINT_WAIT_NODES,
    PLATE_SQUINT_WAITS, PLATE_WAIT_A, PLATE_WAIT_B, PUPIL_OVERLAY, PUPIL_ROOT, RESTING,
    SACCADE_FIX_NODES, SACCADE_NODE, SACCADE_SLEEP_NODE, SACCADE_WAIT_NODES, STATES, WAVE,
    mouth_vertex_ids, plate_expr_anim, plate_expr_node, wave_vertex_ids,
)
from rig.layers import Exit, Layer, OnInput, Raw, State, set_animation_index
from rig.seams import animation_index


# ================================================================================================
# Plate component (SPEC sections 1, 4, 7 standard profile).
# ================================================================================================
MOUTH_SAMPLES = [svgpath.mouth_vertices(art(f"glyph-mouth-{n}.svg")) for n in ("closed", "half", "open")]


STATE_GLYPH = {"idle": "idle", "listening": "listening", "dragged": "dragged",
               # te3n1.5 (wbin4.8): thinking RESTS eyes-open-narrowed and squints only on the Squint
               # layer's beat, every 3-7 s; the old held squint is now that beat's glyph.
               "thinking": "thinkingOpen",
               "waitingInput": "waitingInput", "speaking": "speaking", "success": "success", "error": "error",
               "sleeping": "sleeping", "loading": "loading", "failed": "failed", "degraded": "degraded",
               "working": "working", "dreaming": "dreaming", "content": "content", "startled": "startled",
               "dizzy": "dizzy", "yawning": "sleeping", "trick": "trick"}


# No mouth while speaking (the user: a moving mouth looks stupid); the glyph and the head carry speech.
STATE_MOUTH = {"dragged": MOUTH_MORPH, "waitingInput": MOUTH_O, "error": FROWN, "yawning": MOUTH_O}


STATE_PLATE_SCALE = {"listening": 1.04, "waitingInput": 1.06, "startled": 1.06}


# The thinking squint: random waits of unequal length (3-7 s), then a held squint.
SQUINT_WAITS_MS, SQUINT_MS = (3200, 5100, 6900), 650


# Dreaming: a REM flicker of the closed eye every 6-10 s.
REM_WAITS_MS, REM_MS = (6000, 8100, 9900), 560


def _asleep(node):
    """Into the park: expr in either RESTING band (sleeping, dreaming)."""
    return [OnInput(node, PLATE_IN_EXPR, EXPR[s], 0) for s in RESTING]


def _outside(node, values):
    """Out of the park: expr outside every band in `values`. Bands cannot express "anything but",
    so it is one transition per open region between them (either firing wakes the layer)."""
    vs = sorted(EXPR[s] for s in values)
    edges = [(None, vs[0] - 0.5)] + [(a + 0.5, b - 0.5) for a, b in zip(vs, vs[1:])] + [(vs[-1] + 0.5, None)]
    out = []
    for lo, hi in edges:
        conds = ([when_input(PLATE_IN_EXPR, "greaterThanOrEqual", lo)] if lo is not None else []) + \
                ([when_input(PLATE_IN_EXPR, "lessThan", hi)] if hi is not None else [])
        out.append(Raw(node, f'<StateTransition stateToId="{node}" duration="0">\n'
                             + "\n".join("    " + c for c in conds) + '\n</StateTransition>'))
    return out


def wave_samples(amplitude):
    """The four squiggle phase targets as detached-vertex tuples, y and handle-y scaled by the
    amplitude (x and the stroke untouched, per PUPIL-SPEC 3). Handle rotations are unwrapped
    across phases so a linear key never spins a handle the long way round."""
    phases = [squiggle_phase(k, amplitude) for k in range(4)]
    for vi, prop, k in itertools.product(range(5), (2, 4), range(1, 4)):
        phases[k][vi][prop] = unwrap_angle(phases[k - 1][vi][prop], phases[k][vi][prop])
    return phases


def squiggle_phase(k, amplitude):
    """Squiggle phase k as [x, y, inRotation, inDistance, outRotation, outDistance] per vertex."""
    verts = svgpath.parse_path(svgpath.read_svg(art(f"pupil/squiggle-{k}.svg"))["d"])[0]["verts"]
    sy = lambda c: None if c is None else (c[0], c[1] * amplitude)
    row = []
    for (x, y, cin, cout) in verts:
        ir, idist = svgpath._handle((x, y * amplitude), sy(cin))
        orot, odist = svgpath._handle((x, y * amplitude), sy(cout))
        row.append([x, y * amplitude, ir, idist, orot, odist])
    return row


def unwrap_angle(prev, cur):
    """`cur` moved by whole turns to within half a turn of `prev`."""
    while cur - prev > math.pi:
        cur -= 2 * math.pi
    while cur - prev < -math.pi:
        cur += 2 * math.pi
    return cur


def wave_keys(period_ms, amplitude):
    """Loop of one wave cycle: phases 0, 1, 2, 3 at the quarters, back to 0 at the end."""
    n = frames(period_ms)
    q = [0, round(n / 4), round(n / 2), round(3 * n / 4), n]
    ph = wave_samples(amplitude)
    order = [0, 1, 2, 3, 0]
    keys = {}
    for vi, vid in enumerate(wave_vertex_ids):
        keys[vid] = {}
        for key, idx in ((VX, 0), (VY, 1), (VIN_ROT, 2), (VIN_DIST, 3), (VOUT_ROT, 4), (VOUT_DIST, 5)):
            keys[vid][key] = [(q[j], round(ph[order[j]][vi][idx], 4), LINEAR) for j in range(5)]
            # the last key's bezier is ignored; keep the tuple shape uniform
    return n, keys


def pupil_overlay():
    """iris field (neutral, stationary) with the clipped pupil root: wave behind core, catchlight above."""
    wave_verts = "\n".join(
        f'<CubicDetachedVertex x="{x}" y="{y}" inRotation="{ir}" inDistance="{idist}" outRotation="{orot}" outDistance="{odist}" name="W{i}" id="{vid}"/>'
        for i, ((x, y, ir, idist, orot, odist), vid) in enumerate(zip(wave_samples(1.0)[0], wave_vertex_ids)))
    wave = f'''<Shape x="0" y="0" name="Wave" id="{WAVE}">
    <PointsPath isClosed="false" name="Path">
{indent(wave_verts, "        ")}
    </PointsPath>
    <Stroke thickness="{WAVE_STROKE}" cap="round" join="round" name="Stroke"><SolidColor colorValue="{INK}" name="Color"/></Stroke>
</Shape>'''
    core = svgpath.path_rml(art("pupil/pupil-core.svg"), "Core", CORE, INK)
    catch = svgpath.path_rml(art("pupil/catchlight.svg"), "Catchlight", CATCH, "FFFFFFFF", opacity=0.9)
    iris = svgpath.path_rml(art("pupil/iris-field.svg"), "Iris", IRIS, "FFF7F7F7")
    return f'''<Node x="0" y="0" opacity="0" name="PupilOverlay" id="{PUPIL_OVERLAY}">
    <Node x="0" y="0" name="PupilRoot" id="{PUPIL_ROOT}">
        <ClippingShape sourceId="{IRIS}" name="IrisClip"/>
{indent(catch, "        ")}
{indent(core, "        ")}
{indent(wave, "        ")}
    </Node>
{indent(iris, "    ")}
</Node>'''


def expression_animation(st):
    """State `st`'s Expr animation: its glyph, its mouth, the card scale and (for the pupil states) the wave loop."""
    # Glyphs live in a Solo: one keyed reference picks the drawn child (no opacity stack).
    objs = {GLYPHS_NODE: {ACTIVE_CHILD: Id(GLYPH[STATE_GLYPH[st]])}}
    objs.update({MOUTH_MORPH: {OPACITY: 0}, MOUTH_O: {OPACITY: 0}, FROWN: {OPACITY: 0}})
    if st in STATE_MOUTH:
        objs[STATE_MOUTH[st]] = {OPACITY: 1}
    sc = STATE_PLATE_SCALE.get(st, 1)
    objs[PLATE_CARD] = {SX: sc, SY: sc}
    objs[PLATE_SHADOW] = {SX: sc, SY: sc}
    name = "Expr" + st[0].upper() + st[1:]
    # The pupil overlay: on for idle/listening/speaking with that state's wave loop, off elsewhere.
    if st not in PUPIL:
        objs[PUPIL_OVERLAY] = {OPACITY: 0}
        return animation(name, plate_expr_anim[st], 1, objs)
    n = pupil_loop(objs, PUPIL[st])
    return animation(name, plate_expr_anim[st], n, objs, "loop")


def pupil_loop(objs, pupil):
    """Key the pupil overlay's wave loop into `objs`; returns the loop's length in frames."""
    n, wk = wave_keys(pupil.period_ms, pupil.amplitude)
    objs[PUPIL_OVERLAY] = {OPACITY: 1}
    # The stroke wave read as a bar at every size; the core bobs on the same sine instead.
    objs[WAVE] = {OPACITY: 0}
    objs.update(wk)
    A = pupil.amplitude
    q = [0, round(n / 4), round(n / 2), round(3 * n / 4), n]
    objs[CORE] = {Y: [(q[0], 0, SINE), (q[1], A, SINE), (q[2], 0, SINE), (q[3], -A, SINE), (q[4], 0)]}
    return n


def mouth_open_animation():
    # Open: the mouth morphs closed -> half -> open through the three SVG samples (linear).
    mouth_keys = {}
    for vid, samples in zip(mouth_vertex_ids, zip(*MOUTH_SAMPLES)):
        mouth_keys[vid] = {}
        for key, idx in ((VX, 0), (VY, 1), (VIN_ROT, 2), (VIN_DIST, 3), (VOUT_ROT, 4), (VOUT_DIST, 5)):
            mouth_keys[vid][key] = [(f, s[idx], LINEAR) for f, s in zip((0, 30, 60), samples)]
    return animation("Open", PLATE_OPEN, 60, mouth_keys)


def tune_animations():
    # Tunable pose ranges (bench art direction): frame 0 = value at 0, frame 60 = value at 1, linear.
    tune_anims = []
    for name, t in TUNABLES.items():
        key = {"SX": SX, "SY": SY, "Y": Y}
        lo, hi = t.span
        tune_anims.append(animation("Tune" + name[4:], t.anim, 60, {t.node: {key[p]: [(0, lo, LINEAR), (60, hi)] for p in t.props}}))
    # Saccades: each fixation is one animation - hop out in 60 ms (overshoot), hold, hop back in 80 ms.
    for w in SACCADE_WAITS:
        tune_anims.append(animation(f"SaccadeWait{w.ms}", w.anim, frames(w.ms), {}))
    for i, f in enumerate(SACCADE_FIX):
        dx, dy = f.offset
        hold = beat([700, 1100, 1600, 2400][i % 4])
        tune_anims.append(animation(f"Saccade{i + 1}", f.anim, hold + 8, {SACCADE_NODE: {
            X: [(0, 0, BACK_OUT), (4, dx, None), (hold, dx, SOFT_OUT), (hold + 8, 0)],
            Y: [(0, 0, BACK_OUT), (4, dy, None), (hold, dy, SOFT_OUT), (hold + 8, 0)]}}))
    tune_anims.append(animation("AutoX", PLATE_AUTO_X, 60, {GLYPH_SCALE_NODE: {X: [(0, -6, LINEAR), (60, 6)]}}))
    tune_anims.append(animation("AutoY", PLATE_AUTO_Y, 60, {GLYPH_SCALE_NODE: {Y: [(0, -4, LINEAR), (60, 4)]}}))
    return tune_anims


def saccade_layer_rml():
    # Saccade layer: waits pick a fixation at random; fixations return to a random wait. Asleep
    # (sleeping or dreaming), parked. Transitions are ordered, so the park check comes first.
    asleep_s = _asleep(SACCADE_SLEEP_NODE)
    awake_s = _outside(SACCADE_WAIT_NODES[0], RESTING)
    to_fix = [Exit(n, weight=f.weight) for n, f in zip(SACCADE_FIX_NODES, SACCADE_FIX)]
    to_wait = [Exit(n, weight=10) for n in SACCADE_WAIT_NODES]
    sac_states = [State(w.anim, nid, i, random=True, transitions=asleep_s + to_fix)
                  for i, (w, nid) in enumerate(zip(SACCADE_WAITS, SACCADE_WAIT_NODES))]
    sac_states += [State(f.anim, nid, 3 + i, reset=True, random=True, transitions=asleep_s + to_wait)
                   for i, (f, nid) in enumerate(zip(SACCADE_FIX, SACCADE_FIX_NODES))]
    sac_states.append(State(SACCADE_WAITS[0].anim, SACCADE_SLEEP_NODE, 12, transitions=awake_s))
    return Layer("Saccade", "7:13", SACCADE_WAIT_NODES[0], states=sac_states).rml()


def blink_trigger_layer_rml():
    # The blink trigger is a nested-artboard input, not a view-model trigger: hand-rolled XML.
    return Layer(
        "Blink", "7:11", PLATE_BLINK_REST_NODE,
        any_transitions=[Raw(PLATE_BLINK_NODE,
                             f'<StateTransition stateToId="{PLATE_BLINK_NODE}">\n'
                             f'    <TransitionTriggerCondition inputId="{PLATE_IN_BLINK}"/>\n</StateTransition>')],
        states=[State(PLATE_WAIT_A, PLATE_BLINK_REST_NODE, 0),
                State(PLATE_BLINK_ANIM, PLATE_BLINK_NODE, 1, reset=True,
                      transitions=[Exit(PLATE_BLINK_REST_NODE)])]).rml()


def auto_blink_layer_rml():
    # Eyes closed do not blink: the waits park while expr is sleeping and resume when it is not.
    # The two waits are not random states - each has exactly one way on, the exit into the blink;
    # only the blink itself picks (50/50) which wait it returns to.
    asleep = _asleep(PLATE_AUTO_SLEEP)
    awake_b = _outside(PLATE_AUTO_A, RESTING)
    return Layer(
        "AutoBlink", "7:12", PLATE_AUTO_A,
        states=[State(PLATE_WAIT_A, PLATE_AUTO_A, 0, transitions=asleep + [Exit(PLATE_AUTO_BLINK)]),
                State(PLATE_WAIT_B, PLATE_AUTO_B, 1, transitions=asleep + [Exit(PLATE_AUTO_BLINK)]),
                State(PLATE_BLINK_ANIM, PLATE_AUTO_BLINK, 2, reset=True, random=True,
                      transitions=[Exit(PLATE_AUTO_A, weight=50), Exit(PLATE_AUTO_B, weight=50)]),
                State(PLATE_WAIT_A, PLATE_AUTO_SLEEP, 3, transitions=awake_b)]).rml()


def squint_animations():
    """te3n1.5: the Squint layer's waits and its beat, and the Rem layer's waits and flicker."""
    out = [animation("SquintOff", PLATE_SQUINT_OFF_ANIM, 1, {})]
    out += [animation(f"SquintWait{ms}", aid, frames(ms), {}) for aid, ms in zip(PLATE_SQUINT_WAITS, SQUINT_WAITS_MS)]
    # The squint itself: the old held thinking glyph, swapped in over the open-narrowed rest. A
    # glyph swap, not a tween - the Solo's child is a reference, held per key.
    out.append(animation("Squint", PLATE_SQUINT_ANIM, frames(SQUINT_MS),
                         {GLYPHS_NODE: {ACTIVE_CHILD: Id(GLYPH["thinking"])}}))
    out += [animation(f"RemWait{ms}", aid, frames(ms), {}) for aid, ms in zip(PLATE_REM_WAITS, REM_WAITS_MS)]
    # REM: the closed eye flicks side to side, quick and small, decaying; back on centre at the end.
    n = frames(REM_MS)
    flicks = [(0, 0, STD_DECEL)] + [(3 + 4 * k, round((2.5 if k % 2 == 0 else -2.5) * 0.85 ** k, 3), STD_DECEL) for k in range(7)] + [(n, 0)]
    out.append(animation("RemFlicker", PLATE_REM_ANIM, n, {SACCADE_NODE: {X: flicks, Y: [(0, 0, SINE), (n // 2, 1, SINE), (n, 0)]}}))
    return out


def band_beat_layer_rml(name, lid, expr_state, wait_anims, wait_nodes, beat_anim, beat_node, off_node):
    """A plate layer that plays one beat on random waits while expr sits in `expr_state`'s band:
    Squint for thinking, Rem for dreaming (te3n1.5). Off parks it, and every state leaves for Off
    the moment expr is anywhere else, so a beat never outlives its state's glyph."""
    leave = _outside(off_node, [expr_state])
    states = [State(PLATE_SQUINT_OFF_ANIM, off_node, 0,
                    transitions=[OnInput(wait_nodes[0], PLATE_IN_EXPR, EXPR[expr_state], 0)])]
    states += [State(aid, nid, 1 + k, transitions=leave + [Exit(beat_node)])
               for k, (aid, nid) in enumerate(zip(wait_anims, wait_nodes))]
    states.append(State(beat_anim, beat_node, 1 + len(wait_nodes), reset=True, random=True,
                        transitions=leave + [Exit(nid, weight=10) for nid in wait_nodes]))
    return Layer(name, lid, off_node, states=states).rml()


def plate_component():
    """The whole Plate artboard: node tree, per-state and pose-range animations, and its machine."""
    expr_anims = [expression_animation(st) for st in STATES]

    px, py = PUPIL_PARALLAX
    look_x = animation("LookX", PLATE_LOOKX, 60, {GLYPHS_NODE: {X: [(0, -23, LINEAR), (60, 23)]},  # SPEC 9.1
                                                  PUPIL_OVERLAY: {X: [(0, -23, LINEAR), (60, 23)]},
                                                  PUPIL_ROOT: {X: [(0, -px, LINEAR), (60, px)]}})
    look_y = animation("LookY", PLATE_LOOKY, 60, {GLYPHS_NODE: {Y: [(0, -17, LINEAR), (60, 17)]},
                                                  PUPIL_OVERLAY: {Y: [(0, -17, LINEAR), (60, 17)]},
                                                  PUPIL_ROOT: {Y: [(0, -py, LINEAR), (60, py)]}})
    open_anim = mouth_open_animation()
    # Blink (Trutoiu, Carter, Matthews, Hodgins - Disney Research 2011): human blinks are
    # asymmetric - a fast close and a slow, decelerating open - and ~250-300 ms reads most
    # natural. Close 4 frames (67 ms), hold 1, open 10 (167 ms). Squashes the glyph only.
    shutter = [(0, 1, STD_DECEL), (BLINK_SHUT, 0, None), (BLINK_SHUT + 1, 0, EMPH_DECEL), (BLINK_FRAMES, 1)]
    blink = animation("Blink", PLATE_BLINK_ANIM, BLINK_FRAMES, {GLYPHS_NODE: {SY: shutter}, PUPIL_OVERLAY: {SY: list(shutter)}})
    wait_a = animation("WaitA", PLATE_WAIT_A, frames(4000), {})
    wait_b = animation("WaitB", PLATE_WAIT_B, frames(8000), {})

    tune_anims = tune_animations() + squint_animations()

    # The blend policy (rig/seams.py): the plate's own animations, indexed by their keyed ends,
    # so its layers below are checked against what they actually hand each other. Built before
    # the layers for that reason; the joined XML is written out unchanged further down.
    set_animation_index(animation_index("\n".join(expr_anims + [look_x, look_y, open_anim, blink,
                                                               wait_a, wait_b] + tune_anims)))

    expr_layer = expression_layer("Expression", "7:10", PLATE_IN_EXPR, plate_expr_anim, plate_expr_node)

    saccade_layer = saccade_layer_rml()
    trigger_layer = blink_trigger_layer_rml()
    auto_layer = auto_blink_layer_rml()
    squint_layer = band_beat_layer_rml("Squint", "7:14", "thinking", PLATE_SQUINT_WAITS, PLATE_SQUINT_WAIT_NODES,
                                       PLATE_SQUINT_ANIM, PLATE_SQUINT_NODE, PLATE_SQUINT_OFF_NODE)
    rem_layer = band_beat_layer_rml("Rem", "7:15", "dreaming", PLATE_REM_WAITS, PLATE_REM_WAIT_NODES,
                                    PLATE_REM_ANIM, PLATE_REM_NODE, PLATE_REM_OFF_NODE)

    glyphs = "\n".join(svgpath.path_rml(art(f"glyph-{n}.svg"), n[0].upper() + n[1:], GLYPH[n], INK)
                       for n in GLYPH_ORDER + NEW_GLYPHS)
    mv = "\n".join(
        f'<CubicDetachedVertex x="{s[0]}" y="{s[1]}" inRotation="{s[2]}" inDistance="{s[3]}" outRotation="{s[4]}" outDistance="{s[5]}" name="M{i}" id="{vid}"/>'
        for i, (s, vid) in enumerate(zip(MOUTH_SAMPLES[0], mouth_vertex_ids)))
    mouth_morph = f'''<Shape x="0" y="0" opacity="0" name="Mouth" id="{MOUTH_MORPH}">
    <PointsPath isClosed="true" name="Path">
{indent(mv, "        ")}
    </PointsPath>
    {fill(INK)}
</Shape>'''
    mouth_o = svgpath.path_rml(art("glyph-mouth-o.svg"), "MouthO", MOUTH_O, INK, opacity=0)
    frown = svgpath.path_rml(art("glyph-mouth-frown.svg"), "FrownLine", FROWN, INK, opacity=0)

    # Wrapper nodes carry the tunables so the state, look and blink keys on the inner objects
    # never collide with them. MouthNode's rest y is the SPEC mouth anchor (0, +82).
    return f'''<Artboard isComponent="true" defaultStateMachineId="{PLATE_SM}" x="700" y="0" styleId="7:3" clip="false" width="200" height="200" name="Plate" id="{PLATE_AB}">
    <LayoutComponentStyle name="Style" id="7:3"/>
    <Node x="100" y="100" name="PlateRoot" id="{PLATE_ROOT}">
        <Node x="0" y="82" name="MouthNode" id="{MOUTH_NODE}">
{indent(mouth_morph, "            ")}
{indent(mouth_o, "            ")}
{indent(frown, "            ")}
        </Node>
        <Node x="0" y="0" name="GlyphScale" id="{GLYPH_SCALE_NODE}">
            <Node x="0" y="0" name="Saccade" id="{SACCADE_NODE}">
{indent(pupil_overlay(), "                ")}
                <Solo activeComponentId="{GLYPH[STATE_GLYPH['idle']]}" x="0" y="0" name="Glyphs" id="{GLYPHS_NODE}">
{indent(glyphs, "                    ")}
                </Solo>
            </Node>
        </Node>
        <Node x="0" y="0" name="PlateScale" id="{PLATE_SCALE_NODE}">
            <Shape x="0" y="0" name="Card" id="{PLATE_CARD}">
                {rrect(120, 120, 27)}
                {fill(PLATE_WHITE)}
            </Shape>
            <Shape x="0" y="2" name="Shadow" id="{PLATE_SHADOW}">
                {rrect(120, 120, 27)}
                <Stroke thickness="4" name="Stroke">
                    <SolidColor colorValue="14000000" name="Color"/>
                    <Feather strength="3" name="Feather"/>
                </Stroke>
            </Shape>
        </Node>
    </Node>

{indent(chr(10).join(expr_anims + [look_x, look_y, open_anim, blink, wait_a, wait_b] + tune_anims), "    ")}

    <StateMachine name="Plate" id="{PLATE_SM}">
        <StateMachineNumber name="expr" id="{PLATE_IN_EXPR}"/>
        <StateMachineTrigger name="blink" id="{PLATE_IN_BLINK}"/>
{indent(expr_layer, "        ")}
{indent(trigger_layer, "        ")}
{indent(auto_layer, "        ")}
{indent(saccade_layer, "        ")}
{indent(squint_layer, "        ")}
{indent(rem_layer, "        ")}
    </StateMachine>
</Artboard>
<ComponentAsset artboardId="{PLATE_AB}" name="Plate"/>'''
