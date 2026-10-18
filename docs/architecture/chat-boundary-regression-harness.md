# Chat transport and timeline boundary regression harness

**Status:** Current test-suite map; source-audited 2026-10-18. Test commands below were checked against declared modules and test source locations, not executed by this documentation audit.

The old May matrix used removed `:core` tasks and asserted individual test names that have since moved or disappeared. Use suite filters over the current ownership boundaries:

| Boundary | Current suites | Gradle task |
|---|---|---|
| Portable send orchestration and queue cleanup | `ChatSendCoordinatorConversationSwitchTest`, `ChatSendCoordinatorConcurrentConversationsTest`, `ChatSendCoordinatorCleanupTest` | `:sharedLogic:jvmTest` |
| Android send adapter | `WsChatSendCoordinatorTest`, `WsChatSendCoordinatorQueueTest` | `:feature-chat:testRootDebugUnitTest` |
| App Server turn execution, scope and generation isolation | `AppServerTurnEngineConcurrentConversationsTest`, `AppServerTurnEngineExternalToolGenerationTest`, `AppServerTurnEngineSessionLossTest`, `AppServerTurnEngineTurnBoundaryTest` | `:sharedLogic:jvmTest` |
| Single socket readiness, drain and arrival-order delivery | `KtorAppServerWebSocketTransportTest`, `KtorAppServerWebSocketTransportLifecycleTest`, `KtorAppServerWebSocketTransportIntegrationTest` | `:sharedLogic:jvmTest` |
| Runtime subscriber pressure/control replay | `RuntimeEventFanoutTest`, `RuntimeEventFanoutOverflowTest`, `RuntimeEventFanoutPendingControlTest` | `:sharedLogic:jvmTest` |
| Gateway/repository acquisition and hydration | `TimelineRepositorySingleFlightHydrationTest`, `TimelineRepositoryAcquisitionProvenanceTest`, `TimelineRepositoryIrohRegressionTest` | `:sharedLogic:jvmTest` |
| Canonical semantic writes, paging and presentation | `CanonicalTimelineEngineTest`, `CanonicalTimelinePagingTest`, `CanonicalTimelinePresentationTest`, `CanonicalTimelineCoordinatorIsolationTest` | `:sharedLogic:jvmTest` |
| Android repository cache/loop integration | `TimelineRepositoryTest` | `:core:android-data:testDebugUnitTest` |
| Desktop canonical host lifetime | `DesktopCanonicalTimelineRuntimeTest` | `:desktop:test` |

Run from `android-compose/` with environment paths set per [CONTRIBUTING](../../CONTRIBUTING.md). For example:

```bash
./gradlew :sharedLogic:jvmTest --tests '*ChatSendCoordinatorCleanupTest' --tests '*AppServerTurnEngineConcurrentConversationsTest' --tests '*RuntimeEventFanoutOverflowTest'
./gradlew :feature-chat:testRootDebugUnitTest --tests '*WsChatSendCoordinatorTest' --tests '*WsChatSendCoordinatorQueueTest'
./gradlew :core:android-data:testDebugUnitTest --tests '*TimelineRepositoryTest'
```

Sources are under [sharedLogic/commonTest](../../android-compose/sharedLogic/src/commonTest/kotlin/com/letta/mobile/data/), [sharedLogic/jvmTest](../../android-compose/sharedLogic/src/jvmTest/kotlin/com/letta/mobile/data/), [feature-chat tests](../../android-compose/feature-chat/src/test/java/com/letta/mobile/feature/chat/), [Android data tests](../../android-compose/core/android-data/src/test/java/com/letta/mobile/data/timeline/) and [desktop tests](../../android-compose/desktop/src/test/kotlin/com/letta/mobile/desktop/data/).

This is a boundary-selection guide, not a replacement for required CI. Tests using fake channels do not prove live Iroh, a model provider, JNI, screen-off Android delivery or a remote host's package version. The opt-in live probes and device protocols preserve those separate evidence requirements.

See [ingestion rules](external-transport-timeline-rules.md), [current module map](kmp-module-map.md) and [snapshot policy](timeline-rest-snapshot-policy.md).
