"""The motion library: sustained loops, entries, momentary flashes, idle beats, wander.

Owns every LinearAnimation the root artboard plays except the turn ranges (rig/face.py) and the
hover/breath/blink one-shots gen_scene.py builds inline. It reads its key recipes from
rig/body.py and rig/face.py, its numbers from rig/constants.py and its ids from rig/ids.py;
rig/machine.py wires the animations named here into layers, and gen_scene.py collects them.
A new beat is an animation here plus an id from rig/ids.py alloc() plus a state in rig/machine.py.

Rive rules that bite here:
  - the bezier on a keyframe shapes the segment LEAVING it, not the one arriving. The last key's
    bezier is ignored; a pose that should hold needs an explicit key at both ends of the hold.
  - frames() is milliseconds -> frames: a LinearAnimation duration is frames, so every ms number
    in this module passes through frames() or beat() before it reaches animation().
  - these keys mix over lower layers (Breath, the host turn) on the same nodes, so a beat that
    does not key a property leaves the lower layer showing. A beat may want that; an entry must
    not - an unkeyed property snaps back to rest at the 0 ms cut into the entry, which is what
    letta-mobile-r4bbm was, so every entry keys Face, Body and BodyPlacement at both ends.
  - merge(), not dict.update(): update() replaces a whole object's property map, and two recipes
    that key different properties of the same node (the hop's Body.y and the spin's Body.rotation)
    silently lose one of the two.
"""
from typing import NamedTuple

from rml import (ACCEL, BACK_IN, BACK_IN_OUT, BACK_SOFT, COLOR, EASE_OUT, ELASTIC_HEAVY,
                 ELASTIC_SOFT, EMPH_ACCEL, EMPH_DECEL, GRADIENT_OPACITY, LINEAR, M3_STANDARD,
                 NESTED_VALUE, OPACITY, REMAP_TIME, ROT, SINE, SOFT_OUT, SPRING, STANDARD,
                 STD_DECEL, SX, SY, X, Y, animation)
from rig.body import (
    BREATHING, BREATH_MS, HALO_FAILED, HALO_OPACITY, HALO_SLEEP, INFLATE_NODE, LUMEN, LUMEN_PEAK,
    LUMEN_REST, bone_pose, breath2, breath_scale, lumen_keys, shape_keys, sine, squash,
)
from rig.chart import Chart
from rig.constants import (BLINK_FLIP, DESIGNED_PAIRS, EXPR, IDLE_WAITS, JX, JY, WANDER_WAITS, beat,
                           frames, rad)
from rig.face import spin_keys
from rig.ids import (
    AUTO_X, AUTO_Y, BODY_NODE, DRAG_ANIM, DRAG_REST_ANIM, ERROR_ANIM, FACE, FLASH_REST_ANIM, GLOSS,
    HALO, IDLE_BEATS, IDLE_GLANCE_ANIM, JOYSTICK, PLATE_BLINK, PLATE_EXPR, SHAPES, SUCCESS_ANIM,
    SUSTAINED, TINT, WANDER_GLANCE, WANDER_PEEK, WANDER_SLEEP_SHIFT, WANDER_SLEEP_WAIT, WANDER_SPIN,
    enter_anim, root_state_anim, shape_anim,
)


def merge(into, more):
    """Merge one key recipe into another, per object AND per property; returns `into`.

    `dict.update` replaces a whole object's property map. That is how the success hop lost its
    body: `success_keys.update(spin_keys(...))` overwrote `{BODY_NODE: {Y: hop}}` with
    spin_keys' `{BODY_NODE: {ROT: ...}}`, so `SuccessFlash` never keyed Body.y at all and the
    48 px rise the telemetry could not find (letta-mobile-uesod) was never in the document.
    Merging refuses a genuine collision instead of silently keeping one of the two.
    """
    for obj, props in more.items():
        if obj not in into:
            into[obj] = dict(props)
            continue
        clash = sorted(set(into[obj]) & set(props))
        if clash:
            raise ValueError(f"merge: object {obj} is already keyed on property {clash}; "
                             f"one of the two recipes has to give it up")
        into[obj].update(props)
    return into


