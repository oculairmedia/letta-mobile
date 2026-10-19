# Generated Kotlin API reference

Dokka generates the API reference from declarations and KDoc in `sharedLogic`,
`sharedUI`, `core:ids`, `core:runtime` and `plugin-api`. The root publication
aggregates those modules; the `com.letta.mobile.api-documentation` convention
plugin keeps their visibility and source-link settings consistent.

The [API overview](../../android-compose/Module.md) introduces these modules and
explains platform tags. Each published module owns a `Module.md` beside its build
file, with `# Module :module-name` and `# Package fully.qualified.name` sections.
Read and update those sections when package ownership or extension points change;
Dokka includes the same prose in the module and package pages. Describe purpose,
boundaries and integration seams rather than copying declaration inventories.

The required `test` job and the `api-docs` job enforce package coverage:

```bash
python3 scripts/ci/check-api-package-coverage.py
python3 -m unittest discover -s scripts/tests -p test_api_package_coverage.py -v
```

Run these from the repository root. The checker discovers published modules from
the root Dokka dependencies and compares their production Kotlin/Java packages
with overview sections. Missing, orphaned, duplicate or empty sections fail;
fenced examples and comments do not count. Test sources and generated build
output are excluded. Unknown source-set conventions or aggregate dependency
syntax fail rather than silently reduce coverage; update discovery alongside
such build changes. There is no package baseline to regenerate. This checks
coverage, not the semantic accuracy of ownership claims, which still needs review.

The same jobs reject tracking IDs in production KDoc of published modules:

```bash
python3 scripts/ci/check-kdoc-hygiene.py
python3 -m unittest discover -s scripts/tests -p test_kdoc_hygiene.py -v
```

Describe API intent in KDoc; keep issue references in implementation comments or
commit history. UI test selectors are internal implementation details. Shared
smoke-run support lives in `:appserver-testkit`, while CLI-only probe metrics live
in `:cli`; neither module is part of the product API publication. Health probes
and bundled canvas examples remain public because production consumers use them.

From `android-compose/`, with the Java/Android SDK setup in CONTRIBUTING.md:

```bash
./gradlew --no-daemon --no-parallel --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :dokkaGeneratePublicationHtml
```

Open `android-compose/build/dokka/html/index.html`. Output stays under ignored
`build/` directories; do not commit generated HTML. A module can be generated
individually, for example `:plugin-api:dokkaGeneratePublicationHtml`.

The `api-docs` job in **Android APK CI** generates an HTML artifact on PRs and
main pushes covered by that workflow. Download `kotlin-api-docs` from its Actions
run, extract it, and open `index.html`. The artifact is retained for 30 days.

