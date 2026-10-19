# Module :sharedLogic

Shared domain, transport and state-management code for Letta clients and controller hosts.
Start with repository/session contracts for resources, App Server clients and runtime packages
for turns, and timeline packages for durable history and live projections. UI-facing chat
ports and render models are portable contracts; composable painting belongs to `:sharedUI`.

Read each declaration's platform/source-set tags. `commonMain` holds shared contracts,
`jvmAndAndroid` adds JVM/Android integrations, and `jvmMain` provides JVM process/filesystem
facilities. Android, host-native and wasmJs actuals implement their declared seams; a package
appearing here does not mean its native runtime is available on every target. These package
overviews describe responsibilities, not deployment health or remote-server capabilities.

# Package com.letta.mobile.appserver

Host App Server command specifications and argument validation shared by the command-line entry points. Keep process launching in host bindings; construct validated arguments with `buildAppServerServeCommand` and configure them through [AppServerServeSpec](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.appserver/-app-server-serve-spec/index.html). Smoke-run scenarios live in the separate `:appserver-testkit` module.
