"""The review tools' own tests: `python -m unittest test_tools`.

test_rig.py holds down the document (regenerate parity, ids, references). This file holds down
the tools that read it - rig/chart.py, xsheet.py and timeline.py --chart - because a chart that
misreads a curve is worse than no chart: it makes a floating beat look eased.

  halving      the halving principle produces the fractions an assistant would measure
  classify     a slow-in reads as ease-in and an even curve as linear, on synthetic samples
  pop          one frame carrying the whole move is caught (the snap detector)
  round trip   what Chart.keys() lays down, Chart.of() reads back as the same spacing
  xsheet       IdleBounce renders with extremes, levels and charts; the blink fires in the
               driver column of Enter_idle_listening
  timeline     --chart still prints, and without it the CLI output is what it always was

Standard library only, no pytest. Read-only: nothing here writes into the working tree.
"""
import io
import os
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import timeline                                    # noqa: E402
import xsheet                                      # noqa: E402
from rig.chart import Chart, classify, profile     # noqa: E402

SCENE = os.path.join(HERE, "scene.rml")


def run(module_main, argv):
    buf = io.StringIO()
    with redirect_stdout(buf):
        code = module_main(argv)
    return code, buf.getvalue()


def ramp(n, curve):
    """n+1 samples of a 0..1 curve, one per frame."""
    return [curve(i / float(n)) for i in range(n + 1)]


class Halving(unittest.TestCase):
    def test_toward_the_end(self):
        self.assertEqual(Chart.halving(3, toward="end"), [0.5, 0.75, 0.875])
        self.assertEqual(Chart.halving(1), [0.5])
        self.assertEqual(Chart.halving(4, "end"), [0.5, 0.75, 0.875, 0.9375])

    def test_toward_the_start_is_the_mirror(self):
        self.assertEqual(Chart.halving(3, toward="start"), [0.125, 0.25, 0.5])

    def test_named_patterns_use_it(self):
        self.assertEqual(Chart.pattern("ease-in", 3), Chart.halving(3, "end"))
        self.assertEqual(Chart.pattern("ease-out", 3), Chart.halving(3, "start"))
        self.assertEqual(Chart.pattern("linear", 3), [0.25, 0.5, 0.75])
        self.assertEqual(len(Chart.pattern("s", 5)), 5)

    def test_fractions_must_be_interior(self):
        with self.assertRaises(ValueError):
            Chart(extremes=[(0, 0), (10, 1)], spacing=[0.0, 0.5])
        with self.assertRaises(ValueError):
            Chart(extremes=[(0, 0), (10, 1)], spacing=[0.5, 1.0])


class Classification(unittest.TestCase):
    def test_ease_in_versus_linear(self):
        # slow-IN: most of the distance is covered early, then it crawls to the target.
        ease_in = ramp(24, lambda u: 1 - (1 - u) ** 3)
        linear = ramp(24, lambda u: u)
        self.assertEqual(classify(ease_in), "ease-in")
        self.assertEqual(classify(linear), "linear")

    def test_ease_out_and_s(self):
        self.assertEqual(classify(ramp(24, lambda u: u ** 3)), "ease-out")
        self.assertEqual(classify(ramp(24, lambda u: u * u * (3 - 2 * u))), "s")

    def test_hold(self):
        self.assertEqual(classify([7.0] * 30), "hold")
        self.assertEqual(classify([7.0]), "hold")

    def test_pop_is_detected(self):
        popped = [0.0] * 10 + [1.0] * 10          # nothing, nothing, snap
        self.assertEqual(classify(popped), "pop")
        _total, share, _dev = profile(popped)
        self.assertGreater(max(share), 0.45)

    def test_a_real_ease_is_not_called_a_pop(self):
        # The halving ticks themselves put 50 % of the travel in the first step; that is an
        # ease, not a pop, because the step after it is half as big and not a hundredth.
        self.assertEqual(classify([0.0, 0.5, 0.75, 0.875, 1.0]), "ease-in")


