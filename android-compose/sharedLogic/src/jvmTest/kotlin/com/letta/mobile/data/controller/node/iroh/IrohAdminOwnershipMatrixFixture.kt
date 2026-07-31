package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Single loader for the executable ownership matrix
 * (`resources/appserver/iroh-admin-ownership-matrix.json`).
 *
 * lgns8.21.10: the shim-off parity gate used to carry its own hand-maintained
 * lists of "must succeed natively" / "may degrade" methods, which silently
 * drifted from the matrix (20 of 38 shim-free rows covered). Both the ownership
 * matrix test and the parity gate now read this loader, so adding a matrix row
 * automatically adds a parity expectation with no second list to edit.
 */
internal object IrohAdminOwnershipMatrix {
    /** Owners that must serve WITHOUT the shim after cutover. */
    val SHIM_FREE_OWNERS: Set<String> = setOf("app_server_v2", "controller_native")

    val root: JsonObject by lazy { fixtureJson("iroh-admin-ownership-matrix.json") }
    val inventory: JsonObject by lazy { fixtureJson("installed-protocol-v2-inventory.json") }
    val operations: List<JsonObject> by lazy { root.getValue("operations").jsonArray.map { it.jsonObject } }
    val enums: JsonObject by lazy { root.getValue("enums").jsonObject }

    /**
     * Methods whose post-shim owner serves natively with no fallback — these must
     * return `success:true` shim-off and must never dial the admin proxy.
     */
    fun shimFreeNativeMethods(): List<String> = operations
        .filter { it.requiredString("post_shim_owner") in SHIM_FREE_OWNERS }
        .filter { it.requiredString("post_shim_fallback") == "none" }
        .map { it.requiredString("method") }
        .sorted()

    /** Methods that are intentionally unavailable — exact `capability_unavailable` contract. */
    fun capabilityGatedMethods(): List<String> = operations
        .filter { it.requiredString("post_shim_owner") == "capability_gated_unsupported" }
        .map { it.requiredString("method") }
        .sorted()

    /**
     * Bounded services (admin REST, VibeSync) that are NOT injected in the gate:
     * they must degrade to a clean `success:false`, never throw or hang.
     */
    fun boundedServiceMethods(): List<String> = operations
        .filter { it.requiredString("post_shim_owner") in setOf("admin_rest_service", "vibesync_service") }
        .map { it.requiredString("method") }
        .sorted()

    /**
     * lgns8.9: methods served from the READ-ONLY on-disk local backend store.
     * They must succeed when `LETTA_LOCAL_BACKEND_DIR` is wired to a store that
     * holds the row, and fail closed (never dial an admin host) when it is not.
     */
    fun localBackendStoreMethods(): List<String> = operations
        .filter { it.requiredString("post_shim_owner") == LOCAL_BACKEND_STORE_OWNER }
        .map { it.requiredString("method") }
        .sorted()

    const val LOCAL_BACKEND_STORE_OWNER: String = "local_backend_store"

    fun fixtureJson(name: String): JsonObject {
        val stream = checkNotNull(javaClass.getResourceAsStream("/appserver/$name")) { "Missing fixture $name" }
        return Json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
    }

    fun JsonObject.requiredString(key: String): String =
        checkNotNull(this[key]?.jsonPrimitive?.content) { "Missing '$key' in $this" }

    fun JsonObject.stringSet(key: String): Set<String> =
        this[key]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
}