def travel(f0, v0, f1, v1, spacing="s", ticks=5):
    """A joystick move authored as spacing rather than as a bezier: real in-betweens, linear between.

    The head is the heaviest thing the rig turns, and a bezier states its weight only at the ends -
    the middle is the interpolator's opinion, and BACK_IN_OUT's opinion is that a third of the
    travel happens in a seventh of the time. A chart states the whole of it (`rig/chart.py`:
    spacing IS the weight), so an "s" lays the move down eased out of one extreme and into the
    next with no frame carrying more than about a fifth of it. Linear between the ticks for the
    reason `spin_keys` gives: the in-betweens ARE the spacing, and a bezier on each one would
    re-ease every segment and put the snap back.
    """
    return Chart(extremes=[(f0, v0), (f1, v1)], spacing=spacing, smooth=LINEAR, ticks=ticks).keys()


def wander_animations():
    """The Wander layer's animations: four unequal waits, the glance, peek, spin and the sleep shift.

    The glance and the peek are chart-authored (letta-mobile-q55am): a head with mass leaves rest
    slowly, carries its travel through the middle and eases into the extreme, and the only spring
    in the beat is the short, damped settle at the very end. The old shape - BACK_IN_OUT out to the
    extreme and an ELASTIC_SOFT release straight off a dead hold - put the whole return into its
    first three frames (`Joystick.x max|delta|` 0.176 of a 1.2-unit swing) and read as a twang.
    """
    d = beat(1600)
    # Look away, hold, swing back through centre, settle. The counter-swing is small and the
    # elastic only governs it, so the release is a settle rather than the whole return.
    gx = (travel(0, 0, beat(560), -0.8)[:-1] + [(beat(560), -0.8, None)]
          + travel(beat(900), -0.8, beat(1300), 0.2)[:-1]
          + [(beat(1300), 0.2, ELASTIC_HEAVY), (d, 0)])
    glance = animation("WanderGlance", WANDER_GLANCE, d, {JOYSTICK: {JX: gx}})
    d = beat(1400)
    def peek_axis(top):
        # The counter-swing is 6 % of the travel on purpose. A settle is scored against 2 % of the
        # move's span, so a counter much bigger than that reads to the probe as "still moving" for
        # every frame the spring takes to shed it - a heavier beat that measures as a slower one.
        counter = round(-0.06 * top, 4)
        return (travel(0, 0, beat(450), top)[:-1] + [(beat(450), top, None)]
                + travel(beat(850), top, beat(1150), counter)[:-1]
                + [(beat(1150), counter, ELASTIC_HEAVY), (d, 0)])
    peek = animation("WanderPeek", WANDER_PEEK, d, {JOYSTICK: {JY: peek_axis(0.7), JX: peek_axis(0.3)}})
    spin_k = spin_keys(0, beat(700))
    d7 = beat(700)
    merge(spin_k, squash(INFLATE_NODE, [(0, 1, BACK_IN), (round(d7 * 0.25), 1.05, STANDARD), (round(d7 * 0.62), 1.05, ELASTIC_SOFT), (d7, 1)]))
    spin = animation("WanderSpin", WANDER_SPIN, d7, spin_k)
    # Asleep: one slow, small shift every ~30 s, nothing else.
    sleep_shift = animation("WanderSleepShift", WANDER_SLEEP_SHIFT, beat(3000), {JOYSTICK: {
        JX: [(0, 0.4, SINE), (beat(1500), 0.22, SINE), (beat(3000), 0.4)],
        JY: [(0, 0.5, SINE), (beat(1500), 0.62, SINE), (beat(3000), 0.5)]}})
    return ([animation("WanderWait" + str(k), w.anim, frames(w.ms), {}) for k, w in enumerate(WANDER_WAITS)]
            + [animation("WanderSleepWait", WANDER_SLEEP_WAIT, frames(30000), {}), sleep_shift, glance, peek, spin])


# Default gaze life per state, on the AutoLookX/AutoLookY remaps (0..1, 0.5 = centre). A value
# or a function of the state's loop period (ms) returning keys, so drifts loop with the state.
# Small on purpose: the host's lookX/lookY adds on top (+-23/+-17) and both must fit the card.
# Eyes fixate; they do not drift. These are the resting fixations; the plate's Saccade layer
# adds the quick, random re-fixations that make a still gaze alive.
GAZE = {
    "idle": (0.5, 0.5),
    "listening": (0.5, 0.35),        # up at you
    "thinking": (0.22, 0.25),        # up-left, into its own thoughts
    "waitingInput": (0.5, 0.4),      # straight at you
    "speaking": (0.5, 0.5),
    "error": (0.5, 0.65),            # down
    "sleeping": (0.5, 0.75),
}


