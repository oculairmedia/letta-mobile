"""The exposure sheet: frames down, levels across, read straight out of scene.rml.

    python xsheet.py --animation IdleBounce
    python xsheet.py --animations Enter_idle_listening,StateListening
    python xsheet.py --animation SuccessFlash --wide        # every level, in groups

An X-sheet is the animator's score. One row per frame of the span, one column per level - a
level here is an (object, property) any of the named animations keys - and in each cell the
value, the per-frame delta, and what kind of drawing it is:

    [K]  an extreme: the first key, the last key, or a key the curve turns round at
    (B)  a breakdown: an authored key that is not an extreme
     -   an in-between: no key here, the interpolator made this one

The driver column carries what is not a number: KeyFrameCallback fires (the plate blink) and the
plate's expression flips (NESTED_VALUE keys). Under the table each level gets its timing chart
(rig/chart.py), and the footer gives every level's spacing class - the answer to "is this beat
eased or is it floating?" in one line per level.

Values are authored values: what the generator asked for. Telemetry - what the runtime actually
played - arrives with step C of MOTION-PIPELINE.md, behind `--scenario`.

Standard library only. Read-only: it never writes.
"""
import argparse
import os
import sys
import textwrap

import timeline
from rig.chart import Chart

FPS = timeline.FPS
TERM = 120                       # keep a row inside a 120-column terminal
NESTED_VALUE, NESTED_FIRE = 239, 401
DEG = 180.0 / 3.141592653589793


# --- levels --------------------------------------------------------------------------------------

class Level:
    """One (object, property) column: its authored keys, its per-frame values and its marks."""

    def __init__(self, obj_label, prop_key, keys, anim_name):
        self.object, self.prop_key, self.keys, self.anim = obj_label, prop_key, keys, anim_name
        self.prop = timeline.prop_name(prop_key)
        self.rot = prop_key in timeline.ROTATION_KEYS
        self.label = f"{obj_label} {self.prop}" + (" deg" if self.rot else "")
        self.also = []               # other animations keying the same level
        self.chart = Chart.of(keys)

    def value(self, frame):
        v, _elastic = timeline.sample(self.keys, frame)
        v = v * DEG if self.rot else v
        return 0.0 if abs(v) < 5e-3 else v      # no '-0' rows

    def mark(self, frame):
        """'[K]' extreme, '(B)' interior key, '' in-between."""
        frames = [k.frame for k in self.keys]
        if frame not in frames:
            return ""
        i = frames.index(frame)
        if i == 0 or i == len(frames) - 1:
            return "[K]"
        before = self.keys[i].value - self.keys[i - 1].value
        after = self.keys[i + 1].value - self.keys[i].value
        if before == 0 and after == 0:
            return "(B)"
        return "[K]" if before * after <= 0 else "(B)"


def collect(scene, anims):
    """(levels, drivers, span) for the named animations. Drivers are frame -> [text]."""
    levels, drivers, span = [], {}, 0
    by_key = {}
    for name, anim in anims:
        span = max(span, int(float(anim.get("duration", 0))))
        for ko in anim.findall("KeyedObject"):
            label = scene.label(ko.get("objectId"))
            for kp in ko.findall("KeyedProperty"):
                try:
                    prop_key = int(kp.get("propertyKey"))
                except (TypeError, ValueError):
                    continue
                keys = timeline.read_keys(kp)
                if not keys:
                    continue
                span = max(span, keys[-1].frame)
                if prop_key == NESTED_FIRE:
                    for k in keys:
                        drivers.setdefault(k.frame, []).append(f"{label} fire")
                    continue
                if prop_key == NESTED_VALUE:
                    for k in keys:
                        drivers.setdefault(k.frame, []).append(f"{label}={timeline.num(k.value)}")
                    continue
                if not all(k.kind == "double" for k in keys) or len(keys) < 2:
                    # Not a level: a one-key static pose, a glyph swap, a colour. It still
                    # happened on a frame, so it belongs in the driver column.
                    prop = timeline.prop_name(prop_key)
                    for k in keys:
                        drivers.setdefault(k.frame, []).append(
                            f"{label} {prop}={timeline.value_text(k, scene)}")
                    continue
                ident = (ko.get("objectId"), prop_key)
                if ident in by_key:
                    by_key[ident].also.append(name)
                    continue
                level = Level(label, prop_key, keys, name)
                by_key[ident] = level
                levels.append(level)
    return levels, drivers, span


# --- rendering -----------------------------------------------------------------------------------

def delta_text(prev, cur):
    if prev is None:
        return ""
    d = cur - prev
    if abs(d) < 5e-3:
        return "."
    return ("+" if d > 0 else "-") + timeline.num(abs(d), 2)


def cells(level, span):
    """(mark, value, delta) per frame, 0..span."""
    out, prev = [], None
    for f in range(span + 1):
        v = level.value(f)
        out.append((level.mark(f), timeline.num(v, 2), delta_text(prev, v)))
        prev = v
    return out


