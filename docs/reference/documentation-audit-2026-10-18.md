# Documentation source audit — 2026-10-18

**Status:** Completed source audit of 179 tracked Markdown inputs against code revision
`8cad427f1c963710e949147a4d6b7690a13819ce`. This is a dated audit record, not a perpetual correctness guarantee.

The [downloadable per-file ledger](https://raw.githubusercontent.com/oculairmedia/letta-mobile/5ff0932cd/docs/reference/documentation-audit-2026-10-18.json) records every input document,
classification, findings, source/test evidence, changes and unverified claims. It includes
root and hidden guidance, all 91 docs-tree files, all 59 nested Android/KMP files, tooling,
experiments and vendored references. LF-normalized document hashes identify the reviewed text.
This report and its ledger are audit outputs, outside the input inventory. The ledger
is retained at an immutable Git revision and linked as a downloadable artifact; it is
not part of the maintained documentation source tree.

Three subagents audited architecture, the remaining docs tree, and nested module documentation.
The primary audited root/hidden guidance and other tooling. A second review checked the root
contract changes and the rewritten active architecture documents against implementation.

## Material corrections

- Replaced removed shim/SSE and REST timeline ownership claims with current channel,
  gateway/canonical ledger, paging and generation boundaries.
- Corrected module/host ownership, WASM targets, surviving Android chat scaffold,
  actual CLI commands, Room/cache and runtime-capability claims.
- Corrected full/lite APK names, application IDs, release flag assumptions and Gradle
  flavor commands. Historical release probes are not current Iroh acceptance evidence.
- Reconciled QR documentation with host-private HMAC and Iroh node authentication;
  the scanner does not verify a public envelope signature.
- Corrected composer queuing, mascot state mappings, renderer/test paths and tool contracts.
- Replaced blanket status labels with specific historical scope or current-source references;
  preserved old measurements and external research without presenting them as new evidence.

## Validation and limits

The ledger covers 179/179 inputs, with no duplicate entries; every cited repository evidence
path exists. This does not mean every historical/external assertion has been reproduced.
Existing docs freshness, policy-script tests, relative-link checks and diff checks validate
structural consistency; they cannot certify semantic truth on their own. The generated
Canvas Compose guide retains one literal `[text](url)` syntax placeholder, held equal
to source by its own ratchet; that placeholder is excluded from the relative-link scan.

Targeted source checks executed during the audit:

- System1 synthetic policy: 20 passed; no model or live-service calibration run.
- Chart plugin: 13 passed, 1 packaging test skipped (zip/unzip unavailable).
- Mascot contract/rig: 29 passed; Rive verify passed. Inspect retains an existing Wander
  editor-state overlap warning; no new human-recognition or hardware performance result.
- Fixture counts recomputed: 2,655 assistant / 256 reasoning frames, zero snapshot rewrites;
  the ten identity-corpus ID summaries match their frames.
- Architecture query: 9 passed, 1 Windows-invalid fixture error (follow-up `letta-mobile-omg9w`).

Live host readiness/version, camera/deep-link interoperability, deployment persistence,
production performance, external dashboards/research and license-approval evidence require
separate verification. No production state was changed. The generated `.beads/README.md`
remains an explicit exception: repository policy forbids committing `.beads` artifacts;
use current `AGENTS.md`/`bd` commands instead of its generic onboarding claims.

## Tooling findings

The audit filed source defects in beads rather than silently treating documentation edits
as implementation repairs: `letta-mobile-3tmv6` (legacy seeder overwrites host reachability),
`letta-mobile-ujbt5` (release gates), `letta-mobile-6umrq` (debug bootstrap package),
`letta-mobile-jigqk` (SCIP prerequisites), `letta-mobile-tvg5f` (inline merge-property mirror),
`letta-mobile-omg9w` (Windows fixture), and `letta-mobile-tanw3` (archived POC fallback).
Their live status is in beads; this list records the audit's disposition.
