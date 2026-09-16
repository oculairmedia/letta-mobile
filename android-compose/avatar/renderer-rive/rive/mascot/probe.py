"""Pose telemetry: run a scenario headless and read the motion back as numbers.

The pencil test, without pixels. `gen_scene.py --probe` binds a set of node properties two-way
into view-model numbers (see `rig/probe.py`); the CLI's `--data-dump` then prints them every
frame. One run of about a second returns the real, mixed, post-state-machine value of every
probed property for every frame of a scenario - which is what the authored keyframes cannot tell
you, because the keys say what was asked for and telemetry says what happened.

    python probe.py success                       # sparklines + signatures
    python probe.py conversation --every 2        # one run, four entries
    python probe.py beat:IdleBounce               # one animation, through --solo
    python probe.py enter-listening --json build/probe/enter-listening.json --sheet
    python probe.py success --golden              # write goldens/success.json

Output, one line per property:

    Body.y  ▁▁▂▅▇▇▅▂▁▁  peak -48.0 @19  settle 57  max|Δ| 21.0  spacing ease-in

peak is the sample furthest from rest and the frame it happens on; settle is the last frame the
value is still outside 2 % of the span of the whole move; max|Δ| is the largest per-frame change
(a wall in the bars is a snap); spacing is the Disney class read off the velocity distribution.

Never writes scene.rml: the probed document is built into a temp project, with a `rive.yaml`
whose `push:` section is stripped, so a stray push cannot happen from there.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

# The sparklines are Unicode; a Windows console defaults to cp1252 and would abort the report.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

import scenarios
from rivecli import rive_bin, temp_project

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "build", "probe")
GOLDENS = os.path.join(HERE, "goldens")
BARS = "▁▂▃▄▅▆▇█"
SPARK_WIDTH = 48
SETTLE_TOL = 0.02          # "settled" = within 2 % of the span of the move
FLAT = 1e-6


# --- the probed project --------------------------------------------------------------------------
def probe_project(into, solo=None):
    """Write a complete probed Rive project into `into`; return (dir, solo duration in frames)."""
    return temp_project(into, ["--probe"], solo=solo)


def json_lines(text):
    """The JSON objects in the CLI's output, one per line that starts with '{'."""
    return [json.loads(raw) for raw in (line.strip() for line in text.splitlines()) if raw.startswith("{")]


def run_scenario(scenario, every=1, quiet=False):
    """Build the probed document, run the CLI once, return its JSON Lines as parsed dicts."""
    tmp = tempfile.mkdtemp(prefix="probe-")
    try:
        project, duration = probe_project(tmp, scenario.solo)
        flags = scenario.flags(duration)
        cmd = [rive_bin(), project, "--data-dump=-", f"--data-dump-every={int(every)}",
               "--data-dump-filter=telemetry*", "--quiet"] + flags
        if not quiet:
            print(f"{scenario.name}: {' '.join(flags)}  (every {every})")
        p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
        if p.returncode:
            raise SystemExit("rive failed:\n  " + " ".join(cmd) + "\n" + (p.stderr or p.stdout))
        lines = json_lines(p.stdout)
        if not lines:
            raise SystemExit("no telemetry: the CLI printed no JSON lines\n" + (p.stderr or p.stdout))
        return lines, duration
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# --- the dense table -----------------------------------------------------------------------------
def named_values(line, vm_to_name):
    """(probe name, float value) for every probed, valued item on one dump line."""
    named = ((vm_to_name.get(item.get("path")), item.get("value")) for item in line.get("values", ()))
    return [(name, float(value)) for name, value in named if name is not None and value is not None]


def sparse_samples(lines, vm_to_name):
    """({frame: every value known by then}, the probe names that ever had a value)."""
    seen, sparse, current = set(), {}, {}
    for line in lines:
        if line.get("kind") == "header" or "frame" not in line:
            continue
        for name, value in named_values(line, vm_to_name):
            seen.add(name)
            current[name] = value
        sparse[int(line["frame"])] = dict(current)     # a trailing full line restates its sample
    return sparse, seen


def uniform_grid(sparse, every):
    """(frames, rows) every `every` frames from the first sample to the last, values carried forward."""
    step = max(1, int(every))
    first, last = min(sparse), max(sparse)
    frames, rows, held = [], [], {}
    for f in range(first, last + 1, step):
        held.update(sparse.get(f, {}))
        frames.append(f)
        rows.append(dict(held))
    if frames[-1] != last:
        held.update(sparse[last])
        frames.append(last)
        rows.append(dict(held))
    return frames, rows


def split_seen(probes, seen):
    """(names of the probes the dump carried, names of the ones it did not), in probe order."""
    names = [p.name for p in probes]
    return [n for n in names if n in seen], [n for n in names if n not in seen]


def dense(lines, probes, every=1):
    """JSON Lines -> {frames: [...], values: {name: [...]}, missing: [...]}.

    The dump prints a header, a frame-0 "full" line, then only what CHANGED at each sample, and a
    final full line - and it prints NO line at all for a sample where nothing changed. So the
    table has to be rebuilt on a uniform grid: every `every` frames from the first sample to the
    last, with unchanged values carried forward. That carry is the whole reason this function
    exists, and getting it wrong silently shortens every signature by the held frames.
    """
    sparse, seen = sparse_samples(lines, {p.vm_name: p.name for p in probes})
    if not sparse:
        raise SystemExit("no telemetry frames in the dump")
    frames, rows = uniform_grid(sparse, every)
    names, missing = split_seen(probes, seen)
    values = {n: [row.get(n, 0.0) for row in rows] for n in names}
    return {"frames": frames, "values": values, "missing": missing,
            "units": {p.name: p.unit for p in probes if p.name in seen}}


# --- signatures ----------------------------------------------------------------------------------
def spacing(series, first=0):
    """The Disney spacing class of a telemetry series: 'ease-in' | 'ease-out' | 's' | 'linear' |
    'hold' | 'pop'.

    `rig/chart.py` owns the rule (MOTION-PIPELINE section 2 item 2), so the charts, the exposure
    sheet and these signatures all read a curve the same way. Uniform ticks - 'linear' - are the
    tell for floating; 'pop' is one frame carrying the whole move.
    """
    from rig.chart import Chart
    if len(series) < 2:
        return "hold"
    return Chart.of_samples(series, first=first).spacing


def signature(table):
    """{property: {peak, peak_frame, overshoot_pct, settle_frame, max_delta, spacing}}.

    peak          the sample furthest from rest (the first value), and the frame it lands on
    overshoot_pct how far past the resting-to-final step the peak went; 0 for a self-returning
                  beat, where the peak IS the motion and there is no step to overshoot
    settle_frame  the last frame the value is still outside 2 % of the span of the whole move
    max_delta     the largest change between two adjacent FRAMES (samples / --every)
    """
    frames = table["frames"]
    return {name: series_signature(frames, series) for name, series in table["values"].items()}


def overshoot_pct(series, peak, span):
    """How far past the resting-to-final step the peak went, in percent; 0 when there is no step.

    Overshoot only means something when the move IS a step to a new resting value. A
    self-returning beat (the success hop) ends where it started, and a loop that drifts (the
    breath) ends a hair away from it: neither is a step, and reading the hop's -48 against the
    breath's -1.9 would print a meaningless 2400 %."""
    rest, step = series[0], series[-1] - series[0]
    if abs(step) <= FLAT or abs(step) < 0.2 * span:
        return 0.0
    return max(0.0, ((peak - rest) / step - 1.0) * 100.0)


def settle_frame(frames, series, span):
    """The last frame the value is still outside 2 % of the span of the whole move."""
    tol = max(span * SETTLE_TOL, FLAT)
    outside = [f for f, v in zip(frames, series) if abs(v - series[-1]) > tol]
    return outside[-1] if outside else frames[0]


def per_frame_deltas(frames, series):
    """|change| between adjacent samples, each divided by its own frame interval (the grid's last
    step can be shorter than --every)."""
    return [abs(b - a) / max(1, fb - fa) for (a, b), (fa, fb) in zip(zip(series, series[1:]), zip(frames, frames[1:]))]


def series_signature(frames, series):
    """One property's signature - see `signature`."""
    span = max(series) - min(series)
    i = max(range(len(series)), key=lambda k: abs(series[k] - series[0]))
    peak = series[i]
    deltas = per_frame_deltas(frames, series)
    return {"peak": round(peak, 4), "peak_frame": frames[i],
            "overshoot_pct": round(overshoot_pct(series, peak, span), 2),
            "settle_frame": settle_frame(frames, series, span),
            "max_delta": round(max(deltas) if deltas else 0.0, 4),
            "spacing": spacing(series, frames[0]), "range": round(span, 4)}


# --- printing ------------------------------------------------------------------------------------
def sparkline(series, width=SPARK_WIDTH):
    """Eight-level Unicode bars of DISTANCE FROM REST, so a beat reads as a hump whichever way it
    goes and a 48 px rise and a 48 px drop look the same. Buckets when the run is longer than
    `width`, keeping the largest excursion in each bucket so a one-frame snap survives."""
    if not series:
        return ""
    rest = series[0]
    mag = [abs(v - rest) for v in series]
    hi = max(mag)
    if hi <= FLAT:
        return BARS[0] * min(len(series), width)
    n = min(width, len(series))
    out = []
    for i in range(n):
        a, b = i * len(series) // n, max(i * len(series) // n + 1, (i + 1) * len(series) // n)
        out.append(BARS[min(7, int(max(mag[a:b]) / hi * 7.999))])
    return "".join(out)


def print_report(scenario, table, sig):
    frames = table["frames"]
    print(f"\n  {scenario.name}: frames {frames[0]}..{frames[-1]} "
          f"({len(frames)} samples)  {scenario.note}")
    if table["missing"]:
        print("  not bindable (no value in the dump, dropped): " + ", ".join(table["missing"]))
    collapsed = scenario.collapsed()
    if collapsed:
        print("  WARNING: the CLI applies every --data before the run, so this scenario's repeated"
              f" writes to {', '.join(collapsed)} collapse to the last one. What ran is not what"
              " the step list reads like; probe one leg per run instead.")
    width = max(len(n) for n in table["values"]) if table["values"] else 0
    for name, series in table["values"].items():
        s = sig[name]
        unit = table["units"][name]
        flat = s["range"] <= FLAT
        note = "  (flat)" if flat else ""
        over = f"  overshoot {s['overshoot_pct']:.0f}%" if s["overshoot_pct"] > 1 else ""
        print(f"  {name:<{width}}  {sparkline(series)}  peak {s['peak']:>9.3f}{unit:<5} @{s['peak_frame']:<4}"
              f" settle {s['settle_frame']:<4} max|Δ| {s['max_delta']:>7.3f}  spacing {s['spacing']}{over}{note}")


def print_sheet(table, limit=None):
    """The exposure sheet: frames down, properties across, value and the delta SINCE THE ROW
    ABOVE (so a thinned sheet still adds up). Only the levels that move get a column.

    xsheet.py does this properly - K / B marks, seams, authored versus actual; this is the raw
    grid, and the `--json` table is what that tool will read.
    """
    frames, values = table["frames"], table["values"]
    names = [n for n in values if max(values[n]) - min(values[n]) > FLAT] or list(values)
    cell = 16
    print("\n  frame " + " ".join(f"{n[:cell]:>{cell}}" for n in names))
    rows = list(range(len(frames)))
    if limit and len(rows) > limit:
        step = len(rows) // limit + 1
        rows = rows[::step] + ([rows[-1]] if rows[-1] % step else [])
    previous = None
    for i in rows:
        cells = []
        for n in names:
            v = values[n][i]
            d = 0.0 if previous is None else v - values[n][previous]
            cells.append(f"{v:>8.2f}{('%+.2f' % d) if abs(d) > 5e-3 else '     ':>8}")
        print(f"  {frames[i]:>5} " + " ".join(cells))
        previous = i


# --- main ----------------------------------------------------------------------------------------
def probe(name, every=1, quiet=False):
    """-> (scenario, dense table, signature). The one call the tests and xsheet.py use."""
    from rig.probe import PROBES
    scenario = scenarios.get(name)
    lines, _ = run_scenario(scenario, every, quiet)
    table = dense(lines, PROBES, every)
    return scenario, table, signature(table)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("scenario", help="a name from scenarios.py, or beat:<AnimationName>")
    p.add_argument("--every", type=int, default=1, help="sample every N frames (1)")
    p.add_argument("--json", help="write the dense table + signatures here")
    p.add_argument("--sheet", action="store_true", help="also print the exposure-sheet grid")
    p.add_argument("--rows", type=int, default=0, help="cap the sheet at N rows (0 = every frame)")
    p.add_argument("--golden", action="store_true", help="write goldens/<scenario>.json instead")
    a = p.parse_args(argv)

    scenario, table, sig = probe(a.scenario, a.every)
    print_report(scenario, table, sig)
    if a.sheet:
        print_sheet(table, a.rows or None)

    payload = {"scenario": scenario.name, "every": a.every, "solo": scenario.solo,
               "flags": scenario.flags(table["frames"][-1] or 60), "table": table, "signature": sig}
    if a.json:
        os.makedirs(os.path.dirname(os.path.abspath(a.json)) or ".", exist_ok=True)
        with open(a.json, "w", encoding="utf-8", newline="\n") as f:
            json.dump(payload, f, indent=2, sort_keys=True)
            f.write("\n")
        print("\nwrote", a.json)
    if a.golden:
        os.makedirs(GOLDENS, exist_ok=True)
        out = os.path.join(GOLDENS, a.scenario.replace(":", "-") + ".json")
        with open(out, "w", encoding="utf-8", newline="\n") as f:
            json.dump({"scenario": scenario.name, "every": a.every,
                       "frames": table["frames"], "signature": sig}, f, indent=2, sort_keys=True)
            f.write("\n")
        print("\nwrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
