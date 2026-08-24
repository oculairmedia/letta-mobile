# `:sharedUI`

Compose Multiplatform UI shared by Android and Desktop (wasm later).

**Charter:** Composables, shared chat chrome (markdown/bubbles), A2UI renderer,
and LettaIcons live here. Do **not** put transport, repositories, turn engines,
or projection/reducers here — those stay in `:sharedLogic`.

**Depends on:** `:sharedLogic` (and Compose Multiplatform).

**Consumers:** `:designsystem` (`api`), `:feature-chat`, `:desktop`. `:app` and
`:feature-editagent` receive shared UI transitively via designsystem.

**Kept in `:sharedLogic`:** chat timeline projector/presenter, streaming text
smoother core, tool-display registries, and commonMain projection / token types.

See `docs/architecture/kmp-structure-migration-epic.md` Phase 3.