class Authoring(unittest.TestCase):
    def test_keys_are_extremes_breakdown_and_one_key_per_tick(self):
        c = Chart(extremes=[(0, 0), (24, 100)], spacing="ease-in")
        keys = c.keys()
        self.assertEqual([(f, v) for f, v, *_ in keys],
                         [(0, 0), (6, 50), (12, 75), (18, 87.5), (24, 100)])
        self.assertTrue(all(len(k) == 3 for k in keys[:-1]), "every key but the last leaves eased")
        self.assertEqual(len(keys[-1]), 2, "the last key's bezier is ignored by Rive")
        self.assertTrue(all(k[2] == c.smooth for k in keys[:-1]))

    def test_a_breakdown_is_an_anchor(self):
        c = Chart(extremes=[(0, 0), (22, -48)], breakdown=(8, -40), spacing="ease-in")
        frames = [k[0] for k in c.keys()]
        self.assertIn(8, frames)
        self.assertEqual(frames[0], 0)
        self.assertEqual(frames[-1], 22)
        self.assertEqual(sorted(frames), frames)
        self.assertEqual(len(frames), len(set(frames)), "no two keys on one frame")

    def test_keys_round_trip_through_of_to_the_same_class(self):
        for name in ("ease-in", "ease-out", "s", "linear"):
            with self.subTest(name):
                c = Chart(extremes=[(0, 0), (36, -48)], spacing=name)
                self.assertEqual(Chart.of(c.keys()).spacing, name)

    def test_explicit_fractions_classify_themselves(self):
        c = Chart(extremes=[(0, 1.0), (14, 0.93)], spacing=[0.5, 0.75, 0.875])
        self.assertEqual(c.spacing, "ease-in")


class ChartText(unittest.TestCase):
    def test_extremes_and_breakdown_are_bracketed(self):
        c = Chart(extremes=[(0, 0), (22, -48)], breakdown=(8, -40), spacing="ease-in")
        text = c.text()
        self.assertTrue(text.startswith("[0]"), text)
        self.assertIn("(8)", text)
        self.assertIn("[22]", text)
        self.assertTrue(text.endswith("ease-in"), text)

    def test_uniform_ticks_read_as_uniform(self):
        text = Chart(extremes=[(0, 0), (40, 100)], spacing="linear").text(width=48)
        line = text.split("  ")[0]
        gaps = [len(g) for g in line.split("|")[1:-1]]    # the runs between the in-betweens
        self.assertEqual(len(gaps), 2)
        self.assertLessEqual(max(gaps) - min(gaps), 1, f"linear must read even: {line!r}")

    def test_an_ease_in_crowds_at_the_end(self):
        line = Chart(extremes=[(0, 0), (40, 100)], spacing="ease-in").text(width=48).split("  ")[0]
        gaps = [len(g) for g in line.split("|")[:-1]]
        self.assertGreater(gaps[0], gaps[-1], f"ease-in must crowd at the end: {line!r}")

    def test_of_samples_reads_telemetry(self):
        c = Chart.of_samples(ramp(24, lambda u: 1 - (1 - u) ** 3))
        self.assertEqual(c.spacing, "ease-in")
        self.assertIn("[24]", c.text())

    def test_a_hold_is_drawn_as_a_hold(self):
        c = Chart.of_samples([3.0] * 12)
        self.assertEqual(c.spacing, "hold")
        self.assertIn("=", c.text())


