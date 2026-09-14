"""The motion library: sustained loops, entries, momentary flashes, idle beats, wander."""
from rml import *  # noqa: F401,F403
from rig.constants import *  # noqa: F401,F403
from rig.body import (  # noqa: F401
    BREATHING, BREATH_MS, BREATH_PX, BREATH_SCALE, G_END_X, G_END_Y, G_START_X, G_START_Y, HALO_FAILED,
    HALO_OPACITY, HALO_SLEEP, INFLATE_NODE, LUMEN, LUMEN_PEAK, LUMEN_R, LUMEN_REST, LUMEN_Y0, LUMEN_Y1,
    bone_pose, breath, breath2, breath_scale, lumen_keys, shape_keys, sine, squash,
)
from rig.face import TURN_PX, TURN_ROT, spin_keys  # noqa: F401


def wander_animations():
    glance = animation("WanderGlance", WANDER_GLANCE, beat(1600), {JOYSTICK: {
        JX: [(0, 0, BACK_IN_OUT), (beat(450), -0.8, None), (beat(900), -0.8, BACK_IN_OUT), (beat(1250), 0.4, ELASTIC_SOFT), (beat(1600), 0)]}})
    peek = animation("WanderPeek", WANDER_PEEK, beat(1400), {JOYSTICK: {
        JY: [(0, 0, BACK_IN_OUT), (beat(400), 0.7, None), (beat(900), 0.7, ELASTIC_SOFT), (beat(1400), 0)],
        JX: [(0, 0, BACK_IN_OUT), (beat(400), 0.3, None), (beat(900), 0.3, ELASTIC_SOFT), (beat(1400), 0)]}})
    spin_k = spin_keys(0, beat(700))
    d7 = beat(700)
    spin_k.update(squash(INFLATE_NODE, [(0, 1, BACK_IN), (round(d7 * 0.25), 1.05, STANDARD), (round(d7 * 0.62), 1.05, ELASTIC_SOFT), (d7, 1)]))
    spin = animation("WanderSpin", WANDER_SPIN, d7, spin_k)
    # Asleep: one slow, small shift every ~30 s, nothing else.
    sleep_shift = animation("WanderSleepShift", WANDER_SLEEP_SHIFT, beat(3000), {JOYSTICK: {
        JX: [(0, 0.4, SINE), (beat(1500), 0.22, SINE), (beat(3000), 0.4)],
        JY: [(0, 0.5, SINE), (beat(1500), 0.62, SINE), (beat(3000), 0.5)]}})
    return ([animation("WanderWait" + str(k), aid, frames(ms), {}) for k, (aid, _, ms) in enumerate(WANDER_WAITS)]
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
    return {"idle": (-0.15, 0), "listening": (0, 0), "thinking": (-0.6, -0.2), "waitingInput": (0, 0),
              "speaking": (0.15, 0), "error": (-0.3, 0.25), "sleeping": (0.4, 0.5), "loading": (0, 0),
              "failed": (0, 0.1), "degraded": (0.5, -0.1)}


def sustained_animations():
    """SPEC section 1: root motion (Body and Face move together), plate rotation/offset, tint,
    gloss pulse, and the glyph index. Nothing keys body scale or body vertices."""
    FACING = sustained_facing()
    ROW = {  # key: (root motion, period ms, plate rot deg, face offset, tint, gloss pulse) - SPEC 1 + 9.1
        "idle": ({"y": breath2()}, 2 * BREATH_MS, 0, (0, 0), "00000000", None),
        "listening": ({"y": breath2()}, 2 * BREATH_MS, -2, (0, -14), "00000000", None),
        "thinking": ({"x": sine(2, 3200)}, 3200, -6, (0, 0), "00000000", None),
        "waitingInput": ({"y": sine(19, 1200)}, 1200, 0, (0, -2), "00000000", None),
        "speaking": ({"y": breath2()}, 2 * BREATH_MS, 0, (0, 0), "00000000", None),
        "error": ({"y": 24}, 0, 5, (0, 4), "14000000", None),
        "sleeping": ({"y": breath2(9000)}, 18000, 3, (0, 4), "38000000", None),   # slow, deep; one inflate per two bobs
        "loading": ({}, 1400, 0, (0, 0), "10000000", [(0, 0.8, SINE), (frames(700), 1.0, SINE), (frames(1400), 0.8)]),
        "failed": ({}, 0, 0, (0, 0), "66808080", None),
        "degraded": ({}, 0, 4, (0, 0), "00000000", None),
    }
    out = []
    for st in SUSTAINED:
        motion, period, rot, (fx, fy), tint, gloss = ROW[st]

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
        bs = breath_scale(*BREATHING[st]) if st in BREATHING else 1
        objs = {BODY_NODE: body_keys, FACE: face_keys, TINT: {COLOR: tint}, PLATE_EXPR: {NESTED_VALUE: EXPR[st]},
                INFLATE_NODE: {SX: bs, SY: bs},
                **bone_pose([(0, 1, 0, 0)]),
                LUMEN: lumen_keys(BREATHING[st][0], LUMEN_PEAK * (0.6 if st == "sleeping" else 1)) if st in BREATHING else LUMEN_REST,
                GLOSS: {GRADIENT_OPACITY: gloss if gloss else 1}, JOYSTICK: {JX: jx, JY: jy}, HALO: {OPACITY: halo},
                AUTO_X: {REMAP_TIME: gx(period) if callable(gx) else gx}, AUTO_Y: {REMAP_TIME: gy(period) if callable(gy) else gy}}
        duration = frames(period) if period else 1
        out.append(animation("State" + st[0].upper() + st[1:], root_state_anim[st], duration, objs, "loop" if duration > 1 else "oneShot"))
    return out


def enter_duration(frm, to):
    """ms and bezier of the entry from `frm` to `to`: SPEC section 3 for the designed pairs,
    otherwise the target's default (sleeping settles slowly, waitingInput springs)."""
    if (frm, to) in DESIGNED_PAIRS:
        return DESIGNED_PAIRS[(frm, to)], SOFT_OUT
    return {"sleeping": (600, STANDARD), "waitingInput": (160, SPRING)}.get(to, (160, EASE_OUT))


def enter_animations():
    """One entry per pair of sustained states: a one-shot whose glyph swap hides inside a blink
    fired at frame 0 (the shutter, closed by frame 3); the plate's expression flips while the
    eye is closed and the facing travels to the target's. The SPEC section 3 pairs add designed
    body/face motion that lands on the target's rest; the generic ones key nothing else, so
    face, body and tint hold and then ease into the target over the hand-off blend."""
    F = sustained_facing()
    out = []
    for (frm, to), aid in enter_anim.items():
        fx0, fy0 = F[frm]; fx1, fy1 = F[to]
        d, bez = enter_duration(frm, to)
        n = frames(d)
        turn = BACK_OUT if abs(fx1 - fx0) + abs(fy1 - fy0) > 0.2 else bez   # a real turn lands with overshoot
        keys = {PLATE_EXPR: {NESTED_VALUE: [(0, EXPR[frm], None), (BLINK_FLIP, EXPR[to])]},
                JOYSTICK: {JX: [(0, fx0, turn), (n, fx1)], JY: [(0, fy0, turn), (n, fy1)]}}
        if (frm, to) == ("idle", "listening"):
            # SPEC 9.4: 50 ms anticipation down (+3, +1 deg), lean past to -16/-3 deg at 200 ms, settle -14/-2 deg.
            keys[FACE] = {Y: [(0, 0, EMPH_ACCEL), (frames(50), 3, EMPH_DECEL), (frames(200), -16, M3_STANDARD), (n, -14)],
                          ROT: [(0, 0, EMPH_ACCEL), (frames(50), rad(1), EMPH_DECEL), (frames(200), rad(-3), M3_STANDARD), (n, rad(-2))]}
        elif (frm, to) == ("listening", "thinking"):
            # hold the gaze 60 ms, then turn away and tilt.
            keys[JOYSTICK] = {JX: [(0, fx0, None), (frames(60), fx0, BACK_IN_OUT), (n, fx1)], JY: [(0, fy0, None), (frames(60), fy0, BACK_IN_OUT), (n, fy1)]}
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
        if turn is BACK_OUT and n >= 10:
            keys.update(squash(INFLATE_NODE, [(0, 1, EMPH_ACCEL), (3, 1.04, SOFT_OUT), (min(n - 2, 9), 0.98, SOFT_OUT), (n, 1)]))
        out.append(animation(f"Enter_{frm}_{to}", aid, n, keys, callbacks=(PLATE_BLINK,)))
    return out


def shape_animations():
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
    success_keys.update(bone_pose([(0, 1, 0, 0, EMPH_ACCEL), (f(10), 1.06, 0.008, 0, STD_DECEL), (f(22), 0.93, -0.008, 0, SOFT_OUT),
                                   (f(37.5), 1, 0, 0, EMPH_ACCEL), (f(60), 0.96, -0.006, 0, ACCEL), (f(70), 1.10, 0.012, 0, SOFT_OUT),
                                   (f(87.5), 1.02, 0.003, 0, M3_STANDARD), (S, 1, 0, 0)]))
    success_keys[INFLATE_NODE] = {SX: 1, SY: 1}
    success_keys[FACE].update(squash(FACE, [(0, 1, EMPH_ACCEL), (f(22), 0.97, SOFT_OUT), (f(37.5), 1, EMPH_ACCEL), (f(70), 1.05, ELASTIC_SOFT), (S, 1)])[FACE])
    success_keys.update(spin_keys(f(10), f(70) - f(10)))
    success = animation("SuccessFlash", SUCCESS_ANIM, S, success_keys)
    E = frames(600)
    g = lambda pct: round(E * pct / 100)
    # SPEC 9.5 endpoints
    ex = [(0, 0, STANDARD), (g(16.6667), -24, STANDARD), (g(33.3333), 24, STANDARD), (g(50), -12, STANDARD), (g(66.6667), 0, None), (E, 0)]
    ey = [(0, 0, STANDARD), (g(16.6667), 24, STANDARD), (g(33.3333), 24, STANDARD), (g(50), 24, STANDARD), (g(66.6667), 24, None), (E, 24)]
    er = [(0, 0, STANDARD), (g(16.6667), rad(-7), STANDARD), (g(33.3333), rad(7), STANDARD), (g(50), rad(-3), STANDARD), (g(66.6667), rad(5), None), (E, rad(5))]
    error_keys = {BODY_NODE: {X: ex, Y: ey}, FACE: {X: ex, Y: ey, ROT: er}, PLATE_EXPR: {NESTED_VALUE: EXPR["error"]}}
    error_keys.update(bone_pose([(0, 1, 0, 0, STANDARD), (g(16.6667), 1.07, 0, 0, STANDARD), (g(33.3333), 0.97, 0, 0, STANDARD),
                                 (g(50), 1.04, 0, 0, STANDARD), (g(66.6667), 1, 0, 0, None), (E, 1, 0, 0)]))
    error_keys[INFLATE_NODE] = {SX: 1, SY: 1}
    error = animation("ErrorFlash", ERROR_ANIM, E, error_keys)
    drag_keys = {PLATE_EXPR: {NESTED_VALUE: EXPR["dragged"]}, INFLATE_NODE: {SX: 1, SY: 1}}
    drag_keys.update(bone_pose([(0, round(1 / 0.92, 4), -0.008, 0, None)]))   # vertical compression, horizontal spread
    drag = animation("Dragged", DRAG_ANIM, 1, drag_keys)
    return [animation("FlashRest", FLASH_REST_ANIM, 1, {}), success, error, animation("DragRest", DRAG_REST_ANIM, 1, {}), drag]


def idle_variety_animations():
    m, h, r = beat(250), beat(650), beat(300)
    glance = animation("IdleGlance", IDLE_GLANCE_ANIM, m + h + r, {
        FACE: {ROT: [(0, 0, BACK_IN_OUT), (m, rad(2), None), (m + h, rad(2), ELASTIC_SOFT), (m + h + r, 0)],
               X: [(0, 0, BACK_IN_OUT), (m, 2, None), (m + h, 2, ELASTIC_SOFT), (m + h + r, 0)]}})
    # Stretch: a slow tall stretch (volume kept), face rides up, then a soft elastic settle.
    d = beat(1400)
    stretch = animation("IdleStretch", IDLE_BEATS["stretch"][0], d, dict(
        squash(INFLATE_NODE, [(0, 1, BACK_IN_OUT), (beat(500), 0.93, None), (beat(900), 0.93, ELASTIC_SOFT), (d, 1)]),
        **{FACE: {Y: [(0, 0, BACK_IN_OUT), (beat(500), -7, None), (beat(900), -7, ELASTIC_SOFT), (d, 0)]}}))
    # Tilt: the whole body cocks 6 degrees like a dog hearing something, holds, comes back.
    d = beat(1600)
    tilt = animation("IdleTilt", IDLE_BEATS["tilt"][0], d, {
        BODY_NODE: {ROT: [(0, 0, BACK_IN_OUT), (beat(400), rad(6), None), (beat(1100), rad(6), ELASTIC_SOFT), (d, 0)]},
        FACE: {ROT: [(0, 0, BACK_IN_OUT), (beat(400), rad(4), None), (beat(1100), rad(4), ELASTIC_SOFT), (d, 0)],
               X: [(0, 0, BACK_IN_OUT), (beat(400), 4, None), (beat(1100), 4, ELASTIC_SOFT), (d, 0)]}})
    # Bounce: anticipation squash, a small hop, landing squash, settle.
    d = beat(700)
    hop = [(0, 0, EMPH_ACCEL), (beat(120), 3, STD_DECEL), (beat(320), -14, EMPH_ACCEL), (beat(520), 2, EMPH_DECEL), (d, 0)]
    bounce = animation("IdleBounce", IDLE_BEATS["bounce"][0], d, dict(
        squash(INFLATE_NODE, [(0, 1, STANDARD), (beat(120), 1.06, STANDARD), (beat(320), 0.96, STANDARD), (beat(520), 1.05, ELASTIC_SOFT), (d, 1)]),
        **{BODY_NODE: {Y: hop}, FACE: {Y: hop}}))
    # Shiver: a quick side-to-side shake of the body, the face lagging a frame or two.
    d = beat(420)
    def shake(amp, lag):
        return [(0, 0, STANDARD)] + [(beat(60 * i) + lag, amp * (1 if i % 2 else -1) * (1 - i / 7), STANDARD) for i in range(1, 6)] + [(d, 0)]
    shiver = animation("IdleShiver", IDLE_BEATS["shiver"][0], d, {BODY_NODE: {X: shake(4, 0)}, FACE: {X: shake(3, 2)}})
    # Sigh: a slow deflate (wide squash), the face sinks, then it fills back up.
    d = beat(1800)
    sigh = animation("IdleSigh", IDLE_BEATS["sigh"][0], d, dict(
        squash(INFLATE_NODE, [(0, 1, SINE), (beat(700), 1.05, None), (beat(1100), 1.05, SOFT_OUT), (d, 1)]),
        **{FACE: {Y: [(0, 0, SINE), (beat(700), 5, None), (beat(1100), 5, SOFT_OUT), (d, 0)]}}))
    # Wobble: a decaying rock about the base, like it was nudged.
    d = beat(1300)
    rock = [(0, 0, SINE)] + [(beat(180 * k), rad(4 * (1 if k % 2 else -1) * (1 - k / 7)), SINE) for k in range(1, 6)] + [(d, 0)]
    wobble = animation("IdleWobble", IDLE_BEATS["wobble"][0], d, {BODY_NODE: {ROT: rock}, FACE: {ROT: [(f, v * 0.6) + tuple(k[2:]) for (f, v, *k) in rock]}})
    # Shift: settles its weight to one side for a while, then back.
    d = beat(2600)
    shift = animation("IdleShift", IDLE_BEATS["shift"][0], d, {
        BODY_NODE: {X: [(0, 0, BACK_IN_OUT), (beat(500), 7, None), (beat(2000), 7, SOFT_OUT), (d, 0)],
                    ROT: [(0, 0, BACK_IN_OUT), (beat(500), rad(-2), None), (beat(2000), rad(-2), SOFT_OUT), (d, 0)]},
        FACE: {X: [(0, 0, BACK_IN_OUT), (beat(500), 9, None), (beat(2000), 9, SOFT_OUT), (d, 0)]}})
    # Look-around: the whole face turns to one side, pauses, sweeps to the other, comes home.
    d = beat(2400)
    lookaround = animation("IdleLookAround", IDLE_BEATS["lookaround"][0], d, {JOYSTICK: {
        JX: [(0, 0, BACK_IN_OUT), (beat(500), -0.6, None), (beat(1000), -0.6, BACK_IN_OUT), (beat(1600), 0.55, None), (beat(2000), 0.55, ELASTIC_SOFT), (d, 0)]}})
    return ([animation("IdleWait" + str(k), aid, frames(ms), {}) for k, (aid, _, ms) in enumerate(IDLE_WAITS)]
            + [glance, stretch, tilt, bounce, shiver, sigh, wobble, shift, lookaround])
