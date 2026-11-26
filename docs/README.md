# Documentation index

Start with [README](../README.md) for product/module layout, [AGENTS](../AGENTS.md)
for workflow and architecture rules, and [CONTRIBUTING](../CONTRIBUTING.md) for setup.
[DESIGN](../DESIGN.md) defines design direction; [DESIGN_SYSTEM](DESIGN_SYSTEM.md)
is a usage reference with retained historical values, not a competing policy spec.

## Generated API reference

Use the [Dokka API reference](https://oculairmedia.github.io/baken/api/)
for shared Kotlin declarations, signatures and KDoc. It is generated from the
module overviews and source comments by the documentation CI build.
Keep design intent, behavioral contracts, procedures and dated evidence here.
Registry-generated wire tables remain useful: a Kotlin `JsonObject` declaration
does not document the evaluated JSON schema or the supported widget catalog.

Architecture documents require a `Status:` header. Label plans, historical evidence
and unverified snapshots explicitly; a header alone does not certify freshness.
When implementation ships, update the owning design's status and link its replacement.
Keep historical evidence intact rather than rewriting it to describe newer code.
Live paths can be checked explicitly with a `live-path:` HTML comment containing a repository-relative path.
The existing `scripts/ci/docs-freshness-check.py` gate validates those paths and
architecture status headers in addition to module, gate and root-link consistency.

Use `evidence/` for captured observations, `testing/` for validation procedures,
`handoff/` for dated session handoffs, and `architecture/` for designs/contracts.
Existing historical filenames are retained unless misleading.

The product Gradle root is [android-compose/](../android-compose/): open that
folder in Android Studio/IDEA and run Gradle there. Hosts keep their declared
`app/`, `desktop/`, and `web/` project paths; see the
[entrypoint layout decision](architecture/kmp-phase-6-entrypoints.md).

Other repository directories:

| Directory | Contents and boundary |
| --- | --- |
| [scripts/](../scripts/) | Repository operations and CI entrypoints (build, release, deployment, policy checks); regression tests for these scripts belong in `scripts/tests/`. |
| [tools/](../tools/) | Self-contained analysis/capture tools and optional development services; keep their implementation, usage notes, fixtures and tests together under each tool. |
| [tests/](../tests/) | Existing cross-directory deployment-script tests (`tests/scripts/test_seed_agent_address_book.py`); app/module tests belong in their Gradle source sets. |
| [plugins/](../plugins/) | Packaged plugin examples; [chart](../plugins/chart/README.md) includes its manifest, server, page and protocol tests. |
| [reports/](../reports/) | Ignored local report output; maintained evidence belongs under `docs/`. |

Maintain prose documents under this `docs/` tree, or colocate module/tool usage
next to its sources. Do not create hidden handoff folders or another general
`docs/` root inside the Gradle build.

## Maintaining this index

Navigation below is generated from `git ls-files` Markdown paths under `docs/`,
excluding this index. It uses each document's first heading (or filename when
there is no heading), groups by directory, and skips files deleted in the working
tree. Stage newly added or renamed documents before regenerating; untracked and
ignored build output is outside this navigation's scope.

```bash
python scripts/docs/documentation-index.py --write
python scripts/docs/documentation-index.py --check
```

Edit the introductory guidance here, not the marked navigation. Check mode is
offline and fails on stale titles, paths, missing rows or duplicate rows.

<!-- documentation-index:start -->
## Documents

### General

- [Design sync with Penpot — plan &amp; status](design-sync-with-penpot.md)
- [Letta Design System Reference \(extracted from code\)](DESIGN_SYSTEM.md)
- [Iroh two-client live-sync probe \(headless\)](iroh-two-client-live-sync-probe.md)
- [Release automation bootstrap](RELEASE-AUTOMATION.md)
- [Release checklist](RELEASE.md)
- [Property-Based Testing with kotest-property](testing-with-kotest-property.md)
- [Testing Flows with Turbine](testing-with-turbine.md)

### architecture

