# Letta Timeline POC CLI

Proof of concept for Matrix-style message sync architecture.

**Purpose:** Validate the otid-based timeline design in isolation before touching mobile code.

## Prerequisites

- Java 21+ (`JAVA_HOME` or default JDK on `PATH`)

## Quick start

```bash
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
- `LETTA_TOKEN` — defaults to embedded admin token

## Commands

- `send <message>` — send a message, optimistic append + stream
- `wait [ms]` — pause REPL to let async events complete (default 10000)
- `history` — render full timeline
- `status` — show counts by state
- `stress <n>` — fire N concurrent sends to test race conditions
- `reset` — create a fresh conversation
- `quit` — exit

## Architecture

Three files, ~500 LOC:

- `Timeline.kt` — data model with invariants (ordered events, unique otids)
- `LettaApi.kt` — minimal HTTP client with streaming support
- `SyncLoop.kt` — THE single mutator. All state changes go through here.

Matrix-style txn_id pattern via Letta's `otid` field. Local events are shown optimistically; server echoes them back with our otid, and we swap Local→Confirmed in place.

## Validation

See `/opt/stacks/letta-mobile/docs/architecture/poc-validation-results.md` for scenario results.

## Related beads

- `letta-mobile-a44b` — epic
- `letta-mobile-mdlg` — API validation (DONE)
- `letta-mobile-1786` — this POC
- `letta-mobile-qiad` — Timeline model
- `letta-mobile-cuyq` — SyncLoop
- `letta-mobile-iuq8` — validation scenarios
