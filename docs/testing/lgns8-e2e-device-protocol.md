# lgns8 App Server v2 — On-Device End-to-End Acceptance Protocol

Feeds the lgns8.10 acceptance gate; closes the device-evidence gaps held open on
lgns8.19 and noted across the epic. Every step names its evidence artifact.

Status: landed 2026-07-31 alongside the `letta-mobile-wxy4s` connection-liveness
fix (step 4 below). Steps are still awaiting their on-device runs — this document
is the protocol, not the evidence.

## Preconditions
- Pixel 9 Pro (`com.letta.mobile.dev`) + Compose Desktop, both rebuilt from the
  post-train main tip; record `versionName` / git SHA for both.
- Wrapper + appserver healthy on the Meridian host (`peer list` local dial OK).
- `adb logcat` capture running for the whole session (`Telemetry/*` tags).
- Wrapper log position marked (`wc -l /var/log/meridian-iroh-wrapper.log`).

## 1. Concurrent conversations (or40x + 8xxzv, the epic's headline)
1. Start a long tool-turn in conversation A (Meridian).
2. Switch to conversation B: **no thinking indicator for A's work** may appear.
3. Send into B while A streams: **both turns run and settle independently**
   (pre-fix: B fast-failed `iroh_turn_engine_busy`; pre-or40x: both froze).
4. Evidence: logcat shows `activeTurn.concurrentLeases count=2`, no
   `turn.superseded_nonterminal`, no `ws.turnState.evictedBeforeTerminal`;
   both terminals present; screenshots of both settled conversations.

## 2. Stop button (lgns8.19 device evidence — closes the bead)
1. Start a Bash tool-turn on the Pixel; press Stop mid-stream.
2. UI must show "stopping…" (not idle) until the terminal lands; composer blocked.
3. Evidence: tool process terminated within ~2s on the host
   (`ps` before/after on the tool child); `interrupt.cancelRequested` →
   `interrupt.terminalAfterCancel` telemetry with latency; cancelled terminal
   frame rendered; transcript (messages.jsonl) has **no orphan tool_call**
   (strict-provider validation passes on the next send).
4. Repeat on Desktop (first-ever real desktop abort): same evidence set.
5. Negative: press Stop twice fast → forced local clear telemetered, composer
   usable, next send works (no `Sending` wedge).

## 3. Image pipeline (iej8j device half)
1. Send a photo from the Pixel picker in conversation A; confirm model comments on it.
2. Switch A→B→A: image message survives re-entry (lgns8.20 class).
3. Send a second image; confirm the latest image survives the 8MB cap path
   (`hydrate.image_dropped` must NOT fire for it).
4. Evidence: `strip.parts_stripped` fires for the older image only (6ppdr now
   live on v3); provider request carries a valid `image_url` (proxy log or
   `ImagePipeline` telemetry); screenshots.

## 4. Restart-replay + reconnect (21.1.1 / q0jti / wxy4s)
1. Mid-conversation, `systemctl restart meridian-appserver`: wrapper reattaches,
   next send works without app restart.
2. `systemctl restart meridian-iroh-wrapper`: **both apps must recover without
   manual redial.** `letta-mobile-wxy4s` landed the application-level liveness
   probe that makes this possible: the transport now issues a periodic
   `health.check` over a fresh QUIC bidi stream, and N consecutive failures report
   the loss into the existing supervisor redial path. Before it, nothing detected
   the drop at all — the 15s unacked keepalive datagram kept resetting the local
   QUIC idle timer, so a black-holed peer looked healthy indefinitely (the
   2026-07-31 ~40-minute perceived outage).
3. Expected recovery envelope: detection within roughly one probe interval plus
   the failure budget (~45s worst case, sub-second on Android screen resume via
   `probeNow()`), then supervisor backoff (500ms → 8s) before the redial. Desktop
   additionally re-hydrates the open conversation on the post-redial `Connected`,
   and rebuilds the gateway once if the outage exceeds 60s.
4. Evidence: `IrohLiveness probe.failed` / `probe.declared_dead` telemetry on both
   clients, followed by `IrohSupervisor redial.scheduled` and a fresh
   `probe.start` on the new session id; a visible Reconnecting/stale indicator
   during the dead window (never silently-cached data); wrapper log
   `generation.ready`, `reattached runtimes`, both clients' sends complete
   post-restart; subagent chips still present (registry file survives under
   /var/lib/meridian once r6221 deploys).

## 5. Cross-device realtime (eaczz.8 two-client gate)
1. Desktop and Pixel both open on conversation A.
2. Send from Desktop: Pixel renders the turn live (no reload); and vice versa.
3. Evidence: wrapper `viewerCount=2` for the conversation during fanout; both
   timelines converge to identical terminal state (message ids match).

## 6. External tools (lgns8.17)
1. Trigger a tool the wrapper implements → executes and returns.
2. Trigger an unadvertised/unknown tool → matched `is_error` response, turn
   TERMINATES (no hang), UI shows the failure honestly.
3. Evidence: `external_tool` telemetry, turn terminal in ≤ deadline+margin.

## 7. Subagent chips (22.8)
1. Run a turn that spawns subagents; chips appear on both clients.
2. Restart the wrapper mid-life: chips resurface after reconnect (not lost,
   not duplicated); orphaned ones render as cancelled.
3. Evidence: registry JSON on disk; `lifecycle.*` telemetry; screenshots.

## Sign-off table
| # | Area | Pass/Fail | Evidence link | Notes |
|---|------|-----------|---------------|-------|