def column_width(level, rows):
    wv = max([len(v) for _m, v, _d in rows] + [1])
    wd = max([len(d) for _m, _v, d in rows] + [1])
    return max(len(level.label), 3 + 1 + wv + 1 + wd), wv, wd


def group_levels(levels, widths, driver_w, wide):
    """Chunk the levels into groups that fit a 120-column row."""
    budget = TERM - (5 + 2) - (driver_w + 2)
    groups, cur, used = [], [], 0
    for level in levels:
        w = widths[level][0] + 2
        if cur and used + w > budget:
            groups.append(cur)
            cur, used = [], 0
        cur.append(level)
        used += w
    if cur:
        groups.append(cur)
    return groups if wide else groups[:1], groups


def print_sheet(scene, anims, wide=False, chart_width=40, window=None):
    names = [n for n, _a in anims]
    levels, drivers, span = collect(scene, anims)
    first, last = (0, span) if window is None else (max(0, window[0]), min(span, window[1]))
    print(f"x-sheet  {', '.join(names)}   [{scene.animations[0][0] if scene.animations else '?'}]")
    print(f"  {span + 1} frames (0..{span}, {timeline.num(span / FPS * 1000.0, 1)} ms at {int(FPS)} fps), "
          f"{len(levels)} levels, authored values"
          + (f"; showing {first}..{last}" if (first, last) != (0, span) else ""))
    if not levels:
        print("  (no numeric levels - a rest/placeholder timeline)")
        return
    rows = {level: cells(level, span) for level in levels}
    widths = {level: column_width(level, rows[level]) for level in levels}
    driver_texts = {f: ", ".join(v) for f, v in drivers.items()}
    driver_w = min(26, max([len(t) for t in driver_texts.values()] + [len("driver")]))
    shown, allgroups = group_levels(levels, widths, driver_w, wide)

    for gi, group in enumerate(shown):
        print()
        if len(allgroups) > 1:
            print(f"  levels {gi + 1}/{len(allgroups)}")
        head = f"  {'frame':>5}  {'driver':<{driver_w}}"
        for level in group:
            head += f"  {level.label:<{widths[level][0]}}"
        print(head.rstrip())
        for f in range(first, last + 1):
            line = f"  {f:>5}  {driver_texts.get(f, '')[:driver_w]:<{driver_w}}"
            for level in group:
                w, wv, wd = widths[level]
                mark, val, dl = rows[level][f]
                cell = f"{mark or '-':<3} {val:>{wv}} {dl:>{wd}}"
                line += f"  {cell:<{w}}"
            print(line.rstrip())
        print()
        print(f"  {'spacing':>5}")
        for level in group:
            print(f"  {level.label:<{max(len(l.label) for l in group)}}  {level.chart.text(chart_width)}")

    if len(allgroups) > len(shown):
        rest = [level.label for g in allgroups[len(shown):] for level in g]
        print()
        print(f"  {len(rest)} more levels - rerun with --wide:")
        for line in textwrap.wrap(", ".join(rest), TERM - 6):
            print(f"    {line}")

    print()
    print("  footer: spacing class per level")
    wl = max(len(l.label) for l in levels)
    for level in levels:
        extra = f"   also keyed by {', '.join(level.also)}" if level.also else ""
        print(f"    {level.label:<{wl}}  {level.chart.spacing:<8} {len(level.keys)} keys  "
              f"[{level.anim}]{extra}")


# --- main ----------------------------------------------------------------------------------------

def find(scene, names):
    hits = []
    for want in names:
        match = [(a.get("name"), a) for _b, a in scene.animations if a.get("name") == want]
        if not match:
            match = [(a.get("name"), a) for _b, a in scene.animations
                     if want.lower() in (a.get("name") or "").lower()]
        if not match:
            print(f"no animation matching {want!r}; try `python timeline.py --list`")
            return None
        hits.extend(match[:1])
    return hits


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--rml", default=os.path.join(here, "scene.rml"), help="the document to read")
    p.add_argument("--animation", help="one animation name")
    p.add_argument("--animations", help="comma-separated animation names, sheeted together")
    p.add_argument("--scenario", help="a named driver sequence (telemetry track, step C)")
    p.add_argument("--wide", action="store_true", help="every level, wrapped into groups")
    p.add_argument("--chart-width", type=int, default=40, help="width of the timing charts")
    p.add_argument("--range", help="only these frames, FIRST:LAST (the whole span by default)")
    args = p.parse_args(argv)

    if args.scenario:
        print("telemetry not wired yet")
        return 0
    names = []
    if args.animation:
        names.append(args.animation)
    if args.animations:
        names.extend(n.strip() for n in args.animations.split(",") if n.strip())
    if not names:
        p.print_help()
        return 2

    scene = timeline.Scene(args.rml)
    anims = find(scene, names)
    if anims is None:
        return 1
    window = None
    if args.range:
        try:
            a, b = args.range.split(":")
            window = (int(a), int(b))
        except ValueError:
            print("--range wants FIRST:LAST, e.g. --range 0:60")
            return 2
    print_sheet(scene, anims, wide=args.wide, chart_width=args.chart_width, window=window)
    return 0


if __name__ == "__main__":
    sys.exit(main())
