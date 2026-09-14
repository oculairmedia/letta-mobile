"""Timing charts: Disney's spacing notation, as the authoring unit and as review notation.

A timing chart is the animator's margin drawing - two extremes, an optional breakdown, and the
in-between marks along the line between them. Where the marks bunch, the motion is slow; where
they spread, it is fast. Graham's point, and the reason this module exists: **spacing IS the
weight**. A bezier is a guess at spacing; a chart states it, and `Chart.keys()` turns the ticks
into real keyframes so the document carries the animator's intent, not an interpolator's opinion.

Two directions:

    Chart(extremes=[(0, 0), (22, -48)], breakdown=(8, -40), spacing="ease-in").keys()
        -> the keyframe list rml.animation() consumes: [(frame, value, bezier), ...]

    Chart.of(keys)            # an authored curve, sampled back into ticks and classified
    Chart.of_samples(values)  # a telemetry series, likewise
    chart.text()              # '[0]---(8)------[22]  ease-in'

Conventions:
  - a tick fraction is a **distance** fraction in (0, 1); the ticks themselves are evenly spaced
    in **time**. That is exactly what the margin chart draws: equal frames, unequal positions.
  - "ease-in" means slow-IN to the target (decelerating), so its marks crowd at the end;
    "ease-out" means slow-OUT of the start (accelerating), marks crowd at the start; "s" eases at
    both ends, so the middle carries the travel; "linear" is even (the tell for floating).
  - a breakdown is an anchor, not an in-between: it splits the chart into two spacing runs, each
    of which gets the pattern.
  - `Chart.of` never invents a bezier: it reports what the curve does, using timeline.py's own
    sampler, so what the sheet says is what the document plays.

Standard library only. Read-only: nothing here writes a file.

# Chart authoring example
#
# The IdleShift beat in rig/motion.py is a move-hold-return - two charts and a hold between
# them. Written with this module it would read:
#
#     out  = Chart(extremes=[(0, 0), (beat(500), 7)], spacing="ease-out")     # push off, gaining
#     back = Chart(extremes=[(beat(2000), 7), (d, 0)], spacing="ease-in")     # drift home, easing
#     shift = animation("IdleShift", IDLE_BEATS["shift"].anim, d, {
#         BODY_NODE: {X: out.keys() + back.keys()}, ...})
#
# motion.py is deliberately NOT changed to this: its four hand-authored keys (BACK_IN_OUT out,
# SOFT_OUT home) are not reproducible byte-for-byte through Chart.keys(), which emits explicit
# in-between keys with one smoothing bezier, and scene.rml must stay byte-identical this round.
# Re-authoring a beat with a chart is an output-changing change and belongs in its own PR.
"""
import sys

import timeline
from rml import SINE, Elastic

FPS = 60.0
TICKS = 3                 # the default number of in-betweens a named pattern lays down
PATTERNS = ("ease-in", "ease-out", "s", "linear")
POP_SHARE = 0.45          # one step carrying more than this much of the travel is a pop...
POP_DOMINANCE = 2.5       # ...if it also dwarfs the next largest step
FLAT = 0.04               # |mean deviation from the diagonal| below this reads as even
EVEN = 0.06               # max deviation below this reads as linear rather than s


# --- keys in, samples out ------------------------------------------------------------------------

def _ease(bezier, default=None):
    """An rml bezier token (or Elastic, or None) as a timeline.Ease."""
    if bezier is None:
        bezier = default
    if bezier is None:
        return timeline.HOLD
    if isinstance(bezier, Elastic):
        return timeline.Ease("elastic", amplitude=bezier.amplitude, period=bezier.period,
                             easing=bezier.easing)
    b = tuple(float(x) for x in str(bezier).split())
    if len(b) != 4:
        return timeline.HOLD
    return timeline.Ease("linear" if b == (0.0, 0.0, 1.0, 1.0) else "cubic", bezier=b)


def as_keys(keys, default_bezier=SINE):
    """timeline.Key list from either timeline.Keys or rml (frame, value[, bezier]) tuples."""
    out = []
    for k in keys:
        # Duck-typed, not isinstance: `python timeline.py` imports timeline twice (as __main__
        # and as the module this file reads), so the two Key classes are not the same object.
        if hasattr(k, "frame") and hasattr(k, "ease"):
            out.append(k)
            continue
        frame, value = int(round(float(k[0]))), float(k[1])
        bez = k[2] if len(k) > 2 else None
        out.append(timeline.Key(frame, value, _ease(bez, default_bezier), "double"))
    out.sort(key=lambda k: k.frame)
    return out


def sample_keys(keys, default_bezier=SINE):
    """(first frame, one value per frame) over the span the keys cover."""
    ks = as_keys(keys, default_bezier)
    if not ks:
        return 0, []
    first, last = ks[0].frame, ks[-1].frame
    return first, [timeline.sample(ks, f)[0] for f in range(first, last + 1)]


# --- classification ------------------------------------------------------------------------------

