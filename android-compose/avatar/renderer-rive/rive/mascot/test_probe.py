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

GOLDEN_SCENARIOS = ["enter-listening", "enter-thinking-from-listening", "success", "error",
                    "hover", "beat:IdleBounce", "beat:IdleStretch", "beat:WanderSpin"]

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

    The plate's white card centre sits at CARD in the Face node's space; Face hangs under Arc
    under Turn, and Turn rolls, so the card's height above Turn's origin is

        x_card * sin(Turn.rotation) + (Arc.y + Face.y + y_card) * cos(Turn.rotation) + Turn.y

    all four of which are probed. The residual (the Lean roll, which this deliberately does not
    model) is about 2 px at frame 20; the test allows 6.
    """
    CARD = (-40.0, -48.0)      # the 120x120 card's centre, relative to Face (Plate at -100,-108)
    FRAME = 20
    TOL_PX = 6.0

    @staticmethod
    def _card_centroid_y(png):
        from PIL import Image
        im = Image.open(png).convert("RGB")
        px, (w, h) = im.load(), im.size
        total = n = 0
        for y in range(h):
            for x in range(w):
                if min(px[x, y]) > 235:        # the plate card is the only near-white thing
                    total += y
                    n += 1
        if not n:
            raise AssertionError(f"no white card found in {png}")
        return total / n

    @classmethod
    def _model_y(cls, table, frame):
        i = table["frames"].index(frame)
        v = table["values"]
        theta = v["Turn.rotation"][i]
        local_y = v["Arc.y"][i] + v["Face.y"][i] + cls.CARD[1]
        return cls.CARD[0] * math.sin(theta) + local_y * math.cos(theta) + v["Turn.y"][i]

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
