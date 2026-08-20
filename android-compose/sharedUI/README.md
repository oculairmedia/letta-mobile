# `:sharedUI`

Compose Multiplatform UI shared by Android and Desktop (wasm later).

**Charter:** Composables, shared chat chrome (markdown/bubbles), A2UI renderer,
and LettaIcons live here. Do **not** put transport, repositories, turn engines,
or projection/reducers here — those stay in `:sharedLogic`.

**Depends on:** `:sharedLogic` (and Compose Multiplatform).

**Consumers (Phase 3d):** `:app`, `:desktop`, `:web` will depend on this module
once UI sources move out of `:sharedLogic` (Phase 3b).

Created empty in KMP structure migration Phase 3a so Gradle/IDE can wire the
module before source moves. See `docs/architecture/kmp-structure-migration-epic.md`
Phase 3.
