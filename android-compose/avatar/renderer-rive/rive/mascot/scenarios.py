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
telemetry byte-identical to `--data=state=listening --advance=80`. MOTION-PIPELINE section 2 item
6 assumed otherwise.

So a scenario is not allowed to pretend. The `scenario()` constructor REFUSES a step list that
writes one view-model property twice: it raises with the property named and says what to do instead
(one run per leg, or a Luau driver in the file). That is why there is no `conversation` scenario
and no `enter-thinking-from-listening` - both were four writes to `state` that ran as one, and
both are now what they actually were: `enter-thinking` is idle -> thinking, which is the only
entry a driver scenario can reach, because the Expression layer always starts in `StateIdle`.

A generic entry FROM somewhere other than idle is probed as `beat:Enter_<from>_<to>` instead: the
animation on its own, through `--solo`, where frame 0 is the pose the entry starts from. That is
the honest reading of an entry the state machine cannot be driven into from a cold start.
`collapsed()` stays as the check behind the constructor, and probe.py keeps its warning for a
Scenario built by hand somewhere else.

Two kinds:

  driver scenarios   run the real `Avatar` state machine, so every layer is mixing and the
                     hand-offs between them are measured for real (that is the point).
  beat:<Animation>   one LinearAnimation on its own, through `gen_scene.py --solo` - the only way
                     to see a beat that normally sits behind a random wait, or an entry the
                     machine cannot be driven into. `advance(DURATION)` is resolved to the
                     animation's own length by the runner.
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

    def checked(self):
        """Raise if this scenario writes one view-model property twice; return it if it is honest.

        `scenario()` and `get()` both run it, so nothing reachable through this module's API can
        claim to sequence `--data` when the CLI will not. (A NamedTuple cannot validate in
        __new__ - typing refuses to let a NamedTuple subclass override it - so the check lives
        here and the factory calls it.)"""
        twice = self.collapsed()
        if twice:
            raise ValueError(
                f"scenario {self.name!r} writes {', '.join(twice)} more than once, and the CLI "
                f"applies every --data before the run: the later writes are the only ones that "
                f"happen, so this scenario would not run the sequence it reads like. Split it "
                f"into one run per leg (each about a second), or probe the animation itself with "
                f"beat:<AnimationName>, which plays it through --solo from its own frame 0.")
        return self

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


def scenario(name, steps, solo=None, note=""):
    """A Scenario, refused if it writes one view-model property twice - see Scenario.checked()."""
    return Scenario(name, tuple(steps), solo, note).checked()


SCENARIOS = {s.name: s for s in [
    scenario("enter-listening", (data("state=listening"), advance(60)),
             note="idle -> listening: the Enter_idle_listening one-shot over the Expression loop"),
    scenario("enter-thinking", (data("state=thinking"), advance(60)),
             note="idle -> thinking: the generic Enter_idle_thinking one-shot. A driver scenario "
                  "can only ever enter FROM idle - the Expression layer starts in StateIdle and "
                  "`--data` does not sequence; for an entry out of any other state, probe "
                  "beat:Enter_<from>_<to>"),
    scenario("success", (data("success=true"), advance(60)),
             note="the Flash layer's 800 ms hop + spin with trails, self-returning"),
    scenario("error", (data("error=true"), advance(60)),
             note="the Flash layer's 600 ms shake"),
    scenario("hover", (pointer("move@250,250"), advance(60)),
             note="the file's hover listener: Rest -> Perk -> Held"),
    # The file's own dragStart listener does NOT fire on a synthesised --pointer down+move: that
    # run renders pixel-identical to a plain hover, and nothing like `--data=dragged=true`. So the
    # scenario writes the contract boolean (what the app does) and keeps the gesture alongside it.
    scenario("drag", (data("dragged=true"), pointer("down@250,250"), pointer("move@300,300"),
                      advance(60)),
             note="the Drag layer, driven by the `dragged` boolean; the pointer gesture alone "
                  "does not reach the file's listener under the CLI"),
]}


# A conversation - idle -> listening -> thinking -> speaking -> idle - is not one scenario. It was
# written as one and it never ran as one (`--data` collapses), so it is a LIST of runs: probe each
# in turn and read the legs side by side. The entries between two non-idle states are not reachable
# from a cold start at all; `beat:Enter_listening_thinking` and friends are how those are read.
CONVERSATION = ("enter-listening", "enter-thinking", "beat:Enter_listening_thinking",
                "beat:Enter_thinking_speaking", "beat:Enter_speaking_idle")


def get(name):
    """A scenario by name. `beat:<AnimationName>` is built on the spot and runs through --solo."""
    if name in SCENARIOS:
        return SCENARIOS[name].checked()
    if name.startswith(BEAT):
        anim = name[len(BEAT):]
        if not anim:
            raise SystemExit("beat: needs an animation name, e.g. beat:IdleBounce")
        return scenario(name, (advance(DURATION),), solo=anim,
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
