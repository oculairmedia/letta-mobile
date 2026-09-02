import importlib.util
import json
import pathlib
import tempfile
from typing import Protocol, cast
from unittest import mock
import unittest


class CheckBaselinesModule(Protocol):
    def check(
        self,
        outputs_dir: pathlib.Path,
        rebaseline: bool,
        baselines_path: pathlib.Path = ...,
        retryable_single_startup_exit_code: int | None = ...,
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

    def startup_spec(self, key: str, baseline: float = 100.0, *, gate: bool = True) -> dict:
        source = self.startup_names(key)[0]
        spec = {
            "baseline": baseline,
            "tolerance_pct": 10,
            "source": source,
            "metric": "timeToInitialDisplayMs",
        }
        if not gate:
            spec["gate"] = False
        return spec

    @staticmethod
    def startup_names(key: str) -> tuple[str, str]:
        return {
            "startup.cold.p95_ms": ("StartupBenchmark.coldStartupCompilationPartial", "coldStartupCompilationPartial"),
            "startup.warm.p95_ms": ("StartupBenchmark.warmStartup", "warmStartup"),
        }[key]

    def write_startup_retry_case(
        self,
        cold_observed: float | None,
        warm_observed: float | None,
        *,
        cold_gate: bool = True,
        warm_gate: bool = True,
        cold_baseline: float = 100.0,
        warm_baseline: float = 100.0,
    ) -> None:
        startup_specs = {
            "startup.cold.p95_ms": self.startup_spec(
                "startup.cold.p95_ms", cold_baseline, gate=cold_gate
            ),
            "startup.warm.p95_ms": self.startup_spec(
                "startup.warm.p95_ms", warm_baseline, gate=warm_gate
            ),
        }
        self.write_baselines(startup_specs)
        for key, observed in (
            ("startup.cold.p95_ms", cold_observed),
            ("startup.warm.p95_ms", warm_observed),
        ):
            if observed is not None:
                _, name = self.startup_names(key)
                self.write_measurement(
                    make_benchmark(
                        "com.letta.mobile.macrobenchmark.StartupBenchmark",
                        name,
                        {"timeToInitialDisplayMs": {"P95": observed}},
                    ),
                    name=f"{key}-benchmarkData.json",
                )

    def assert_fails_closed(self, key: str, spec: dict, measurement: dict) -> None:
        self.write_baselines({key: spec})
        self.write_measurement(measurement)
        self.assertEqual(
            check_baselines.check(
                self.outputs_dir,
                rebaseline=False,
                baselines_path=self.baselines_path,
            ),
            2,
        )

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

    def test_invalid_gating_inputs_fail_closed(self) -> None:
        scenarios = (
            (
                "selection.first_content.p95_ms",
                {
                    "baseline": 100.0,
                    "source": "ConversationOpenBenchmark.coldOpen",
                    "metric": "selectionToFirstContentMs",
                },
                make_benchmark(
                    "com.letta.mobile.macrobenchmark.StartupBenchmark",
                    "coldStartupCompilationPartial",
                    {"timeToInitialDisplayMs": {"P95": 90.0}},
                ),
            ),
            (
                "selection.stable_viewport.p95_ms",
                {
                    "baseline": 100.0,
                    "source": "ConversationOpenBenchmark.warmOpen",
                    "metric": "selectionToStableViewportMs",
                },
                make_benchmark(
                    "com.letta.mobile.macrobenchmark.ConversationOpenBenchmark",
                    "warmOpen",
                    {"timeToInitialDisplayMs": {"P95": 90.0}},
                ),
            ),
            (
                "startup.cold.p95_ms",
                {
                    "baseline": 100.0,
                    "source": "StartupBenchmark.coldStartupCompilationPartial",
                    "metric": "timeToInitialDisplayMs",
                    "min_samples": 10,
                },
                make_benchmark(
                    "com.letta.mobile.macrobenchmark.StartupBenchmark",
                    "coldStartupCompilationPartial",
                    {"timeToInitialDisplayMs": {"P95": 90.0, "runs": [90.0] * 5}},
                ),
            ),
        )
        for key, spec, measurement in scenarios:
            with self.subTest(key=key):
                for output in self.outputs_dir.iterdir():
                    output.unlink()
                self.assert_fails_closed(key, spec, measurement)

    def test_mixed_valid_and_invalid_matches_fail_closed(self) -> None:
        self.write_baselines(
            {
                "startup.cold.p95_ms": {
                    "baseline": 100.0,
                    "source": "StartupBenchmark.coldStartupCompilationPartial",
                    "metric": "timeToInitialDisplayMs",
                }
            }
        )
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.StartupBenchmark",
                "coldStartupCompilationPartial",
                {"timeToInitialDisplayMs": {"P95": 90.0}},
            ),
            name="valid-benchmarkData.json",
        )
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.StartupBenchmark",
                "coldStartupCompilationPartial",
                {"otherMetric": {"P95": 1.0}},
            ),
            name="invalid-benchmarkData.json",
        )

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=False,
            baselines_path=self.baselines_path,
        )

        self.assertEqual(result, 2)

    def test_malformed_metric_values_fail_closed(self) -> None:
        spec = {
            "baseline": 100.0,
            "source": "StartupBenchmark.warmStartup",
            "metric": "timeToInitialDisplayMs",
        }
        for metric in (
            {"P95": "not-a-number"},
            {"P95": True},
            {"runs": [90.0, "not-a-number"]},
            {"runs": [90.0, float("inf")]},
            {"runs": [True] * 10},
            [90.0],
        ):
            with self.subTest(metric=metric):
                for output in self.outputs_dir.iterdir():
                    output.unlink()
                self.assert_fails_closed(
                    "startup.warm.p95_ms",
                    spec,
                    make_benchmark(
                        "com.letta.mobile.macrobenchmark.StartupBenchmark",
                        "warmStartup",
                        {"timeToInitialDisplayMs": metric},
                    ),
                )

    def test_null_runs_and_malformed_metrics_fail_closed(self) -> None:
        spec = {
            "baseline": 100.0,
            "source": "StartupBenchmark.warmStartup",
            "metric": "timeToInitialDisplayMs",
            "min_samples": 10,
        }
        for metrics in (
            {"timeToInitialDisplayMs": {"P95": 90.0, "runs": None}},
            None,
        ):
            with self.subTest(metrics=metrics):
                for output in self.outputs_dir.iterdir():
                    output.unlink()
                self.assert_fails_closed(
                    "startup.warm.p95_ms",
                    spec,
                    make_benchmark(
                        "com.letta.mobile.macrobenchmark.StartupBenchmark",
                        "warmStartup",
                        metrics,
                    ),
                )

    def test_every_matching_gating_observation_meets_minimum_samples(self) -> None:
        spec = {
            "baseline": 100.0,
            "source": "StartupBenchmark.warmStartup",
            "metric": "timeToInitialDisplayMs",
            "min_samples": 10,
        }
        self.write_baselines({"startup.warm.p95_ms": spec})
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.StartupBenchmark",
                "warmStartup",
                {"timeToInitialDisplayMs": {"P95": 90.0, "runs": [90.0] * 10}},
            ),
            name="complete-benchmarkData.json",
        )
        self.write_measurement(
            make_benchmark(
                "com.letta.mobile.macrobenchmark.StartupBenchmark",
                "warmStartup",
                {"timeToInitialDisplayMs": {"P95": 120.0, "runs": [120.0] * 5}},
            ),
            name="undersampled-benchmarkData.json",
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

    def test_retryable_exit_code_must_be_distinct_process_failure_status(self) -> None:
        for value in ("0", "-1", "256"):
            with self.subTest(value=value):
                with mock.patch("sys.stderr"):
                    with self.assertRaises(SystemExit) as raised:
                        check_baselines.main([
                            str(self.outputs_dir),
                            "--retryable-single-startup-exit-code",
                            value,
                        ])
                self.assertEqual(raised.exception.code, 2)

    def test_retryable_exit_code_accepts_valid_status(self) -> None:
        self.assertEqual(check_baselines._retryable_exit_code("3"), 3)

    def test_single_startup_regression_can_request_retry_exit_code(self) -> None:
        self.write_startup_retry_case(
            cold_observed=120.0,
            warm_observed=100.0,
            warm_baseline=50.0,
            warm_gate=False,
        )

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=False,
            baselines_path=self.baselines_path,
            retryable_single_startup_exit_code=3,
        )

        self.assertEqual(result, 3)

    def test_single_warm_start_regression_can_request_retry_exit_code(self) -> None:
        self.write_startup_retry_case(cold_observed=90.0, warm_observed=120.0)

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=False,
            baselines_path=self.baselines_path,
            retryable_single_startup_exit_code=3,
        )

        self.assertEqual(result, 3)

    def test_multiple_regressions_do_not_request_retry(self) -> None:
        self.write_startup_retry_case(cold_observed=120.0, warm_observed=120.0)

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=False,
            baselines_path=self.baselines_path,
            retryable_single_startup_exit_code=3,
        )

        self.assertEqual(result, 1)

    def test_retryable_exit_does_not_override_missing_measurements(self) -> None:
        self.write_startup_retry_case(cold_observed=120.0, warm_observed=None)

        result = check_baselines.check(
            self.outputs_dir,
            rebaseline=False,
            baselines_path=self.baselines_path,
            retryable_single_startup_exit_code=3,
        )

        self.assertEqual(result, 2)

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
