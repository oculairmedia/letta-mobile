# Canonical JSON configurations

Bead: `letta-mobile-by4vm.20`. Code: `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/serialization/LettaJson.kt`.

## Problem

Before this change, main had 171 `Json { ... }` constructions in 161 production files (114 of them
in 107 `sharedLogic` files). They set `ignoreUnknownKeys`, `isLenient`, `coerceInputValues`,
`encodeDefaults` and `explicitNulls` in about 40 different combinations, so App Server schema drift
was handled differently on every path: one path dropped an unknown field, another failed the
frame, a third turned a type change into a default value.

## The three instances

| Instance | Bytes it handles | `ignoreUnknownKeys` | `isLenient` | `coerceInputValues` | `encodeDefaults` | `explicitNulls` |
|---|---|---|---|---|---|---|
| `LettaJson.wire` | App Server `/ws` frames, Iroh-relayed turn frames, the `plugins.*` admin_rpc bodies | true | false | false | false | false |
| `LettaJson.adminRpc` | Letta REST bodies over Iroh `admin_rpc`, send-path decoder | true | true | true | false | false |
| `LettaJson.persisted` | Files, Room JSON columns, preferences the app writes | true | false | false | true | true |

All three keep the default `classDiscriminator = "type"`, which is what `AppServerInboundFrame` and
`AppServerCommand` declare with `@JsonClassDiscriminator("type")`. The plugin types on the
`plugins.*` wire (`PluginState`, `PluginHealth`, `PluginRuntime`) declare `@JsonClassDiscriminator("kind")`. The KDoc on each instance gives
the rationale for each flag. In short:

- `wire` tolerates new fields from a newer App Server but fails on malformed frames or type
  changes, so drift is reported through `AppServerProtocol`'s redacted decode diagnostics and not
  turned into defaults that the turn engine then trusts.
- `adminRpc` keeps the leniency the admin_rpc sources already shipped (letta-mobile-71orq): the
  Letta REST models write unset optional fields as explicit `null` where the Kotlin model has a
  non-null default.
- `persisted` writes every value, so changing a Kotlin default later cannot change what an existing
  record means. It ignores unknown keys so a record from a newer build still reads after a
  downgrade, and it refuses lenient or coerced input because the app wrote those bytes itself.
  `HeadlessTimelineStore` has no `ignoreUnknownKeys` only because it never decodes; it writes
  diagnostic dumps with the same `encodeDefaults = true` stance.

There is no lenient instance for tests. Every test in the tree decodes with `AppServerProtocol.json`
or a production instance, so none needed one.

## Why there are three instances and not two

The bead asked for one wire instance and one persisted instance, with `AppServerProtocol.json` and
`lettaWireJson` both becoming aliases of the wire instance. Their flags differ, so unifying them
would have changed behaviour:

| Flag | `AppServerProtocol.json` | `lettaWireJson` |
|---|---|---|
| `ignoreUnknownKeys` | true | true |
| `isLenient` | false | **true** |
| `coerceInputValues` | false | **true** |
| `encodeDefaults` | false | false (default) |
| `explicitNulls` | false | false |

Consumers that depend on each:

- `AppServerProtocol.json` decodes and encodes App Server frames in 23 production files, among
  them `AppServerInboundFrame`, `AppServerTurnEngine`, `AppServerTimelineTransport`,
  `AppServerAgentRepository`, `AppServerDiscovery`, `AppServerWorkspaceRelay`,
  `SettledTurnCollector` and `WasmAppServerSession`, and in every App Server contract test. It is
  now `LettaJson.wire`, so all of them read through the canonical instance without being edited.
- `lettaWireJson` is used by `OutboundMessageCreate` (send path), `IrohApprovalSubmitBody`,
  `IrohAdminRpcChatGateway` and `IrohAdminRpcAgentDirectory`, which handle Letta REST shapes, the
  same family as the admin_rpc sources. It is now `LettaJson.adminRpc`.