def sustained_facing():
    """The facing (joystick x, y) each sustained state rests at - SPEC section 1."""
    return {"idle": (-0.15, 0), "listening": (0, 0), "thinking": (-0.6, -0.2), "waitingInput": (0, 0),
              "speaking": (0.15, 0), "error": (-0.3, 0.25), "sleeping": (0.4, 0.5), "loading": (0, 0),
              "failed": (0, 0.1), "degraded": (0.5, -0.1)}


class StateRow(NamedTuple):
    """One sustained state's pose: what moves, how long the loop is, and how the plate reads."""
    motion: dict          # {"x"|"y": keys or a constant} - the root delta Body and Face share
    period_ms: int        # loop length; 0 = a single-frame hold
    plate_rot_deg: float
    face_offset: tuple    # (x, y) px the Face carries on top of the root motion
    tint: str             # ARGB over the body
    gloss_pulse: list     # gradient-opacity keys, or None for a flat gloss


STATE_ROWS = {  # SPEC 1 + 9.1
    "idle": StateRow({"y": breath2()}, 2 * BREATH_MS, 0, (0, 0), "00000000", None),
    "listening": StateRow({"y": breath2()}, 2 * BREATH_MS, -2, (0, -14), "00000000", None),
    "thinking": StateRow({"x": sine(2, 3200)}, 3200, -6, (0, 0), "00000000", None),
    "waitingInput": StateRow({"y": sine(19, 1200)}, 1200, 0, (0, -2), "00000000", None),
    "speaking": StateRow({"y": breath2()}, 2 * BREATH_MS, 0, (0, 0), "00000000", None),
    "error": StateRow({"y": 24}, 0, 5, (0, 4), "14000000", None),
    "sleeping": StateRow({"y": breath2(9000)}, 18000, 3, (0, 4), "38000000", None),   # slow, deep; one inflate per two bobs
    "loading": StateRow({}, 1400, 0, (0, 0), "10000000", [(0, 0.8, SINE), (frames(700), 1.0, SINE), (frames(1400), 0.8)]),
    "failed": StateRow({}, 0, 0, (0, 0), "66808080", None),
    "degraded": StateRow({}, 0, 4, (0, 0), "00000000", None),
}


class Rest(NamedTuple):
    """Where a sustained loop sits on its own frame 0: the pose an entry starts from, or lands on."""
    body_x: float
    body_y: float
    face_x: float
    face_y: float
    face_rot: float     # radians
    inflate: float      # BodyPlacement scale (the breath's low point)


def sustained_rest(state):
    """The `Rest` of a sustained state, derived from the same StateRow table the loop is built
    from: the root motion's first value, the face offset on top of it, the plate's rotation, and
    the low point of the breath's inflate. A generic entry uses it at both ends, so the pose the
    state before it was holding travels to the pose the next one holds instead of snapping to
    zero at a 0 ms hand-off (letta-mobile-r4bbm)."""
    row = STATE_ROWS[state]
    first = lambda keys: keys[0][1] if isinstance(keys, list) else keys
    mx, my = first(row.motion.get("x", 0)), first(row.motion.get("y", 0))
    fx, fy = row.face_offset
    return Rest(mx, my, mx + fx, my + fy, rad(row.plate_rot_deg),
                BREATHING[state].lo if state in BREATHING else 1)


