#!/usr/bin/env python3
"""Render this run's perf numbers next to the last main runs (the control).

The perf gate measures once and fails on a breach (letta-mobile-by4vm.32). A
breach on the shared GitHub emulator can be the change or the runner; the
control tells them apart. If recent main runs, which did not contain this
change, sit at or above the same ceiling, the runner moved: a human may rerun
the job. If main sits comfortably below it, the change is the suspect.

Usage:
    python3 perf/perf_control.py --current build/perf-summary/perf-summary.json \
        --runs control/runs.json --control-dir control [--limit 5]

`runs.json` is `gh run list --json databaseId,headSha,conclusion,createdAt,url`
output; each run's downloaded artifact lives under `<control-dir>/<databaseId>/`.
Prints Markdown for $GITHUB_STEP_SUMMARY. Always exits 0 unless input is unusable.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

GATED_METRICS = ("startup.cold.p95_ms", "startup.warm.p95_ms")


def load_summary(path: pathlib.Path) -> dict[str, dict]:
    """`perf-summary.json` -> {metric key: row}. Empty when absent or unreadable."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return {row["key"]: row for row in data.get("metrics", []) if isinstance(row, dict) and "key" in row}


def find_run_summary(run_dir: pathlib.Path) -> pathlib.Path | None:
    """The deciding summary in a run's artifact: the last attempt when older runs retried."""
    found = sorted(run_dir.rglob("perf-summary.json"))
    if not found:
        return None
    return max(found, key=lambda p: ("attempt-2" in p.parts, "attempt-1" not in p.parts))


def control_rows(runs: list[dict], control_dir: pathlib.Path, limit: int) -> list[tuple[dict, dict[str, dict]]]:
    rows = []
    for run in runs:
        summary_path = find_run_summary(control_dir / str(run.get("databaseId")))
        metrics = load_summary(summary_path) if summary_path else {}
        if metrics:
            rows.append((run, metrics))
        if len(rows) == limit:
            break
    return rows


def ceiling_of(current: dict[str, dict], key: str) -> float | None:
    return (current.get(key) or {}).get("ceiling")


def cell(row: dict | None, ceiling: float | None) -> str:
    """Observed ms, flagged when over today's ceiling (older runs had other ceilings)."""
    if not row or row.get("observed") is None:
        return "n/a"
    over = ceiling is not None and row["observed"] > ceiling
    return f"{row['observed']:.0f}" + (" **over**" if over else "")


def verdict(current: dict[str, dict], control: list[tuple[dict, dict[str, dict]]]) -> list[str]:
    lines = []
    for key in GATED_METRICS:
        mine = current.get(key) or {}
        ceiling = mine.get("ceiling")
        if mine.get("status") != "REGRESSION" or ceiling is None:
            continue
        values = [m[key]["observed"] for _, m in control if m.get(key, {}).get("observed") is not None]
        if not values:
            lines.append(f"- `{key}` breached ({mine['observed']:.0f} > {ceiling:.0f}); no control data.")
            continue
        over = sum(1 for v in values if v > ceiling)
        lines.append(
            f"- `{key}` breached ({mine['observed']:.0f} > ceiling {ceiling:.0f}). "
            f"Control: {min(values):.0f}-{max(values):.0f} ms, {over}/{len(values)} main runs over the ceiling. "
            + (
                "Main breaches too: likely runner noise. A rerun is reasonable; note it on the PR."
                if over
                else "Main stays under it: the change is the suspect; investigate before any rerun."
            )
        )
    return lines


def render(current: dict[str, dict], control: list[tuple[dict, dict[str, dict]]]) -> str:
    ceilings = " / ".join(
        "n/a" if ceiling_of(current, k) is None else f"{ceiling_of(current, k):.0f}" for k in GATED_METRICS
    )
    out = [
        "## Perf control: last main runs",
        "",
        f"Ceilings (cold / warm p95 ms): {ceilings}. One measurement decides; there is no automatic retry.",
        "",
        "| Run | Commit | Result | cold p95 ms | warm p95 ms |",
        "| --- | --- | --- | ---: | ---: |",
        "| **this run** | | | " + " | ".join(cell(current.get(k), ceiling_of(current, k)) for k in GATED_METRICS) + " |",
    ]
    for run, metrics in control:
        link = f"[{run.get('databaseId')}]({run.get('url')})" if run.get("url") else str(run.get("databaseId"))
        out.append(
            f"| {link} | `{str(run.get('headSha', ''))[:9]}` | {run.get('conclusion', '?')} | "
            + " | ".join(cell(metrics.get(k), ceiling_of(current, k)) for k in GATED_METRICS)
            + " |"
        )
    if not control:
        out.append("| (no main run with a perf summary found) | | | | |")
    out += ["", *verdict(current, control)]
    return "\n".join(out) + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--current", type=pathlib.Path, required=True)
    parser.add_argument("--runs", type=pathlib.Path, required=True)
    parser.add_argument("--control-dir", type=pathlib.Path, required=True)
    parser.add_argument("--limit", type=int, default=5)
    args = parser.parse_args(argv)
    try:
        runs = json.loads(args.runs.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"perf_control: unreadable run list {args.runs}: {exc}", file=sys.stderr)
        return 2
    print(render(load_summary(args.current), control_rows(runs, args.control_dir, args.limit)), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
