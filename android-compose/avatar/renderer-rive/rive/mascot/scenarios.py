"""Named driver sequences: what to do to the rig, as data.

A scenario is a list of steps in order - a view-model write, a pointer gesture, or an advance -
and every step maps to one CLI flag. `probe.py` turns a scenario into an argv tail and one
headless run; `xsheet.py` names the same scenarios so the sheet and the telemetry line up.

    python scenarios.py                     # list them
    python scenarios.py enter-listening     # the flags it expands to

**`--data` does not sequence.** `--pointer` and `--advance` do - a pointer gesture happens at the
point in the run where its flag sits - but every `--data` is applied BEFORE the scene runs,
whatever its position in argv, so two writes to the same property collapse to the last one.
Measured, not assumed: `--data=state=idle --advance=40 --data=state=listening --advance=40` prints
telemetry byte-identical to `--data=state=listening --advance=80`. `collapsed()` names the
properties a scenario writes more than once and `probe.py` prints that as a warning, because the
scenario still runs - it just runs a different scenario than it reads like. Probing a real
sequence of state entries therefore needs one run per leg (or a Luau driver in the file), not one
run with four writes; MOTION-PIPELINE section 2 item 6 assumed otherwise.

Two kinds:

  driver scenarios   run the real `Avatar` state machine, so every layer is mixing and the
                     hand-offs between them are measured for real (that is the point).
  beat:<Animation>   one LinearAnimation on its own, through `gen_scene.py --solo` - the only way
                     to see a beat that normally sits behind a random wait. `advance(DURATION)`
                     is resolved to the animation's own length by the runner.
"""
import sys
from typing import NamedTuple

DURATION = "duration"     # advance(DURATION): as long as the solo animation lasts
BEAT = "beat:"


class Step(NamedTuple):
    kind: str             # data | pointer | advance
    value: object

    def flag(self, duration=None):
        if self.kind == "advance":
            n = duration if self.value is DURATION or self.value == DURATION else self.value
            if not n:
                raise SystemExit("advance(DURATION) needs a solo animation with a duration")
            return f"--advance={int(n)}"
        return f"--{self.kind}={self.value}"


def data(write):
    return Step("data", write)


def pointer(gesture):
    return Step("pointer", gesture)


def advance(frames):
    return Step("advance", frames)


class Scenario(NamedTuple):
    name: str
    steps: tuple
    solo: str = None            # a LinearAnimation name -> gen_scene.py --solo
    note: str = ""

    def flags(self, duration=None):
        return [s.flag(duration) for s in self.steps]

    def collapsed(self):
        """View-model properties this scenario writes more than once - the CLI keeps only the
        last, so these steps do not happen when the scenario reads as though they do."""
        seen, twice = set(), []
        for s in self.steps:
            if s.kind != "data":
                continue
            prop = str(s.value).split("=", 1)[0]
            if prop in seen and prop not in twice:
                twice.append(prop)
            seen.add(prop)
        return twice

    def span(self, duration=None):
        """Total frames advanced - the length of the telemetry table."""
        total = 0
        for s in self.steps:
            if s.kind == "advance":
                total += int(duration if s.value == DURATION else s.value)
        return total


HOLD = 40          # conversation: long enough for an entry one-shot to finish and the loop to show


SCENARIOS = {s.name: s for s in [
    Scenario("enter-listening", (data("state=listening"), advance(60)),
             note="idle -> listening: the Enter_idle_listening one-shot over the Expression loop"),
    Scenario("enter-thinking-from-listening",
             (data("state=listening"), advance(30), data("state=thinking"), advance(60)),
             note="MEANT to settle in listening then enter thinking - `--data` collapses, so it "
                  "probes Enter_idle_thinking; kept as a golden and as the standing evidence"),
    Scenario("success", (data("success=true"), advance(60)),
             note="the Flash layer's 800 ms hop + spin with trails, self-returning"),
    Scenario("error", (data("error=true"), advance(60)),
             note="the Flash layer's 600 ms shake"),
    Scenario("hover", (pointer("move@250,250"), advance(60)),
             note="the file's hover listener: Rest -> Perk -> Held"),
    # The file's own dragStart listener does NOT fire on a synthesised --pointer down+move: that
    # run renders pixel-identical to a plain hover, and nothing like `--data=dragged=true`. So the
    # scenario writes the contract boolean (what the app does) and keeps the gesture alongside it.
    Scenario("drag", (data("dragged=true"), pointer("down@250,250"), pointer("move@300,300"),
                      advance(60)),
             note="the Drag layer, driven by the `dragged` boolean; the pointer gesture alone "
                  "does not reach the file's listener under the CLI"),
    Scenario("conversation",
             (data("state=idle"), advance(HOLD), data("state=listening"), advance(HOLD),
              data("state=thinking"), advance(HOLD), data("state=speaking"), advance(HOLD),
              data("state=idle"), advance(HOLD)),
             note="MEANT to be four entries in one run; `--data` collapses to the last write, so "
                  "it probes the idle loop. Needs one run per leg - see collapsed()"),
]}


def get(name):
    """A scenario by name. `beat:<AnimationName>` is built on the spot and runs through --solo."""
    if name in SCENARIOS:
        return SCENARIOS[name]
    if name.startswith(BEAT):
        anim = name[len(BEAT):]
        if not anim:
            raise SystemExit("beat: needs an animation name, e.g. beat:IdleBounce")
        return Scenario(name, (advance(DURATION),), solo=anim,
                        note=f"{anim} on its own, through a solo state machine")
    raise SystemExit(f"unknown scenario {name!r}; try one of: "
                     + ", ".join(sorted(SCENARIOS)) + ", or beat:<AnimationName>")


def names():
    return sorted(SCENARIOS)


if __name__ == "__main__":
    if len(sys.argv) > 1:
        s = get(sys.argv[1])
        print(f"{s.name}{'  (solo ' + s.solo + ')' if s.solo else ''}")
        print(f"  {s.note}")
        print("  " + " ".join(s.flags(duration=60)))
    else:
        width = max(len(n) for n in names())
        for n in names():
            print(f"{n:<{width}}  {SCENARIOS[n].note}")
        print(f"{'beat:<Animation>':<{width}}  one LinearAnimation on its own "
              "(IdleBounce, IdleStretch, WanderSpin, ...)")
