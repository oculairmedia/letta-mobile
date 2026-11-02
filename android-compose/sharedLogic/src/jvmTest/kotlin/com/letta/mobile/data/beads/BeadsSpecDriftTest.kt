package com.letta.mobile.data.beads

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class BeadsSpecDriftTest {
    @Test
    fun operationIdsMatchVendoredSpec() {
        val spec = File("src/commonMain/resources/beads/openapi.v0-bd-1.3.2.yaml").readText()
        val ids = Regex("(?m)^\\s+operationId:\\s*(\\S+)\\s*$").findAll(spec).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.toSet().size, "Duplicate operation IDs in spec")
        assertEquals(ids.size, BeadsOperation.entries.size)
        assertEquals(ids.toSet(), BeadsOperation.entries.map { it.operationId }.toSet())
    }
}