def profile(values):
    """(total travel, per-step share, deviation of the travelled curve from the diagonal).

    The travelled curve is cumulative absolute distance: monotonic even when the value turns
    round, which is what a chart measures (marks along a line, not a signed graph)."""
    steps = [abs(values[i + 1] - values[i]) for i in range(len(values) - 1)]
    total = sum(steps)
    if not steps or total <= 1e-9:
        return total, [], []
    share, run, cum = [s / total for s in steps], 0.0, []
    for s in share:
        run += s
        cum.append(run)
    m = len(share)
    return total, share, [cum[k] - (k + 1) / m for k in range(m)]


def classify(values):
    """'ease-in' | 'ease-out' | 's' | 'linear' | 'hold' | 'pop' for a per-frame value series."""
    if len(values) < 2:
        return "hold"
    total, share, dev = profile(values)
    if not share:
        return "hold"
    ordered = sorted(share, reverse=True)
    biggest, runner_up = ordered[0], (ordered[1] if len(ordered) > 1 else 0.0)
    if biggest > POP_SHARE and (runner_up <= 1e-9 or biggest / runner_up > POP_DOMINANCE):
        return "pop"
    interior = dev[:-1] or dev
    mean_dev = sum(interior) / len(interior)
    if mean_dev > FLAT:
        return "ease-in"     # travelled early, crawls home: slow-in
    if mean_dev < -FLAT:
        return "ease-out"    # holds back, then goes: slow-out
    if max(abs(d) for d in dev) < EVEN:
        return "linear"
    return "s"               # even overall but not even locally: eased at both ends


# --- the chart -----------------------------------------------------------------------------------

def _round(v, digits=4):
    r = round(float(v), digits)
    return int(r) if r == int(r) else r


