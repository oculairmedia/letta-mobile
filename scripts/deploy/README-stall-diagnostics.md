# App Server stall diagnostics & build isolation

Repository-owned templates for the host-side pieces added under
`letta-mobile-jsfrn`. Like the other files in this directory these are
**templates, not live units** — installing them is a separate step.

## The incident these exist for

On 2026-08-23 the App Server stopped responding. All three Iroh peers dropped
with `closeReason=timed out`, the in-flight turn died with
`activeTurn.releasedWithoutTerminal`, and the wrapper logged:

```
App Server connection lost: 1006 Connection was closed without close frame
```

That 1006 is not a cause, it is a verdict. `letta.js` (0.30.29) pings every
30s and calls `client.terminate()` — an abrupt close, which the peer sees as
1006 — when no pong has arrived for 90s:

```js
APP_SERVER_HEARTBEAT_INTERVAL_MS = 30000
APP_SERVER_PONG_TIMEOUT_MS       = 90000
```

`lastPongAt` is only updated in `socket.on("pong")`. That is wall-clock elapsed
time on a **single-threaded event loop**: when the loop is blocked the server can
neither send pings nor process pongs, so on unblocking it sees a stale
`lastPongAt` and kills a peer that was answering the whole time. It cannot tell
"peer is dead" from "I was too busy to listen". Confirmed recurring — 46
`App-server terminating unresponsive socket` entries in one log generation.

**The trigger is host memory pressure, not App Server CPU work.** The watchdog
caught a live stall at 15:50:48:

```
FAIL 10050 timeout_after_10000ms connect=3 upgrade=-1
```

`connect=3ms` means the kernel completed the TCP handshake; `upgrade=-1` means
Node never ran the upgrade handler within 10s. Concurrently: load average
**30.64** at **70–83% idle CPU**, `si=4540` swap-in, ~10.3GB swapped, App Server
at 0.8% CPU. It was not computing — it was blocked faulting its own pages back
in from swap.

That also explains the simultaneity nothing else did: under a host-wide swap
storm the wrapper stalls too, so its QUIC keepalives stop (all peers time out)
*and* the App Server misses pongs (1006). One cause, both symptoms, in the
observed order — QUIC idle timeouts are shorter than the 90s pong timeout.

## What is here

| File | Purpose |
|---|---|
| `meridian-stall-watchdog.service` | Samples App Server WS round-trip latency; captures evidence on a stall |
| `stall-watchdog.sh` | The sampling loop |
| `appserver-probe.cjs` | Dependency-free RFC6455 probe (raw `net`/`crypto`) |
| `meridian-builds.slice` | Resource domain for agent-spawned builds |
| `adopt-builds.sh` | Migrates build daemons out of the App Server cgroup |
| `meridian-build-adopter.{service,timer}` | Runs the adopter every 60s |
| `meridian-appserver.memory-protection.conf` | `MemoryLow=1G` drop-in |
| `meridian-iroh-wrapper.memory-protection.conf` | `MemoryLow=1500M` drop-in |

## Install

```bash
# 1. Scripts
install -m 0755 -D scripts/deploy/adopt-builds.sh      /usr/local/lib/meridian/adopt-builds.sh
install -m 0755 -D scripts/deploy/stall-watchdog.sh    /usr/local/lib/meridian/stall-watchdog.sh
install -m 0755 -D scripts/deploy/appserver-probe.cjs  /usr/local/lib/meridian/appserver-probe.cjs

# 2. Units
install -m 0644 scripts/deploy/meridian-builds.slice           /etc/systemd/system/
install -m 0644 scripts/deploy/meridian-build-adopter.service  /etc/systemd/system/
install -m 0644 scripts/deploy/meridian-build-adopter.timer    /etc/systemd/system/
install -m 0644 scripts/deploy/meridian-stall-watchdog.service /etc/systemd/system/

# 3. Memory protection drop-ins (note the .d/ directory names differ from the filenames)
install -m 0644 -D scripts/deploy/meridian-appserver.memory-protection.conf \
  /etc/systemd/system/meridian-appserver.service.d/memory-protection.conf
install -m 0644 -D scripts/deploy/meridian-iroh-wrapper.memory-protection.conf \
  /etc/systemd/system/meridian-iroh-wrapper.service.d/memory-protection.conf

systemctl daemon-reload
systemctl enable --now meridian-stall-watchdog.service
systemctl enable --now meridian-build-adopter.timer
```

