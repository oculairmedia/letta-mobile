# Documentation index

Start with [README](../README.md) for product/module layout, [AGENTS](../AGENTS.md)
for workflow and architecture rules, and [CONTRIBUTING](../CONTRIBUTING.md) for setup.
[DESIGN](../DESIGN.md) defines design direction; [DESIGN_SYSTEM](DESIGN_SYSTEM.md)
is a usage reference with retained historical values, not a competing policy spec.

## Generated API reference

Use the [Dokka API reference](https://oculairmedia.github.io/letta-mobile/api/)
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
[plan/](../plan/) holds historical planning; [poc/](../poc/) holds experimental code,
not production modules. Existing historical filenames are retained unless misleading.

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
| [third_party/](../third_party/README.md) | Retained external source trees and their integration/provenance notes. Production DrawBox is separately vendored as a Gradle module with [VENDORED.md](../android-compose/drawbox/VENDORED.md). |
| [reports/](../reports/) | Ignored local report output; maintained evidence belongs under `docs/`. |

Maintain prose documents under this `docs/` tree, or colocate module/tool usage
next to its sources. Do not create hidden handoff folders or another general
`docs/` root inside the Gradle build.

## Source audit

The [2026-10-18 audit](reference/documentation-audit-2026-10-18.md) covers every tracked
Markdown input and links a per-file source/evidence ledger, including unverified historical
and live claims. A passing freshness script is structural validation, not a full semantic audit.

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

- [letta-mobile Architecture Review](architecture-review.md)
- [Design sync with Penpot — plan &amp; status](design-sync-with-penpot.md)
- [Letta Design System Reference \(extracted from code\)](DESIGN_SYSTEM.md)
- [Iroh two-client live-sync probe \(headless\)](iroh-two-client-live-sync-probe.md)
- [Push Ingest Analysis \(letta-mobile-o5zmj\)](PUSH_INGEST_ANALYSIS.md)
- [Release automation bootstrap](RELEASE-AUTOMATION.md)
- [Release Gate Ledger](RELEASE-GATE-LEDGER.md)
- [Release checklist](RELEASE.md)
- [Property-Based Testing with kotest-property](testing-with-kotest-property.md)
- [Testing Flows with Turbine](testing-with-turbine.md)
- [PRD: Tool Card Readability + Interaction Polish](tool-card-polish-prd.md)
- [Desktop/Android UI alignment — fanout brief](ui-alignment-fanout.md)
- [UI cleanup &amp; token adherence — handoff](ui-token-adherence-handoff.md)

### architecture

- [A2UI Android Renderer Phase 0 Decision](architecture/a2ui-android-renderer-phase0.md)
- [Android System Access Capability Matrix](architecture/android-system-access-capability-matrix.md)
- [App Server Controller Deployment Guide](architecture/app-server-controller-deploy.md)
- [App Server v2 and Meridian State Audit Specification](architecture/app-server-v2-audit-spec.md)
- [Canonical JSON configurations](architecture/canonical-json.md)
- [Chat transport and timeline boundary regression harness](architecture/chat-boundary-regression-harness.md)
- [Letta Mobile Architecture Diagrams](architecture/diagrams.md)
- [Distributed agent fabric foundation](architecture/distributed-agent-fabric-foundation.md)
- [Embed Letta Code as on-device runtime - integration design](architecture/embed-letta-code-integration.md)
- [External transport timeline ingestion and reconcile rules](architecture/external-transport-timeline-rules.md)
- [Iroh Admin RPC Ownership Matrix \(lgns8.13\)](architecture/iroh-admin-ownership-matrix.md)
- [KMP Best-Practices Migration Plan](architecture/kmp-best-practices-migration-plan.md)
- [KMP Extraction Snapshot — 2026-06-06](architecture/kmp-extraction-snapshot-2026-06-06.md)
- [KMP module map](architecture/kmp-module-map.md)
- [Runnable entry points and host layout](architecture/kmp-phase-6-entrypoints.md)
- [Epic: KMP project structure migration](architecture/kmp-structure-migration-epic.md)
- [Letta API Behavior Empirical Findings](architecture/letta-api-behaviors.md)
- [Letta Code file/shell tools on Android access layers](architecture/letta-code-android-file-shell-tool-map.md)
- [Letta Code-style Android host tools](architecture/letta-code-android-host-tools.md)
- [LettaShim Retirement Implementation and Deployment Runbook](architecture/lettashim-retirement-deployment-runbook.md)
- [lgns8 epic: status, achievable scope, and the shim-retirement ceiling](architecture/lgns8-shim-retirement-snapshot-2026-07-31.md)
- [Live external tools and re-advertisement](architecture/live-external-tools.md)
- [Message Sync Architecture Migration Plan](architecture/message-sync-migration.md)
- [Mobile embedded bridge and desktop runtime hosting](architecture/mobile-embedded-bridge-vs-desktop.md)
- [POC Validation Results](architecture/poc-validation-results.md)
- [Progressive Composer Affordances: Design &amp; Migration Plan](architecture/progressive-composer-affordances.md)
- [Android Project Work API Contract](architecture/project-work-api-android-contract.md)
- [PromptComposerState prototype](architecture/prompt-composer-state-migration.md)
- [Repository cache and paging policy](architecture/repository-cache-paging-policy.md)
- [Screen-Off Streaming: Architecture Findings](architecture/screen-off-streaming-findings.md)
- [Screen-Off Streaming Measurement Protocol](architecture/screen-off-streaming-measurement.md)
- [Shared-module layering ratchet](architecture/shared-layering-ratchet.md)
- [Stream-frame eviction instead of session teardown](architecture/stream-frame-eviction-spec.md)
- [Timeline history snapshot and reconcile policy](architecture/timeline-rest-snapshot-policy.md)
- [Typed ids and the id ratchets](architecture/typed-ids-and-id-ratchets.md)
- [Windows Chat UI Decision](architecture/windows-chat-ui-decision.md)

### bugs

- [Notification Content Bug](bugs/notification-thinking-content.md)

### deploy

- [Iroh agent address book — operator runbook](deploy/iroh-agent-address-book.md)
- [Redeploy meridian-iroh-wrapper for the workspace relay \(letta-mobile-bzvro.37\)](deploy/workspace-relay-host-redeploy.md)

### design

- [canvas.compose: validated implementation plan \(v1\)](design/canvas-compose-plan.md)
- [canvas.compose: prior-art research](design/canvas-compose-prior-art.md)
- [Canvas Plugin Platform: Architecture Plan \(revision 2\)](design/canvas-plugin-platform-plan.md)
- [Canvas Plugin Platform: Prior Art Research](design/canvas-plugin-platform-prior-art.md)
- [Desktop App — Design-Template Update Spec](design/desktop-template-update-spec.md)
- [Design decision: editorial prose for chat](design/editorial-prose.md)
- [Letta Desktop reference: gap analysis for our client](design/letta-desktop-reference-gap-analysis.md)
- [On-demand agent tools via the Meridian CLI](design/on-demand-tools-via-meridian-cli.md)
- [Letta Onboarding And Desktop UI Review](design/onboarding-desktop-ui-review-2026-06.md)
- [Prior-art survey: design-system &amp; Penpot-sync references](design/starred-prior-art-survey.md)

### development

- [Generated Kotlin API reference](development/api-documentation.md)
- [Phone preview on the desktop](development/phone-preview.md)

### evidence

- [m6oa1.6 Iroh Subagent Capture Report](evidence/m6oa1-6-iroh-subagent-capture-report.md)

### handoff

- [flk.2 — Streaming Markdown Flicker Regression Notes](handoff/flk2-streaming-markdown-flicker.md)
- [Handoff: PR-2 stream integrity and live status \(F06–F10\)](handoff/letta-desktop-parity-pr2-stream.md)
- [Handoff: PR-5 Conversations and models \(F15-F18\)](handoff/letta-desktop-parity-pr5-conversations.md)
- [Handoff: Letta Desktop parity PR-6 — memory browser, secrets vault, file mentions \(F24–F26\)](handoff/letta-desktop-parity-pr6-memory-workspace.md)
- [Handoff: Merge Multi-Run Responses Into Single Virtual Run](handoff/merge-runs-2026-05-07.md)
- [Handoff — letta-mobile-mmnn \(Claude Code session, 2026-05-07\)](handoff/mmnn.md)

### observability

- [Telemetry conventions](observability/conventions.md)
- [Sentry ANR / crash budget dashboard](observability/sentry-anr-crash-budget.md)
- [StreamSubscriber observability](observability/stream-subscriber.md)

### performance

- [App performance bottleneck audit — 2026-05](performance/app-bottleneck-audit-2026-05.md)
- [Chat timeline perf baseline — post-Rive-mobile snapshot \(closing measurement\)](performance/chat-perf-baseline-post-rive-mobile-2026-09-14.md)
- [Chat timeline perf baseline — pre-Rive-mobile snapshot](performance/chat-perf-baseline-pre-rive-mobile-2026-09-13.md)
- [Chat pinch-to-zoom performance note](performance/chat-pinch-zoom.md)
- [Data-Efficiency Audit — LettaMobile Android](performance/data-efficiency-audit.artifact.md)
- [Iroh image replay investigation](performance/iroh-image-replay-inefficiency.md)
- [Production JankStats Sampling](performance/jankstats-production-sampling.md)
- [Android perf regression gate](performance/perf-gate.md)
- [Mobile Chat Render / Streaming Hot-Path Audit](performance/PERF_AUDIT_mobile_render.md)
- [Android performance playbook \(v1\)](performance/playbook.md)
- [App Performance Score History](performance/score-history.md)

### reference

- [canvas\_compose v1 reference](reference/canvas-compose-v1.md)
- [LCP wire v1: the canvas plugin protocol](reference/canvas-plugin-wire-v1.md)
- [Current App Server protocol and admin ownership reference](reference/current-app-server-protocol.md)
- [Documentation source audit — 2026-10-18](reference/documentation-audit-2026-10-18.md)
- [Looking up Letta documentation](reference/letta-docs.md)
- [Plugin trust model](reference/plugin-trust-model.md)
- [Plugin view bridge \`lcp-view/1\` — for plugin page authors](reference/plugin-view-bridge-v1.md)
- [QR Pairing Protocol](reference/qr-pairing-protocol.md)

### testing

- [5s9lq.1 Chat Hydration Trace Baseline](testing/5s9lq-1-chat-hydration-trace.md)
- [lgns8.10 acceptance evidence ledger](testing/lgns8-acceptance-evidence-ledger.md)
- [lgns8 App Server v2 — On-Device End-to-End Acceptance Protocol](testing/lgns8-e2e-device-protocol.md)
- [On-device App Server: model ids and test-agent cleanup](testing/on-device-local-backend.md)

### tooling

- [Self-hosted code intelligence pilot](tooling/serena-scip-pilot.md)
<!-- documentation-index:end -->
