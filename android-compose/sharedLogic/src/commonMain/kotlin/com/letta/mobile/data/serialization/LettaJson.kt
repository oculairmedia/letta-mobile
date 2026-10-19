package com.letta.mobile.data.serialization

import kotlinx.serialization.json.Json

/**
 * The canonical [Json] configurations for Letta Mobile.
 *
 * Every production `Json {... }` outside this file is known debt, frozen in
 * `android-compose/architecture-tests/json-instances-baseline.txt` and flagged on changed files
 * by the `NoAdHocJsonInstance` detekt guardrail. New code picks one of these instances by what
 * the bytes are, never by what happens to decode today:
 *
 * | Instance | Bytes it handles | Unknown keys | Lenient / coerce | Defaults | Nulls |
 * |--------------|----------------------------------------------------|--------------|------------------|----------|----------|
 * | [wire]       | App Server `/ws`, Iroh turn frames, `plugins.*`    | ignored | no / no | omitted | omitted |
 * | [adminRpc]   | Letta REST bodies relayed over Iroh `admin_rpc`    | ignored | yes / yes | omitted | omitted |
 * | [persisted]  | Files and rows this app writes and reads back | ignored | no / no | written | written |
 *
 * There is deliberately no lenient test instance: tests decode with the instance the production
 * path uses, so a test cannot pass on input production would reject.
 *
 * See docs/architecture/canonical-json.md for the ratchet and the migration ledger.
 */
object LettaJson {
    /**
     * App Server and Iroh turn frames. This is the configuration `AppServerProtocol.json` has always
     * used, and `AppServerProtocol.json` is now an alias of it.
     *
     * - `ignoreUnknownKeys = true`: the App Server (`@letta-ai/letta-code`) adds fields between
     *   releases; an older app must keep decoding a newer server's frames.
     * - `isLenient = false`, `coerceInputValues = false`: the frames are machine-written JSON from a
     *   pinned protocol. A malformed frame or a type change is schema drift that must surface as a
     *   decode failure (with the redacted diagnostic in `AppServerProtocol`), not be silently turned
     *   into a default value that the turn engine then trusts.
     * - `encodeDefaults = false`, `explicitNulls = false`: outbound commands carry only what the
     *   caller set, so the server applies its own defaults; on decode an absent nullable field reads
     *   as `null`.
     * - `classDiscriminator` stays the library default `"type"`, which is what the sealed frame and
     *   command hierarchies declare with `@JsonClassDiscriminator("type")`.
     */
    val wire: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * Letta REST-shaped payloads carried over Iroh `admin_rpc` and the send-path decoder (the
     * former `lettaWireJson`). These bodies come from the Letta server's REST models, which write
     * unset optional fields as explicit `null` (for example `"metadata": null`) where the Kotlin
     * model has a non-null default. `coerceInputValues = true` with
     * `explicitNulls = false` maps those to the property default instead of failing the whole
     * response; `isLenient = true` is kept because it was already shipped on every one of these
     * paths.
     *
     * This differs from [wire] on purpose and is not to be used for App Server frames: coercion
     * would hide exactly the drift that [wire] is meant to surface.
     */
    val adminRpc: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * Local stores: files, Room JSON columns, preferences. Strict about values, tolerant only of
     * unknown keys:
     *
     * - `encodeDefaults = true` and `explicitNulls = true` (the default): every value is written
     *   out, so changing a Kotlin default later cannot silently change what an existing record
     *   means when it is read back.
     * - `ignoreUnknownKeys = true`: a record written by a newer build (one more field) still reads
     *   on an older build after a downgrade or a partial rollout, instead of throwing and losing
     *   the store.
     * - `isLenient = false`, `coerceInputValues = false`: the app wrote these bytes itself, so a
     *   malformed value or a type mismatch is corruption or an unmigrated schema change and must
     *   fail loudly where the store can decide to migrate or reset, not decode as a default.
     *
     * `HeadlessTimelineStore` has no `ignoreUnknownKeys` only because it never decodes: it encodes
     * diagnostic dumps. Its `encodeDefaults = true` is the same write-everything stance as here.
     */
    val persisted: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
        encodeDefaults = true
        explicitNulls = true
    }
}
