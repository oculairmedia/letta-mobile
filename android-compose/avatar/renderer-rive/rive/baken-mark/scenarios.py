"""Named driver sequences for the mark, as data - the mascot's `scenarios.py` model, its builders.

Every step is one CLI flag. `--data` does NOT sequence (the CLI applies every `--data` before the
run), so the shared `scenario()` constructor refuses a scenario that writes one property twice.
Every state is therefore entered from the view model at load - which is exactly how the layers'
conditional entries start it - and a transition BETWEEN two states is read as two legs, or from
the authored keys (`python ../mascot/timeline.py` reads any scene.rml).

    python scenarios.py               # list them
    python scenarios.py connecting    # the flags one expands to
"""
import sys

import rig

_m = rig.mascot_module("scenarios")
Scenario, scenario, data, advance, DURATION, BEAT = (_m.Scenario, _m.scenario, _m.data, _m.advance,
                                                     _m.DURATION, _m.BEAT)

STATE_FRAMES = {"idle": 330, "loading": 100, "connecting": 140, "degraded": 200, "ambient": 330}

SCENARIOS = {s.name: s for s in [
    *[scenario(st, (data(f"state={st}"), advance(n)), note=f"one full {st} cycle from a cold start")
      for st, n in STATE_FRAMES.items()],
    scenario("boot", (data("boot=true"), advance(90)),
             note="the splash entrance, fired before the first advance (conditional entry)"),
    scenario("success", (data("success=true"), advance(80)),
             note="the peer-lock settle: dip, lift-and-tuck, warm bloom"),
    scenario("hover", (data("hovered=true"), advance(30)), note="the 2 deg lean perk"),
    scenario("theme-light", (data("theme=light"), advance(10)),
             note="light palette from frame 0 (no dark flash: conditional entry)"),
    *[scenario(f"reduce-motion-{st}", (data("reduceMotion=true"), data(f"state={st}"), advance(600)),
               note=f"10 s of {st} with reduceMotion: a static pose, no loop")
      for st in STATE_FRAMES],
    scenario("reduce-motion-boot", (data("reduceMotion=true"), data("boot=true"), advance(60)),
             note="boot with reduceMotion: a fade, no seat or roll"),
    scenario("reduce-motion-success", (data("reduceMotion=true"), data("success=true"), advance(60)),
             note="success with reduceMotion: a warm bloom, no dip or lift"),
]}

GOLDEN = ["idle", "loading", "connecting", "degraded", "ambient", "boot", "success", "hover",
          "reduce-motion-connecting", "reduce-motion-degraded", "reduce-motion-boot",
          "reduce-motion-success"]


def get(name):
    if name in SCENARIOS:
        return SCENARIOS[name].checked()
    if name.startswith(BEAT):
        anim = name[len(BEAT):]
        return scenario(name, (advance(DURATION),), solo=anim, note=f"{anim} on its own, through --solo")
    raise SystemExit(f"unknown scenario {name!r}; try one of: {', '.join(sorted(SCENARIOS))}, or beat:<Animation>")


def names():
    return sorted(SCENARIOS)


if __name__ == "__main__":
    if len(sys.argv) > 1:
        s = get(sys.argv[1])
        print(f"{s.name}\n  {s.note}\n  " + " ".join(s.flags(duration=60)))
    else:
        width = max(len(n) for n in names())
        for n in names():
            print(f"{n:<{width}}  {SCENARIOS[n].note}")
