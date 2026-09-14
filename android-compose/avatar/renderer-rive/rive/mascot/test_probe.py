"""Telemetry tests: every golden scenario's motion signature, and one pixel cross-check.

    python -m unittest test_probe

Each golden scenario is probed once (one headless CLI run, no rendering) and its signature is
diffed against `goldens/<scenario>.json` with tolerances, so a regression in a beat fails a test
with no image at all. Re-golden on purpose - `python probe.py <scenario> --golden` - and put the
diff in the commit.

The last test is the one that keeps the whole method honest: telemetry claims the plate rises
about 42 px by frame 20 of the success hop, so a screenshot at frame 20 had better show the white
card about that much higher than at frame 0. If the numbers and the pixels ever disagree, it is
the numbers that are wrong, and everything built on them is too.

Budget: about 30 s (eight probes plus two screenshots). Needs the Rive CLI and Pillow.
"""
import json
import math
import os
import subprocess
import tempfile
import unittest

import probe
import scenarios

HERE = os.path.dirname(os.path.abspath(__file__))

# `enter-thinking-from-listening` used to be here. It never probed what its name said - the CLI
# applies every `--data` before the run, so its two writes to `state` collapsed and it ran as
# plain idle -> thinking. It is now called `enter-thinking`, which is what it always was, and the
# entry out of a state the machine cannot be driven into is probed as the animation itself:
# `beat:Enter_error_listening` starts at error's held pose (letta-mobile-r4bbm).
GOLDEN_SCENARIOS = ["enter-listening", "enter-thinking", "success", "error", "hover",
                    "beat:Enter_error_listening", "beat:IdleBounce", "beat:IdleStretch",
                    "beat:WanderSpin"]

PEAK_TOL = 0.05        # 5 % of the property's own range
OVERSHOOT_TOL = 5.0    # percentage points
FRAME_TOL = 3          # frames, for peak_frame and settle_frame
EPS = 1e-4


def golden_path(name):
    return os.path.join(HERE, "goldens", name.replace(":", "-") + ".json")


class Goldens(unittest.TestCase):
    """One run per scenario; the signature must match the committed golden."""

    def _check(self, name):
        path = golden_path(name)
        self.assertTrue(os.path.exists(path),
                        f"no golden for {name}: run `python probe.py {name} --golden`")
        with open(path, encoding="utf-8") as f:
            want = json.load(f)
        _scenario, table, got = probe.probe(name, every=want.get("every", 1), quiet=True)

        self.assertEqual(sorted(got), sorted(want["signature"]),
                         f"{name}: the probed property set changed")
        for prop, ref in want["signature"].items():
            now = got[prop]
            tol = max(abs(ref.get("range", 0.0)) * PEAK_TOL, EPS)
            with self.subTest(scenario=name, property=prop):
                self.assertAlmostEqual(now["peak"], ref["peak"], delta=tol, msg="peak")
                self.assertAlmostEqual(now["max_delta"], ref["max_delta"], delta=tol, msg="max|delta|")
                self.assertAlmostEqual(now["overshoot_pct"], ref["overshoot_pct"],
                                       delta=OVERSHOOT_TOL, msg="overshoot %")
                self.assertLessEqual(abs(now["peak_frame"] - ref["peak_frame"]), FRAME_TOL,
                                     "peak frame")
                self.assertLessEqual(abs(now["settle_frame"] - ref["settle_frame"]), FRAME_TOL,
                                     "settle frame")
                self.assertEqual(now["spacing"], ref["spacing"], "spacing class")
        self.assertFalse(table["missing"],
                         f"{name}: probes stopped binding: {table['missing']}")


def _add_golden_tests():
    for name in GOLDEN_SCENARIOS:
        method = "test_" + name.replace(":", "_").replace("-", "_")
        setattr(Goldens, method, lambda self, n=name: self._check(n))


_add_golden_tests()


class ProbeDocument(unittest.TestCase):
    def test_every_probe_binds(self):
        """A probe the CLI never reports is a probe that silently reads zero for ever."""
        from rig.probe import PROBES
        _scenario, table, _sig = probe.probe("success", quiet=True)
        self.assertEqual(table["missing"], [])
        self.assertEqual(sorted(table["values"]), sorted(p.name for p in PROBES))

    def test_probe_ids_are_outside_the_rig(self):
        from rig import ids
        from rig.probe import PROBES
        taken = ids.all_ids()
        for p in PROBES:
            self.assertNotIn(p.prop_id, taken, f"{p.name} would collide with a rig id")