- [App Server Controller Deployment Guide](architecture/app-server-controller-deploy.md)
- [App Server v2 and Meridian State Audit Specification](architecture/app-server-v2-audit-spec.md)
- [Canonical JSON configurations](architecture/canonical-json.md)
- [Chat transport and timeline boundary regression harness](architecture/chat-boundary-regression-harness.md)
- [Letta Mobile Architecture Diagrams](architecture/diagrams.md)
- [Distributed agent fabric foundation](architecture/distributed-agent-fabric-foundation.md)
- [Embed Letta Code as on-device runtime - integration design](architecture/embed-letta-code-integration.md)
- [External transport timeline ingestion and reconcile rules](architecture/external-transport-timeline-rules.md)
- [Iroh Admin RPC Ownership Matrix \(lgns8.13\)](architecture/iroh-admin-ownership-matrix.md)
- [KMP module map](architecture/kmp-module-map.md)
- [Runnable entry points and host layout](architecture/kmp-phase-6-entrypoints.md)
- [LettaShim Retirement Implementation and Deployment Runbook](architecture/lettashim-retirement-deployment-runbook.md)
- [Live external tools and re-advertisement](architecture/live-external-tools.md)
- [Mobile embedded bridge and desktop runtime hosting](architecture/mobile-embedded-bridge-vs-desktop.md)
- [Repository cache and paging policy](architecture/repository-cache-paging-policy.md)
- [Shared-module layering ratchet](architecture/shared-layering-ratchet.md)
- [Timeline history snapshot and reconcile policy](architecture/timeline-rest-snapshot-policy.md)
- [Typed ids and the id ratchets](architecture/typed-ids-and-id-ratchets.md)

### deploy

- [letta-code runtime patches](deploy/appserver-runtime-patches.md)
- [Iroh agent address book — operator runbook](deploy/iroh-agent-address-book.md)
- [letta-code upgrade runbook](deploy/letta-code-upgrade.md)
- [Redeploy meridian-iroh-wrapper for the workspace relay \(letta-mobile-bzvro.37\)](deploy/workspace-relay-host-redeploy.md)

### design

- [canvas.compose: validated implementation plan \(v1\)](design/canvas-compose-plan.md)
- [Canvas Plugin Platform: Architecture Plan \(revision 2\)](design/canvas-plugin-platform-plan.md)
- [Design decision: editorial prose for chat](design/editorial-prose.md)
- [On-demand agent tools via the Meridian CLI](design/on-demand-tools-via-meridian-cli.md)

### development

- [Generated Kotlin API reference](development/api-documentation.md)
- [Phone preview on the desktop](development/phone-preview.md)

### observability

- [Telemetry conventions](observability/conventions.md)
- [Sentry ANR / crash budget dashboard](observability/sentry-anr-crash-budget.md)
- [StreamSubscriber observability](observability/stream-subscriber.md)

### performance

- [Chat pinch-to-zoom performance note](performance/chat-pinch-zoom.md)
- [Production JankStats Sampling](performance/jankstats-production-sampling.md)
- [Android perf regression gate](performance/perf-gate.md)
- [Android performance playbook \(v1\)](performance/playbook.md)
- [App Performance Score History](performance/score-history.md)

### reference

