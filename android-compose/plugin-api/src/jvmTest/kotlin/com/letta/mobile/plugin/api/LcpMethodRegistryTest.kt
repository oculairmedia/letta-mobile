package com.letta.mobile.plugin.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SPI and the LCP wire map one-to-one: every SPI member is a wire method, an initialize parameter, or jvm-only. */
class LcpMethodRegistryTest {
    @Test
    fun `every plugin member is a host-to-plugin wire method`() {
        val mapped = LcpMethod.entries.filter { it.direction == LcpDirection.HOST_TO_PLUGIN }.map { it.spiMember }.toSet()
        assertEquals(mapped, members(CanvasPlugin::class.java).map { "CanvasPlugin.$it" }.toSet())
    }

    @Test
    fun `every host member is a plugin-to-host wire method, an initialize parameter or jvm-only`() {
        val mapped = LcpMethod.entries.filter { it.direction == LcpDirection.PLUGIN_TO_HOST }
            .map { it.spiMember.removePrefix("PluginHost.") }.toSet()
        val accounted = mapped + LcpMethod.INITIALIZE_PARAMS + LcpMethod.JVM_ONLY_HOST_MEMBERS
        assertEquals(accounted, members(PluginHost::class.java).toSet())
        assertTrue((mapped intersect LcpMethod.JVM_ONLY_HOST_MEMBERS).isEmpty())
    }

    @Test
    fun `wire names are unique and resolve back`() {
        assertEquals(LcpMethod.entries.size, LcpMethod.entries.map { it.wire }.toSet().size)
        LcpMethod.entries.forEach { assertEquals(it, LcpMethod.byWire(it.wire)) }
        assertNull(LcpMethod.byWire("tools/call"))
    }

    @Test
    fun `requests have deadlines from the plan and notifications have none`() {
        assertEquals(10_000L, LcpMethod.INITIALIZE.deadlineMillis)
        assertEquals(30_000L, LcpMethod.ACTIVATE.deadlineMillis)
        assertEquals(100_000L, LcpMethod.INVOKE.deadlineMillis)
        assertEquals(5_000L, LcpMethod.HEALTH.deadlineMillis)
        LcpMethod.entries.filter { it.notification }.forEach { assertNull(it.deadlineMillis, it.wire) }
    }

    /** The interface's own member names, properties by their Kotlin name. */
    private fun members(type: Class<*>): List<String> =
        type.declaredMethods.filter { !it.isSynthetic }.map { it.name.propertyName() }.distinct()

    private fun String.propertyName(): String =
        if (startsWith("get") && length > 3) substring(3).replaceFirstChar { it.lowercase() } else this
}
