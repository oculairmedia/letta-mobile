# Windows Chat UI Decision

Status: accepted
Date: 2026-06-06
Tracking bead: `letta-mobile-w1f7p`

## Decision

Build the Windows chat surface as a platform-specific Compose Desktop UI over
the shared `:sharedLogic` contracts. Do not move the Android `:feature-chat`
Compose UI into a `sharedUI` module for the first Windows desktop release.

Android chat remains the reference implementation for behavior and visual
semantics, not the source artifact to reuse directly. Windows should consume
the shared timeline, repository/session, A2UI, projection, and token contracts
that now exist in `sharedLogic`, then render those contracts with desktop
layout, input, windowing, and file-system affordances.

## Context

The timeline extraction has already made the conversation engine portable:
timeline models, reducers, transport seams, repository/session contracts,
chat render-model builders, A2UI protocol seams, and design tokens are now in
`sharedLogic/commonMain`. The remaining question is whether the visible chat UI
should be shared through Compose Multiplatform or rebuilt per platform on top
of those contracts.

The current Android chat UI is optimized for a phone app. It assumes Android
activity lifecycle, Hilt ViewModels, Android resources, system bars, IME/nav-bar
insets, haptics, Activity Result pickers, Android clipboard and toast services,
Android image sharing/saving, Android WebView-backed rich content, and mobile
chat layout. Those are not incidental dependencies; they shape the screen
composition and interactions.

The current desktop module already boots a Windows JVM Compose shell and states
the expected layout direction: persistent desktop navigation and conversation
list/detail surfaces rather than Android bottom navigation.

## Options Considered

### Share Android Feature-Chat UI Now

This would create a `sharedUI` module and move chat components such as
`ChatScreen`, `ChatMessageList`, `ChatComposer`, `ChatToolCallCards`, A2UI
renderers, and `ChatImageViewer` into common Compose source.

Upside:

- one visible chat implementation to maintain
- faster superficial parity if the moved code compiles
- shared component tests could eventually cover Android and desktop rendering

Downside:

- high abstraction cost before the desktop product shape is proven
- Android-specific dependencies must be replaced across hot UI paths
- mobile layout and gesture assumptions would leak into Windows
- Hilt, Android resources, window insets, Activity Result, haptics, image
  sharing, Android WebView content, and fullscreen behavior all need seams
- early desktop work would spend most of its time preserving Android behavior
  instead of making the Windows app usable

This is not the right first Windows slice.

### Build Platform-Specific Windows UI Over Shared Logic

This uses the shared contracts as the product boundary and implements desktop
rendering in `:desktop`.

Upside:

- fastest path to a Windows app that feels native enough to use
- keeps the shared boundary pure and testable
- lets desktop use appropriate panes, menus, keyboard shortcuts, file dialogs,
  pointer behavior, and windowing
- avoids destabilizing the mature Android chat route
- creates evidence about which components are genuinely worth sharing later

Downside:

- Android and Windows rendering code can drift
- parity requires explicit shared fixtures and behavior tests
- some low-level visual components may be duplicated before a later extraction

This is the selected strategy.

### Share Only Low-Level Chat Components Later

After Windows has a working chat surface, small pieces may move into a future
`sharedUI` module if they are platform-neutral and already useful on at least
two platforms. Likely candidates are pure text/code/tool-call render rows,
message grouping presenters, loading/error/empty states, and simple token
adapters. Navigation chrome, image pickers, system bars, and platform menus
should stay platform-owned.

This is a later optimization, not a prerequisite for Windows.

## Windows Shared Contracts

The first Windows chat implementation should depend on these shared contracts:

- `com.letta.mobile.data.timeline.Timeline`,
  `TimelineEvent`, `DeliveryState`, `TimelineSyncEvent`, and the timeline
  reducer/runtime helpers for deterministic conversation state.
