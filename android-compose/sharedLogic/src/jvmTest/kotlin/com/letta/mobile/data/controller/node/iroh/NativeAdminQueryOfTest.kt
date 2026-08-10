package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-f2ap5.
 *
 * `NativeAdmin.queryOf` used to be written as
 * `v.toLongOrNull()?.let { put(key, JsonPrimitive(it)) } ?: put(key, JsonPrimitive(v))`.
 * `JsonObjectBuilder.put` returns the *previous* value for the key — null for a fresh
 * key — so the elvis branch always fired and overwrote the numeric primitive with a
 * string one. Every numeric admin-RPC param therefore went out quoted.
 *
 * The App Server honours a limit only when it is a JSON number
 * (`typeof body.limit === "number" ? body.limit : 20`), so a quoted limit was silently
 * discarded and every paginated admin call clamped to 20 rows. That is what capped the
 * agent roster at 20 of 1536 and defeated the letta-mobile-pu7j7 offset emulation.
 */
class NativeAdminQueryOfTest {
    @Test
    fun numericValuesAreEmittedAsUnquotedJsonNumbers() {
        val query = NativeAdmin.queryOf("limit" to "50", "offset" to "100")
        val limit = query?.get("limit")?.jsonPrimitive
        val offset = query?.get("offset")?.jsonPrimitive

        assertEquals("50", limit?.content)
        assertEquals("100", offset?.content)
        assertFalse(limit?.isString ?: true, "limit must be an unquoted number, got: $limit")
        assertFalse(offset?.isString ?: true, "offset must be an unquoted number, got: $offset")
        assertEquals(JsonPrimitive(50L), limit)
        assertEquals(JsonPrimitive(100L), offset)
    }

    @Test
    fun nonNumericValuesStayQuotedStrings() {
        val query = NativeAdmin.queryOf("order" to "desc")
        val order = query?.get("order")?.jsonPrimitive

        assertEquals("desc", order?.content)
        assertTrue(order?.isString ?: false, "non-numeric values must stay quoted, got: $order")
    }

    @Test
    fun mixedNumericAndStringValuesKeepTheirOwnEncoding() {
        val query = requireNotNull(NativeAdmin.queryOf("limit" to "50", "order" to "desc"))

        assertFalse(query["limit"]?.jsonPrimitive?.isString ?: true)
        assertTrue(query["order"]?.jsonPrimitive?.isString ?: false)
    }

    @Test
    fun serializedFormHasAnUnquotedLimit() {
        // The wire shape is what the App Server's `typeof === "number"` check sees.
        assertEquals("""{"limit":50}""", NativeAdmin.queryOf("limit" to "50").toString())
    }

    @Test
    fun nullValuesAreDroppedAndAnAllNullQueryIsNull() {
        assertNull(NativeAdmin.queryOf("limit" to null))

        val query = NativeAdmin.queryOf("limit" to "50", "offset" to null)
        assertEquals(setOf("limit"), query?.keys)
    }

    @Test
    fun negativeAndZeroValuesRemainNumbers() {
        val query = requireNotNull(NativeAdmin.queryOf("offset" to "0", "delta" to "-5"))

        assertFalse(query["offset"]?.jsonPrimitive?.isString ?: true)
        assertFalse(query["delta"]?.jsonPrimitive?.isString ?: true)
        assertEquals(JsonPrimitive(0L), query["offset"]?.jsonPrimitive)
        assertEquals(JsonPrimitive(-5L), query["delta"]?.jsonPrimitive)
    }
}
