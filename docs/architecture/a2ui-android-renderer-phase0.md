# A2UI Android Renderer Phase 0 Decision

Date: 2026-05-17
Bead: `letta-mobile-51xm.1`

## Decision

Build the Letta Mobile A2UI renderer as a new in-repo Android module rather than forking or vendoring an existing renderer wholesale.

Use the official `google/A2UI` v0.9 specification and Kotlin agent SDK as the protocol reference. Use `TanXudong-Vivo/A2UI-Android-Renderer` and `lmee/A2UI-Android` as implementation references for renderer shape, streaming, data binding, and test ideas, but do not make either repository the base dependency.

Recommended Phase 1 base:

- Add a new module under `android-compose`, for example `:a2ui-renderer`.
- Keep the protocol/runtime layer separate from Compose rendering, following the `a2ui-core` plus `a2ui-compose` split used by `TanXudong-Vivo/A2UI-Android-Renderer`.
- Start with the v0.9 envelope contract: `createSurface`, `updateComponents`, `updateDataModel`, and `deleteSurface`.
- Keep transport integration in this app's existing WS/session layer instead of importing candidate network clients.
- Add conformance-style parser/runtime tests from the start, based on official v0.9 schema examples and this app's ToolApprovalCard round-trip needs.

## Why Not Fork

The project needs tight integration with existing Letta Mobile surfaces, chat timeline state, host-tool approval policy, feature modules, Material theme tokens, and the WS transport. The community renderers are useful, but each would need invasive rewrites before it matched this app's architecture. Starting in-repo keeps the first vertical slice small and prevents a long-lived fork from accumulating local-only changes.

The main goal of Phase 1+ is not to ship a generic Android A2UI SDK. It is to prove the Letta-specific vertical slice:

1. App advertises A2UI capability.
2. Existing WS transport receives A2UI frames.
3. Renderer creates and updates a native Compose surface.
4. ToolApprovalCard renders with Once, Session, Forever, and Deny.
5. User action flows back to the shim and unblocks the agent.

## Evaluation Matrix

| Candidate | License | Local build | Protocol fit | Widget coverage | Tests | Verdict |
|---|---:|---:|---|---|---:|---|
| `google/A2UI` | Apache-2.0 | Kotlin SDK tests pass from local `C:` clone | Official v0.9 schema, streaming parser, conformance tests. Not an Android renderer. | No Compose renderer in official tree. | 4 Kotlin SDK tests plus broader repo tests. | Use as protocol source of truth, not renderer base. |
| `TanXudong-Vivo/A2UI-Android-Renderer` | Apache-2.0 file, GitHub API reports NOASSERTION | Passes `:a2ui-core:testDebugUnitTest :a2ui-compose:testDebugUnitTest :app:assembleDebug` | Closest architecture: modular core/Compose split, v0.9 envelopes, streaming adapter, path binding, action events. | 13 implemented components, plus placeholders for Tabs, Modal, Video, AudioPlayer. | 8 JVM/Android unit test files. | Best reference implementation. Do not fork due maturity, old AGP/Kotlin, partial catalog, standalone theme/transport. |
| `lmee/A2UI-Android` | Apache-2.0 | Fresh clone does not build directly. `android_compose` lacks root plugin versions/settings, so Gradle cannot resolve `com.android.library`. | Claims v0.10 support. Includes renderer, transport, validation, data model, accessibility, tests. | Broadest Android coverage, 20+ components claimed. Some media components are placeholders in code. | 15 test files. | Strong reference for API surface and tests, but too monolithic and not directly buildable enough to fork. |
| `coder-brzhang/a2ui-compose` | No license file found | Library and sample APK assemble successfully using an external Gradle wrapper | v0.10-draft, JSONL/SSE parser, JSON Pointer style bindings. | 18 basic catalog models/render dispatch. | 0 tests. | Reference only. Do not vendor or fork without license clarity and tests. |
| `NikhilBhutani/compose-genui` | MIT | `:genui:testDebugUnitTest :app:assembleDebug` passes | Explicitly A2UI-inspired, but not v0.8/v0.9 protocol compliant. Uses tree document model and an LLM generation loop. | 60+ Material component specs. | 2 tests. | Not a protocol base. Useful catalog/design inspiration only. |
| `AndroidPoet/nebula` | No license file found | Not required for A2UI decision | Server-driven JSON-to-Compose KMP, not A2UI. | Separate protocol. | 8 tests. | Architecture inspiration only. |
| `rufolangus/AgenticLauncher` | Unknown | Public clone failed with 404 | Not directly evaluated. | Unknown. | Unknown. | Not available as a direct dependency/reference. |

## Source Notes

Primary sources:

- A2UI v0.9 draft spec: <https://a2ui.org/specification/v0.9-a2ui/>
- A2UI community renderers page: <https://a2ui.org/ecosystem/renderers/>
- Official repository: <https://github.com/google/A2UI>
- Android renderer candidates: <https://github.com/lmee/A2UI-Android>, <https://github.com/TanXudong-Vivo/A2UI-Android-Renderer>, <https://github.com/coder-brzhang/a2ui-compose>, <https://github.com/NikhilBhutani/compose-genui>
- Architecture-only reference: <https://github.com/AndroidPoet/nebula>

Official A2UI v0.9 is still draft. The spec defines a JSON streaming protocol with four server-to-client envelopes, adjacency-list component storage, JSON Pointer data binding, a basic catalog, and client-to-server action messages. It also says v0.9 capabilities and metadata are exchanged through transport metadata or initialization payloads rather than first-class A2UI messages.

