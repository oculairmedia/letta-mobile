"""Telemetry tests: `python -m unittest test_probe`. Needs the Rive CLI (a local gate, like the
mascot's), about 20 s.

  goldens       every scenario in scenarios.GOLDEN probed and diffed against goldens/<name>.json
                with the mascot's tolerances. Re-golden on purpose (`python probe.py <name>
                --golden`) and put the diff in the commit.
  reduceMotion  ten seconds of every state with reduceMotion moves NOTHING from frame 1 on (frame
                0 is the authored default before the first advance - mascot README gotcha); the
                calm boot and success change light only. The plain states are the control: each
                of them does move, so a probe that stopped reading cannot pass for a calm rig.
  scope         the mark's one-shots run on the Event nodes only: success never moves a Pose node
                beyond the idle loop's own sway.
"""
import json
import os
import unittest

import rig  # noqa: F401 - path bootstrap
import probe
import scenarios
from rig.probe import MOTION, PROBES

HERE = os.path.dirname(os.path.abspath(__file__))

PEAK_TOL = 0.05
OVERSHOOT_TOL = 5.0
FRAME_TOL = 3
EPS = 1e-4
FLAT = 1e-3


def _check_golden(case, name):
    path = probe.golden_path(name)
    case.assertTrue(os.path.exists(path), f"no golden for {name}: python probe.py {name} --golden")
    with open(path, encoding="utf-8") as f:
        want = json.load(f)
    _s, table, got = probe.probe(name, every=want.get("every", 1), quiet=True)
    case.assertEqual(sorted(got), sorted(want["signature"]), f"{name}: the probed property set changed")
    for prop, ref in want["signature"].items():
        now, tol = got[prop], max(abs(ref.get("range", 0.0)) * PEAK_TOL, EPS)
        with case.subTest(scenario=name, property=prop):
            case.assertAlmostEqual(now["peak"], ref["peak"], delta=tol, msg="peak")
            case.assertAlmostEqual(now["max_delta"], ref["max_delta"], delta=tol, msg="max|delta|")
            case.assertAlmostEqual(now["overshoot_pct"], ref["overshoot_pct"], delta=OVERSHOOT_TOL)
            case.assertLessEqual(abs(now["peak_frame"] - ref["peak_frame"]), FRAME_TOL, "peak frame")
            case.assertLessEqual(abs(now["settle_frame"] - ref["settle_frame"]), FRAME_TOL, "settle frame")
            case.assertEqual(now["spacing"], ref["spacing"], "spacing class")
    case.assertFalse(table["missing"], f"{name}: probes stopped binding: {table['missing']}")


class Goldens(unittest.TestCase):
    pass


for _name in scenarios.GOLDEN:
    setattr(Goldens, "test_" + _name.replace("-", "_").replace(":", "_"),
            lambda self, n=_name: _check_golden(self, n))


def _after_first(table):
    """{probe: range of its values from frame 1 on}."""
    return {n: max(v[1:]) - min(v[1:]) for n, v in table["values"].items()}


class ReduceMotion(unittest.TestCase):
    def _ranges(self, name, every=1):
        _s, table, _sig = probe.probe(name, every=every, quiet=True)
        self.assertFalse(table["missing"], table["missing"])
        return _after_first(table)

    def test_every_state_holds_still(self):
        for state in scenarios.STATE_FRAMES:
            with self.subTest(state=state):
                moving = {n: r for n, r in self._ranges(f"reduce-motion-{state}", every=10).items() if r > FLAT}
                self.assertEqual(moving, {}, f"reduceMotion {state} still moves")

    def test_every_plain_state_moves(self):
        for state in scenarios.STATE_FRAMES:
            with self.subTest(state=state):
                moving = {n for n, r in self._ranges(state).items() if r > FLAT}
                self.assertTrue(moving, f"plain {state} moves nothing: the probes stopped reading")

    def test_calm_boot_and_success_change_light_only(self):
        for name in ("reduce-motion-boot", "reduce-motion-success"):
            ranges = self._ranges(name)
            with self.subTest(scenario=name):
                moved = {n: r for n, r in ranges.items() if n in MOTION and r > FLAT}
                self.assertEqual(moved, {}, f"{name} moves a stone")
                lit = {n for n, r in ranges.items() if n not in MOTION and r > FLAT}
                self.assertTrue(lit, f"{name} shows nothing at all")

    def test_the_states_differ_under_reduce_motion(self):
        """Each calm state is still readable: no two share the same static picture."""
        pictures = {}
        for state in scenarios.STATE_FRAMES:
            _s, table, _sig = probe.probe(f"reduce-motion-{state}", every=60, quiet=True)
            pictures[state] = tuple(round(v[-1], 3) for v in table["values"].values())
        self.assertEqual(len(set(pictures.values())), len(pictures), pictures)


class Scope(unittest.TestCase):
    def test_success_runs_on_the_event_nodes(self):
        _s, table, _sig = probe.probe("success", quiet=True)
        ranges = _after_first(table)
        self.assertGreater(ranges["PebbleEvent.y"], 30, "no lift")
        self.assertGreater(ranges["PebbleBloom.opacity"], 0.9, "no bloom")
        self.assertLess(ranges["PebblePose.x"], 2, "success leaked into the Pose layer")

    def test_every_probe_binds(self):
        _s, table, _sig = probe.probe("idle", quiet=True)
        self.assertEqual(table["missing"], [])
        self.assertEqual(sorted(table["values"]), sorted(p.name for p in PROBES))


if __name__ == "__main__":
    unittest.main()
