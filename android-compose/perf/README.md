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
