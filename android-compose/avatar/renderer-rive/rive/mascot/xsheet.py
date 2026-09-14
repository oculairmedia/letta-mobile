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
from typing import NamedTuple

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


class Keyed(NamedTuple):
    """One KeyedProperty of one animation, parsed: who keys it, what, and the keys."""
    anim: str
    object_id: str
    label: str
    prop_key: int
    keys: list


def is_level(keys):
    """A level is a numeric curve with at least two keys."""
    return len(keys) >= 2 and all(k.kind == "double" for k in keys)


class Collector:
    """Accumulates levels, the driver column and the span over the named animations."""

    def __init__(self, scene):
        self.scene = scene
        self.levels, self.drivers, self.span, self.by_key = [], {}, 0, {}

    def drive(self, keys, text):
        for k in keys:
            self.drivers.setdefault(k.frame, []).append(text(k))

    def add(self, kp):
        self.span = max(self.span, kp.keys[-1].frame)
        if kp.prop_key == NESTED_FIRE:
            self.drive(kp.keys, lambda k: f"{kp.label} fire")
        elif kp.prop_key == NESTED_VALUE:
            self.drive(kp.keys, lambda k: f"{kp.label}={timeline.num(k.value)}")
        elif not is_level(kp.keys):
            # Not a level: a one-key static pose, a glyph swap, a colour. It still happened on a
            # frame, so it belongs in the driver column.
            prop = timeline.prop_name(kp.prop_key)
            self.drive(kp.keys, lambda k: f"{kp.label} {prop}={timeline.value_text(k, self.scene)}")
        else:
            self.add_level(kp)

    def add_level(self, kp):
        ident = (kp.object_id, kp.prop_key)
        if ident in self.by_key:
            self.by_key[ident].also.append(kp.anim)
            return
        level = Level(kp.label, kp.prop_key, kp.keys, kp.anim)
        self.by_key[ident] = level
        self.levels.append(level)


def keyed_properties(scene, name, anim):
    """Every KeyedProperty of `anim` with a numeric key and at least one keyframe, in document order."""
    out = []
    for ko in anim.findall("KeyedObject"):
        label = scene.label(ko.get("objectId"))
        for kp in ko.findall("KeyedProperty"):
            prop_key = timeline.property_key(kp.get("propertyKey"))
            keys = timeline.read_keys(kp) if prop_key >= 0 else []
            if keys:
                out.append(Keyed(name, ko.get("objectId"), label, prop_key, keys))
    return out


def collect(scene, anims):
    """(levels, drivers, span) for the named animations. Drivers are frame -> [text]."""
    sheet = Collector(scene)
    for name, anim in anims:
        sheet.span = max(sheet.span, int(float(anim.get("duration", 0))))
        for kp in keyed_properties(scene, name, anim):
            sheet.add(kp)
    return sheet.levels, sheet.drivers, sheet.span


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


class Layout(NamedTuple):
    """How the sheet is laid out: every group or the first, the chart width, a frame window."""
    wide: bool = False
    chart_width: int = 40
    window: tuple = None


class Sheet:
    """The collected levels with everything the table needs: cells, column widths, driver texts."""

    def __init__(self, levels, drivers, span, layout):
        self.levels, self.span, self.layout = levels, span, layout
        window = layout.window
        self.first, self.last = (0, span) if window is None else (max(0, window[0]), min(span, window[1]))
        self.rows = {level: cells(level, span) for level in levels}
        self.widths = {level: column_width(level, self.rows[level]) for level in levels}
        self.driver_texts = {f: ", ".join(v) for f, v in drivers.items()}
        self.driver_w = min(26, max([len(t) for t in self.driver_texts.values()] + [len("driver")]))

    def header(self, names, board):
        shown = f"; showing {self.first}..{self.last}" if (self.first, self.last) != (0, self.span) else ""
        print(f"x-sheet  {', '.join(names)}   [{board}]")
        print(f"  {self.span + 1} frames (0..{self.span}, {timeline.num(self.span / FPS * 1000.0, 1)} ms at "
              f"{int(FPS)} fps), {len(self.levels)} levels, authored values" + shown)

    def cell(self, level, frame):
        w, wv, wd = self.widths[level]
        mark, val, dl = self.rows[level][frame]
        cell = f"{mark or '-':<3} {val:>{wv}} {dl:>{wd}}"
        return f"  {cell:<{w}}"

    def print_table(self, group):
        head = f"  {'frame':>5}  {'driver':<{self.driver_w}}" + "".join(
            f"  {level.label:<{self.widths[level][0]}}" for level in group)
        print(head.rstrip())
        for f in range(self.first, self.last + 1):
            line = f"  {f:>5}  {self.driver_texts.get(f, '')[:self.driver_w]:<{self.driver_w}}"
            print((line + "".join(self.cell(level, f) for level in group)).rstrip())

    def print_spacing(self, group):
        print()
        print(f"  {'spacing':>5}")
        wl = max(len(l.label) for l in group)
        for level in group:
            print(f"  {level.label:<{wl}}  {level.chart.text(self.layout.chart_width)}")

    def print_groups(self):
        shown, allgroups = group_levels(self.levels, self.widths, self.driver_w, self.layout.wide)
        for gi, group in enumerate(shown):
            print()
            if len(allgroups) > 1:
                print(f"  levels {gi + 1}/{len(allgroups)}")
            self.print_table(group)
            self.print_spacing(group)
        print_hidden(allgroups[len(shown):])

    def print_footer(self):
        print()
        print("  footer: spacing class per level")
        wl = max(len(l.label) for l in self.levels)
        for level in self.levels:
            extra = f"   also keyed by {', '.join(level.also)}" if level.also else ""
            print(f"    {level.label:<{wl}}  {level.chart.spacing:<8} {len(level.keys)} keys  "
                  f"[{level.anim}]{extra}")


