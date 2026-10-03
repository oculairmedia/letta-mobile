package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.LcpMethod
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/reference/canvas-plugin-wire-v1.md carries the method table and the error codes generated
 * from [LcpMethod] and [LcpErrorCode] word for word between its markers, so the author's reference
 * cannot drift from the registry (letta-mobile-s416w.25). The tables are also written to
 * build/canvas-plugin-wire-v1-tables.md, so a change can be pasted into the doc.
 */
class LcpWireDocTest {
    @Test
    fun theReferenceDocCarriesTheGeneratedTables() {
        val tables = methodTable() + "\n\n" + errorTable()
        File("build").resolve("canvas-plugin-wire-v1-tables.md").apply { parentFile.mkdirs() }.writeText(tables + "\n")

        val doc = File(DOC).also { assertTrue(it.isFile, "missing ${it.absolutePath}") }.readText().replace("\r\n", "\n")
        assertTrue(START in doc && END in doc, "the doc has lost its table markers")
        val copy = doc.substringAfter(START).substringBefore(END).trim('\n')
        assertEquals(tables, copy, "docs/reference/canvas-plugin-wire-v1.md is out of date: paste build/canvas-plugin-wire-v1-tables.md between its markers")
    }

    private fun methodTable(): String = buildList {
        add("| Method | Direction | Kind | Deadline | Capability | SPI member |")
        add("|---|---|---|---|---|---|")
        LcpMethod.entries.forEach { method ->
            val deadline = method.deadline?.toString() ?: "-"
            val capability = LcpCapabilityGuard.byMethod[method]?.wire ?: if (method == LcpMethod.EMIT) "by content" else "-"
            val kind = if (method.isRequest) "request" else "notification"
            add("| `${method.wire}` | ${method.direction.label} | $kind | $deadline | $capability | `${method.spiMember}` |")
        }
    }.joinToString("\n")

    private fun errorTable(): String = buildList {
        add("| Code | Meaning |")
        add("|---|---|")
        LcpErrorCode.meanings.forEach { (code, meaning) -> add("| $code | $meaning |") }
    }.joinToString("\n")

    private companion object {
        /** From the sharedLogic module directory, where Gradle runs its tests. */
        const val DOC = "../../docs/reference/canvas-plugin-wire-v1.md"
        const val START = "<!-- lcp_wire_tables:start -->"
        const val END = "<!-- lcp_wire_tables:end -->"
    }
}