class Chart:
    """Two extremes, an optional breakdown, and a spacing pattern. See the module docstring."""

    def __init__(self, extremes, breakdown=None, spacing="ease-in", smooth=SINE, ticks=TICKS):
        pair = list(extremes)
        if len(pair) != 2:
            raise ValueError("a chart has exactly two extremes")
        self.extremes = [(int(round(f)), float(v)) for f, v in pair]
        self.breakdown = None if breakdown is None else (int(round(breakdown[0])), float(breakdown[1]))
        self.smooth = smooth
        self.ticks = ticks
        self.fps = FPS
        if isinstance(spacing, str):
            self.spacing = spacing
            self.fractions = self.pattern(spacing, ticks) if spacing in PATTERNS else []
        else:
            fr = sorted(float(x) for x in spacing)
            if any(not 0.0 < x < 1.0 for x in fr):
                raise ValueError("tick fractions must lie strictly inside (0, 1)")
            self.fractions = fr
            self.spacing = classify([0.0] + fr + [1.0])
        self._samples = None     # (first frame, values) when read off a curve or telemetry
        self._marks = None       # [(frame, token)] when read off a curve

    # -- patterns ---------------------------------------------------------------------------------

    @staticmethod
    def halving(n, toward="end"):
        """The halving principle as fractions: n=3 toward the end -> [0.5, 0.75, 0.875].

        Halve the gap, then halve what is left, and again - the classic way an assistant finds
        the in-betweens of a slow-in without measuring anything."""
        fr = [1.0 - 2.0 ** -(i + 1) for i in range(n)]
        if toward == "start":
            fr = sorted(1.0 - x for x in fr)
        elif toward != "end":
            raise ValueError("toward is 'end' or 'start'")
        return fr

    @staticmethod
    def pattern(name, n=TICKS):
        """A named spacing as tick (distance) fractions for n evenly-timed in-betweens."""
        if name == "ease-in":
            return Chart.halving(n, "end")
        if name == "ease-out":
            return Chart.halving(n, "start")
        if name == "linear":
            return [(i + 1) / (n + 1.0) for i in range(n)]
        if name == "s":
            out = []
            for i in range(n):
                u = (i + 1) / (n + 1.0)
                out.append(u ** 2 / (u ** 2 + (1 - u) ** 2))
            return out
        raise ValueError(f"unknown spacing {name!r}; one of {PATTERNS} or explicit fractions")

    # -- authoring --------------------------------------------------------------------------------

    @property
    def anchors(self):
        a, b = self.extremes
        return [a, self.breakdown, b] if self.breakdown else [a, b]

    def keys(self):
        """The keyframe list rml.animation() consumes: extremes, breakdown, and one real key per
        tick. The in-betweens are keys, not a bezier's guess; `smooth` only rounds the corners
        between them. The bezier on a key shapes the segment LEAVING it (rml convention), so the
        last key carries none."""
        if not self.fractions and len(self.anchors) < 3:
            raise ValueError(f"spacing {self.spacing!r} has no ticks to lay down")
        out, seen = [], set()
        anchors = self.anchors
        for (fa, va), (fb, vb) in zip(anchors, anchors[1:]):
            if fa not in seen:
                out.append((fa, _round(va)))
                seen.add(fa)
            span = fb - fa
            for i, p in enumerate(self.fractions):
                frame = int(round(fa + span * (i + 1) / (len(self.fractions) + 1.0)))
                if frame <= fa or frame >= fb or frame in seen:
                    continue
                out.append((frame, _round(va + (vb - va) * p)))
                seen.add(frame)
        last = anchors[-1]
        if last[0] not in seen:
            out.append((last[0], _round(last[1])))
        out.sort(key=lambda k: k[0])
        return [(f, v, self.smooth) for f, v in out[:-1]] + [out[-1]]

    # -- reading ----------------------------------------------------------------------------------

    @classmethod
    def of(cls, keys, fps=FPS, default_bezier=SINE):
        """Read an authored curve back as a chart: its interior keys become the marks, its
        per-frame travel the spacing."""
        ks = as_keys(keys, default_bezier)
        if len(ks) < 2:
            raise ValueError("a chart needs at least two keys")
        first, values = sample_keys(ks, default_bezier)
        chart = cls(extremes=[(ks[0].frame, ks[0].value), (ks[-1].frame, ks[-1].value)],
                    spacing=classify(values))
        chart.fps = fps
        chart._samples = (first, values)
        marks = [(ks[0].frame, f"[{ks[0].frame}]")]
        for k in ks[1:-1]:
            marks.append((k.frame, "|"))
        marks.append((ks[-1].frame, f"[{ks[-1].frame}]"))
        chart._marks = marks
        return chart

    @classmethod
    def of_samples(cls, values, first=0, fps=FPS):
        """Read a telemetry series (one value per frame) as a chart."""
        vals = [float(v) for v in values]
        if len(vals) < 2:
            raise ValueError("a chart needs at least two samples")
        last = first + len(vals) - 1
        chart = cls(extremes=[(first, vals[0]), (last, vals[-1])], spacing=classify(vals))
        chart.fps = fps
        chart._samples = (first, vals)
        ticks = [first + round((last - first) * (i + 1) / (TICKS + 1.0)) for i in range(TICKS)]
        chart._marks = ([(first, f"[{first}]")] + [(t, "|") for t in ticks if first < t < last]
                        + [(last, f"[{last}]")])
        return chart

    def samples(self):
        """(first frame, one value per frame). Authored charts sample their own keys."""
        if self._samples is None:
            self._samples = sample_keys(self.keys(), self.smooth)
        return self._samples

    def marks(self):
        """[(frame, token)] - '[f]' an extreme, '(f)' the breakdown, '|' an in-between."""
        if self._marks is not None:
            return self._marks
        ends = {self.extremes[0][0], self.extremes[1][0]}
        bd = self.breakdown[0] if self.breakdown else None
        out = []
        for f, _v, *_rest in self.keys():
            out.append((f, f"[{f}]" if f in ends else (f"({f})" if f == bd else "|")))
        return out

    # -- the margin chart as a string ---------------------------------------------------------------

    def text(self, width=44, label=True):
        """'[0]---(8)------[22]  ease-in' - marks placed by distance travelled, not by frame."""
        first, values = self.samples()
        total, share, _dev = profile(values)
        marks = self.marks()
        suffix = f"  {self.spacing}" if label else ""
        if not share:
            body = marks[0][1] + "=" * max(1, width - len(marks[0][1]) - len(marks[-1][1])) + marks[-1][1]
            return body + suffix
        cum, run = {first: 0.0}, 0.0
        for i, s in enumerate(share):
            run += s
            cum[first + i + 1] = run
        placed = [(cum.get(min(max(f, first), first + len(values) - 1), 0.0), token) for f, token in marks]
        return _lay_out(placed, width) + suffix

    def __str__(self):
        return self.text()

    def __repr__(self):
        return f"<Chart {self.extremes[0]} -> {self.extremes[1]} {self.spacing}>"


def _lay_out(items, width):
    """Tokens at their fraction along a line of dashes, centred so even ticks read as even."""
    items = sorted(items, key=lambda it: it[0])
    if not items:
        return ""
    lo = len(items[0][1]) // 2
    hi = max(lo + 1, width - len(items[-1][1]) + len(items[-1][1]) // 2)
    line, cursor = [" "] * (hi + len(items[-1][1]) + 2), -1
    for pos, token in items:
        centre = int(round(lo + max(0.0, min(1.0, pos)) * (hi - lo)))
        start = max(centre - len(token) // 2, cursor + 1, 0)
        for i, ch in enumerate(token):
            line[start + i] = ch
        cursor = start + len(token) - 1
    for i in range(cursor + 1):
        if line[i] == " ":
            line[i] = "-"
    return "".join(line[:cursor + 1])


# --- a quick look --------------------------------------------------------------------------------

def main(argv=None):
    """python -m rig.chart [pattern ...] - print the named patterns as charts."""
    names = list(argv if argv is not None else sys.argv[1:]) or list(PATTERNS)
    for name in names:
        c = Chart(extremes=[(0, 0), (24, 100)], spacing=name)
        print(f"{name:>9}  {c.text()}")
        print(f"{'':>9}  keys {[(f, v) for f, v, *_ in c.keys()]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
