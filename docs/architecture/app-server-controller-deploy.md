# App Server Controller Deployment Guide

## Overview

The App Server controller architecture supports two deployment paths:

1. **Factory-default**: Meridian points at a stock `letta app-server` (baseline App Server v2 protocol only)
2. **Extended**: Meridian uses an adjusted controller/server that advertises extras beyond the baseline protocol

This document describes how capability negotiation gates which path is active and how users install each variant.

---

## Factory-Default User Story

### Setup

1. User installs Meridian Mobile (Android app)
2. User points Meridian at a stock `letta app-server` (no modifications required)
3. The stock server advertises **only** the baseline App Server v2 protocol:
   - `runtime_start`
   - `input`
   - `stream_delta`
   - `sync`
   - `abort`

### Capability Negotiation

When Meridian connects to the factory-default endpoint:

1. [`CapabilityAdvertiser`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/capability/CapabilityAdvertiser.kt) queries the endpoint for advertised capabilities
2. Factory endpoint returns **empty set** (no extras beyond baseline)
3. [`CapabilityNegotiator`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/capability/CapabilityNegotiator.kt) interprets empty advertisement as baseline-only
4. [`RemoteCapabilities`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/capability/RemoteCapabilities.kt) returns `FACTORY_DEFAULT` (all extras `false`)

### Tool Registry Gating

With factory-default capabilities:

1. [`ExternalToolRegistry`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/extras/ExternalToolRegistry.kt) gates all extra tools based on `RemoteCapabilities`
2. Since all extras are `false`, **no extra tools** are advertised to the App Server
3. Only baseline protocol operations are available

### What Works

- Full chat conversation UI
- User input → agent response turns
- Streaming assistant messages
- Turn completion signals
- Approval workflows (via baseline protocol)
- Runtime lifecycle (`runtime_start`, `abort`, `sync`)

### What's NOT Available

- Image hydration
- Subagent chips/introspection
- Goals tracking
- Slash commands
- Scheduled tasks
- Reflection/introspection APIs
- Slim agent projection
- Conversation-scoped push

**Key Point**: All core chat functionality works via the baseline App Server v2 protocol. No admin shim required.

---

## Extended User Story

### Setup

