# Chat timeline perf baseline — post-Rive-mobile snapshot (closing measurement)

Captured **2026-09-14** as the closing measurement against the
pre-Rive-mobile baseline (`chat-perf-baseline-pre-rive-mobile-2026-09-13.md`,
PR #1549). The regression gate flagged by that doc
(`letta-mobile-yprhb`) has been resolved. Run spinners and full mascot
system are merged on `main`.

## Scope of this snapshot

- App variant: `com.letta.mobile.dev`
- Device: **Pixel 9 Pro** (daily driver; 2 XL cost canary still pending)
- Window: ~60 seconds of continuous chat interaction with run spinners
  firing and mascot active across states
- Frames captured: **1,860**
- Source: `adb shell dumpsys gfxinfo com.letta.mobile.dev framestats`
- Pipeline: Rive on mobile + chat timeline + run spinner layer

## Headline numbers

| Metric                   | Value         | Pre-Rive (#1549) | Delta          |
|--------------------------|---------------|------------------|----------------|
| Total frames             | 1,860         | 657              | +1,203         |
| **Jank %**               | **1.45 %**    | 2.44 %           | **-0.99 pp better** |
| **P50 frame time**       | **10 ms**     | 8 ms             | +2 ms          |
| **P95 frame time**       | **15 ms**     | 18 ms            | **-3 ms better** |
| **P99 frame time**       | **31 ms**     | 28 ms            | +3 ms (gate)   |
| Missed vsync             | 11 / 1,860 (0.6 %) | 1.2 %     | -0.6 pp better |
| High input latency evts  | 1,948         | 1,149            | +799 (soft flag) |
| Slow UI thread frames    | 26 / 1,860 (1.4 %) | 2.3 %       | -0.9 pp better |
| GPU P50                  | 5 ms          | 4 ms             | +1 ms          |

## Gate verdict (from #1549 re-measurement protocol)

Acceptance gate: P99 within **+5 ms** of pre-Rive baseline (≤ 33 ms
ceiling), jank **≤ 5%**.

- P99 = 31 ms ≤ 33 ms ceiling — **PASS** (by 2 ms)
- Jank = 1.45 % ≤ 5 % target — **PASS** (well under)

The gate passes. The regression flagged by the intermediate 4.75%
measurement is fully closed.

## Comparison across all four snapshots

| Metric     | Aug 28 (#1444) | Sep 12 (`lvfy4`) | Sep 13 (pre-Rive) | Sep 13 (mascot) | **Sep 14 (this)** |
|------------|---------------:|----------------:|------------------:|----------------:|------------------:|
| Jank %     | 8.24           | 3.27            | 2.44              | 4.75            | **1.45**          |
| P50 ms     | ~14            | 11              | 8                 | 6               | **10**            |
| P95 ms     | ~32            | 21              | 18                | 21              | **15**            |
| P99 ms     | 117 / 48       | 34              | 28                | 81              | **31**            |
| Frames     | —              | 2,021           | 657               | 6,910           | **1,860**         |

Read: this snapshot is **strictly better than the pre-Rive baseline on
jank, P95, missed vsync, and slow UI thread**. P50 is 2 ms higher
(probably the run spinner composables taking a small slice on the main
thread; acceptable). P99 is +3 ms (within gate). The architectural
fixes that resolved the 4.75% regression also cleared headroom for
the run spinner layer.

## What changed since the 4.75% regression

The mascot regression was resolved by the same merge wave that landed
run spinners (PRs #1554, #1555, #1557, #1558, #1559). The
architectural patterns that landed:

- **One live surface per agent** — single Rive host per agent, no
  duplicate state-machine instances competing for the GPU.
- **Mascot runtime lifecycle owned by surface** — defensive cleanup on
  activity teardown prevents leaked state machines.
- **`MascotAvatar` owns the live-or-still rule** — the conversation
  row only animates when a run is in flight; the rest of the chat
  timeline draws a still mascot (or the orb). This is what kept the
  jank under control even with run spinners firing.
- **`AgentAvatar` everywhere** — one composable, no per-surface
  re-implementation. Reduces recomposition scope.

## Soft flags (not blockers)

- **High input latency 1,948** — higher than pre-Rive (1,149) but
  much lower than the 6,552 spike during the regression. Worth
  watching on the next input-pipeline change, but not a regression.
- **2 XL cost canary still not measured.** PM owns the re-run before
  this baseline is treated as authoritative for the cost canary.

## Open follow-ups

- `letta-mobile-59ph9` — regression test asserting
  `AvatarDirector.setLookTarget` per-frame delta is bounded. Filed
  to lock in the contract that yprhb verified empirically.
- 2 XL re-run — same protocol, both numbers into
  `perf/baselines.json` before treating as authoritative for merge
  gates.

## Cross-references

- `chat-perf-baseline-pre-rive-mobile-2026-09-13.md` (PR #1549) —
  the gate that caught the regression
- `letta-mobile-yprhb` — regression flag, now verified closed
- `letta-mobile-59ph9` — follow-up test bead
- `letta-mobile-7xnat` — planning epic for semantic-cue layer (next
  direction, not active)
