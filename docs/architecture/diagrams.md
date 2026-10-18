# Letta Mobile Architecture Diagrams

**Status:** Current structural diagrams; audited against module builds and transport implementations on 2026-10-18.

## Product modules

```mermaid
flowchart TB
    Android[":app / :feature-chat"] --> UI[":sharedUI"]
    Desktop[":desktop"] --> UI
    Browser[":web — wasmJs"] --> UI
    Android --> Data[":core:android-data — Room / DataStore / DI"]
    Data --> Logic[":sharedLogic"]
    UI --> Logic
    UI --> Avatar[":avatar:core / renderer-rive"]
    UI --> Draw[":drawbox"]
    Logic --> Contracts[":core:ids / :core:runtime"]
    Android --> Design[":designsystem — remaining Android wrappers"]
    Design --> UI
    PluginView[":plugin-view — platform plugin containers"] --> UI
```

This diagram shows major dependencies, not every test or source-set edge. [Gradle settings](../../android-compose/settings.gradle.kts) and the [module map](kmp-module-map.md) include all projects. The former `:bot`, `:core`, `:feature-admin`, `:feature-home`, `:feature-settings` and `:plugin-host` nodes are not current Gradle projects.

## App Server connection and frame delivery

```mermaid
flowchart LR
    Socket["One /ws socket"] --> Decode["AppServerProtocol"]
    Decode --> Delivery["AppServerInboundDelivery"]
    Delivery --> Ordered["inboundFrames — arrival order"]
    Delivery --> Control["controlFrames — request correlation"]
    Delivery --> Stream["streamFrames — derived observer view"]
    Ordered --> Router["AppServerRuntimeEventRouter"]
    Router --> Fanout["RuntimeEventFanout — bounded subscriber queues"]
    Fanout --> Turn["AppServerTurnEngine / turn subscribers"]
```

[KtorAppServerWebSocketTransport](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/appserver/KtorAppServerWebSocketTransport.kt) owns one connection generation. [AppServerInboundDelivery](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/appserver/AppServerInboundDelivery.kt) preserves arrival order for the main path; do not merge the two derived views to reconstruct that order. A full transport queue fails the generation. [RuntimeEventFanout](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/fanout/RuntimeEventFanout.kt) uses nonblocking offers and detaches a full subscriber, isolating that subscriber from other runtimes.

## Hosting and client paths

```mermaid
flowchart TB
    Client["Android / Desktop / browser"] --> Channel["Shared session channel"]
    Channel --> Iroh["Iroh host wrapper"]
    Channel --> Direct["Direct WebSocket dial"]
    Iroh --> AppServer["Letta App Server"]
    Direct --> AppServer
    Local["LocalAppServerHost"] --> AppServer
```

Android's embedded launcher runs libnode in process; JVM hosts launch a process. Both use [LocalAppServerHost](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/runtime/host/LocalAppServerHost.kt). The production host wrapper is `:iroh-wrapper-cli`; `:appserver-cli` supervises/probes an App Server and is a separate artifact. See [deployment guide](app-server-controller-deploy.md).