class PixelCrossCheck(unittest.TestCase):
    """Telemetry versus the only ground truth the CLI gives: a rendered frame.

    The plate's white card sits at CARD in the Face node's space, and the whole placement chain
    above it is probed: Face hangs under Arc under Turn, Turn slides, rolls AND foreshortens, and
    the Lean node rolls the lot about a pivot LEAN px below. `_model_y` walks that chain with the
    probed numbers and nothing else.

    It used to skip Turn's scale and the Lean roll, on the grounds that both were small - which
    held only while `Turn.scaleX` was pinned at 1.000 by the TurnY/TurnX collision
    (letta-mobile-72r3a). With the plate foreshortening for real, the full chain is what agrees
    with the pixels: measured against the rendered card at frames 0, 5, 10, 15, 20, 25, 30 and 40
    of the success flash, the model tracks every one of them to better than half a pixel, so the
    tolerance here is 3 px rather than the old 6.

    The measurement is the MIDPOINT OF THE CARD'S VERTICAL EXTENT, not the centroid of its white
    pixels: the glyph and the mouth punch ink out of the card, and how much of that ink shows
    depends on the pose, which drifts a centroid by a few pixels for reasons that have nothing to
    do with where the card is.
    """
    CARD = (-1.0, -9.5)        # the card's centre in Face space, measured against the render
    LEAN = 158.0               # FacePlacement sits this far above the Lean pivot
    ORIGIN = 420.0             # Entity y (270) + the Lean node's own y (150)
    FRAME = 20
    TOL_PX = 3.0

    @staticmethod
    def _card_centroid_y(png):
        from PIL import Image
        im = Image.open(png).convert("RGB")
        px, (w, h) = im.load(), im.size
        rows = [y for y in range(h) for x in range(w) if min(px[x, y]) > 235]
        if not rows:
            raise AssertionError(f"no white card found in {png}")
        return (min(rows) + max(rows)) / 2.0

    @classmethod
    def _model_y(cls, table, frame):
        i = table["frames"].index(frame)
        v = table["values"]
        theta, lean = v["Turn.rotation"][i], v["Lean.rotation"][i]
        # the card in Turn's child space, then Turn's own scale / roll / slide...
        x = v["Face.x"][i] + cls.CARD[0]
        y = v["Face.y"][i] + cls.CARD[1] + v["Arc.y"][i]
        tx = v["Turn.x"][i] + math.cos(theta) * v["Turn.scaleX"][i] * x - math.sin(theta) * y
        ty = v["Turn.y"][i] + math.sin(theta) * v["Turn.scaleX"][i] * x + math.cos(theta) * y
        # ...then the Lean roll about its pivot, and the Entity's placement.
        return cls.ORIGIN + math.sin(lean) * tx + math.cos(lean) * (ty - cls.LEAN)

    def test_success_hop_moves_the_pixels_it_says_it_does(self):
        scenario = scenarios.get("success")
        _s, table, _sig = probe.probe("success", quiet=True)
        predicted = self._model_y(table, self.FRAME) - self._model_y(table, 0)
        self.assertLess(predicted, -20, "the probe says the plate barely moved; the rig changed")

        tmp = tempfile.mkdtemp(prefix="probe-xcheck-")
        try:
            project, _ = probe.probe_project(tmp)
            shots = {}
            for frame in (0, self.FRAME):
                png = os.path.join(tmp, f"f{frame}.png")
                cmd = [probe.rive_bin(), project, f"--screenshot={png}", "--quiet",
                       "--data=success=true", f"--advance={frame}"]
                run = subprocess.run(cmd, capture_output=True, text=True)
                self.assertFalse(run.returncode, (run.stderr or run.stdout))
                shots[frame] = self._card_centroid_y(png)
            measured = shots[self.FRAME] - shots[0]
        finally:
            import shutil
            shutil.rmtree(tmp, ignore_errors=True)

        self.assertAlmostEqual(
            measured, predicted, delta=self.TOL_PX,
            msg=(f"{scenario.name}: telemetry predicts the card rises {-predicted:.1f} px by frame "
                 f"{self.FRAME}, the render shows {-measured:.1f} px"))


if __name__ == "__main__":
    unittest.main()
