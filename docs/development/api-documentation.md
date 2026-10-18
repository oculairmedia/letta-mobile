# Generated Kotlin API reference

Dokka generates the API reference from declarations and KDoc in `sharedLogic`,
`sharedUI`, `core:ids`, `core:runtime` and `plugin-api`. The root publication
aggregates those modules; the `com.letta.mobile.api-documentation` convention
plugin keeps their visibility and source-link settings consistent.

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
generated files are suppressed. Existing missing KDocs do not fail generation;
generation errors do. This is not a claim of complete KDoc coverage.

Keep architecture decisions, operating procedures, design intent and measured
evidence in hand-written docs. Generate API shapes from source and keep runtime
or protocol inventories tied to their owning schemas/tests; Dokka cannot infer
deployment state or prove behavior described in a comment.

Reference: [Dokka Gradle documentation](https://kotlinlang.org/docs/dokka-gradle.html).

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
