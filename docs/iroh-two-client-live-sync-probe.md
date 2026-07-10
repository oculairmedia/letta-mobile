# Iroh two-client live-sync probe (headless)

`app-server-iroh-two-client-probe` reproduces Emmanuel's entire manual
two-device test loop with **no human and no physical screens**. It is the
headless acceptance test for the Iroh multi-client live-sync feature
(letta-mobile-r3i1z): two physical screens should never be needed to validate
multi-client sync again.

## What it does

It dials **two real mobile client transports** (`IrohChannelTransport` — the
exact production stack, including the observer-ingestion loop from `8ef785638`
and the re-subscribe-on-reconnect fix from r3i1z) to the **same** server, both
subscribed to the same conversation, and asserts live multi-client sync through
the **full real stack** (real Iroh QUIC, real server-side fanout, real observer
reducer):

| Round | Action | Assertion |
| --- | --- | --- |
| `round1_A_sends_B_observes` | A sends a turn | B receives it LIVE: user echo (matching text) + cumulative assistant stream + exactly one completed terminal, in order |
| `round2_B_sends_A_observes` | B sends a turn | A receives it symmetrically |
| `round3_after_B_redial_A_sends_B_observes` | B drops + redials (no manual re-hydrate), then A sends | B **auto-re-subscribed** on its fresh Ready (deliverable A) and still receives the turn live |

Each assertion prints one greppable `[iroh-2client] check=<name> PASS|FAIL` line;
the process exits `1` if any check fails (CI-friendly). Add `--json` for a
machine-readable summary.

## Modes

### Hermetic (CI, no backend, no devices)

The hermetic stub server (`app-server-serve-iroh-stub`) shares **one**
`ConnectionRegistry` across connections, so its server-side turn fanout is
identical to the deployed wrapper. Deliverable A (re-subscribe) lives entirely
client-side, so the redial round is meaningful hermetically too.

```bash
scripts/iroh_two_client_hermetic.sh                 # default: --timeout-ms 90000
scripts/iroh_two_client_hermetic.sh --timeout-ms 60000
scripts/iroh_two_client_hermetic.sh --skip-redial   # isolate the base two-way sync
```

The script boots the stub on free ports with a throwaway key, waits for its
printed ticket, runs the probe against it, and propagates the probe exit code.

### Live wrapper (real deployed server)

Point `--backend` at the live wrapper's Iroh URL (node id + `host:port` from
`/var/log/meridian-iroh-wrapper.log`, e.g. `192.168.50.90:4501`). The redial
round requires the wrapper build to carry deliverable A; if it does not, round 3
fails loudly (`observer_never_received_terminal`) — that IS the signal that the
wrapper needs the re-subscribe fix.

```bash
cd android-compose
./gradlew --quiet :cli:run -PcliArgs='"app-server-iroh-two-client-probe" \
  "--backend" "iroh://<node-id>@192.168.50.90:4501" \
  "--token" "<auth-token>" \
  "--agent-id" "<agent-id>" \
  "--conversation-id" "<shared-conv-id>" \
  "--json"'
```

## Options

| Option | Default | Purpose |
| --- | --- | --- |
| `--backend` | (required) | Iroh backend both clients dial (`iroh://<node-id>@<host:port>` or ticket) |
| `--token` | `$LETTA_IROH_AUTH_TOKEN` | Bearer token for the auth frame (shared by both clients) |
| `--agent-id` | `""` | Agent id both clients drive (blank = server default) |
| `--conversation-id` | `2client-conv-<epoch>` | Shared conversation both clients view |
| `--message` | `two-client ping` | User message text sent each round |
| `--timeout-ms` | `60000` | Per-round wall budget for the observer to receive the turn |
| `--settle-ms` | `1500` | Post-subscribe / post-redial settle before a send (viewer registration lands) |
| `--skip-redial` | off | Skip round 3 (redial + auto-re-subscribe) to isolate the base two-way sync |
| `--json` | off | Also print the machine-readable JSON summary |

## Relationship to the other Iroh gates

- `IrohFanoutServeTest` covers mid-turn join / slow-client / fault isolation
  hermetically at the **serve seam** (server-side fanout in isolation).
- `IrohObserverIngestionTest` covers the **client-side observer path** in
  isolation (injected frames → reducer).
- This two-client probe is the **end-to-end** proof: two full real client
  stacks over real QUIC, so it validates the whole feature (fanout + observer
  ingestion + re-subscribe-on-reconnect) composed together.
