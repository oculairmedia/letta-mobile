# Plugin-first application architecture

**Status:** Proposed draft, 2026-10-19. The requested direction includes both
product functionality and replaceable infrastructure providers through plugins,
with both bundled plugins and runtime-installed extensions required.
This document records an audit and proposed migration, not shipped behavior.

## Intent and scope

Every product function should enter the application through an extension contract,
including the default distribution's own features. Bundled code must use the same
registration, dependency, lifecycle and removal rules as other implementations.
Moving an existing feature behind a factory while the shell still knows its name,
routes and controllers would leave the architectural dependency in place.

The default distribution can select a set of bundled extensions. Its composition
root may assemble them; the generic shell must not import their implementations.
Packaging, dynamic code loading and contribution registration are separate concerns.
A bundled KMP extension participates without runtime code loading; installed
extensions must enter the same contribution and provider resolution model.

## Source audit baseline: 2026-10-19

The implementation claims in this section describe the pre-migration audit at
commit [`c38fce1536b77bd893eb676f2a33fd8f4f4d4d12`](https://github.com/oculairmedia/letta-mobile/tree/c38fce1536b77bd893eb676f2a33fd8f4f4d4d12).
At that baseline, plugins extend canvas content and agent actions, without arbitrary
application feature registration. Relative declaration links help locate ownership;
this dated evidence is not a generated API reference or a claim about later revisions.

| Existing boundary | Implemented responsibility and limit |
| --- | --- |
| [CanvasPlugin](../../android-compose/plugin-api/src/commonMain/kotlin/com/letta/mobile/plugin/api/CanvasPlugin.kt) | Lifecycle, action invocation, element events, settings notifications and health. A Kotlin SPI declaration does not establish a working JAR loader. |
| [PluginHost](../../android-compose/plugin-api/src/commonMain/kotlin/com/letta/mobile/plugin/api/PluginHost.kt) | Canvas emits/reads, assets, logs, secrets, scope and restricted HTTP contract. No generic shell-page or repository-provider registration. |
| [PluginManifest](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/plugin/PluginManifest.kt) | Elements, actions, HTML pages, settings, secrets and runtime declarations. No shell routes, navigation groups or search-provider contributions. |
| [PluginRegistryReducer](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/plugin/PluginRegistryReducer.kt) | Portable install/update/rollback, consent, enable/disable and fault transitions. I/O and execution sit elsewhere. |
| [PluginHostRuntime](../../android-compose/sharedLogic/src/jvmMain/kotlin/com/letta/mobile/data/plugin/host/PluginHostRuntime.kt) | JVM package persistence, serialized management operations, drivers, kind catalog and published agent tools. |
| [ExternalToolRegistry](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/extras/ExternalToolRegistry.kt) | Fixed tools plus live ToolSource registration/removal and advertisement. Fixed names already take precedence over colliding dynamic tools. |
| [ShellPageContent / ShellPageDeps](../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/ShellPage.kt) | Shared Compose page injection, keyed by a fixed list of product page kinds. Useful seam, not an open contribution model. |
| [ShellDestination](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/shell/ShellDestination.kt) | Sealed destination variants and a finite ShellPageKind enum enumerate product features. Adding a new category requires shell changes. |

<!-- live-path: android-compose/plugin-api/src/commonMain/kotlin/com/letta/mobile/plugin/api/CanvasPlugin.kt -->
<!-- live-path: android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/plugin/PluginManifest.kt -->
<!-- live-path: android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/ShellPage.kt -->

### Runtime and platform limits

At the audited commit,
[PluginDriverHost](https://github.com/oculairmedia/letta-mobile/blob/c38fce1536b77bd893eb676f2a33fd8f4f4d4d12/android-compose/sharedLogic/src/jvmMain/kotlin/com/letta/mobile/data/plugin/host/PluginDriverHost.kt)
constructs ProcessPluginDriver directly. That
[driver](https://github.com/oculairmedia/letta-mobile/blob/c38fce1536b77bd893eb676f2a33fd8f4f4d4d12/android-compose/sharedLogic/src/jvmMain/kotlin/com/letta/mobile/data/plugin/host/ProcessPluginDriver.kt)
rejects a runtime that is not PluginRuntime.Process. JVM and service variants in
the manifest are declarations, not evidence of implemented execution support.
The audited launch boundary supplies setting defaults and an empty secrets
container; persisted overrides and secret delivery need implementation before
features can rely on them.

The wrapper's [PluginHostWiring](../../android-compose/iroh-wrapper-cli/src/main/kotlin/com/letta/mobile/cli/commands/PluginHostWiring.kt)
connects the runtime to canvas kinds and the external-tool registry.
[plugin-view](../../android-compose/plugin-view/build.gradle.kts) configures Android
and JVM targets, with Android WebView and desktop JCEF bindings.
[WebShellPages](../../android-compose/web/src/wasmJsMain/kotlin/com/letta/mobile/web/WebShellPages.kt)
explicitly supplies UnavailablePluginCatalog. Browser plugin parity is therefore
work to implement, rather than a capability to assume.

The runtime-hosting provider foundation is underway in `letta-mobile-wtioi.2`,
after this audit; it is not shipped behavior at the baseline above. The agreed scope
still requires all six infrastructure provider families and both bundled and
runtime-installed extensions. This first slice does not complete that migration or
establish browser execution support.

## Proposed architecture: contracts, kernel and distribution

The following categories describe proposed responsibilities, not existing APIs or
approved module names. Choose module boundaries after proving the first slice.

- **Extension contracts:** portable descriptors, contribution identities, service
  contracts, lifecycle results and navigation data. Contracts and kernel have no
  Compose, Clikt or feature implementation imports.
- **Kernel:** validates contributions, resolves dependencies, owns lifecycle and
  dispatches generic requests. It knows contracts and identities, not product names.
- **Shared shell/rendering adapters:** replaceable provider extensions for chrome,
  navigation rendering, commands, settings and page presentation. Distribution
  assembly selects these adapters; presentation belongs outside the kernel.
- **Feature extensions:** own product state, data policy, controllers, UI and
  their contributions. Communication uses explicit contracts, not sibling imports.
- **Distribution assembly:** selects bundled extensions and platform bindings.
  Android, desktop and browser consume the same feature registration logic.

**Proposed minimum bootstrap:** discovery, descriptor validation, contract/trust
verification, dependency resolution and lifecycle ownership. Trust verification
here is the loading contract, not a fixed product authentication implementation.
Transport, storage, authentication, telemetry, runtime hosting and OS integrations
are replaceable providers selected by distribution assembly. The bootstrap must
not choose concrete providers by product name, silently inject a fallback, or
hide dependencies in an unrestricted global service locator. The precise bootstrap
trust mechanism and loading strategy remain design decisions to validate.

## Proposed contribution semantics

An extension descriptor needs a stable publisher-qualified identity, version and
supported contract range. Contributions need stable IDs, ownership and supported
platforms. Define these semantics before selecting Kotlin class names or JSON shapes.

Descriptors, route IDs and runtime kinds use open contribution identities: adding
an extension must not require another product or runtime enum case. The current v1
sealed PluginRuntime declaration remains a limitation until its contract migrates.

| Contribution | Required behavior |
| --- | --- |
| Routes and native pages | Stable route identity and typed/validated arguments; shared Compose implementation may supply content. Unknown or disabled routes render a generic unavailable state. |
| Navigation entries | Reference registered routes, carry labels/order/context, and disappear when their owner is unavailable. No product-specific enum switch in shell chrome. |
| Commands and agent tools | Owned handlers and declarative input/identity contracts include CLI commands, help and menus as well as agent tools. Enforce collisions explicitly; reuse ToolSource where its semantics fit. |
| Shell and presentation providers | Replaceable shared chrome, navigation renderers and native/external presentation adapters; resolve dependencies and lifecycle through the same provider contracts. |
| Search providers | Owned query execution with cancellation, result identity and a registered destination/action. Disable removes results and cancels outstanding queries. |
| Settings sections | Owned schema/presentation and persistence contract; preserve secret separation. Removing UI does not imply deleting stored settings. |
| Infrastructure providers | Typed slots for transport, storage, authentication, telemetry, runtime hosting and OS integration; explicit provided/required contract identities, version compatibility and deterministic selection. Consumers do not reach into implementations. |
| Canvas kinds and HTML pages | Adapt existing manifest kinds, actions and page bridge; retain unknown-kind fallback and capability checks. |

Registration is transactional: validate ownership, duplicates, version ranges,
platform availability and dependency cycles before making contributions visible.
Required dependencies fail activation explicitly; optional dependencies must be
declared and observable. Choose override/replacement rules deliberately rather
than accepting whichever implementation registers first.
Resolve the whole startup graph before constructing providers, including provider
dependencies on one another. Missing required providers, incompatible contracts,
ambiguous bindings and startup cycles produce an explanation naming the dependency
chain. Provider contracts define acquisition/release and shutdown ordering; runtime
replacement must not transfer active sessions to a new implementation implicitly.

Lifecycle must be owned and observable: discover/validate, resolve, initialize,
activate, deactivate and dispose, with explicit failure results. This is a target
behavior to reconcile with existing registry states, not a second competing state machine.
Every job, listener, route handler and subscription belongs to the owner's scope.
Startup publishes only usable contributions; a partial activation rolls back.

Disable first stops new dispatch, then removes contributions and cancels owned
work, then disposes resources. Define how dependent extensions become unavailable
or block removal before implementing cascade behavior. In-flight actions receive
an explicit cancellation/unavailable result; they do not silently switch providers.
Re-enabling creates a fresh generation so stale completions cannot republish state.

Persist route and settings identities without persisting implementation objects.
Restoring a missing extension shows an actionable unavailable state while keeping
the stored identity recoverable. Destructive data deletion is a separate operation.

## Native Compose and external execution

Bundled native features remain shared KMP/Compose code. Their lifecycle and
contribution registration follow extension rules even when their code is linked
at build time. Platform modules bind OS facilities and presentation containers.

External HTML pages continue through the existing page bridge and permission
boundary. Treat them as one presentation type; do not force native product pages
into HTML or expose unrestricted host internals to make the bridge universal.
Executor selection (bundled, process, service or another supported runtime) must
be explicit per host and report unsupported capabilities before activation.
Both bundled and runtime-installed extensions are required delivery modes.
Installation validates descriptor schema and contract versions before loading;
updates need explicit compatible schema/data migrations or refusal with rollback.
This does not imply hot replacement of active providers or dynamic native Compose
loading across all hosts. Those loading and activation mechanisms need separate designs.

Browser parity starts with bundled extensions and a portable registration model.
A browser cannot execute a JVM JAR or spawn the host's process driver. Dynamic
browser execution requires a separate supported runtime, loading/integrity policy,
isolation and lifecycle design; remote-host actions require a browser-capable
adapter to the existing host protocol. Neither mechanism exists merely because
the manifest declares runtime kinds. Do not add platform-specific feature logic
to compensate for an absent execution adapter.

## Migration stages and acceptance

1. **Agree on contracts:** infrastructure replaceability is in scope. Define typed
   provider slots, bootstrap trust, contribution ownership and replacement policy.
   Support both bundled assembly and runtime installation through those contracts;
   define executor support by host, with hot replacement decided separately.
2. **Establish contracts and empty-shell tests:** add portable registration and
   dependency/lifecycle behavior, plus shared Compose content binding. A shell
   with no features boots and explains its empty state without product imports.
3. **Prove one complete product slice:** Templates is the preferred candidate:
   it already has a dedicated page/controller boundary and a bounded catalog/create
   workflow. Register its route, navigation and actions through the proposed model,
   including agent-creation service dependencies. Projects is a second candidate,
   but its host relay/capability requirements add execution-adapter work.
4. **Migrate remaining features in reviewable slices:** move each feature's state,
   policy, rendering and bindings behind contributions. Keep shared behavior once;
   remove its hardcoded shell/controller wiring as part of the same slice.
5. **Unify existing installed plugins:** adapt canvas/action contributions and
   registry transitions to the common ownership/lifecycle boundary. Add executors
   only with conformance and platform-support evidence. Avoid parallel registries
   advertising conflicting versions of the same capability.
   Roll out installed process contributions on the existing JVM host first, then
   browser-capable remote actions/HTML pages and other executors with explicit
   support negotiation; maintain bundled-feature parity across all three clients.
6. **Enforce the resulting boundary:** remove temporary compatibility wiring and
   require every newly introduced product function to register its contributions.

First-slice evidence:
[Templates page](../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/pages/templates/TemplatesPage.kt),
[AgentTemplatesController](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/templates/AgentTemplatesController.kt)
and [ShellPage](../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/ShellPage.kt).
These existing declarations identify the slice; they do not already implement
the proposed generic route and contribution contracts.

The smallest useful implementation connects a Templates contribution to existing
dispatch: replace its construction in [ShellAdminBinding](../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/pages/ShellAdminBinding.kt)
and its page lookup in ShellPage with owner-scoped registration, retaining the
current TemplatesPage and AgentTemplatesController. Supply agent creation through
an explicit typed dependency and prove the contribution can be omitted/disabled.
Keep the existing `templates` URL as a compatibility alias while moving its
[ShellRoute](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/shell/ShellRoute.kt)
binding into the contribution. A registry with no real dispatcher consumer is
not completion of this stage; installed-extension parity remains subsequent work.

## Enforcement and verification proposal

Reuse the repository's architecture tooling to enforce dependency direction:
kernel/contracts cannot import Compose, Clikt or feature implementations;
platform hosts cannot construct concrete feature controllers or infrastructure providers directly;
sibling extensions use explicit contracts, including infrastructure consumers.
Distribution assembly is the declared exception and must remain isolated from
runtime shell dispatch. Ratchet existing violations down during migration; never
raise thresholds to accept new feature-specific kernel dependencies.

Contract tests must exercise duplicate IDs, missing/ambiguous providers, version
mismatch, cycles, partial startup rollback, disable during work, re-enable and
stale completion. Include an empty distribution and one with the first slice
omitted, proving that no hidden static registration is necessary for shell startup.

Run shared lifecycle and route tests across configured targets, then verify Android,
desktop and browser navigation/rendering parity. API snapshots expose contract
changes; package coverage and Dokka describe extension ownership. Generate the
contribution catalog and dependency report from registered descriptors once those
descriptors exist. Documentation must distinguish supported runtime capabilities
from declarations that have no executor.