- `com.letta.mobile.data.chat.projection.ChatRenderModelBuilder`,
  `ChatRenderModel`, `ChatRenderItem`, `ChatMessageListChange`,
  `ChatDisplayMode`, `MessageGrouping`, and `RunStepClassification` for
  platform-neutral message ordering, grouping, and run/tool classifications.
- `com.letta.mobile.data.repository.api.*` repository interfaces and
  `com.letta.mobile.data.session.SessionRepositoryGraph` for platform-neutral
  session wiring.
- `com.letta.mobile.data.transport.api.IChannelTransport`, mobile WebSocket
  frame contracts, SSE frame contracts, replay cursor contracts, and
  `TimelineExternalTransportWriter` for remote transport integration.
- `com.letta.mobile.data.a2ui.*` protocol, action, surface, binding, and data
  model contracts for desktop A2UI rendering.
- `com.letta.mobile.ui.theme.*` design tokens, including spacing, sizing,
  shape, chat dimensions, typography scalars, palette presets, and chat
  background tokens.
- Runtime contracts such as `BackendDescriptor`, `RuntimeEvent`,
  `RuntimeEventOutbox`, `TurnCommand`, `TurnEngine`, MemFS, AgentFile, and
  tool approval contracts when local runtime chat is enabled on desktop.

Windows should not consume Android `R`, Hilt ViewModels, Room/DataStore
implementations, Android paging adapters, `WindowInsets`, Android `View`,
Android haptics, or Android activity APIs.

## Desktop Migration Plan

1. Keep the desktop module as the runnable Windows app and wire it to
   `sharedLogic` contracts, not Android feature modules.
2. Add JVM desktop implementations for repository/session graph construction,
   secure settings, health checks, transport, cursor persistence, and local
   storage where needed.
3. Build a desktop chat shell with a persistent conversation list, detail pane,
   and composer. The shell should consume shared repository/session graph
   contracts and expose desktop keyboard and menu affordances.
4. Render conversations from `ChatRenderModelBuilder` output. Use shared
   `ChatRenderItem` and run/tool classifications as the parity boundary.
5. Implement desktop A2UI renderers over the shared A2UI protocol. Desktop owns
   URL opening, file picking, command routing, focus, keyboard traversal, and
   confirmation surfaces.
6. Adapt shared design tokens into Compose Desktop `Dp`, typography, shape, and
   color values. Android and desktop can differ in layout density while still
   using the same semantic token source.
7. Add fixture tests that feed the same shared timeline inputs into the shared
   projection and assert stable `ChatRenderItem` ordering/grouping. Use those
   tests to prevent Android/Windows chat drift.
8. Revisit `sharedUI` only after the Windows chat surface exists and has at
   least one stable component that both Android and Windows want unchanged.

## If UI Sharing Is Revisited

A future `sharedUI` extraction needs explicit seams for these Android-specific
dependencies before any code move:

- dependency injection and ViewModel creation: Hilt, Android lifecycle, saved
  state, and navigation-scoped ViewModels
- resources: Android `R`, `stringResource`, `painterResource`, fonts, and
  Android-only drawables
- windowing and insets: system bars, IME/nav bars, edge-to-edge, fullscreen
  image viewing, and Activity/window control
- input: touch gestures, pinch zoom, long press, haptics, pointer hover,
  keyboard shortcuts, focus traversal, and context menus
- platform services: clipboard, toasts, URL opening, sharing, saving, file
  dialogs, camera/gallery pickers, and speech recognition
- performance hooks: Android `Choreographer`, Android tracing/logging, and any
  frame timing assumptions
- rich content: Android WebView-backed math/diagram renderers and platform
  markdown/link detection
- attachments: Android URI/content resolver handling, bitmap decoding,
  permissions, and media store writes

Only components free of those dependencies should enter `sharedUI/commonMain`.

## Consequences

Windows gets a shorter path to a bootable, usable desktop chat application.
Android avoids churn in its mature chat route. The cost is that visual parity
must be enforced through shared projection contracts, token adapters, and tests
rather than by compiling one UI tree for every platform.