1. User installs Meridian Mobile (Android app)
2. User installs the **extended controller/server** (see [Install Path](#extended-server-install-path))
3. The extended server advertises **extras** beyond the baseline protocol:
   - `image_hydration`
   - `subagent_chips`
   - `goals`
   - `slash_commands`
   - `schedules`
   - `reflection`
   - `slim_agents`
   - `scoped_push`

### Capability Negotiation

When Meridian connects to the extended endpoint:

1. `CapabilityAdvertiser` queries the endpoint for advertised capabilities
2. Extended endpoint returns the set of extra capabilities it supports
3. `CapabilityNegotiator` parses the advertised set
4. `RemoteCapabilities` returns capabilities with advertised extras enabled

Example:
```kotlin
// Extended endpoint advertises all extras
val capabilities = RemoteCapabilities(
    imageHydration = true,
    subagentChips = true,
    goals = true,
    slashCommands = true,
    schedules = true,
    reflection = true,
    slimAgents = true,
    scopedPush = true,
)
```

### Tool Registry Gating

With extended capabilities:

1. `ExternalToolRegistry` gates extra tools based on enabled `RemoteCapabilities`
2. For each enabled capability, the corresponding tool is advertised to the App Server
3. The App Server receives the full set of external tool definitions during `runtime_start`

Tools advertised when all extras are enabled:
- `image_hydration` (image data in messages)
- `subagent_chips` (subagent state updates)
- `goals` (goal tracking and management)
- `slash_commands` (slash command execution)
- `schedules` (scheduled task management)
- `reflection` (reflection/introspection)
- `slim_agents` (slim agent projection for multi-agent scenarios)

### What Works

Everything from factory-default **plus**:
- All advertised extra capabilities
- Corresponding external tools
- Enhanced UI affordances gated by capabilities

---

## Capability Negotiation Logic

### Capability Gating Flow

```
1. CapabilityAdvertiser.advertise()
   ↓
2. CapabilityNegotiator.negotiate()
   ↓
3. RemoteCapabilities (extras enabled per advertisement)
   ↓
4. ExternalToolRegistry.standard(capabilities)
   ↓
5. Only tools whose capability is enabled are advertised
```

### Classes Involved

| Class | Role |
|-------|------|
| [`CapabilityAdvertiser`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/capability/CapabilityAdvertiser.kt) | Pluggable interface for querying endpoint capabilities |
| [`CapabilityNegotiator`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/capability/CapabilityNegotiator.kt) | Negotiates capabilities with remote endpoint |
| [`RemoteCapabilities`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/capability/RemoteCapabilities.kt) | Capability flags (baseline vs extras) |
| [`ExternalToolRegistry`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/extras/ExternalToolRegistry.kt) | Registry that gates tool advertisement based on capabilities |
| [`DefaultAppServerController`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/DefaultAppServerController.kt) | Controller that wires negotiation + registry together |

### Safe Defaults

The system is **default-safe**:
- Unknown/absent capability advertisement → baseline-only (factory-compatible)
- Forward-compatible: unknown capability strings are ignored
- Graceful degradation: missing extras simply aren't offered

---

## Extended Server Install Path

### Installable: `meridian-app-server`

The extended controller/server is distributed as a standalone installable via the `:appserver-cli` module.

#### Building the Installable

From the `android-compose` directory:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME=/opt/android-sdk \
./gradlew :appserver-cli:distZip
```

This produces:
```
android-compose/appserver-cli/build/distributions/meridian-app-server.zip
```

#### Installing and Running

1. Unzip the distribution:
   ```bash
   unzip meridian-app-server.zip
   cd meridian-app-server
   ```

2. Run the server:
   ```bash
   bin/meridian-app-server --letta-command pnpm --letta-arg dlx --letta-arg @letta-ai/letta-code@0.27.15 --listen ws://0.0.0.0:4500
   ```

3. For non-loopback deployments, add WebSocket auth:
   ```bash
   bin/meridian-app-server --listen ws://0.0.0.0:4500 --ws-auth capability-token --ws-token-file ./token.txt --ws-token-sha256 <sha256>
   ```

#### CLI Integration

The `letta-mobile-cli` also exposes the `app-server-serve` command for debugging:

```bash
cd android-compose
./gradlew :cli:run -PcliArgs="app-server-serve --listen ws://127.0.0.1:4500"
```

See [`android-compose/cli/README.md`](../../android-compose/cli/README.md) for full CLI documentation.

### Smoke Test

The CLI provides `app-server-smoke` to verify a running extended server:

```bash
export APP_SERVER_TEST_URL="ws://127.0.0.1:4500"
export APP_SERVER_TEST_AGENT_ID="agt_x"
export APP_SERVER_TEST_CONVERSATION_ID="conv_x"

./gradlew :cli:run -PcliArgs="app-server-smoke --message 'hello'"
```

Expected output:
```
[app-server] connect ws://127.0.0.1:4500
[lifecycle] Started
[stream] ...
[lifecycle] Completed
```

---

## Deployment Decision Matrix

| Scenario | Deploy Path | Capabilities | External Tools |
|----------|-------------|--------------|----------------|
| User wants basic chat | Factory-default | Baseline only | None |
| User wants advanced features | Extended | Baseline + extras | Advertised extras |
| Unknown/future server | Factory-default (safe fallback) | Baseline only | None |
| Partial-extras server | Extended (subset) | Baseline + advertised subset | Corresponding subset |

---

## Testing

### End-to-End Smoke Test

The [`FactoryVsExtendedDeploymentTest`](../../android-compose/sharedLogic/src/commonTest/kotlin/com/letta/mobile/data/controller/capability/FactoryVsExtendedDeploymentTest.kt) proves the deployment story end-to-end:

1. **Factory endpoint**: empty advertisement → baseline-only → no extra tools
2. **Extended endpoint (all extras)**: full advertisement → all extras → all extra tools
3. **Extended endpoint (partial extras)**: subset advertisement → subset extras → subset tools
4. **Empty advertisement**: treated as factory-default
5. **Unknown capabilities**: gracefully ignored (forward compatibility)

Run the test:
```bash
cd android-compose
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME=/opt/android-sdk \
./gradlew :sharedLogic:jvmTest --tests 'com.letta.mobile.data.controller.capability.FactoryVsExtendedDeploymentTest'
```

---

## Summary

- **Factory-default**: Stock `letta app-server` works out-of-the-box (baseline protocol only)
- **Extended**: Install `meridian-app-server` for extras beyond baseline
- **Capability negotiation**: `CapabilityNegotiator` + `RemoteCapabilities` gate which path is active
- **Tool registry**: `ExternalToolRegistry` advertises tools only when corresponding capabilities are enabled
- **Safe defaults**: Unknown/absent advertisement → baseline-only (factory-compatible)
- **Install path**: `:appserver-cli:distZip` produces the extended server installable
- **Testing**: `FactoryVsExtendedDeploymentTest` proves the end-to-end flow with no real network required

For implementation details, see the controller stack under `com.letta.mobile.data.controller.*`.
