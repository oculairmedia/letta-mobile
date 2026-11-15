# What letta-mobile can learn from Capri (capri-desktop)

Status: research note, 2026-11-14. Source read: `github.com/lachydotmcg/capri-desktop` at `448f3d4` (2026-11-15), shallow clone.
Author of this note: PM-letta-mobile. Our side read at `origin/main` `6d66031c8`.

## 0. Ground rules for using this note

- **Licence.** Capri is PolyForm Noncommercial 1.0.0 plus a required credit notice. We may read it and learn from its design.
  We must not copy its code, art, animations or other assets into letta-mobile, which is not a noncommercial-only project.
  Everything below is a design description written in our own words, to be implemented from our own code and tests.
- **What Capri is.** A Windows-first (C#, with Mac/Linux/Quest previews) animated desktop companion that talks, listens,
  joins voice calls, plays games and drives the mouse and keyboard, on the user's own model keys or a local model.
  Unofficial fan project of a third-party character (IP risk is theirs, not a reason to avoid the ideas).
- **What we are.** Kotlin Multiplatform: logic in `sharedLogic/commonMain`, UI in `sharedUI`, Android, desktop and web as
  peers; agents run on our host (App Server + Iroh wrapper). Every idea below is placed in that architecture, shared first,
  with platforms supplying only what they physically own.

Each section: **what Capri does** (observed in its code or docs, with file), **why it matters to us**, **our current state**
(file on our main), **how to implement it cleanly**, **how to verify it**, and **effort / priority**.

---

## 1. Voice: telling a real interruption from a laugh or a "yeah" (talk-over)

**What Capri does.** Two separate rules, one per situation:
- *Off a call* (`app/Asides.cs`, `TalkOver`): while she speaks, the mic level is compared against a learned "share" of her own
  voice in the mic (her echo). It learns that share for her first ~600 ms, or until the mic has risen with her for three chunks,
  capped at 1.5 s (headphones never rise). It also keeps the room's quietest level from the last second, so someone talking over
  her in her first words is not mistaken for echo. It takes **three loud chunks in a row** to stop her, so a cough or a bang does
  not. Between her words (breaths, room tone) the mic is held against her loudest level in the last second, so her own ringing
  echo does not cut her off. Each constant carries the ticket that set it.
- *On a call* (`app/CallEars.cs`, `CallOver`): call audio comes from the call app's own process (Windows process loopback), not
  the mic. A sliding window of 12 chunks must contain **9 loud ones** before she stops; that is "someone really talks over her
  for about a second", while a laugh or a quick "yeah" is a few chunks and is ignored. The audio that interrupted her is **held**
  and released as the next utterance, so the interrupter's words are not lost.

**Why it matters to us.** Our voice mode interrupts on `bargeInSustain = 400 ms` of voice above the echo-guard thresholds
(`sharedLogic data/voice/vad/EndOfTurnPolicy.kt`). That is a single sustain rule; it has no "k of n" window, no learned echo
share, no hold-and-replay of the interrupting speech, and no notion of the device's speaker/headphone path.

**Our current state.** `EndOfTurnPolicy` (thresholds, `bargeInSustain`, `trailingSilence`, `echoGuard`), `TurnTracker`,
`EarSession`; Android exposes `echoCanceler` / `noiseSuppressor` availability (`androidMain .../AndroidAudio.kt`); the Windows
test showed interruption works but the engine has no explicit backchannel rule.

**How to implement it cleanly (shared, one place).**
1. Add a pure `BargeInDetector` in `sharedLogic data/voice/vad/` beside `TurnTracker`: input is the hop stream already flowing
   (`HopObservation` with `outputActive`), output is `Interrupt` / `Hold`. Rule: k-of-n window (default 9 of 12 hops at our hop
   size, i.e. ~1 s), configurable in `EndOfTurnPolicy` as `bargeInWindow` and `bargeInNeeded`, replacing the single sustain.
   Keep `bargeInSustain` only as the derived minimum for back-compat in tests.
2. Learned echo share: a small `EchoShareEstimator` (pure, commonMain) fed with the mouth's output level (we already know when
   our own speech plays) and the mic level, learning for the first N hops of each spoken segment and decaying slowly. The
   detector compares `micLevel - share * outputLevel` against the threshold. No platform code: platforms already supply levels.
3. Hold-and-replay: when an interrupt fires, the ear keeps the hops that caused it (bounded ring buffer, already similar to our
   `preRoll`) and starts the new turn from them, so the first words of the interruption are transcribed.
4. Platform part (desktop only, later): process loopback capture of a chosen call app is Windows-specific and genuinely
   platform-owned; it would be an `AudioSource` implementation in `desktop`, not shared logic. Only if we build call mode.

**Verify.** Table tests over synthetic hop sequences: a laugh (3-4 loud hops), a "yeah" (2-5 hops), a cough mid-reply, a real
interruption (≥ 1 s), and speakers vs headphones (echo share rising or not). Each must give the expected outcome; the existing
`TurnTrackerTest` rows stay green. Then a phone + desktop session with the speaker on.