`MemoryLow` is a cgroup attribute, so `daemon-reload` applies it **live** — the
App Server does not need restarting. `JAVA_OPTS` in `iroh-wrapper.env.example`
takes effect on the wrapper's next restart; to arm the running process without
one, use the `jcmd VM.log` command documented in that file.

## Two triggers, one signature

Both observed stalls produced an identical probe result — and they had different
causes. **The probe alone cannot tell them apart**, which is why the watchdog now
records App Server RSS on every sample:

| | load | swap-in | App Server RSS | cause |
|---|---|---|---|---|
| 15:50:48 | 30.64 | `si=4540` | 227MB | host memory pressure; blocked faulting its own pages in |
| 17:45:23 | 3.87 | `si=8` | **2700MB** | V8 full GCs crowding the 4.05GB default heap ceiling |

The units in this directory address the **first** trigger only. The second needs
a manual edit to `/etc/meridian/appserver.env` (there is no template for it in
this repo — it holds provider credentials):

```
NODE_OPTIONS=--import=file://…/letta-code-patch-register.mjs --max-old-space-size=8192
```

That takes the same live set from 67% to ~34% of the ceiling, so full GCs become
far rarer. Individual pause length scales with the live set and is **unchanged** —
this buys headroom, it does not fix the cause.

The cause is upstream: `loadConversationMessages` (`letta.js:129917`) reads
transcripts with the **unbounded** `readJsonlFile` (whole file + `JSON.parse` per
line, then five more full passes) and caches the result in
`localMessagesByConversationKey`, which is only ever evicted in `deleteAgent`. A
bounded `readJsonlFileSuffix` exists and is used by its neighbours at `129809`
and `129887` — but not here. One 203MB / 47,949-row transcript dominates this
store; the next largest is 28MB and all 2224 together total just 0.7GB.

Deliberately **not** adding `--trace-gc`: it logs every scavenge and would drown
the `[Listen V2]` lines in the same log. The RSS column is what actually
distinguishes the two modes.

## Reading the output

- `/var/log/meridian-probe-latency.log` — round-trip time series. **Healthy
  baseline is 5–9ms.** A `FAIL` with `connect=` set but `upgrade=-1` means the
  App Server's event loop was blocked; the kernel accepted the socket and Node
  never got to it.
- `/var/log/meridian-stalls/<ts>/` — thread dumps and host state. Two wrapper
  dumps 5s apart: **diff them**. Identical stacks = genuinely stuck; moved =
  merely busy. One dump cannot tell those apart, which is the whole question.
- `/var/log/meridian-iroh-wrapper-gc.log` — GC and safepoint pauses.

On the next occurrence, the latency series decides which side owns the problem:
a spike means the App Server's loop stalled (the wrapper is innocent); flat
latency while the wrapper stalls means the wrapper is at fault.

## Gotchas worth not rediscovering

- **`appserver-probe.cjs` deliberately implements raw RFC6455.** The only `ws`
  module on the host lives under a version-pinned `/root/letta-code-<ver>/`
  path that moves on every upgrade, and a diagnostic must not break when the
  thing it diagnoses is upgraded.
- **Watchdog `CPUQuota` must stay ≥25%.** At 10% the probe reported ~95ms
  against a true 8ms round trip — node's own startup was being throttled, so the
  series measured the watchdog instead of the App Server.
- **Build isolation migrates rather than intercepts.** Two alternatives were
  rejected: patching `android-compose/gradlew` (it is the *generated* Gradle
  wrapper — regenerating it silently drops the change), and replacing `$SHELL`
  for the App Server (`letta.js` honours `process.env.SHELL`, but the blast
  radius is every command every agent runs). cgroup v2 permits moving a running
  process by writing its PID to the target `cgroup.procs`, and memory charging
  follows it — verified: a 300MB allocation inside the adopted cgroup moved
  `meridian-builds.slice` `memory.current` 0 → 300MB → 0, even though
  `cgroup.subtree_control` is empty and the leaf has no controller files
  (charges fall to the nearest controller-enabled ancestor).
- **The adopter uses a strict allowlist** and never touches the App Server's
  MainPID. Short-lived agent shell commands stay where they are; only the
  long-lived build JVMs move.
- **Side benefit:** once adopted, builds survive an App Server restart.
  `meridian-appserver` uses `KillMode=control-group`, so it otherwise reaps
  in-flight builds sharing its cgroup.
- **Not fixed here:** the upstream wall-clock pong timeout. These changes attack
  the trigger (memory pressure), not the escalation from "slow" to "session torn
  down". That fix belongs in `@letta-ai/letta-code`.
