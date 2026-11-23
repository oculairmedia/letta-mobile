"""Loop lag: a looping key list played a few frames late, and the exact ease split it needs.

The overlap of the sustained loops (letta-mobile-su08n): the face plays the body's motion a beat
behind it. Rotating a loop's keys round its period cuts one segment in two; `split_ease` cuts that
segment's cubic ease exactly, so the lagged loop is the original motion with one key more - no
sampled ticks (they cost the web bundle budget) and no approximation.

    lagged(keys, lag, period)    the key list, `lag` frames late, anchored at the original's frame 0
    split_ease(bezier, u)        an ease cut at time fraction u: the two halves' eases, the value there

Keys are rml.py's: (frame, value[, bezier]); a key without a bezier plays on rml's animation default
(SOFT_OUT). Pure math on key lists; rig/motion.py's `loop_lag` is the one caller.
"""
from typing import NamedTuple

import timeline
from rml import LINEAR, SOFT_OUT


def ease_of(bezier):
    """An rml bezier token as timeline's Ease, so a key list can be sampled like the file plays it."""
    if not bezier:
        return timeline.HOLD
    if bezier == LINEAR:
        return timeline.Ease("linear")
    return timeline.Ease("cubic", tuple(float(v) for v in bezier.split()))


# --- the exact split -----------------------------------------------------------------------------
def _lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def _bezier_x_inverse(x1, x2, u):
    """The curve parameter at which a (0,0)-(1,1) ease's time reaches u (time is monotone in it)."""
    lo, hi = 0.0, 1.0
    for _ in range(60):
        t = (lo + hi) / 2
        x = 3 * (1 - t) ** 2 * t * x1 + 3 * (1 - t) * t * t * x2 + t ** 3
        lo, hi = (t, hi) if x < u else (lo, t)
    return (lo + hi) / 2


def _box(points, origin, extent):
    """Control points renormalised into the unit box from `origin` across `extent`, as a token."""
    def axis(v, i):
        return (v - origin[i]) / extent[i] if abs(extent[i]) > 1e-9 else v
    (_, _), p1, p2, (_, _) = points
    return " ".join(f"{round(c, 6):g}" for c in (axis(p1[0], 0), axis(p1[1], 1), axis(p2[0], 0), axis(p2[1], 1)))


def split_ease(bezier, u):
    """An ease cut at time fraction u, exactly: (the first part's ease, the second's, the value
    fraction reached at the cut). de Casteljau at the parameter where the time is u, then each half
    renormalised into its own unit box - two keys carry the cut segment with no approximation."""
    x1, y1, x2, y2 = (float(v) for v in bezier.split())
    t = _bezier_x_inverse(x1, x2, u)
    p0, p1, p2, p3 = (0.0, 0.0), (x1, y1), (x2, y2), (1.0, 1.0)
    p01, p12, p23 = _lerp(p0, p1, t), _lerp(p1, p2, t), _lerp(p2, p3, t)
    p012, p123 = _lerp(p01, p12, t), _lerp(p12, p23, t)
    cut = _lerp(p012, p123, t)
    first = _box((p0, p01, p012, cut), p0, cut)
    second = _box((cut, p123, p23, p3), cut, (1 - cut[0], 1 - cut[1]))
    return first, second, cut[1]


# --- the wrap ------------------------------------------------------------------------------------
def bezier_of(key):
    """A key's leaving curve; a key without one plays on the animation default (rml: SOFT_OUT)."""
    return key[2] if len(key) > 2 and key[2] else SOFT_OUT


class Segment(NamedTuple):
    """The segment a frame falls in: the key it leaves and the key it reaches."""
    before: tuple
    after: tuple


def _segment_at(keys, frame):
    """The segment that holds `frame` (both ends the same key when the frame is a key's)."""
    before = max((k for k in keys if k[0] <= frame), key=lambda k: k[0])
    after = before if before[0] == frame else next(k for k in keys if k[0] > frame)
    return Segment(before, after)


def _falls_on_key(segment):
    """True when the cut lands exactly on a key, so nothing has to be split."""
    return segment.before is segment.after


def _split_at(segment, frame):
    """(the value at `frame`, the first half's ease, the second half's ease) of a segment cut there."""
    a, b = segment
    first, second, fraction = split_ease(bezier_of(a), (frame - a[0]) / (b[0] - a[0]))
    return float(a[1]) + (float(b[1]) - float(a[1])) * fraction, first, second


def _rotated(keys, cut, lag, leaving):
    """Every key moved `lag` frames later round the loop: the keys from the cut on open it, the keys
    before the cut follow. `leaving` replaces one key's curve (the half of a split segment)."""
    period = cut + lag
    head = [(k[0] - cut, float(k[1]), bezier_of(k)) for k in keys if cut <= k[0] < period]
    tail = [(k[0] + lag, float(k[1]), leaving.get(id(k), bezier_of(k))) for k in keys if k[0] < cut]
    return head + tail


def _wrapped(keys, lag, period):
    """The key list rotated `lag` frames later round the loop, the segment the wrap cuts split
    exactly: [(frame, value, bezier)] from frame 0 to `period`."""
    cut = period - lag
    segment = _segment_at(keys, cut)
    if _falls_on_key(segment):
        return _rotated(keys, cut, lag, {}) + [(period, float(segment.before[1]))]
    value, first, second = _split_at(segment, cut)
    return [(0, value, second)] + _rotated(keys, cut, lag, {id(segment.before): first}) + [(period, value)]


def lagged(keys, lag, period):
    """A looping key list played `lag` frames late: value(t) = keys(t - lag), wrapped at `period`.

    Every segment keeps its own curve, shifted round the loop; the one the wrap cuts in two is split
    exactly (split_ease), so the lagged loop is the original motion a beat late, with one key more,
    and still closes on itself. It is anchored so its frame 0 is the original's: the lag is a shape,
    not an offset - it moves the whole track by at most a third of a pixel, and every rest an entry
    or a calm twin lands on is exactly what it was. A constant, or no lag, comes back unchanged."""
    if not isinstance(keys, list) or not lag or period <= 1:
        return keys
    out = _wrapped(keys, lag, period)
    anchor = float(keys[0][1]) - out[0][1]
    return [(k[0], round(k[1] + anchor, 4)) + tuple(k[2:]) for k in out]
