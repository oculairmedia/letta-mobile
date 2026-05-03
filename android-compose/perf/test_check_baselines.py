import importlib.util
import json
import pathlib
import tempfile
from typing import Protocol, cast
import unittest


class CheckBaselinesModule(Protocol):
    def check(
        self,
        outputs_dir: pathlib.Path,
        rebaseline: bool,
        baselines_path: pathlib.Path = ...,
        retryable_single_cold_start_exit_code: int | None = ...,
        summary_json_path: pathlib.Path | None = ...,
        summary_md_path: pathlib.Path | None = ...,
    ) -> int: ...


MODULE_PATH = pathlib.Path(__file__).with_name("check_baselines.py")
SPEC = importlib.util.spec_from_file_location("check_baselines", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
raw_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(raw_module)
check_baselines = cast(CheckBaselinesModule, raw_module)


def make_benchmark(class_name: str, name: str, metrics: dict) -> dict:
    return {
        "benchmarks": [
            {
                "className": class_name,
                "name": name,
                "metrics": metrics,
            }
        ]
    }


class CheckBaselinesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp_dir.name)
        self.outputs_dir = self.root / "outputs"
        self.outputs_dir.mkdir()
        self.baselines_path = self.root / "baselines.json"

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_baselines(self, metrics: dict) -> None:
        self.baselines_path.write_text(
            json.dumps({"metrics": metrics}, indent=2) + "\n"
        )

    def write_measurement(self, payload: dict, name: str = "sample-benchmarkData.json") -> None:
        (self.outputs_dir / name).write_text(json.dumps(payload, indent=2) + "\n")

    def test_regression_exits_nonzero(self) -> None:
        self.write_baselines(
            {
                "startup.cold.p95_ms": {
                    "baseline": 100.0,
                    "tolerance_pct": 10,
                    "source": "StartupBenchmark.coldStartupCompilationPartial",
                    "metric": "timeToInitialDisplayMs",
                }
            }
        )
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.StartupBenchmark",
                "coldStartupCompilationPartial",
                {"timeToInitialDisplayMs": {"P95": 120.0}},
            )
        )

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=False,
            baselines_path=self.baselines_path,
        )

        self.assertEqual(result, 1)

    def test_absolute_tolerance_prevents_small_jank_flake(self) -> None:
        self.write_baselines(
            {
                "scroll.jank.pct": {
                    "baseline": 4.0,
                    "tolerance_pct": 15,
                    "tolerance_abs": 1.0,
                    "source": "ScrollJankBenchmark.scrollChatListCompilationPartial",
                    "metric": "frameDurationCpuMs",
                    "aggregation": "jankCountPercent",
                }
            }
        )
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.ScrollJankBenchmark",
                "scrollChatListCompilationPartial",
                {"frameDurationCpuMs": {"jankCountPercent": 4.8}},
            )
        )

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=False,
            baselines_path=self.baselines_path,
        )

        self.assertEqual(result, 0)

    def test_rebaseline_seeds_null_metrics(self) -> None:
        self.write_baselines(
            {
                "composer.typing.jank.pct": {
                    "baseline": None,
                    "tolerance_pct": 15,
                    "tolerance_abs": 1.0,
                    "source": "ComposerTypingBenchmark.typeComposerCompilationPartial",
                    "metric": "frameDurationCpuMs",
                    "aggregation": "jankCountPercent",
                }
            }
        )
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.ComposerTypingBenchmark",
                "typeComposerCompilationPartial",
                {"frameDurationCpuMs": {"jankCountPercent": 3.4567}},
            )
        )

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=True,
            baselines_path=self.baselines_path,
        )

        self.assertEqual(result, 0)
        updated = json.loads(self.baselines_path.read_text())
        self.assertEqual(updated["metrics"]["composer.typing.jank.pct"]["baseline"], 3.457)

    def test_unseeded_metric_fails_without_rebaseline(self) -> None:
        self.write_baselines(
            {
                "startup.warm.p95_ms": {
                    "baseline": None,
                    "tolerance_pct": 10,
                    "source": "StartupBenchmark.warmStartup",
                    "metric": "timeToInitialDisplayMs",
                }
            }
        )
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.StartupBenchmark",
                "warmStartup",
                {"timeToInitialDisplayMs": {"P95": 200.0}},
            )
        )

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=False,
            baselines_path=self.baselines_path,
        )

        self.assertEqual(result, 2)

    def test_non_gating_metric_does_not_fail_verify(self) -> None:
        self.write_baselines(
            {
                "startup.warm.p95_ms": {
                    "baseline": 200.0,
                    "tolerance_pct": 10,
                    "source": "StartupBenchmark.warmStartup",
                    "metric": "timeToInitialDisplayMs",
                    "gate": False,
                }
            }
        )
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.StartupBenchmark",
                "warmStartup",
                {"timeToInitialDisplayMs": {"P95": 400.0}},
            )
        )

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=False,
            baselines_path=self.baselines_path,
        )

        self.assertEqual(result, 0)

    def test_single_cold_start_regression_can_request_retry_exit_code(self) -> None:
        self.write_baselines(
            {
                "startup.cold.p95_ms": {
                    "baseline": 100.0,
                    "tolerance_pct": 10,
                    "source": "StartupBenchmark.coldStartupCompilationPartial",
                    "metric": "timeToInitialDisplayMs",
                },
                "startup.warm.p95_ms": {
                    "baseline": 50.0,
                    "tolerance_pct": 10,
                    "source": "StartupBenchmark.warmStartup",
                    "metric": "timeToInitialDisplayMs",
                    "gate": False,
                },
            }
        )
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.StartupBenchmark",
                "coldStartupCompilationPartial",
                {"timeToInitialDisplayMs": {"P95": 120.0}},
            ),
            name="cold-benchmarkData.json",
        )
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.StartupBenchmark",
                "warmStartup",
                {"timeToInitialDisplayMs": {"P95": 100.0}},
            ),
            name="warm-benchmarkData.json",
        )

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=False,
            baselines_path=self.baselines_path,
            retryable_single_cold_start_exit_code=3,
        )

        self.assertEqual(result, 3)

    def test_summary_files_include_diagnostics(self) -> None:
        self.write_baselines(
            {
                "startup.cold.p95_ms": {
                    "baseline": 100.0,
                    "tolerance_pct": 10,
                    "source": "StartupBenchmark.coldStartupCompilationPartial",
                    "metric": "timeToInitialDisplayMs",
                }
            }
        )
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.StartupBenchmark",
                "coldStartupCompilationPartial",
                {"timeToInitialDisplayMs": {"P95": 105.0}},
            )
        )
        summary_json = self.root / "perf-summary.json"
        summary_md = self.root / "perf-summary.md"

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=False,
            baselines_path=self.baselines_path,
            summary_json_path=summary_json,
            summary_md_path=summary_md,
        )

        self.assertEqual(result, 0)
        summary = json.loads(summary_json.read_text())
        self.assertEqual(summary["metrics"][0]["key"], "startup.cold.p95_ms")
        self.assertEqual(summary["metrics"][0]["source"], "StartupBenchmark.coldStartupCompilationPartial")
        self.assertIn("sample-benchmarkData.json", summary["metrics"][0]["source_path"])
        self.assertIn("startup.cold.p95_ms", summary_md.read_text())


if __name__ == "__main__":
    unittest.main()