class XSheet(unittest.TestCase):
    def test_idle_bounce_renders(self):
        code, out = run(xsheet.main, ["--animation", "IdleBounce"])
        self.assertEqual(code, 0)
        self.assertIn("[K]", out)
        self.assertIn("Body y", out)
        self.assertIn("frame", out)
        self.assertIn("spacing", out)
        self.assertIn("footer: spacing class per level", out)

    def test_rows_fit_a_120_column_terminal(self):
        _code, out = run(xsheet.main, ["--animation", "SuccessFlash"])
        for line in out.splitlines():
            self.assertLessEqual(len(line), 120, line)

    def test_marks_are_extremes_and_breakdowns(self):
        # SuccessFlash's bone scales pass through interior keys the curve does not turn at.
        _code, out = run(xsheet.main, ["--animation", "SuccessFlash", "--wide"])
        self.assertIn("[K]", out)
        self.assertIn("(B)", out)

    def test_a_key_the_curve_turns_at_is_an_extreme(self):
        keys = [timeline.Key(f, v, timeline.HOLD, "double")
                for f, v in [(0, 0), (5, 3), (10, -14), (15, 0)]]
        level = xsheet.Level("Body", 14, keys, "Synthetic")
        self.assertEqual([level.mark(f) for f in (0, 5, 10, 15)], ["[K]", "[K]", "[K]", "[K]"])
        self.assertEqual(level.mark(7), "", "an in-between carries no mark")
        rising = [timeline.Key(f, v, timeline.HOLD, "double")
                  for f, v in [(0, 0), (5, 3), (10, 9), (15, 12)]]
        level = xsheet.Level("Body", 14, rising, "Synthetic")
        self.assertEqual([level.mark(f) for f in (0, 5, 10, 15)], ["[K]", "(B)", "(B)", "[K]"])

    def test_driver_column_shows_the_blink_callback(self):
        _code, out = run(xsheet.main, ["--animation", "Enter_idle_listening"])
        self.assertIn("blink fire", out)
        self.assertIn("expr=", out, "the plate expression flip belongs in the driver column")

    def test_several_animations_share_their_levels(self):
        _code, out = run(xsheet.main, ["--animations", "Enter_idle_listening,StateListening",
                                       "--wide", "--range", "0:4"])
        self.assertIn("also keyed by StateListening", out)
        self.assertIn("levels 1/", out)

    def test_wide_shows_every_level(self):
        _c, narrow = run(xsheet.main, ["--animation", "SuccessFlash", "--range", "0:2"])
        _c, wide = run(xsheet.main, ["--animation", "SuccessFlash", "--range", "0:2", "--wide"])
        self.assertIn("rerun with --wide", narrow)
        self.assertNotIn("rerun with --wide", wide)
        self.assertGreater(wide.count("levels "), narrow.count("levels "))

    def test_scenario_is_refused_until_telemetry_is_wired(self):
        with redirect_stderr(io.StringIO()) as err, self.assertRaises(SystemExit) as exit_:
            run(xsheet.main, ["--scenario", "enter-listening"])
        self.assertEqual(exit_.exception.code, 2)
        self.assertIn("probe.py enter-listening", err.getvalue())

    def test_the_header_names_the_selected_animations_artboard(self):
        _code, out = run(xsheet.main, ["--animation", "Blink", "--range", "0:1"])
        self.assertIn("[Plate]", out.splitlines()[0])

    def test_an_unknown_animation_is_a_clean_miss(self):
        code, out = run(xsheet.main, ["--animation", "NoSuchBeat"])
        self.assertEqual(code, 1)
        self.assertIn("no animation matching", out)


class TimelineChart(unittest.TestCase):
    def test_chart_prints_under_each_property(self):
        code, out = run(timeline.main, ["IdleBounce", "--chart"])
        self.assertEqual(code, 0)
        self.assertIn("[0]", out)
        self.assertIn("[59]", out)
        self.assertIn("ease-in", out)

    def test_marks_crowding_one_end_still_draw(self):
        # ErrorFlash's y holds for most of its span, so every interior mark crowds the far end and
        # the laid-out line runs past the requested width; that used to raise an IndexError.
        code, out = run(timeline.main, ["ErrorFlash", "--chart"])
        self.assertEqual(code, 0)
        self.assertIn("[36]  ease-in", out)
        self.assertTrue(Chart(extremes=[(0, 0), (40, 1)], spacing=[0.97, 0.98, 0.99]).text(width=12))

    def test_the_default_output_is_unchanged(self):
        _code, plain = run(timeline.main, ["IdleBounce"])
        _code, charted = run(timeline.main, ["IdleBounce", "--chart"])
        self.assertNotIn("[59]  ", plain)
        for line in plain.splitlines():
            self.assertIn(line, charted.splitlines())


if __name__ == "__main__":
    unittest.main()