The official community-renderers page currently identifies `lmee/A2UI-Android` as a Jetpack Compose renderer covering Android 5.0+ with 20+ components, and `TanXudong-Vivo/A2UI-Android-Renderer` as a modular Android renderer with v0.9 support, 13 fully implemented components, streaming rendering, path data binding, and a pluggable catalog.

Repository metadata was checked on 2026-05-17:

- `google/A2UI`: 14,739 stars, Apache-2.0, latest cloned commit `9423435`, pushed 2026-05-17.
- `lmee/A2UI-Android`: 47 stars, Apache-2.0, latest cloned commit `860fa71`, pushed 2026-04-17.
- `coder-brzhang/a2ui-compose`: 7 stars, no GitHub license, latest cloned commit `374cc36`, pushed 2026-04-28.
- `NikhilBhutani/compose-genui`: 4 stars, MIT, latest cloned commit `a2fcd5c`, pushed 2026-02-13.
- `TanXudong-Vivo/A2UI-Android-Renderer`: 0 stars, Apache-2.0 license file but GitHub license detection is NOASSERTION, latest cloned commit `7a583ec`, pushed 2026-05-07.
- `AndroidPoet/nebula`: 22 stars, no GitHub license, latest cloned commit `12faefd`, pushed 2026-03-10.

## Local Build Results

Build environment:

- JDK: Android Studio JBR, OpenJDK 21.0.10.
- Android SDK: `C:\Users\Emmanuel\AppData\Local\Android\Sdk`.
- Scratch clones: `U:\a2ui-eval`, with an additional local `C:\Users\Emmanuel\AppData\Local\Temp\a2ui-eval-official` clone for the official Kotlin SDK because Gradle 9.4 failed to configure from the mapped `U:` drive.

Commands that passed:

```powershell
# TanXudong-Vivo/A2UI-Android-Renderer
.\gradlew.bat --no-daemon :a2ui-core:testDebugUnitTest :a2ui-compose:testDebugUnitTest :app:assembleDebug

# NikhilBhutani/compose-genui
.\gradlew.bat --no-daemon :genui:testDebugUnitTest :app:assembleDebug

# coder-brzhang/a2ui-compose, using compose-genui's wrapper because this repo lacks gradlew scripts/jar
U:\a2ui-eval\compose-genui\gradlew.bat --no-daemon -p U:\a2ui-eval\a2ui-compose :a2ui-compose:assembleDebug :sample:assembleDebug

# google/A2UI agent_sdks/kotlin, from local C: clone
.\gradlew.bat --no-daemon test
```

Commands that failed:

```powershell
# lmee/A2UI-Android android_compose
U:\a2ui-eval\compose-genui\gradlew.bat --no-daemon -p U:\a2ui-eval\A2UI-Android\android_compose testDebugUnitTest assembleDebug
```

Failure:

```text
Plugin [id: 'com.android.library'] was not found ... plugin dependency must include a version number
```

The repo has `android_compose/build.gradle.kts`, but no root Gradle settings/plugin management or wrapper capable of building that module from a fresh clone.

## Implementation Guidance For Phase 1

Do:

- Model incoming A2UI as app-owned data classes first, ideally in a pure Kotlin runtime package with unit tests.
- Keep the renderer input close to official v0.9 names: `surfaceId`, `catalogId`, `components`, `path`, `value`, and `root`.
- Use app module boundaries and existing serialization choices rather than importing Gson-based runtime code.
- Treat the first catalog as intentionally small: Text, Column, Row, Card, Button, Divider, and enough data binding to render ToolApprovalCard.
- Add parser/runtime tests before UI tests: message ordering, unknown component fallback, missing root buffering, duplicate component update, data-model merge, action envelope emission.
- Reuse app design-system tokens for Compose rendering. Do not import candidate theme, animation, or network layers.
- Define a Letta custom catalog namespace for ToolApprovalCard instead of squeezing host-tool approval into the generic Button/TextField catalog.

Avoid:

- Forking `lmee/A2UI-Android` wholesale. It is broad but monolithic, not directly buildable from clone, and includes unrelated transport, charts, theme, and demo code.
- Vendoring `coder-brzhang/a2ui-compose` until license is explicit.
- Using `compose-genui` as protocol infrastructure. Its README explicitly states it does not implement v0.8 streaming JSONL, v0.8/v0.9 message types, or JSON Pointer binding.
- Letting imported renderer code decide transport behavior. The app already has the WS session and host-tool approval boundaries that A2UI must pass through.

## Upstream Contribution Plan

Since the recommendation is build-in-repo rather than fork, there is no required upstream fork branch.

Potential upstream contributions after Phase 2 or Phase 3:

- Add minimal v0.9 conformance fixtures or parser edge-case tests to `google/A2UI` if Letta uncovers ambiguous behavior.
- Open issues or PRs against `TanXudong-Vivo/A2UI-Android-Renderer` for generic parser/runtime defects discovered while comparing behavior.
- Ask `coder-brzhang/a2ui-compose` to add an explicit license before any reuse beyond reading the source as reference.

## Phase 1 Exit Criteria

Before starting broad widget work, Phase 1 should leave the repo with:

- A new A2UI runtime module or package with tested v0.9 message parsing.
- A documented WS frame envelope agreed with the letta rig epic `letta-mgn`.
- A capability-negotiation payload containing supported catalog IDs and Letta custom widget IDs.
- A no-op or placeholder Compose surface host that can receive a `createSurface` plus `updateComponents` stream and expose a testable surface state.