Both aliases are `===` to their canonical instance, pinned in `LettaJsonTest`. Whether `adminRpc`
can ever converge on `wire` is a behaviour question for its own bead: it needs evidence that the
Letta REST bodies on those paths no longer send explicit nulls for non-null fields.

## The ratchet

- `android-compose/architecture-tests/json-instances-baseline.txt` lists every production file that
  still builds its own `Json`, one sorted repo-relative path per line. Production means any
  `src/<set>` whose name does not contain `test`, excluding `build`, `generated` and `ksp`.
- `JsonInstanceRatchetTest` runs in `:architecture-tests:test` and fails when a file that is not
  listed constructs a `Json { }`, a `Json(from) { }` or a fully qualified
  `kotlinx.serialization.json.Json { }`. Only `LettaJson.kt` is exempt.
- Stale lines do not fail; the test prints them. Anyone may delete a stale line, and a PR that
  migrates a file does not have to touch the baseline, so roughly 25 queued PRs cannot conflict
  on it. The list is sorted and unique, which the test does enforce.
- The `NoAdHocJsonInstance` rule (`quality/detekt-rules`, enabled in `detekt-guardrails.yml`)
  reports the same constructions in files changed from `origin/main`, so the author hears about it
  before CI. It reads the same baseline and skips listed files: `guardrailDetekt` has no baseline
  of its own, and without the skip any PR touching a listed file would fail on debt it did not add.

## Migrating a file

1. Compare the file's flags to the target instance one by one. A file moves only if they are
   identical, including the defaults it leaves unset (for example, `ignoreUnknownKeys = true` alone
   means `explicitNulls = true`, so it is **not** `wire`).
2. Replace the construction with the instance and import `com.letta.mobile.data.serialization.LettaJson`.
3. Delete the file's baseline line, or leave it for a later cleanup; a stale line does not fail.
4. If the flags differ, migrating is a behaviour change. Open a bead that names the drift the path
   has to tolerate, and change it there with a test of that drift.

Baseline entries on the App Server and Iroh paths carry a `#` comment that names the flags that stop
them from migrating.

## Ledger

| Step | Files constructing `Json` | Constructions |
|---|---|---|
| main before this change | 161 | 171 |
| `AppServerProtocol.json` and `lettaWireJson` aliased (adds `LettaJson.kt`) | 159 + `LettaJson.kt` | 169 + 3 canonical |
| 11 admin_rpc files moved to `LettaJson.adminRpc` | 148 + `LettaJson.kt` | 157 + 3 canonical |
| `PluginAdminRpc.json` removed: `plugins.*` on `LettaJson.wire` (by4vm.102, protocol change) | one file fewer | one fewer |

Files moved to `LettaJson.adminRpc`, each with exactly `ignoreUnknownKeys`, `isLenient`,
`explicitNulls = false` and `coerceInputValues` before the change:
`IrohAdminRpcAgentSource`, `IrohAdminRpcBlockSource` (2 constructions), `IrohAdminRpcClient`,
`IrohAdminRpcMcpSource`, `IrohAdminRpcModelSource`, `IrohAdminRpcPassageSource`,
`IrohAdminRpcProjectSource`, `IrohAdminRpcProviderSource`, `IrohAdminRpcRunSource`,
`IrohAdminRpcToolSource` (all `sharedLogic/src/jvmAndAndroid/.../data/repository/`) and
`core/android-data/.../data/timeline/IrohAdminRpcTimelineTransport`.

Known exact matches that were left in place: `System1Client` has the `adminRpc` flags but is not on
the App Server or Iroh path, and `CachedAgentRepository` has one `adminRpc`-identical construction
next to one that is not. `LcpWire` and `CanvasPluginElements` have the `wire` flags but speak their
own protocols, so tying them to the App Server wire configuration is a separate decision.

`LettaJson.persisted` has no consumers yet. The 14 constructions that set exactly
`ignoreUnknownKeys = true` and `encodeDefaults = true` (mostly file stores, for example
`FileCanvasDeliveryStore`, `FileCanvasRelayStore` and `RoomMemFsStore`) match it flag for flag
and are the next migration candidates. They are not on the wire path, so this change left them alone.
