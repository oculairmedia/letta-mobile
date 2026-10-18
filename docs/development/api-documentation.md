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