def sustained_animations():
    """SPEC section 1: root motion (Body and Face move together), plate rotation/offset, tint,
    gloss pulse, and the glyph index. Nothing keys body scale or body vertices."""
    FACING = sustained_facing()
    out = []
    for st in SUSTAINED:
        row = STATE_ROWS[st]
        motion, period, rot, tint, gloss = row.motion, row.period_ms, row.plate_rot_deg, row.tint, row.gloss_pulse
        fx, fy = row.face_offset

        def shifted(keys, offset):
            if isinstance(keys, list):
                return [(f, v + offset) + tuple(k[2:]) for k, (f, v) in zip(keys, [(k[0], k[1]) for k in keys])]
            return keys + offset

        mx, my = motion.get("x", 0), motion.get("y", 0)
        body_keys = {X: mx, Y: my}
        face_keys = {X: shifted(mx, fx), Y: shifted(my, fy), ROT: rad(rot)}
        jx, jy = FACING[st]
        halo = {"sleeping": HALO_SLEEP, "failed": HALO_FAILED}.get(st, HALO_OPACITY)
        gx, gy = GAZE.get(st, (0.5, 0.5))
        bs = breath_scale(*BREATHING[st]) if st in BREATHING else 1   # period, lo, hi
        objs = {BODY_NODE: body_keys, FACE: face_keys, TINT: {COLOR: tint}, PLATE_EXPR: {NESTED_VALUE: EXPR[st]},
                INFLATE_NODE: {SX: bs, SY: bs},
                **bone_pose([(0, 1, 0, 0)]),
                LUMEN: (lumen_keys(BREATHING[st].period_ms, LUMEN_PEAK * (0.6 if st == "sleeping" else 1))
                        if st in BREATHING else LUMEN_REST),
                GLOSS: {GRADIENT_OPACITY: gloss if gloss else 1}, JOYSTICK: {JX: jx, JY: jy}, HALO: {OPACITY: halo},
                AUTO_X: {REMAP_TIME: gx(period) if callable(gx) else gx}, AUTO_Y: {REMAP_TIME: gy(period) if callable(gy) else gy}}
        duration = frames(period) if period else 1
        out.append(animation("State" + st[0].upper() + st[1:], root_state_anim[st], duration, objs, "loop" if duration > 1 else "oneShot"))
    return out


class EnterTiming(NamedTuple):
    """How long an entry one-shot runs and the curve it travels on."""
    ms: int
    bezier: str


TURNING = 0.2        # facing delta above which an entry is a real turn, not a settle
TURN_STRETCH = 1.35  # ...and gets that much more travel time, because a head has mass


def turning(frm, to, facing=None):
    """How far the facing moves across this entry, in joystick units (x and y summed)."""
    F = facing or sustained_facing()
    (fx0, fy0), (fx1, fy1) = F[frm], F[to]
    return abs(fx1 - fx0) + abs(fy1 - fy0)


def enter_duration(frm, to):
    """The EnterTiming of the entry from `frm` to `to`: SPEC section 3 for the designed pairs,
    otherwise the target's default (sleeping settles slowly, waitingInput springs).

    A generic entry that actually turns the head - more than TURNING units of facing - gets
    TURN_STRETCH more time to do it (letta-mobile-q55am). 160 ms was the duration of a glyph swap
    under a blink, and swinging the whole head 0.65 units of facing through it read as weightless.
    The five designed pairs keep their SPEC section 3 durations exactly; their extra weight comes
    from the curve alone."""
    if (frm, to) in DESIGNED_PAIRS:
        return EnterTiming(DESIGNED_PAIRS[(frm, to)], SOFT_OUT)
    timing = {"sleeping": EnterTiming(600, STANDARD),
              "waitingInput": EnterTiming(160, SPRING)}.get(to, EnterTiming(160, EASE_OUT))
    if turning(frm, to) > TURNING:
        return EnterTiming(round(timing.ms * TURN_STRETCH), timing.bezier)
    return timing


