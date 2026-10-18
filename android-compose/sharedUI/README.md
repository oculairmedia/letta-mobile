# `:sharedUI`

Compose Multiplatform UI shared by Android, desktop and browser (wasmJs).

**Charter:** shared pages, shell, chat rows and composer, A2UI, canvas, mascot UI,
icons and rendering belong here. Transport, repositories, turn engines and portable
projection/reducers belong in `:sharedLogic`.

**Targets:** Android, JVM and wasmJs, declared in `build.gradle.kts`. `skikoMain`
shares rendering actuals between JVM and wasmJs.

**Project dependencies:** commonMain exposes `:sharedLogic`, `:avatar:core` and
`:drawbox`; skikoMain exposes `:avatar:renderer-rive`. JVM tests use
`:sharedUI-devfixtures`. Third-party Compose/rendering dependencies are listed in
the build script.

**Consumers:** `:designsystem`, `:feature-chat`, `:desktop`, `:web` and
`:plugin-view`; Android `:app` receives the shared UI through its dependencies.
New product UI goes here; platform modules bind navigation, lifecycle and services.

See the root `AGENTS.md` ownership rule and `docs/architecture/kmp-module-map.md`.
