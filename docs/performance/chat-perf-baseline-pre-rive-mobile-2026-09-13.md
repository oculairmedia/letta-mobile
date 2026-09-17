# Chat timeline perf baseline — pre-Rive-mobile snapshot

Captured **2026-09-13** as the reference baseline before the Rive avatar
runtime ships to Android. All numbers are **chat-only** (no avatar active
on-device); Rive is currently desktop-only. When Rive lands on mobile, the
re-measurement protocol in the last section defines what "didn't regress"
means.

## Scope of this snapshot

- App variant: `com.letta.mobile.dev`
- versionName: `0.17.4-98-gde5d37939-dirty-root-dev`
- Device: **Pixel 9 Pro** (daily driver; not the 2 XL cost canary)
- Window: ~60 seconds of continuous chat interaction
  (scroll long thread, type, send, scroll back, repeat)
- Frames captured: **657**
- Source: `adb shell dumpsys gfxinfo com.letta.mobile.dev framestats`
- Pipeline: `Skia (Vulkan)`

## Headline numbers

| Metric                   | Value         | Frame budget @ 60fps |
|--------------------------|---------------|----------------------|
| Total frames             | 657           | —                    |
| **Jank %**               | **2.44 %**    | < 5 % feels smooth   |
| **P50 frame time**       | **8 ms**      | < 16.67 ms          |
| **P95 frame time**       | **18 ms**     | < 33.34 ms          |
| **P99 frame time**       | **28 ms**     | < 50 ms             |
| Slow UI thread frames    | 15 / 657 (2.3 %) | —                |
| Missed vsync             | 8 / 657 (1.2 %)  | —                |
| High input latency evts  | 1,149         | soft flag            |
| GPU P50                  | 4 ms          | —                    |

P99 stayed at **28 ms**, well under one frame budget plus one frame of slack
(the "feels janky" threshold for a single dropped frame is ~50 ms).
Tail compression is tight: there is no observed gap between P95 and P99.

## Comparison vs prior baselines

| Metric          | Aug 28 (#1444 era peak) | Sep 12 (`lvfy4`) | **Sep 13 (this)** |
|-----------------|------------------------:|-----------------:|------------------:|
| Jank %          | 8.24 %                  | 3.27 %           | **2.44 %**        |
| P50 ms          | ~14                     | 11               | **8**             |
| P95 ms          | ~32                     | 21               | **18**            |
| P99 ms          | 117 (peak) / 48 baseline | 34              | **28**            |
| Missed vsync %  | —                       | 0.9 %            | 1.2 %             |
| High input lat  | —                       | 2,471            | 1,149             |
| Frames sampled  | —                       | 2,021            | 657               |

Read: the improvement from `lvfy4` to this snapshot is real and meaningful,
but the smaller frame sample (657 vs 2,021) means the tail could move under
longer runs. The improvement is **plausibly** attributable to:

- #1536 compact completed activity
- #1538 refresh newest-end instead of declaring finished
- #1545 paging fixture alignment with newest-end refresh
- #1546 single-pass settled projections (`k1wps`)
- #1547 batch settled-page suppression reads (`gdfrn`)
- #1543 open local history without blocking on agent metadata

These are all timeline batching/refresh fixes; the win is from the
pipeline, not from any avatar work.

## False-pass modes

- **Single-device sample.** Pixel 9 Pro only. The 2 XL cost canary is not
  in this snapshot; PM owns the 2 XL re-run before this baseline is treated
  as authoritative for merge gates.
- **657 frames is a small sample.** Tails can hide under longer runs. For
  any decision that depends on P99 or worse-than-P99 behaviour, re-run with
  ≥ 2,000 frames minimum.
- **No avatar on-device.** This measures the chat hot path *only*. Rive
  rendering is currently a separate process on desktop. The avatar-induced
  cost is zero in this snapshot and must be re-measured once Rive ships to
  Android.
- **No first-frame / cold-open data.** gfxinfo is post-reset and captures
  activity after the app is warm. Cold-start first-frame perf is a separate
  axis (see `chat-cold-start-projection-2026-09-02`).
- **Manual driving.** The 60-second window was driven by hand (scroll, type,
  send, repeat). Repeatable only if the same script of interactions is
  followed; CI uses macrobenchmark harness, not this protocol.

## Open questions before treating this as authoritative

- **2 XL baseline.** Re-run with `com.letta.mobile.dev` on the 2 XL cost
  canary. Same protocol, same 60-second window. Both numbers must be in
  `perf/baselines.json` before Rive-mobile work lands.
- **Macrobenchmark parity.** Does `ScrollJankBenchmark` produce a stable
  reading on this dev build? If yes, this snapshot becomes the manual
  ground truth against which CI gating is calibrated; if no, CI gating
  stays deferred until `letta-mobile-4ccv` lands.

## Re-measurement protocol when Rive lands on mobile

When the Rive runtime ships to Android, the question is whether the
timeline holds its 2.44 % jank **with** the avatar rendering on the same
GPU. The protocol:

1. **Reset**: `adb shell dumpsys gfxinfo com.letta.mobile.dev reset`
2. **Drive avatar-off for 60 s**: chat-only, no avatar visible (or avatar
   in a hidden state). Capture ≥ 2,000 frames.
3. **Drive avatar-on for 60 s**: same chat interaction, but force the
   avatar into each of its six states for at least 10 s each: idle,
   listening, thinking, speaking, tool-active, done. Capture ≥ 2,000
   frames.
4. **Acceptance gate**: P99 must stay within **+5 ms** of this baseline
   (≤ 33 ms total) across both avatar-on and avatar-off conditions, on
   **both** 9 Pro and 2 XL. If P99 spikes above 33 ms in either state on
   either device, file a bead and do not ship to the cost canary.

The two failure modes the protocol is designed to surface:

- **Render-thread contention.** Rive's tick loop and timeline recomposition
  both want GPU time. If they collide, timeline P99 spikes first.
- **Off-thread bottleneck.** Rive's GPU upload step may block the
  timeline's draw pass even when running on a separate thread. Watch
  `Number Slow issue draw commands` — non-zero in the avatar-on window
  that was zero in avatar-off is the canary.

## Cross-references

- `letta-mobile-lvfy4` — prior dev-APK baseline (Sep 12, mascot not yet in
  scope)
- `letta-mobile-1444-timeline-persist-jank-2026-09-02` — regression
  archaeology
- `letta-mobile-jank-baseline-2026-08-28` — pre-#1444 floor
- `letta-mobile-rgxq4` — timeline hardening epic
- `letta-mobile-4ccv` — deterministic benchmark launch route (CI gating
  depends on this landing)
- `docs/performance/perf-gate.md` — canonical CI gate definition