On main pushes, `assemble-web-pages` combines the WASM site with the generated
reference under `api/`, then `deploy-web-pages` publishes the single Pages artifact.
The app remains at the site root; the API reference is served at
[letta-mobile/api/](https://oculairmedia.github.io/letta-mobile/api/) after merge
and a successful main deployment. PRs generate downloadable docs without deploying.
Both builds must succeed before the combined site can deploy.

CI pins source links to the checked-out commit using `-PdokkaSourceRef=<sha>`.
Local generation defaults to `main`; pass your commit or branch to link your
current revision. Public/protected production declarations are included and
generated files are suppressed. Missing KDocs are checked against a shrinking diagnostic baseline for the
runtime and plugin API modules and shared extension packages. Other packages
remain outside that coverage policy; generation errors fail the build.

Keep architecture decisions, operating procedures, design intent and measured
evidence in hand-written docs. Generate API shapes from source and keep runtime
or protocol inventories tied to their owning schemas/tests; Dokka cannot infer
deployment state or prove behavior described in a comment.

Reference: [Dokka Gradle documentation](https://kotlinlang.org/docs/dokka-gradle.html).

## Generated structure report

The required `test` job also generates a [structure report](https://oculairmedia.github.io/letta-mobile/structure/),
published beside the API reference and attached to each PR as `kotlin-structure-report`.
It reuses the architecture tests' Konsist production scope and package import
resolver; the module-direction section is the existing boundary gate's report,
not a second rule inventory. The advisory architecture export job generates the
same report. Reports under `android-compose/build/reports/structure/` are generated,
not checked in.

Named Kotlin classes, interfaces and objects (including nested types, excluding
local/anonymous types) count once per module/qualified name/kind across platforms.
Aliases and Java declarations are outside this metric. Packages allow 50 types;
existing larger packages have exact limits in `architecture-tests/structure-baseline.json`.
Cyclic import edges are baselined by identity, including cycles longer than two
packages. New debt fails; improvements require deleting/lowering stale entries.
CI also compares the baseline with the immutable PR base SHA, so regenerating it
cannot authorize growth. After a reduction, run the generator with `--write-baseline`
and the same `--base-ref`; it writes observed values but still rejects growth.

Public declaration, mirrored-name and probe/sample tables are review candidates,
not dead-code proofs or hard gates. The consumer heuristic checks top-level public
names for mentions in other production Kotlin files, conservatively including
comments and strings. Same-file calls, aliases, reflection, generated code and
external plugin consumers need manual review. Import edges are source evidence,
not resolved call graphs. These limitations are displayed in the generated page.

## Linking to generated APIs

Link to the full published URL, including `/letta-mobile/api/`, so Markdown read
on GitHub reaches the same site as browser users. Verify the actual generated
page; do not infer a type's URL from its Kotlin name. After generation, run:

```bash
python3 scripts/ci/check-api-doc-links.py
```

Run this from the repository root. The `api-docs` job runs it too, checking
published API links in tracked Markdown against local generated pages and HTML
anchors. It ignores fenced examples and never relies on the currently deployed
site, so renamed or removed symbols can fail the PR before deployment.

In module/package overviews, make concrete API references links to their generated
declaration pages. Plain backticks remain appropriate for source-set names, module
names and example values; they do not create a checked symbol reference. When an
API moves or is renamed, update its overview link in the same PR. A link proves
that the published declaration exists, not that prose about its platform or
behavior is accurate; review those claims against the declaration's source sets.

The required `test` status aggregates `test-workload`, `api-docs` and
`api-contract-windows`. These run in parallel; generation errors, missing linked
declarations, changed API signatures and failed tests all block that status.
The aggregate runs even when a dependency is skipped or cancelled and accepts
only explicit success from all three jobs.

## Maintaining the KDoc contract

`api-docs` captures actual Dokka `Undocumented:` diagnostics for `:core:runtime`,
`:plugin-api`, and the shared controller extras, timeline, transport API, App Server and chat
session packages. Each baseline entry retains its module, overload signature
and source set. Counts are printed per module, but removing documentation on one
API cannot be offset by documenting a different API. `:core:runtime` must have
zero missing comments and cannot be baselined.

The checker rejects new undocumented signatures, stale baseline entries, and
baseline additions relative to the PR base commit. It also rejects failed or
cached module generation: cached HTML cannot replay documentation warnings.
The monitored Dokka tasks therefore run freshly, while other Gradle tasks may
still use their caches.

After documenting or removing an existing API, capture a successful build and
refresh the baseline from its diagnostics, then review the resulting deletions:

```bash
cd android-compose
set -o pipefail
./gradlew --no-daemon --no-parallel --max-workers=2 --console=plain \
  -Pkotlin.compiler.execution.strategy=in-process \
  :core:runtime:dokkaGenerateModuleHtml :plugin-api:dokkaGenerateModuleHtml \
  :sharedLogic:dokkaGenerateModuleHtml 2>&1 | tee dokka-diagnostics.log
cd ..
python3 scripts/ci/check-api-doc-undocumented.py android-compose/dokka-diagnostics.log --write-baseline
python3 scripts/ci/check-api-doc-undocumented.py android-compose/dokka-diagnostics.log --diff-base origin/main
```

Writing a baseline records evidence; it does not authorize new debt. New public
APIs in the monitored packages need KDoc. Describe semantics and constraints,
including constructor properties and enum entries, rather than repeating names.

## Automation boundaries and next increments

The 2026-10-18 follow-up audit distinguishes declaration documentation from
evaluated registries and behavior. Dokka exposes a `JsonObject` or `List<String>`
property without evaluating a tool schema or widget list, and some aliased token
values are absent. Remove copied inventories only after checking the generated
replacement actually contains the information.

| Reference | Existing authority | Further automation |
|---|---|---|
| Protocol and admin ownership | Pinned [protocol inventory](../../android-compose/sharedLogic/src/jvmTest/resources/appserver/installed-protocol-v2-inventory.json), [ownership matrix](../../android-compose/sharedLogic/src/jvmTest/resources/appserver/iroh-admin-ownership-matrix.json), registry parity tests | Render current commands, counts, hashes and ownership directly from these fixtures, with source commit/version shown. |
| Tool schemas, wire tables and widget catalogs | Runtime registries; [LCP table test](../../android-compose/sharedLogic/src/jvmTest/kotlin/com/letta/mobile/data/plugin/wire/LcpWireDocTest.kt) and [Canvas Compose guide test](../../android-compose/sharedLogic/src/jvmTest/kotlin/com/letta/mobile/data/canvas/compose/CanvasComposeGuideDocTest.kt) already generate marked content | Publish evaluated schemas/catalogs and existing generated outputs directly; remove manual copy/paste updates while retaining parity checks. |
| Modules, targets and dependencies | [Architecture exporter](../../android-compose/build-logic/src/main/kotlin/com/letta/mobile/architecture/ArchitectureGraphPlugin.kt) already emits configured Gradle/KMP records | Render inventories and dependency maps from its JSONL; retain human ownership/rationale beside them. |
| Deployment and performance evidence | Dated captures with version, commit and scope | Validate metadata and links; require fresh captures to claim a new observation. Source pins cannot establish deployed state. |

Protocol/ownership generation has the highest immediate value: the existing
`scripts/appserver/verify-v2-audit-doc.mjs` searches the historical v2 audit for
current pinned values, so it fails after a pin moves even when the old evidence
is honestly dated. Generate a separate current reference rather than rewriting
history to satisfy that assertion. Each generator should have deterministic
fixture tests and reject stale output; schema/widget changes must alter its
reference without requiring a live host.

KDoc also needs review: generated pages repeat stale comments faithfully.
Generation and link gates prove the publication is buildable and navigable;
they do not prove the runtime behavior described in prose.


The required Kotlin guardrail scan compares code with the PR merge base before
selecting changed files. Comment-only edits do not bring unchanged runtime debt
into scope. New files, code or literal changes, and malformed source remain in
scope; staged and uncommitted edits are included locally. Git failures stop the
scan rather than returning an empty selection. This selection does not change
detekt rules or debt baselines.