def enter_animations():
    """One entry per pair of sustained states: a one-shot whose glyph swap hides inside a blink
    fired at frame 0 (the shutter, closed by frame 3); the plate's expression flips while the
    eye is closed and the facing travels to the target's. The SPEC section 3 pairs add designed
    body/face motion that lands on the target's rest; a generic one travels the SOURCE state's
    held pose to the TARGET's over the entry's own curve.

    That last part is letta-mobile-r4bbm. A generic entry used to key nothing on Face, Body or
    BodyPlacement, so a state that holds a pose - error's 24 px drop and 5 deg roll, listening's
    -14 px lift, degraded's 4 deg, sleeping's 0.985 inflate - handed that pose back to rest across
    a 0 ms cut the moment the entry started: 61 seams on the ledger. The poses are derivable from
    the same StateRow table the loops are built from (`sustained_rest`), so the entry now starts
    where the state before it was and eases to where the next one holds."""
    F = sustained_facing()
    out = []
    for (frm, to), aid in enter_anim.items():
        fx0, fy0 = F[frm]; fx1, fy1 = F[to]
        timing = enter_duration(frm, to)
        bez = timing.bezier
        n = frames(timing.ms)
        # A real turn lands with overshoot - but a small one, off a heavy departure. BACK_OUT left
        # the old pose at 3.7x the average speed and BACK_SOFT leaves at 3.1x with no less arrival.
        turn = BACK_SOFT if turning(frm, to, F) > TURNING else bez
        r0, r1 = sustained_rest(frm), sustained_rest(to)
        keys = {PLATE_EXPR: {NESTED_VALUE: [(0, EXPR[frm], None), (BLINK_FLIP, EXPR[to])]},
                JOYSTICK: {JX: [(0, fx0, turn), (n, fx1)], JY: [(0, fy0, turn), (n, fy1)]}}
        if (frm, to) not in DESIGNED_PAIRS:
            # The generic entry: hold nothing, travel everything. Every property is keyed at both
            # ends even when the two rests agree, so no earlier pose can leak through the one-shot.
            ramp = lambda v0, v1: [(0, v0, bez), (n, v1)]
            keys[BODY_NODE] = {X: ramp(r0.body_x, r1.body_x), Y: ramp(r0.body_y, r1.body_y)}
            keys[FACE] = {X: ramp(r0.face_x, r1.face_x), Y: ramp(r0.face_y, r1.face_y),
                          ROT: ramp(r0.face_rot, r1.face_rot)}
        elif (frm, to) == ("idle", "listening"):
            # SPEC 9.4: 50 ms anticipation down (+3, +1 deg), lean past to -16/-3 deg at 200 ms, settle -14/-2 deg.
            keys[FACE] = {Y: [(0, 0, EMPH_ACCEL), (frames(50), 3, EMPH_DECEL), (frames(200), -16, M3_STANDARD), (n, -14)],
                          ROT: [(0, 0, EMPH_ACCEL), (frames(50), rad(1), EMPH_DECEL), (frames(200), rad(-3), M3_STANDARD), (n, rad(-2))]}
        elif (frm, to) == ("listening", "thinking"):
            # hold the gaze 60 ms, then turn away and tilt.
            keys[JOYSTICK] = {JX: [(0, fx0, None), (frames(60), fx0, BACK_SOFT), (n, fx1)], JY: [(0, fy0, None), (frames(60), fy0, BACK_SOFT), (n, fy1)]}
            keys[FACE] = {Y: [(0, -14, STANDARD), (n, 0)], ROT: [(0, rad(-2), STANDARD), (n, rad(-6))]}
        elif (frm, to) == ("thinking", "speaking"):
            keys[FACE] = {ROT: [(0, rad(-6), SOFT_OUT), (frames(140), 0, None), (n, 0)]}
        elif (frm, to) == ("speaking", "idle"):
            # the small exhale: +1 at 120 ms, then rest.
            keys[FACE] = {Y: [(0, 0, SOFT_OUT), (frames(120), 1, SOFT_OUT), (n, 0)]}
            keys[BODY_NODE] = {Y: [(0, 0, SOFT_OUT), (frames(120), 1, SOFT_OUT), (n, 0)]}
        elif (frm, to) == ("error", "idle"):
            # SPEC 9.5: root +24 -> 0 (face carries its +4 offset on top), plate +5 deg -> 0.
            keys[FACE] = {Y: [(0, 28, SOFT_OUT), (n, 0)], ROT: [(0, rad(5), SOFT_OUT), (n, 0)]}
            keys[BODY_NODE] = {Y: [(0, 24, SOFT_OUT), (n, 0)]}
            keys[TINT] = {COLOR: [(0, "14000000"), (frames(100), "00000000")]}
        # The inflate travels from the source state's breath low point to the target's (sleeping
        # rests at 0.985, everything else at 1), with the turn's own squash riding on top of it.
        base = lambda f: r0.inflate + (r1.inflate - r0.inflate) * (f / n)
        if turn is BACK_SOFT and n >= 10:
            sq = squash(INFLATE_NODE, [(0, 1, EMPH_ACCEL), (3, 1.04, SOFT_OUT), (min(n - 2, 9), 0.98, SOFT_OUT), (n, 1)])
            merge(keys, {INFLATE_NODE: {k: [(f, round(v * base(f), 4)) + tuple(rest) for (f, v, *rest) in ks]
                                        for k, ks in sq[INFLATE_NODE].items()}})
        elif r0.inflate != r1.inflate:
            merge(keys, {INFLATE_NODE: {SX: [(0, r0.inflate, bez), (n, r1.inflate)],
                                        SY: [(0, r0.inflate, bez), (n, r1.inflate)]}})
        out.append(animation(f"Enter_{frm}_{to}", aid, n, keys, callbacks=(PLATE_BLINK,)))
    return out


