#!/usr/bin/env python3
"""
Parse androidx.benchmark JSON output and compare against perf baselines.

Usage:
    python perf/check_baselines.py <outputs-dir>
    python perf/check_baselines.py <outputs-dir> --rebaseline

Exit codes:
    0 — all measured metrics within tolerance
    1 — at least one metric regressed
    2 — malformed, missing, undersampled, or unseeded gating input
    configured by --retryable-single-cold-start-exit-code — only the cold
        startup gate regressed, so CI may rerun once before failing hard
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib
import sys
from typing import Iterable


HERE = pathlib.Path(__file__).resolve().parent
BASELINES_PATH = HERE / "baselines.json"
COLD_START_METRIC_KEY = "startup.cold.p95_ms"


def _load_baselines(baselines_path: pathlib.Path) -> dict:
    return json.loads(baselines_path.read_text(encoding="utf-8"))


def _save_baselines(data: dict, baselines_path: pathlib.Path) -> None:
    baselines_path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


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
            payload = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise SystemExit(f"[check_baselines] {path}: {exc}") from exc
        for bench in payload.get("benchmarks", []):
            enriched = dict(bench)
            enriched["__source_path"] = str(path)
            yield enriched


def _pick_metric(bench: dict, metric: str, aggregation: str | None) -> float | None:
    """Best-effort metric extraction across benchmark JSON schema versions."""
    entry = bench.get("metrics", {}).get(metric)
    if not isinstance(entry, dict):
        return None
    try:
        if aggregation and aggregation in entry:
            return float(entry[aggregation])
        for key in ("P95", "p95", "median", "P50", "p50", "mean"):
            if key in entry:
                return float(entry[key])
        runs = entry.get("runs")
        if isinstance(runs, list) and runs:
            ordered = sorted(float(run) for run in runs)
            index = max(0, int(len(ordered) * 0.95) - 1)
            return ordered[index]
    except (TypeError, ValueError):
        return None
    return None


def _match_bench(bench: dict, source: str) -> bool:
    class_name = bench.get("className", "")
    name = bench.get("name", "")
    return f"{class_name.rsplit('.', 1)[-1]}.{name}" == source


def _format_optional_float(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.3f}"


def _write_summary_json(path: pathlib.Path, rows: list[dict], exit_code: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps({"exit_code": exit_code, "metrics": rows}, indent=2) + "\n",
        encoding="utf-8",
    )


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
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


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


def _new_summary_row(key: str, spec: dict, gate_enabled: bool) -> dict:
    return {
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


def _minimum_sample_error(key: str, spec: dict, values: list[tuple[dict, float]]) -> str | None:
    minimum_samples = int(spec.get("min_samples", 0))
    if not minimum_samples:
        return None
    sample_counts = [
        len(bench.get("metrics", {}).get(spec["metric"], {}).get("runs", []))
        for bench, _ in values
    ]
    undersampled = [count for count in sample_counts if count < minimum_samples]
    if not undersampled:
        return None
    return f"{key}: {min(undersampled)} samples reported, {minimum_samples} required"


def _find_observation(key: str, spec: dict, measurements: list[dict], gate_enabled: bool) -> tuple[dict | None, float | None, str | None]:
    matches = [bench for bench in measurements if _match_bench(bench, spec["source"])]
    if not matches:
        message = f"{key}: missing benchmark {spec['source']}"
        return None, None, message if gate_enabled else None

    picked = [(bench, _pick_metric(bench, spec["metric"], spec.get("aggregation"))) for bench in matches]
    has_invalid_value = any(value is None or not math.isfinite(value) for _, value in picked)
    if has_invalid_value:
        message = f"{key}: missing or non-finite metric {spec['metric']}"
        return None, None, message if gate_enabled else None
    values = [(bench, value) for bench, value in picked if value is not None]

    sample_error = _minimum_sample_error(key, spec, values)
    if sample_error:
        return None, None, sample_error if gate_enabled else None
    bench, value = max(values, key=lambda item: item[1])
    return bench, value, None


def _evaluate_observation(key: str, spec: dict, row: dict, observed: float, rebaseline: bool) -> str | None:
    row["observed"] = observed
    baseline = spec.get("baseline")
    if rebaseline:
        spec["baseline"] = round(observed, 3)
        row["baseline"] = spec["baseline"]
        row["status"] = "seed"
        return "updated"
    if baseline is None:
        row["status"] = "unseeded"
        return "unseeded" if row["gate"] else None

    baseline = float(baseline)
    tolerance_pct = float(spec.get("tolerance_pct", 10))
    tolerance_abs = spec.get("tolerance_abs")
    tolerance_abs = float(tolerance_abs) if tolerance_abs is not None else None
    ceiling = _ceiling(baseline, tolerance_pct, tolerance_abs)
    row.update(
        baseline=baseline,
        ceiling=ceiling,
        delta_pct=_delta_pct(observed, baseline),
        status="info" if not row["gate"] else ("ok" if observed <= ceiling else "REGRESSION"),
    )
    return "failure" if row["status"] == "REGRESSION" else None


def _evaluate_metric(key: str, spec: dict, measurements: list[dict], rebaseline: bool) -> tuple[dict, str | None]:
    gate_enabled = bool(spec.get("gate", True))
    row = _new_summary_row(key, spec, gate_enabled)
    bench, observed, invalid = _find_observation(key, spec, measurements, gate_enabled)
    if bench is None or observed is None:
        row["status"] = "missing" if invalid else "skip"
        print(f"[{row['status']}] {invalid or key}")
        return row, "invalid" if invalid else None

    row["source_path"] = bench.get("__source_path")
    outcome = _evaluate_observation(key, spec, row, observed, rebaseline)
    print(
        f"[{row['status']}] {key}: observed={observed:.3f} "
        f"baseline={_format_optional_float(row.get('baseline'))} "
        f"ceiling={_format_optional_float(row.get('ceiling'))} "
        f"source={spec['source']} metric={spec['metric']} json={row['source_path']}"
    )
    return row, outcome


def _regression_exit_code(failures: list[dict], retryable_exit_code: int | None) -> int:
    only_cold_start = [row["key"] for row in failures] == [COLD_START_METRIC_KEY]
    if only_cold_start and retryable_exit_code is not None:
        return retryable_exit_code
    return 1


def _report_problems(label: str, rows: list[dict]) -> None:
    print(f"\n{label}:", file=sys.stderr)
    for row in rows:
        print(
            f"  - {row['key']}: status={row['status']}; observed={row.get('observed')}; "
            f"baseline={row.get('baseline')}; ceiling={row.get('ceiling')}; "
            f"source={row['source']}; metric={row['metric']}; json={row.get('source_path')}",
            file=sys.stderr,
        )


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
    rows: list[dict] = []
    outcomes: list[str | None] = []

    for key, spec in baselines["metrics"].items():
        row, outcome = _evaluate_metric(key, spec, measurements, rebaseline)
        rows.append(row)
        outcomes.append(outcome)

    if rebaseline and "updated" in outcomes:
        _save_baselines(baselines, baselines_path)
        print(f"[check_baselines] wrote {outcomes.count('updated')} updates to {baselines_path}")

    invalid = [row for row, outcome in zip(rows, outcomes) if outcome == "invalid"]
    unseeded = [row for row, outcome in zip(rows, outcomes) if outcome == "unseeded"]
    failures = [row for row, outcome in zip(rows, outcomes) if outcome == "failure"]
    if invalid or unseeded:
        _report_problems("Invalid or unseeded perf measurement window", invalid + unseeded)
        exit_code = 2
    elif failures:
        _report_problems("Perf regressions detected", failures)
        exit_code = _regression_exit_code(failures, retryable_single_cold_start_exit_code)
    else:
        exit_code = 0

    _write_summaries(rows, exit_code, summary_json_path, summary_md_path)
    return exit_code


def main(argv: list[str]) -> int:
    description = __doc__.splitlines()[0] if __doc__ else "Check benchmark perf baselines."
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("outputs_dir", type=pathlib.Path)
    parser.add_argument("--baselines", type=pathlib.Path, default=BASELINES_PATH)
    parser.add_argument("--rebaseline", action="store_true")
    parser.add_argument("--retryable-single-cold-start-exit-code", type=int, default=None)
    parser.add_argument("--summary-json", type=pathlib.Path, default=None)
    parser.add_argument("--summary-md", type=pathlib.Path, default=None)
    args = parser.parse_args(argv)

    if not args.outputs_dir.is_dir():
        print(f"Not a directory: {args.outputs_dir}", file=sys.stderr)
        return 2
    try:
        return check(
            args.outputs_dir,
            args.rebaseline,
            baselines_path=args.baselines.resolve(),
            retryable_single_cold_start_exit_code=args.retryable_single_cold_start_exit_code,
            summary_json_path=args.summary_json,
            summary_md_path=args.summary_md,
        )
    except FileNotFoundError as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
