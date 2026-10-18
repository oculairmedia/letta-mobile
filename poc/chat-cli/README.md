# Letta Timeline POC CLI

**Status:** Historical REST/SSE timeline experiment (April 2026), not a production transport.
Source-audited 2026-10-18 against this directory's `Main.kt`, `SyncLoop.kt`, `LettaApi.kt`,
`Timeline.kt` and standalone build. The app now uses the App Server/Iroh path in sharedLogic.

**Purpose:** Validate the otid-based timeline design in isolation before touching mobile code.

## Prerequisites

- Java 21+ (`JAVA_HOME` or default JDK on `PATH`)

## Quick start

```bash
# From the repository root:
cd poc/chat-cli

# Build
./gradlew installDist

# Run tests
./gradlew test

# Launch CLI (creates new conversation)
./build/install/chat-cli/bin/chat-cli

# Launch CLI with existing conversation
./build/install/chat-cli/bin/chat-cli <agent-id> <conversation-id>
```

## Environment

- `LETTA_BASE_URL` — defaults to `http://192.168.50.90:8289`
- `LETTA_TOKEN` — required operator override for any intentional live run. The old code has a hard-coded fallback credential; it must not be assumed valid or safe for a current deployment. This audit did not use it.

## Commands

- `send <message>` — send a message, optimistic append + stream
- `wait [ms]` — pause REPL to let async events complete (default 10000)
- `history` — render full timeline
- `status` — show counts by state
- `stress <n>` — fire N concurrent sends to test race conditions
- `reset` — create a fresh conversation
- `quit` — exit

## Architecture

Core model/transport/mutator files (plus the `Main.kt` REPL):

- `Timeline.kt` — data model with invariants (ordered events, unique otids)
- `LettaApi.kt` — minimal HTTP client with streaming support
- `SyncLoop.kt` — THE single mutator. All state changes go through here.

Matrix-style txn_id pattern via Letta's `otid` field. Local events are shown optimistically; server echoes them back with our otid, and we swap Local→Confirmed in place.

## Validation

See [historical scenario results](../../docs/architecture/poc-validation-results.md). Those captures were not re-run against a live service in this audit.

## Related beads

- `letta-mobile-a44b` — epic
- `letta-mobile-mdlg` — API validation (DONE)
- `letta-mobile-1786` — this POC
- `letta-mobile-qiad` — Timeline model
- `letta-mobile-cuyq` — SyncLoop
- `letta-mobile-iuq8` — validation scenarios