def shape_animations():
    """One single-frame vertex-morph animation per identity, for the Shape layer."""
    return [animation("Shape" + s[0].upper() + s[1:], shape_anim[s], 1, shape_keys(s)) for s in SHAPES]


def momentary_animations():
    """SPEC section 2. Root delta on Body and Face together; plate rotation on Face."""
    S = frames(800)
    f = lambda pct: round(S * pct / 100)
    # SPEC 9.4: the bezier on a key is the *incoming* curve of the next row.
    hop = [(0, 0, EMPH_ACCEL), (f(10), 6, STD_DECEL), (f(37.5), -48, EMPH_ACCEL), (f(70), 4, EMPH_DECEL), (f(87.5), -1, M3_STANDARD), (S, 0)]
    success_keys = {
        BODY_NODE: {Y: hop},
        FACE: {Y: hop, ROT: [(0, 0, EMPH_ACCEL), (f(10), rad(-2), STD_DECEL), (f(37.5), rad(2), EMPH_ACCEL), (f(70), rad(-1), EMPH_DECEL), (f(87.5), 0, M3_STANDARD), (S, 0)]},
        PLATE_EXPR: {NESTED_VALUE: EXPR["success"]}}
    # Squash and stretch, volume preserved: crouch before the jump, stretch on the way up,
    # neutral at the apex, stretch falling, squash on landing, elastic settle. The plate joins in.
    # SPEC 10.5 success deformation keys (S, L, H); the inflate node holds 1 while the bones own the body.
    merge(success_keys, bone_pose([(0, 1, 0, 0, EMPH_ACCEL), (f(10), 1.06, 0.008, 0, STD_DECEL), (f(22), 0.93, -0.008, 0, SOFT_OUT),
                                   (f(37.5), 1, 0, 0, EMPH_ACCEL), (f(60), 0.96, -0.006, 0, ACCEL), (f(70), 1.10, 0.012, 0, SOFT_OUT),
                                   (f(87.5), 1.02, 0.003, 0, M3_STANDARD), (S, 1, 0, 0)]))
    success_keys[INFLATE_NODE] = {SX: 1, SY: 1}
    merge(success_keys, squash(FACE, [(0, 1, EMPH_ACCEL), (f(22), 0.97, SOFT_OUT), (f(37.5), 1, EMPH_ACCEL), (f(70), 1.05, ELASTIC_SOFT), (S, 1)]))
    # The spin also keys BODY_NODE (its roll), so this MUST merge: `update` would replace the
    # body's whole property map and take the hop's Y with it (letta-mobile-uesod).
    merge(success_keys, spin_keys(f(6), f(92) - f(6)))
    success = animation("SuccessFlash", SUCCESS_ANIM, S, success_keys)
    E = frames(600)
    g = lambda pct: round(E * pct / 100)
    # SPEC 9.5 endpoints
    ex = [(0, 0, STANDARD), (g(16.6667), -24, STANDARD), (g(33.3333), 24, STANDARD), (g(50), -12, STANDARD), (g(66.6667), 0, None), (E, 0)]
    ey = [(0, 0, STANDARD), (g(16.6667), 24, STANDARD), (g(33.3333), 24, STANDARD), (g(50), 24, STANDARD), (g(66.6667), 24, None), (E, 24)]
    er = [(0, 0, STANDARD), (g(16.6667), rad(-7), STANDARD), (g(33.3333), rad(7), STANDARD), (g(50), rad(-3), STANDARD), (g(66.6667), rad(5), None), (E, rad(5))]
    error_keys = {BODY_NODE: {X: ex, Y: ey}, FACE: {X: ex, Y: ey, ROT: er}, PLATE_EXPR: {NESTED_VALUE: EXPR["error"]}}
    merge(error_keys, bone_pose([(0, 1, 0, 0, STANDARD), (g(16.6667), 1.07, 0, 0, STANDARD), (g(33.3333), 0.97, 0, 0, STANDARD),
                                 (g(50), 1.04, 0, 0, STANDARD), (g(66.6667), 1, 0, 0, None), (E, 1, 0, 0)]))
    error_keys[INFLATE_NODE] = {SX: 1, SY: 1}
    error = animation("ErrorFlash", ERROR_ANIM, E, error_keys)
    drag_keys = {PLATE_EXPR: {NESTED_VALUE: EXPR["dragged"]}, INFLATE_NODE: {SX: 1, SY: 1}}
    merge(drag_keys, bone_pose([(0, round(1 / 0.92, 4), -0.008, 0, None)]))   # vertical compression, horizontal spread
    drag = animation("Dragged", DRAG_ANIM, 1, drag_keys)
    return [animation("FlashRest", FLASH_REST_ANIM, 1, {}), success, error, animation("DragRest", DRAG_REST_ANIM, 1, {}), drag]


