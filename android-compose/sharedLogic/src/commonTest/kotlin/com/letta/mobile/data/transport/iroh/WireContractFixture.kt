package com.letta.mobile.data.transport.iroh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared data-only fixture reader; client fakes return [response] rather than inventing wire shapes. */
class WireContractFixture(text: String) {
    private val document = Json.parseToJsonElement(text).jsonObject
    val method: String = document.getValue("method").jsonPrimitive.content
    val cases: List<JsonObject> = document.getValue("cases").jsonArray.map { it.jsonObject }

    init {
        require(cases.isNotEmpty()) { "Contract $method must have cases" }
        val names = cases.map { it.getValue("name").jsonPrimitive.content }
        require(names.size == names.toSet().size) { "Contract $method has duplicate case names" }
    }

    /** The complete response envelope, identical for host assertions and client test doubles. */
    fun response(caseName: String): JsonObject = cases.single {
        it.getValue("name").jsonPrimitive.content == caseName
    }.getValue("expected_response").jsonObject
}