def print_hidden(groups):
    """The levels a narrow sheet left out, named so the reader knows to ask for --wide."""
    rest = [level.label for g in groups for level in g]
    if not rest:
        return
    print()
    print(f"  {len(rest)} more levels - rerun with --wide:")
    for line in textwrap.wrap(", ".join(rest), TERM - 6):
        print(f"    {line}")


def board_of(scene, anims):
    """The artboard the first selected animation lives on; '?' when nothing is selected."""
    first = anims[0][1] if anims else None
    return next((board for board, anim in scene.animations if anim is first), "?")


def print_sheet(scene, anims, layout=Layout()):
    levels, drivers, span = collect(scene, anims)
    sheet = Sheet(levels, drivers, span, layout)
    sheet.header([n for n, _a in anims], board_of(scene, anims))
    if not levels:
        print("  (no numeric levels - a rest/placeholder timeline)")
        return
    sheet.print_groups()
    sheet.print_footer()


# --- main ----------------------------------------------------------------------------------------

def find_one(scene, want):
    """The animation named exactly `want`, else the first whose name contains it, else None."""
    exact = [(a.get("name"), a) for _b, a in scene.animations if a.get("name") == want]
    loose = [(a.get("name"), a) for _b, a in scene.animations if want.lower() in (a.get("name") or "").lower()]
    return (exact or loose or [None])[0]


def find(scene, names):
    hits = []
    for want in names:
        hit = find_one(scene, want)
        if hit is None:
            print(f"no animation matching {want!r}; try `python timeline.py --list`")
            return None
        hits.append(hit)
    return hits


def parser():
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--rml", default=os.path.join(here, "scene.rml"), help="the document to read")
    p.add_argument("--animation", help="one animation name")
    p.add_argument("--animations", help="comma-separated animation names, sheeted together")
    p.add_argument("--scenario", help="a named driver sequence (telemetry track, step C) - not wired yet; "
                                      "use `python probe.py <scenario> --sheet`")
    p.add_argument("--wide", action="store_true", help="every level, wrapped into groups")
    p.add_argument("--chart-width", type=int, default=40, help="width of the timing charts")
    p.add_argument("--range", help="only these frames, FIRST:LAST (the whole span by default)")
    return p


def requested_names(args):
    names = [args.animation] if args.animation else []
    return names + [n.strip() for n in (args.animations or "").split(",") if n.strip()]


def parse_window(text):
    """'FIRST:LAST' -> (first, last); None for no --range; raises ValueError when unreadable."""
    if not text:
        return None
    a, b = text.split(":")
    return int(a), int(b)


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    if args.scenario:
        # Refused rather than accepted as a no-op: a script that asked for telemetry must not read
        # an exit status of 0 as having got it.
        p.error("--scenario (telemetry) is not wired into the x-sheet yet; use `python probe.py "
                f"{args.scenario} --sheet`")
    names = requested_names(args)
    if not names:
        p.print_help()
        return 2
    scene = timeline.Scene(args.rml)
    anims = find(scene, names)
    if anims is None:
        return 1
    try:
        window = parse_window(args.range)
    except ValueError:
        print("--range wants FIRST:LAST, e.g. --range 0:60")
        return 2
    print_sheet(scene, anims, Layout(args.wide, args.chart_width, window))
    return 0


if __name__ == "__main__":
    sys.exit(main())