def idle_variety_animations():
    """The IdleVariety layer's animations: four unequal waits and the nine idle beats."""
    # The glance gives 200 ms of its hold to its return, for the reason the tilt does: an elastic
    # release is front-loaded, so frames are the only damping it has. Total length is unchanged.
    m, h, r = beat(250), beat(450), beat(500)
    # Rotation leaves and settles heavier than the offset it rides on: BACK_SOFT out, ELASTIC_HEAVY
    # home. The 2 px of travel is a translation and keeps the tokens it had (letta-mobile-q55am).
    glance = animation("IdleGlance", IDLE_GLANCE_ANIM, m + h + r, {
        FACE: {ROT: [(0, 0, BACK_SOFT), (m, rad(2), None), (m + h, rad(2), ELASTIC_HEAVY), (m + h + r, 0)],
               X: [(0, 0, BACK_IN_OUT), (m, 2, None), (m + h, 2, ELASTIC_SOFT), (m + h + r, 0)]}})
    # Stretch: a slow tall stretch (volume kept), face rides up, then a soft elastic settle.
    d = beat(1400)
    stretch = animation("IdleStretch", IDLE_BEATS["stretch"].anim, d, dict(
        squash(INFLATE_NODE, [(0, 1, BACK_IN_OUT), (beat(500), 0.93, None), (beat(900), 0.93, ELASTIC_SOFT), (d, 1)]),
        **{FACE: {Y: [(0, 0, BACK_IN_OUT), (beat(500), -7, None), (beat(900), -7, ELASTIC_SOFT), (d, 0)]}}))
    # Tilt: the whole body cocks 6 degrees like a dog hearing something, holds, comes back.
    # The hold gives up 250 ms so the unwind has 63 frames instead of 42: an elastic release is
    # front-loaded whatever its period (about a fifth of the travel lands in the first frame), so
    # the only way to take the whip out of it is to hand it more frames. The beat's own length,
    # and the frame it is back at rest by, are unchanged (letta-mobile-q55am).
    d = beat(1600)
    tilt = animation("IdleTilt", IDLE_BEATS["tilt"].anim, d, {
        BODY_NODE: {ROT: [(0, 0, BACK_SOFT), (beat(400), rad(6), None), (beat(850), rad(6), ELASTIC_HEAVY), (d, 0)]},
        FACE: {ROT: [(0, 0, BACK_SOFT), (beat(400), rad(4), None), (beat(850), rad(4), ELASTIC_HEAVY), (d, 0)],
               X: [(0, 0, BACK_IN_OUT), (beat(400), 4, None), (beat(850), 4, ELASTIC_SOFT), (d, 0)]}})
    # Bounce: anticipation squash, a small hop, landing squash, settle.
    d = beat(700)
    hop = [(0, 0, EMPH_ACCEL), (beat(120), 3, STD_DECEL), (beat(320), -14, EMPH_ACCEL), (beat(520), 2, EMPH_DECEL), (d, 0)]
    bounce = animation("IdleBounce", IDLE_BEATS["bounce"].anim, d, dict(
        squash(INFLATE_NODE, [(0, 1, STANDARD), (beat(120), 1.06, STANDARD), (beat(320), 0.96, STANDARD), (beat(520), 1.05, ELASTIC_SOFT), (d, 1)]),
        **{BODY_NODE: {Y: hop}, FACE: {Y: hop}}))
    # Shiver: a quick side-to-side shake of the body, the face lagging a frame or two.
    d = beat(420)
    def shake(amp, lag):
        return [(0, 0, STANDARD)] + [(beat(60 * i) + lag, amp * (1 if i % 2 else -1) * (1 - i / 7), STANDARD) for i in range(1, 6)] + [(d, 0)]
    shiver = animation("IdleShiver", IDLE_BEATS["shiver"].anim, d, {BODY_NODE: {X: shake(4, 0)}, FACE: {X: shake(3, 2)}})
    # Sigh: a slow deflate (wide squash), the face sinks, then it fills back up.
    d = beat(1800)
    sigh = animation("IdleSigh", IDLE_BEATS["sigh"].anim, d, dict(
        squash(INFLATE_NODE, [(0, 1, SINE), (beat(700), 1.05, None), (beat(1100), 1.05, SOFT_OUT), (d, 1)]),
        **{FACE: {Y: [(0, 0, SINE), (beat(700), 5, None), (beat(1100), 5, SOFT_OUT), (d, 0)]}}))
    # Wobble: a decaying rock about the base, like it was nudged. The envelope is geometric, not
    # linear: a nudge loses most of its energy in the first swing back, and the old `1 - k/7` ramp
    # still had a quarter of the amplitude left on the fifth swing, which is what read as lively.
    # `tuple(k)`, not `tuple(k[2:])`: k is already the rest of the tuple, so slicing it threw the
    # SINE away and let the animation default (SOFT_OUT, the most front-loaded curve in rml.py)
    # drive the face. That alone was `Face.rotation max|delta|` 0.0185 against the body's 0.0069 -
    # the face snapped where the body eased, on keys that were supposed to be the same curve.
    d = beat(1300)
    rock = [(0, 0, SINE)] + [(beat(180 * k), rad(4 * (1 if k % 2 else -1) * 0.6 ** k), SINE) for k in range(1, 6)] + [(d, 0)]
    wobble = animation("IdleWobble", IDLE_BEATS["wobble"].anim, d, {BODY_NODE: {ROT: rock}, FACE: {ROT: [(f, v * 0.6) + tuple(k) for (f, v, *k) in rock]}})
    # Shift: settles its weight to one side for a while, then back.
    d = beat(2600)
    shift = animation("IdleShift", IDLE_BEATS["shift"].anim, d, {
        BODY_NODE: {X: [(0, 0, BACK_IN_OUT), (beat(500), 7, None), (beat(2000), 7, SOFT_OUT), (d, 0)],
                    # The roll leaves heavy; it keeps SOFT_OUT home because an elastic release over
                    # this 50-frame leg measures LIVELIER than the bezier, not heavier - the spring
                    # front-loads a fifth of the travel into one frame where SOFT_OUT spreads it.
                    ROT: [(0, 0, BACK_SOFT), (beat(500), rad(-2), None), (beat(2000), rad(-2), SOFT_OUT), (d, 0)]},
        FACE: {X: [(0, 0, BACK_IN_OUT), (beat(500), 9, None), (beat(2000), 9, SOFT_OUT), (d, 0)]}})
    # Look-around: the whole face turns to one side, pauses, sweeps to the other, comes home.
    d = beat(2400)
    # The sweep across is the longest single joystick move in the rig; it is charted for the same
    # reason the wander beats are, and only the last leg home springs.
    lookaround = animation("IdleLookAround", IDLE_BEATS["lookaround"].anim, d, {JOYSTICK: {
        JX: (travel(0, 0, beat(500), -0.6)[:-1] + [(beat(500), -0.6, None)]
             + travel(beat(1000), -0.6, beat(1600), 0.55)[:-1] + [(beat(1600), 0.55, None)]
             + travel(beat(1900), 0.55, beat(2250), -0.06)[:-1]
             + [(beat(2250), -0.06, ELASTIC_HEAVY), (d, 0)])}})
    return ([animation("IdleWait" + str(k), w.anim, frames(w.ms), {}) for k, w in enumerate(IDLE_WAITS)]
            + [glance, stretch, tilt, bounce, shiver, sigh, wobble, shift, lookaround])