**Effort / priority.** Medium; **high value** now that voice mode is live on desktop and Android.

---

## 2. Voice: phantom transcripts and only trusting the owner's voice

**What Capri does.** It does not appear to run a stock-phrase hallucination filter on transcripts (none found in `app/` or
`cross/`); instead it prevents most phantom turns upstream: the room level is tracked continuously ("the room's level is its
quietest moment ... creeps up 2% a chunk between lines, never mid-line", `Capri.cs` `Gather`), the wake threshold is relative to
that room level, and audio while she speaks is gated by the talk-over rule above. On calls, **only the owner's mic (or typing)
can wake her or say yes**; speech from the call audio can ask but never confirm ("Friends can ask her for things, and you
confirm" is a security switch, off by default).

**Why it matters to us.** Bead `letta-mobile-r47hv`: phantom "So" / "Thank you." turns in a loud room. Our
`TranscriptPolicy` only drops stock phrases on turns that are both short AND quiet, and our requests use `response_format=json`
(no `no_speech_prob`).

**How to implement it cleanly.**
1. (r47hv, already briefed to Claude) `verbose_json` + `no_speech_prob` / `avg_logprob`, and stock phrases on short turns
   regardless of loudness.
2. Adaptive room floor, as Capri does: make our energy detector's floor track the quietest recent level between turns and never
   move mid-turn (`EnergySpeechDetector` in `sharedLogic data/voice/vad/`). This lowers phantom turns at the source.
3. Owner-only confirmation as a **rule in the shared voice loop**, not in each platform: any utterance that would approve an
   agent action (tool approval, secret request answer) must come from the device's own ear, never from relayed or call audio.
   We already route approvals through the shared approval path; voice only needs to mark an utterance's source
   (`UtteranceSource.OwnerMic | OtherAudio`) and the approval path must refuse `OtherAudio`.

**Verify.** Recorded loud-room fixtures through `EarSession` (no transcript expected); the r47hv tests; a test that an approval
spoken from `OtherAudio` is refused.

**Effort / priority.** Small to medium; **high** (it is a live bug).

---

## 3. Narrating what a coding agent is doing (Capri's "Code/Husk Mode")

**What Capri does.** It installs hooks into Claude Code (`~/.claude/settings.json` or `CLAUDE_CONFIG_DIR`), Codex
(`~/.codex/hooks.json` or `CODEX_HOME`) and OpenCode (a plugin), and turns their events into an embodied, glanceable presence:

| Agent event | Capri shows |
| --- | --- |
| gets your prompt | "Claude's on it." |
| reads, edits, runs, searches | a quiet caption, e.g. "Editing Brain.cs" |
| hits a failure | flinches: "Claude hit a snag." |
| needs your OK | says, out loud if voice is on: "Claude needs your OK. Allow npm test?" |
| finishes | done reaction plus a short answer, read out if you are looking elsewhere |
| starts a subagent | sends out a clone of herself |
| compacts its memory | glitches: "There goes that memory." |

Design rules worth copying: **she only watches** (never approves or controls the agent); "Off" removes only her own hooks
(it recognises its hook entries by an exact command pattern, `Husk.Ours`); secrets are stripped before any event text goes to a
cloud model; a settings folder that is a symlink into a dotfiles repo is handled so her copy does not leak into the repo.

**Why it matters to us.** We already have the events (our own App Server run stream, subagent activity, approvals) and a
mascot. We have no single, calm, glanceable "what is my agent doing right now / does it need me" presence across devices.

**Our current state.** Run lifecycle and tool events on the Iroh stream; `data/presence/SubagentActivity.kt`; approval
requests in the turn engine; the mascot with gaze/rig states (sharedUI). The pieces exist but are not one presence model.

**How to implement it cleanly.**
1. A pure `AgentPresence` reducer in `sharedLogic data/presence/`: input is our existing typed events (turn started, tool call
   started/ended with tool name and target, approval requested, turn failed, turn finished, subagent spawned, compaction);
   output is a small sealed `PresenceState` (`Idle`, `Working(caption)`, `NeedsYou(question)`, `Snag(summary)`,
   `Done(summary)`, `Delegating(count)`, `Compacting`) with a short caption built from tool name + target (no raw content).
2. Views are lenses (per our "no crystallized UI" rule): the mascot maps `PresenceState` to its rig states; an Android
   notification and a desktop tray/overlay can subscribe to the same flow; voice mode can speak `NeedsYou` when the user
   is away. No per-platform logic beyond rendering.
3. Read-only by construction: `AgentPresence` has no path to approve anything; approval stays in the existing approval UI.
4. Redaction: captions use tool name and a path basename only; never raw arguments or outputs.
5. Optional, later: the same reducer can consume external coding agents' events (Claude Code / Codex hooks on the user's
   machine) through the desktop app, which would make our mascot the presence for any agent. If we do that, copy Capri's
   discipline: own-entries-only install/uninstall, exact recognition of our hook lines, symlinked config folders respected.

**Verify.** Reducer tests from recorded event sequences (one per row of the table above); a UI test that the mascot state
follows; the approval path untouched.

**Effort / priority.** Medium; **high product value** (it is the "is my agent stuck / does it need me" question).

---

## 4. A risky-action gate for agents that act on the user's behalf

**What Capri does.** Before its own computer-use agent clicks or types, the step text is classified with explicit vocabularies
(`app/Computer.cs`): **Risky** (send, post, publish, reply, buy, pay, book, delete...), **Costly** (money words, currency
symbols), **Bare** yes/no questions about a step ("Buy it?", "Send it now?") that only the user may answer, **Drafty**
("save it as a draft" is not sending), and explicit negations ("don't send"). Costly and risky steps always reach the user.

**Why it matters to us.** Our agents can call tools that send messages, publish canvases, run shell, or (soon) answer secret
requests; approvals today are tool-level, not intent-level.

**How to implement it cleanly.** Do not copy the regex approach: our tools are typed. Add a declarative **risk class per tool
capability** (`ReadOnly`, `LocalWrite`, `External` (sends/publishes), `Costly` (spends money / credits), `Secret`) in the shared
tool registry, and make the approval policy a pure function of (risk class, user setting, caller identity). Free-text
classification is only a fallback for generic tools (e.g. a browser click), behind the same policy.

**Verify.** Policy table tests; an end-to-end test that an `External` tool from a CLAIMED caller always asks.

**Effort / priority.** Medium; medium priority (useful as agent autonomy grows; aligns with the caller-trust work just merged).

---

## 5. An in-app self-test that runs in CI and on device

**What Capri does.** The real app has a smoke mode (`cross/Smoke.cs`, `app/Capri.cs`) that drives itself for ~35 minutes in CI
(`ci/build-windows.ps1 -SmokeTest -SmokeSeconds 2100`) and records ~2,000 plain-language `PASS ...` lines, one per user-visible
promise ("PASS size: changing her size keeps her sitting on the same spot"). Install and silent uninstall are tested too.

**Why it matters to us.** Today device verification is manual (Emmanuel, or me with adb). We have a few debug-only self-test
receivers (`app/src/debug/.../*SelfTest*.kt`) but no general harness.

**How to implement it cleanly.**
1. A shared `SelfCheck` API in `sharedLogic` (name, run, PASS/FAIL with a plain sentence), checks registered from shared code
   (timeline identity, sync, voice loop with fake audio) and platform code (notifications, audio devices).
2. A debug-only entry point per platform (Android broadcast / instrumentation, desktop `--self-check`) that runs them against
   the real app graph and writes JUnit XML, so our existing CI readers and Kotzilla capture apply.
3. Start with the checks that would have caught today's bugs: timeline row identity across a re-cut, rotation leak, the
   nextRevision rollback.

**Verify.** The harness fails when a check is broken on purpose (same "able to fail" rule as the TLA+ skill).

**Effort / priority.** Medium; high leverage for "merged ≠ verified".

---

## 6. Presence and mascot vocabulary

**What Capri does.** A small, legible set of companion states with distinct animations: idle/blink, talking, being dragged
("tossed"), glitching, napping and booting back up, perching on a window edge, swaying to music. The agent's work states (§3)
reuse the same vocabulary (flinch, glitch, clone).

**How to apply it.** Our mascot already has rig states and gaze; the gap is a mapping from **meaning** to animation that every
surface shares. Define the mapping once (`PresenceState` → mascot pose) and keep the art ours. Do not copy assets.

**Effort / priority.** Small once §3 exists; low on its own.

---

## 7. Process lessons (cheap, immediate)

- **Constants carry their reason and ticket.** Every voice threshold in Capri says what broke when it was different
  ("cut her voice off mid-reply (Mika, Ashy, 2 Nov)"). Adopt this for our voice and timeline thresholds; it would have made
  the October flicker fixes easier to keep.
- **Honest security doc.** Capri's SECURITY.md lists what leaves the machine, per destination and trigger, including its own
  weak spots. A one-page equivalent for letta-mobile (host, wrapper, voice endpoint, Kotzilla in dev builds) would help users
  and reviewers; it fits the voice-setup-on-host decision (`letta-mobile-ynoyg`).
- **Mods with full trust are a cost.** Capri's code mods compile into its own process (Roslyn, no sandbox), gated only by a
  consent prompt. If we ever allow user code (plugins), keep our planned capability-scoped runtime instead.

---

## 8. Suggested order

1. §2 phantom turns (bead r47hv, in progress) plus the adaptive room floor.
2. §1 barge-in k-of-n + echo share + hold-and-replay (new bead).
3. §3 `AgentPresence` reducer and mascot/notification lenses (new bead; product-facing).
4. §5 SelfCheck harness (new bead; process leverage).
5. §4 risk classes per tool capability (new bead; with the caller-trust work).
6. §6 and §7 as small follow-ups.

Each new bead should follow our contract-first rule where it crosses host/client (§3 if external agents' events cross the wire)
and be one PR per stage with red-first tests.
