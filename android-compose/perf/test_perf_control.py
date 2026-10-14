import importlib.util
import json
import pathlib
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("perf_control.py")
SPEC = importlib.util.spec_from_file_location("perf_control", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
perf_control = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(perf_control)


def summary(cold: float, warm: float, *, cold_status: str = "ok") -> dict:
    return {
        "exit_code": 0,
        "metrics": [
            {"key": "startup.cold.p95_ms", "status": cold_status, "observed": cold, "ceiling": 2000.0},
            {"key": "startup.warm.p95_ms", "status": "ok", "observed": warm, "ceiling": 600.0},
        ],
    }


class PerfControlTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp_dir.name)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write(self, relative: str, payload: object) -> pathlib.Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload))
        return path

    def render(self, control_values: list[float]) -> str:
        current = self.write("current.json", summary(2100.0, 500.0, cold_status="REGRESSION"))
        runs = []
        for index, cold in enumerate(control_values):
            runs.append({"databaseId": index, "headSha": f"sha{index}", "conclusion": "success"})
            self.write(f"control/{index}/perf/perf-summary.json", summary(cold, 450.0))
        # A cancelled run without an artifact is skipped, not counted.
        runs.insert(0, {"databaseId": 99, "headSha": "gone", "conclusion": "cancelled"})
        runs_path = self.write("runs.json", runs)
        rows = perf_control.control_rows(json.loads(runs_path.read_text()), self.root / "control", 5)
        return perf_control.render(perf_control.load_summary(current), rows)

    def test_quiet_main_points_at_the_change(self) -> None:
        out = self.render([1500.0, 1600.0])
        self.assertIn("| **this run** | | | 2100 **over** | 500 |", out)
        self.assertIn("0/2 main runs over the ceiling", out)
        self.assertIn("the change is the suspect", out)
        self.assertNotIn("gone", out)

    def test_noisy_main_says_rerun_is_reasonable(self) -> None:
        out = self.render([1500.0, 2050.0])
        self.assertIn("2050 **over**", out)
        self.assertIn("1/2 main runs over the ceiling", out)
        self.assertIn("likely runner noise", out)

    def test_retried_runs_use_their_last_attempt(self) -> None:
        self.write("run/attempt-1/perf-summary.json", summary(2500.0, 400.0))
        last = self.write("run/attempt-2/perf-summary.json", summary(1500.0, 400.0))
        self.assertEqual(perf_control.find_run_summary(self.root / "run"), last)


if __name__ == "__main__":
    unittest.main()