- [canvas\_compose reference](reference/canvas-compose-v1.md)
- [LCP wire v1: the canvas plugin protocol](reference/canvas-plugin-wire-v1.md)
- [Current App Server protocol and admin ownership reference](reference/current-app-server-protocol.md)
- [Looking up Letta documentation](reference/letta-docs.md)
- [Host notebook grants v1](reference/notebook-authority-v1.md)
- [Plugin trust model](reference/plugin-trust-model.md)
- [Plugin view bridge \`lcp-view/1\` — for plugin page authors](reference/plugin-view-bridge-v1.md)
- [QR Pairing Protocol](reference/qr-pairing-protocol.md)

### reference/contracts

- [Active turn v1: contract proposal](reference/contracts/active-turn-v1.md)
- [Canvas mount v1: host-owned canvas defaults and bindings](reference/contracts/canvas-mount-v1.md)
- [Conversation feed v1: the host's durable, resumable record of every turn](reference/contracts/conversation-feed-v1.md)
- [Conversation lanes v1](reference/contracts/conversation-lanes-v1.md)
- [Current focus v1: contract proposal](reference/contracts/current-focus-v1.md)
- [Keystore v1: provider contract](reference/contracts/keystore-v1.md)
- [Lane inbox v1](reference/contracts/lane-inbox-v1.md)
- [Object verbs v1: contract proposal](reference/contracts/object-verbs-v1.md)
- [Pins v1: the host's pinned agents, one list shared by every device](reference/contracts/pins-v1.md)
- [Project registry v1: host-owned project record, project file and read methods](reference/contracts/project-registry-v1.md)
- [Admin RPC Wire Contracts](reference/contracts/README.md)
- [Secret request v1: secure secret-input flow](reference/contracts/secret-request-v1.md)
- [Service credentials v1: non-LLM service secrets held on the host](reference/contracts/service-credentials-v1.md)
- [Shell state v1: the host's one versioned document of shell and user state](reference/contracts/shell-state-v1.md)
- [Voice endpoint v1: host-stored speech setup and speech through the host](reference/contracts/voice-endpoint-v1.md)

### reference/contracts/agent.list

- [agent.list: Slim List v1](reference/contracts/agent.list/slim-list-v1.md)

### reference/contracts/beads.config.get

- [beads.config.get v1](reference/contracts/beads.config.get/beads-v1.md)

### reference/contracts/beads.config.set

- [beads.config.set v1](reference/contracts/beads.config.set/beads-v1.md)

### reference/contracts/beads.context

- [beads.context v1](reference/contracts/beads.context/beads-v1.md)

### reference/contracts/beads.dependencies.add

- [beads.dependencies.add v1](reference/contracts/beads.dependencies.add/beads-v1.md)

### reference/contracts/beads.dependencies.cycles

- [beads.dependencies.cycles v1](reference/contracts/beads.dependencies.cycles/beads-v1.md)

### reference/contracts/beads.dependencies.remove

- [beads.dependencies.remove v1](reference/contracts/beads.dependencies.remove/beads-v1.md)

### reference/contracts/beads.dependencies.tree

- [beads.dependencies.tree v1](reference/contracts/beads.dependencies.tree/beads-v1.md)

### reference/contracts/beads.events.list

- [beads.events.list v1](reference/contracts/beads.events.list/beads-v1.md)

### reference/contracts/beads.issues.claim

- [beads.issues.claim v1](reference/contracts/beads.issues.claim/beads-v1.md)

### reference/contracts/beads.issues.claimNext

- [beads.issues.claimNext v1](reference/contracts/beads.issues.claimNext/beads-v1.md)

### reference/contracts/beads.issues.close

- [beads.issues.close v1](reference/contracts/beads.issues.close/beads-v1.md)

### reference/contracts/beads.issues.create

- [beads.issues.create v1](reference/contracts/beads.issues.create/beads-v1.md)

### reference/contracts/beads.issues.get

- [beads.issues.get v1](reference/contracts/beads.issues.get/beads-v1.md)

### reference/contracts/beads.issues.list

- [beads.issues.list v1](reference/contracts/beads.issues.list/beads-v1.md)

### reference/contracts/beads.issues.release

- [beads.issues.release v1](reference/contracts/beads.issues.release/beads-v1.md)

### reference/contracts/beads.issues.update

- [beads.issues.update v1](reference/contracts/beads.issues.update/beads-v1.md)

### reference/contracts/beads.memories.list

- [beads.memories.list v1](reference/contracts/beads.memories.list/beads-v1.md)

### reference/contracts/beads.memories.write

- [beads.memories.write v1](reference/contracts/beads.memories.write/beads-v1.md)

### reference/contracts/beads.ready

- [beads.ready v1](reference/contracts/beads.ready/beads-v1.md)

### reference/contracts/beads.ready.count

- [beads.ready.count v1](reference/contracts/beads.ready.count/beads-v1.md)

### reference/contracts/beads.stats

- [beads.stats v1](reference/contracts/beads.stats/beads-v1.md)

### reference/contracts/canvas.mount.binding.bind

- [canvas.mount.binding.bind: Canvas Mount v1](reference/contracts/canvas.mount.binding.bind/canvas-mount-v1.md)

### reference/contracts/canvas.mount.binding.replace

- [canvas.mount.binding.replace: Canvas Mount v1](reference/contracts/canvas.mount.binding.replace/canvas-mount-v1.md)

### reference/contracts/canvas.mount.candidates

- [canvas.mount.candidates: Canvas Mount v1](reference/contracts/canvas.mount.candidates/canvas-mount-v1.md)

### reference/contracts/canvas.mount.default.clear

- [canvas.mount.default.clear: Canvas Mount v1](reference/contracts/canvas.mount.default.clear/canvas-mount-v1.md)

### reference/contracts/canvas.mount.default.set

- [canvas.mount.default.set: Canvas Mount v1](reference/contracts/canvas.mount.default.set/canvas-mount-v1.md)

### reference/contracts/canvas.mount.resolve

- [canvas.mount.resolve: Canvas Mount v1](reference/contracts/canvas.mount.resolve/canvas-mount-v1.md)

### reference/contracts/conversation.list

- [conversation.list: Visible Recents v1](reference/contracts/conversation.list/visible-recents-v1.md)

### reference/contracts/cron.pause

- [\`cron.pause\` v1](reference/contracts/cron.pause/control-v1.md)

### reference/contracts/cron.resume

- [\`cron.resume\` v1](reference/contracts/cron.resume/control-v1.md)

### reference/contracts/feed.subscribe

- [feed.subscribe: conversation feed v1](reference/contracts/feed.subscribe/feed-v1.md)

### reference/contracts/health.check

- [health.check: Controller Status v1](reference/contracts/health.check/status-v1.md)

### reference/contracts/lease.list

- [lease.list v1](reference/contracts/lease.list/wrapper-records-v1.md)

### reference/contracts/lease.upsert

- [lease.upsert v1](reference/contracts/lease.upsert/wrapper-records-v1.md)

### reference/contracts/message.list

- [message.list: Byte-Bounded Pages v1](reference/contracts/message.list/pages-v1.md)

### reference/contracts/monitor.stop

- [monitor.stop: Background process stop v1](reference/contracts/monitor.stop/process-v1.md)

### reference/contracts/node-carrier

- [Node carrier v1: one client stack over any transport](reference/contracts/node-carrier/node-carrier-v1.md)

### reference/contracts/pins.get

- [pins.get: pins v1](reference/contracts/pins.get/pins-v1.md)

### reference/contracts/pins.set

- [pins.set: pins v1](reference/contracts/pins.set/pins-v1.md)

### reference/contracts/reflection.get

- [reflection.get: Reflection Settings v2](reference/contracts/reflection.get/settings-v2.md)

### reference/contracts/reflection.set

- [reflection.set: Reflection Settings v2](reference/contracts/reflection.set/settings-v2.md)

### reference/contracts/run.record

- [Wrapper run records and leases v1](reference/contracts/run.record/wrapper-records-v1.md)

### reference/contracts/secret.request

- [secret.request: Secret Request v1](reference/contracts/secret.request/secret-request-v1.md)

### reference/contracts/secret.request.cancel

- [secret.request.cancel: Secret Request v1](reference/contracts/secret.request.cancel/secret-request-v1.md)

### reference/contracts/secret.request.get

- [secret.request.get: Secret Request v1](reference/contracts/secret.request.get/secret-request-v1.md)

### reference/contracts/secret.request.list

- [secret.request.list: Secret Request v1](reference/contracts/secret.request.list/secret-request-v1.md)

### reference/contracts/secret.request.status

- [secret.request.status: Secret Request v1](reference/contracts/secret.request.status/secret-request-v1.md)

### reference/contracts/secret.request.submit

- [secret.request.submit: Secret Request v1](reference/contracts/secret.request.submit/secret-request-v1.md)

### reference/contracts/service.credential.list

- [service.credential.list: service credentials v1](reference/contracts/service.credential.list/service-credentials-v1.md)

### reference/contracts/service.credential.remove

- [service.credential.remove: service credentials v1](reference/contracts/service.credential.remove/service-credentials-v1.md)

### reference/contracts/service.credential.set

- [service.credential.set: service credentials v1](reference/contracts/service.credential.set/service-credentials-v1.md)

### reference/contracts/service.credential.test

- [service.credential.test: service credentials v1](reference/contracts/service.credential.test/service-credentials-v1.md)

### reference/contracts/shell.state.get

- [shell.state.get: shell state v1](reference/contracts/shell.state.get/shell-state-v1.md)

### reference/contracts/shell.state.propose

- [shell.state.propose: shell state v1](reference/contracts/shell.state.propose/shell-state-v1.md)

### reference/contracts/slash\_command.execute

- [Slash command execution v1](reference/contracts/slash_command.execute/execute-v1.md)

### reference/contracts/toolset.update

- [toolset.update v1](reference/contracts/toolset.update/toolset-v1.md)

### reference/contracts/voice.models.list

- [voice.models.list: voice endpoint v1](reference/contracts/voice.models.list/voice-endpoint-v1.md)

### reference/contracts/voice.setup.clear

- [voice.setup.clear: voice endpoint v1](reference/contracts/voice.setup.clear/voice-endpoint-v1.md)

### reference/contracts/voice.setup.get

- [voice.setup.get: voice endpoint v1](reference/contracts/voice.setup.get/voice-endpoint-v1.md)

### reference/contracts/voice.setup.set

- [voice.setup.set: voice endpoint v1](reference/contracts/voice.setup.set/voice-endpoint-v1.md)

### reference/contracts/voice.setup.test

- [voice.setup.test: voice endpoint v1](reference/contracts/voice.setup.test/voice-endpoint-v1.md)

### reference/contracts/voice.speech

- [voice.speech: voice endpoint v1 \(streamed\)](reference/contracts/voice.speech/voice-endpoint-v1.md)

### reference/contracts/voice.transcribe

- [voice.transcribe: voice endpoint v1](reference/contracts/voice.transcribe/voice-endpoint-v1.md)

### testing

- [lgns8 App Server v2 — On-Device End-to-End Acceptance Protocol](testing/lgns8-e2e-device-protocol.md)
- [On-device App Server: model ids and test-agent cleanup](testing/on-device-local-backend.md)
- [Seeded pagination and timeline-replay fuzz tests](testing/pagination-replay-fuzz.md)

### tooling

- [Self-hosted code intelligence pilot](tooling/serena-scip-pilot.md)

### tooling/verifying-timeline-identity

- [Verifying timeline row identity](tooling/verifying-timeline-identity/SKILL.md)
<!-- documentation-index:end -->
