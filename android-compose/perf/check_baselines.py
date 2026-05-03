#!/usr/bin/env python3
"""
Parse androidx.benchmark JSON output and compare against perf baselines.

Usage:
    python perf/check_baselines.py <outputs-dir>            # verify only
    python perf/check_baselines.py <outputs-dir> --rebaseline  # seed/update baselines

`outputs-dir` is typically
    android-compose/macrobenchmark/build/outputs/connected_android_test_additional_output/<device>/

where androidx.benchmark 1.3.x writes `*-benchmarkData.json` files.

Exit codes:
    0 — all measured metrics within tolerance
    1 — at least one metric regressed
    2 — malformed input or unseeded baselines in verify mode
    configured by --retryable-single-cold-start-exit-code — only the cold
        startup gate regressed, so CI may rerun once before failing hard

See letta-mobile-o7ob.4.1.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
from typing import Iterable


HERE = pathlib.Path(__file__).resolve().parent
BASELINES_PATH = HERE / "baselines.json"
COLD_START_METRIC_KEY = "startup.cold.p95_ms"


def _load_baselines(baselines_path: pathlib.Path) -> dict:
    return json.loads(baselines_path.read_text())


def _save_baselines(data: dict, baselines_path: pathlib.Path) -> None:
    baselines_path.write_text(json.dumps(data, indent=2) + "\n")


def _ceiling(baseline: float, tolerance_pct: float, tolerance_abs: float | None) -> float:
    ceiling = baseline * (1.0 + tolerance_pct / 100.0)
    if tolerance_abs is not None:
        ceiling = max(ceiling, baseline + tolerance_abs)
    return ceiling


def _delta_pct(observed: float, baseline: float | None) -> float | None:
    if baseline is None or baseline == 0:
        return None
    return ((observed - baseline) / baseline) * 100.0


def _iter_measurements(outputs_dir: pathlib.Path) -> Iterable[dict]:
    """Yield individual benchmark entries from androidx.benchmark JSON files."""
    files = sorted(outputs_dir.rglob("*-benchmarkData.json"))
    if not files:
        raise FileNotFoundError(
            f"No *-benchmarkData.json files found under {outputs_dir}. "
            "Did the macrobench task actually run?"
        )
    for path in files:
        try:
            payload = json.loads(path.read_text())
        except json.JSONDecodeError as exc:
            raise SystemExit(f"[check_baselines] {path}: {exc}") from exc
        for bench in payload.get("benchmarks", []):
            enriched = dict(bench)
            enriched["__source_path"] = str(path)
            yield enriched


def _pick_metric(bench: dict, metric: str, aggregation: str | None) -> float | None:
    """Best-effort metric extraction across benchmark JSON schema versions."""
    metrics = bench.get("metrics", {})
    entry = metrics.get(metric)
    if entry is None:
        return None
    # androidx.benchmark reports per-iteration + summary stats.
    if aggregation and aggregation in entry:
        return float(entry[aggregation])
    # Fall back to p95 / median / mean depending on availability.
    for key in ("P95", "p95", "median", "P50", "p50", "mean"):
        if key in entry:
            return float(entry[key])
    runs = entry.get("runs")
    if isinstance(runs, list) and runs:
        ordered = sorted(runs)
        idx = max(0, int(len(ordered) * 0.95) - 1)
        return float(ordered[idx])
    return None


def _match_bench(bench: dict, source: str) -> bool:
    cls = bench.get("className", "")
    name = bench.get("name", "")
    fqn = f"{cls.rsplit('.', 1)[-1]}.{name}"
    return fqn == source


def _format_optional_float(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.3f}"


def _write_summary_json(path: pathlib.Path, rows: list[dict], exit_code: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"exit_code": exit_code, "metrics": rows}, indent=2) + "\n")


def _write_summary_markdown(path: pathlib.Path, rows: list[dict], exit_code: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Android perf gate summary",
        "",
        f"Exit code: `{exit_code}`",
        "",
        "| Metric | Status | Gate | Observed | Baseline | Ceiling | Δ vs baseline | Source | JSON |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- |",
    ]
    for row in rows:
        delta = "n/a" if row.get("delta_pct") is None else f"{row['delta_pct']:.2f}%"
        lines.append(
            "| {key} | {status} | {gate} | {observed} | {baseline} | {ceiling} | {delta} | {source} / {metric} | {path} |".format(
                key=row["key"],
                status=row["status"],
                gate="yes" if row.get("gate") else "no",
                observed=_format_optional_float(row.get("observed")),
                baseline=_format_optional_float(row.get("baseline")),
                ceiling=_format_optional_float(row.get("ceiling")),
                delta=delta,
                source=row.get("source", "n/a"),
                metric=row.get("metric", "n/a"),
                path=row.get("source_path", "n/a"),
            )
        )
    path.write_text("\n".join(lines) + "\n")


def _write_summaries(
    summary_rows: list[dict],
    exit_code: int,
    summary_json_path: pathlib.Path | None,
    summary_md_path: pathlib.Path | None,
) -> None:
    if summary_json_path is not None:
        _write_summary_json(summary_json_path, summary_rows, exit_code)
    if summary_md_path is not None:
        _write_summary_markdown(summary_md_path, summary_rows, exit_code)


def check(
    outputs_dir: pathlib.Path,
    rebaseline: bool,
    baselines_path: pathlib.Path = BASELINES_PATH,
    retryable_single_cold_start_exit_code: int | None = None,
    summary_json_path: pathlib.Path | None = None,
    summary_md_path: pathlib.Path | None = None,
) -> int:
    baselines = _load_baselines(baselines_path)
    measurements = list(_iter_measurements(outputs_dir))
    failures: list[dict] = []
    unseeded: list[str] = []
    summary_rows: list[dict] = []
    updates = 0

    for key, spec in baselines["metrics"].items():
        matches = [b for b in measurements if _match_bench(b, spec["source"])]
        gate_enabled = bool(spec.get("gate", True))
        row = {
            "key": key,
            "status": "skip",
            "gate": gate_enabled,
            "source": spec.get("source"),
            "metric": spec.get("metric"),
            "aggregation": spec.get("aggregation"),
            "observed": None,
            "baseline": spec.get("baseline"),
            "ceiling": None,
            "delta_pct": None,
            "source_path": None,
        }
        if not matches:
            print(f"[skip] {key}: no measurement for {spec['source']}")
            summary_rows.append(row)
            continue
        # If multiple iterations ran, take the worst (highest) — we gate
        # on regressions, not best-cases.
        picked_values = [
            (b, _pick_metric(b, spec["metric"], spec.get("aggregation")))
            for b in matches
        ]
        values = [(b, v) for b, v in picked_values if v is not None]
        if not values:
            print(f"[skip] {key}: {spec['metric']} not reported")
            summary_rows.append(row)
            continue
        worst_bench, observed = max(values, key=lambda item: item[1])
        row["observed"] = observed
        row["source_path"] = worst_bench.get("__source_path")
        baseline = spec.get("baseline")
        tolerance_pct = float(spec.get("tolerance_pct", 10))
        tolerance_abs = spec.get("tolerance_abs")
        if tolerance_abs is not None:
            tolerance_abs = float(tolerance_abs)

        if rebaseline:
            spec["baseline"] = round(observed, 3)
            row["baseline"] = spec["baseline"]
            row["status"] = "seed"
            updates += 1
            print(
                f"[seed] {key}: baseline := {spec['baseline']} "
                f"source={spec['source']} metric={spec['metric']} json={row['source_path']}"
            )
            summary_rows.append(row)
            continue

        if baseline is None:
            print(
                f"[unseeded] {key}: baseline is null, run with --rebaseline on the canonical CI device "
                f"source={spec['source']} metric={spec['metric']} json={row['source_path']}"
            )
            row["status"] = "unseeded"
            if gate_enabled:
                unseeded.append(key)
            summary_rows.append(row)
            continue

        baseline = float(baseline)
        row["baseline"] = baseline
        ceiling = _ceiling(baseline, tolerance_pct, tolerance_abs)
        row["ceiling"] = ceiling
        row["delta_pct"] = _delta_pct(observed, baseline)
        if not gate_enabled:
            status = "info"
        else:
            status = "ok" if observed <= ceiling else "REGRESSION"
        row["status"] = status
        tolerance_label = f"+{tolerance_pct:.0f}%"
        if tolerance_abs is not None:
            tolerance_label += f", +{tolerance_abs:.3f} abs"
        gate_label = "gating" if gate_enabled else "informational"
        print(
            f"[{status}] {key}: observed={observed:.3f} "
            f"baseline={baseline:.3f} ceiling={ceiling:.3f} "
            f"delta={row['delta_pct']:.2f}% tolerance=({tolerance_label}) "
            f"mode={gate_label} source={spec['source']} metric={spec['metric']} "
            f"json={row['source_path']}"
        )
        if gate_enabled and observed > ceiling:
            failures.append(row)
        summary_rows.append(row)

    if rebaseline and updates:
        _save_baselines(baselines, baselines_path)
        print(f"[check_baselines] wrote {updates} updates to {baselines_path}")
        _write_summaries(summary_rows, 0, summary_json_path, summary_md_path)
        return 0

    if failures:
        failing_keys = [row["key"] for row in failures]
        retryable_single_cold_start = failing_keys == [COLD_START_METRIC_KEY]
        exit_code = (
            retryable_single_cold_start_exit_code
            if retryable_single_cold_start and retryable_single_cold_start_exit_code is not None
            else 1
        )
        print("\nPerf regressions detected:", file=sys.stderr)
        for row in failures:
            print(
                f"  - {row['key']}: observed={row['observed']:.3f} > ceiling={row['ceiling']:.3f} "
                f"(baseline={row['baseline']:.3f}; delta={row['delta_pct']:.2f}%; "
                f"source={row['source']}; metric={row['metric']}; json={row['source_path']})",
                file=sys.stderr,
            )
        if exit_code != 1:
            print(
                "\nOnly cold startup regressed. CI may rerun the benchmark once to distinguish "
                "shared-emulator noise from a repeatable regression.",
                file=sys.stderr,
            )
        _write_summaries(summary_rows, exit_code, summary_json_path, summary_md_path)
        return exit_code

    if unseeded:
        print("\nUnseeded perf baselines detected:", file=sys.stderr)
        for key in unseeded:
            print(f"  - {key}", file=sys.stderr)
        _write_summaries(summary_rows, 2, summary_json_path, summary_md_path)
        return 2

    _write_summaries(summary_rows, 0, summary_json_path, summary_md_path)
    return 0


def main(argv: list[str]) -> int:
    description = __doc__.splitlines()[0] if __doc__ else "Check benchmark perf baselines."
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("outputs_dir", type=pathlib.Path)
    parser.add_argument(
        "--baselines",
        type=pathlib.Path,
        default=BASELINES_PATH,
        help="Path to the baselines.json file to read/write.",
    )
    parser.add_argument(
        "--rebaseline",
        action="store_true",
        help="Overwrite baselines in baselines.json with the measured values.",
    )
    parser.add_argument(
        "--retryable-single-cold-start-exit-code",
        type=int,
        default=None,
        help=(
            "Return this exit code instead of 1 when the only regression is "
            "startup.cold.p95_ms. CI uses this to rerun the benchmark once."
        ),
    )
    parser.add_argument(
        "--summary-json",
        type=pathlib.Path,
        default=None,
        help="Optional path for a compact machine-readable perf summary.",
    )
    parser.add_argument(
        "--summary-md",
        type=pathlib.Path,
        default=None,
        help="Optional path for a markdown perf summary artifact.",
    )
    args = parser.parse_args(argv)

    baselines_path = args.baselines.resolve()

    if not args.outputs_dir.is_dir():
        print(f"Not a directory: {args.outputs_dir}", file=sys.stderr)
        return 2

    try:
        return check(
            args.outputs_dir,
            args.rebaseline,
            baselines_path=baselines_path,
            retryable_single_cold_start_exit_code=args.retryable_single_cold_start_exit_code,
            summary_json_path=args.summary_json,
            summary_md_path=args.summary_md,
        )
    except FileNotFoundError as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
