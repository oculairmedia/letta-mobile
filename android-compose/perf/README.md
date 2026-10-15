# perf/

Performance baseline + CI regression gate config for letta-mobile.
Tracks issue `letta-mobile-o7ob.4.1`.

## Files

- `baselines.json` — canonical per-metric baseline (median of the last
  green CI run on the API 33 emulator) and tolerance envelope.
- `baselines.schema.json` — schema for the baseline file.
- `check_baselines.py` — parser that reads macrobenchmark JSON output
  and fails the build when a metric regresses past tolerance.
- `test_check_baselines.py` — regression tests for the parser and
  rebaseline flow.
- `perf_control.py` (+ `test_perf_control.py`) — on a breach, renders this run
  next to the last 5 main runs in the job summary. There is no automatic
  retry; a human reruns only when that control shows the runner moved.

The `perf-scripts` job runs `python3 -m unittest discover -s perf -p 'test_*.py'`
and `scripts/ci/baseline-profile-staleness.py` (warns when the committed
Baseline Profile no longer resolves) on every PR, main push and nightly run.
The `perf-gate` macrobenchmark itself runs nightly and on main pushes, not on PRs.

## Workflow

1. CI runs the startup subset of
   `:macrobenchmark:connectedBenchmarkAndroidTest` against the canonical API 33
   `pixel_6` emulator.
2. CI runs `python perf/check_baselines.py <path-to-outputs>` which
   exits non-zero on regression.
3. Re-baselining is a deliberate commit:
   ```
   python perf/check_baselines.py <path> --rebaseline
   git diff perf/baselines.json
   git commit -m "perf: rebaseline after <reason>"
   ```

If the baselines are still `null`, verify mode exits with code `2`
until a maintainer seeds them on the canonical CI device.

`ScrollJankBenchmark` and `ComposerTypingBenchmark` remain in the repo for local
investigation, but they are non-gating until the app exposes a deterministic
benchmark launch surface on CI.

See `docs/performance/perf-gate.md` and `letta-mobile-o7ob.4.1`.
