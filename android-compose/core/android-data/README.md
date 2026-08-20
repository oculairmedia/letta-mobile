# `:core:android-data`

Android-only persistence, DI, and session wiring.

**Charter:** Room, DataStore, Hilt modules, `SessionManager` / `SessionScoped*`,
Android Ktor (OkHttp), and Android-bound repository implementations live here.
Do **not** add portable domain logic, transport contracts, or reducers — those
belong in `:sharedLogic/commonMain` (see `docs/architecture/kmp-module-map.md`).

Consumers: `:app`, `:feature-*`, `:designsystem`, `:cli`, `:core:testutil`.
Desktop does **not** depend on this module; it binds through `:sharedLogic`
plus `desktop/data` adapters.

Renamed from `:core:data` in KMP structure migration Phase 2 so the Android-only
role is obvious in the module graph.
