# perf/

Performance baseline + CI regression gate config for letta-mobile.
Tracks issue `letta-mobile-o7ob.4.1`.

## Files

- `baselines.json` — canonical per-metric baseline (median of the last
  green CI run) and tolerance envelope. `null` baselines are skipped by
  the gate until a human seeds them.
- `check_baselines.py` — parser that reads macrobenchmark JSON output
  and fails the build when a metric regresses past tolerance.

## Workflow

1. CI runs `:macrobenchmark:connectedBenchmarkAndroidTest` against a
   lab device (or Firebase Test Lab).
2. CI runs `python perf/check_baselines.py <path-to-outputs>` which
   exits non-zero on regression.
3. Re-baselining is a deliberate commit:
   ```
   python perf/check_baselines.py <path> --rebaseline
   git diff perf/baselines.json
   git commit -m "perf: rebaseline after <reason>"
   ```

See `letta-mobile-o7ob.4.1`.
