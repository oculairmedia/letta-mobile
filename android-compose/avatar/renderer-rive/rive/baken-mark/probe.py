"""Pose telemetry for the mark: run a scenario headless and read the motion back as numbers.

The mascot's `probe.py`, pointed at this rig: the dense table, the signatures, the sparklines and
the report are its functions, reused as they are; only the probed document (this rig's
`gen_scene.py --probe`) and the scenario list (this rig's `scenarios.py`) are the mark's.

    python probe.py connecting                 # sparklines + signatures
    python probe.py success --sheet            # plus the exposure-sheet grid
    python probe.py beat:Boot                  # one animation, through --solo
    python probe.py success --golden           # write goldens/success.json, on purpose

Never writes scene.rml: the probed document is built into a temp project whose rive.yaml has no
`push:` section.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

import rig
import scenarios

_mp = rig.mascot_module("probe")
from rivecli import animation_duration, rive_bin, without_push  # noqa: E402 - after the bootstrap

HERE = os.path.dirname(os.path.abspath(__file__))
GOLDENS = os.path.join(HERE, "goldens")

# Reused as they are.
dense, signature, print_report, print_sheet, json_lines = (_mp.dense, _mp.signature, _mp.print_report,
                                                           _mp.print_sheet, _mp.json_lines)


def temp_project(into, flags, solo=None):
    """This rig's gen_scene.py into `into` (with `flags`, and `--solo` when asked) beside an
    unpushable rive.yaml. Returns (dir, the solo animation's duration in frames or 0)."""
    rml = os.path.join(into, "scene.rml")
    args = list(flags) + (["--solo", solo] if solo else [])
    p = subprocess.run([sys.executable, os.path.join(HERE, "gen_scene.py"), rml] + args,
                       cwd=HERE, capture_output=True, text=True)
    if p.returncode:
        raise SystemExit((p.stderr or p.stdout).strip() or "gen_scene.py failed")
    duration = 0
    if solo:
        with open(rml, encoding="utf-8") as f:
            duration = animation_duration(f.read(), solo)
    with open(os.path.join(HERE, "rive.yaml"), encoding="utf-8") as f:
        kept = list(without_push(f))
    with open(os.path.join(into, "rive.yaml"), "w", encoding="utf-8", newline="\n") as f:
        f.writelines(kept)
    return into, duration


def run_scenario(scenario, every=1, quiet=False):
    tmp = tempfile.mkdtemp(prefix="bakenprobe-")
    try:
        project, duration = temp_project(tmp, ["--probe"], scenario.solo)
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


def probe(name, every=1, quiet=False):
    """-> (scenario, dense table, signature)."""
    from rig.probe import PROBES
    scenario = scenarios.get(name)
    lines, _ = run_scenario(scenario, every, quiet)
    table = dense(lines, PROBES, every)
    return scenario, table, signature(table)


def golden_path(name):
    return os.path.join(GOLDENS, name.replace(":", "-") + ".json")


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("scenario", help="a name from scenarios.py, or beat:<AnimationName>")
    p.add_argument("--every", type=int, default=1)
    p.add_argument("--json", help="write the dense table + signatures here")
    p.add_argument("--sheet", action="store_true")
    p.add_argument("--rows", type=int, default=0)
    p.add_argument("--golden", action="store_true", help="write goldens/<scenario>.json")
    a = p.parse_args(argv)

    scenario, table, sig = probe(a.scenario, a.every)
    print_report(scenario, table, sig)
    if a.sheet:
        print_sheet(table, a.rows or None)
    if a.json:
        os.makedirs(os.path.dirname(os.path.abspath(a.json)) or ".", exist_ok=True)
        with open(a.json, "w", encoding="utf-8", newline="\n") as f:
            json.dump({"scenario": scenario.name, "every": a.every, "table": table, "signature": sig},
                      f, indent=2, sort_keys=True)
            f.write("\n")
    if a.golden:
        os.makedirs(GOLDENS, exist_ok=True)
        out = golden_path(a.scenario)
        with open(out, "w", encoding="utf-8", newline="\n") as f:
            json.dump({"scenario": scenario.name, "every": a.every, "frames": table["frames"],
                       "signature": sig}, f, indent=2, sort_keys=True)
            f.write("\n")
        print("\nwrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
