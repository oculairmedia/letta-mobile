# Self-hosted code intelligence pilot

## Serena MCP

Serena is pinned to `v1.6.0` / commit
`93b9544ea9def8e93cb6a90f8ea67befe3c8fee4`. The checked-in project config
indexes `android-compose` but excludes Android UI/application modules, generated
outputs, third-party code, and local caches. This keeps the default Kotlin LSP
scope representative and bounded around `sharedLogic`, `core`, CLI, desktop,
and avatar JVM/shared code.

```bash
scripts/mcp/serena.sh setup
scripts/mcp/serena.sh check
# Configure an MCP client using config/mcp/README.md, or run stdio directly:
scripts/mcp/serena.sh serve
```

The wrapper sets `SERENA_HOME`, uv cache/tool/Python paths, and XDG paths under
ignored `.local/`. Serena's checked-in project definition stays in
`.serena/project.yml`; mutable cache, logs, and memories below `.serena/` are
ignored. It applies 3 GiB virtual-memory, 30-minute CPU, 256-process,
4096-descriptor, and 120-second per-tool limits. `setup` also bounds dependency
resolution to three minutes; `serve` and `check` are offline-only and never
install implicitly. No token is required or accepted by the examples. Stop the MCP
client to terminate Serena, then run `scripts/mcp/serena.sh clean` to remove only
this worktree's cache. If a language server survives an abnormal client exit,
inspect it with `ps` and terminate that PID before cleaning; the script never
uses broad `pkill` patterns.

## scip-java Kotlin/JVM pilot

`scip-java` is pinned to `v0.13.1` with its published SHA-256. Current project
inputs are Gradle 9.4.1, Kotlin 2.4.0, AGP 9.2.0, and JVM target 17 (21 for
`appserver-cli`). Upstream supports Gradle Kotlin indexing but explicitly lists
Android Gradle integration as unsupported. This build is an Android/KMP
multi-project, so successful indexing is not claimed.

The pilot is deliberately opt-in and non-blocking. Gradle configuration for
this stack requires JDK 21 or newer; the script uses the common Linux JDK 21
path when present or accepts an explicit `SCIP_JAVA_HOME`. It asks scip-java to
compile only representative JVM/shared tasks:

- `:core:domain:compileKotlin`
- `:core:ids:compileKotlinJvm`
- `:core:runtime:compileKotlinJvm`
- `:sharedLogic:compileKotlinJvm`

It does not modify settings, module build files, build logic, or CI workflows.
Validation on the pinned stack reaches the representative compilation tasks but
fails because scip-java's `AnalyzerRegistrar` is binary-incompatible with Kotlin
2.4.0 (`AbstractMethodError: CompilerPluginRegistrar.getPluginId`). Therefore
this repository does not currently produce a SCIP artifact. The script preserves
the log and exits clearly instead of creating a placeholder index.

```bash
scripts/scip/scip-java-pilot.sh doctor
scripts/scip/scip-java-pilot.sh install
LETTA_SCIP_JAVA_EXPERIMENTAL=1 scripts/scip/scip-java-pilot.sh index
scripts/scip/scip-java-pilot.sh extract
scripts/scip/scip-java-pilot.sh clean
```

The index has a 16 GiB virtual-memory ceiling with a 2 GiB JVM heap, two Gradle
workers, a 20-minute wall-clock
limit, a 30-minute CPU limit, and a 256 MiB artifact cap. Outputs and a private
Gradle cache live under `.local/scip-java/`. `index.scip` is advisory only: it
must not gate builds, reviews, or CI.

`extract` requires the separate `scip` CLI and converts the protobuf through
`scip print --json`. It emits at most 20,000 newline-delimited records with
schema `letta.architecture.graph.v1`: `meta`, file/symbol `node`, and
`declares`/`references` `edge` records. This compact JSONL seam is intended for
the graph query MCP's ingestion adapter, not as a replacement for its canonical
Gradle/module graph. Record limits fail atomically rather than returning a
truncated graph.
