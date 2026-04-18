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
    2 — malformed input (missing files, unparseable JSON)

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


def _load_baselines() -> dict:
    return json.loads(BASELINES_PATH.read_text())


def _save_baselines(data: dict) -> None:
    BASELINES_PATH.write_text(json.dumps(data, indent=2) + "\n")


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
            yield bench


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


def check(outputs_dir: pathlib.Path, rebaseline: bool) -> int:
    baselines = _load_baselines()
    measurements = list(_iter_measurements(outputs_dir))
    failures: list[str] = []
    updates = 0

    for key, spec in baselines["metrics"].items():
        matches = [b for b in measurements if _match_bench(b, spec["source"])]
        if not matches:
            print(f"[skip] {key}: no measurement for {spec['source']}")
            continue
        # If multiple iterations ran, take the worst (highest) — we gate
        # on regressions, not best-cases.
        values = [
            v for v in (
                _pick_metric(b, spec["metric"], spec.get("aggregation"))
                for b in matches
            )
            if v is not None
        ]
        if not values:
            print(f"[skip] {key}: {spec['metric']} not reported")
            continue
        observed = max(values)
        baseline = spec.get("baseline")
        tolerance_pct = float(spec.get("tolerance_pct", 10))

        if rebaseline or baseline is None:
            spec["baseline"] = round(observed, 3)
            updates += 1
            print(f"[seed] {key}: baseline := {spec['baseline']}")
            continue

        ceiling = baseline * (1.0 + tolerance_pct / 100.0)
        status = "ok" if observed <= ceiling else "REGRESSION"
        print(
            f"[{status}] {key}: observed={observed:.3f} "
            f"baseline={baseline:.3f} ceiling={ceiling:.3f} "
            f"(+{tolerance_pct:.0f}%)"
        )
        if observed > ceiling:
            failures.append(
                f"{key}: {observed:.3f} > {ceiling:.3f} "
                f"(baseline {baseline:.3f} + {tolerance_pct:.0f}%)"
            )

    if rebaseline and updates:
        _save_baselines(baselines)
        print(f"[check_baselines] wrote {updates} updates to {BASELINES_PATH}")
        return 0

    if failures:
        print("\nPerf regressions detected:", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1

    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("outputs_dir", type=pathlib.Path)
    parser.add_argument(
        "--rebaseline",
        action="store_true",
        help="Overwrite baselines in baselines.json with the measured values.",
    )
    args = parser.parse_args(argv)

    if not args.outputs_dir.is_dir():
        print(f"Not a directory: {args.outputs_dir}", file=sys.stderr)
        return 2

    return check(args.outputs_dir, args.rebaseline)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
