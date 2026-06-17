package com.letta.mobile.runtime.notifications

import com.letta.mobile.runtime.actions.MobileActionCapabilityStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStoreTest {
    @Test
    fun `ring buffer caps at max size and keeps newest`() {
        val store = InMemoryNotificationStore(maxSize = 3)
        repeat(5) { i ->
            store.record(CapturedNotification("pkg.$i", "t$i", "x$i", i.toLong()))
        }
        val recent = store.recent(10)
        assertEquals(3, recent.size)
        // newest first: 4, 3, 2
        assertEquals("pkg.4", recent[0].packageName)
        assertEquals("pkg.2", recent[2].packageName)
    }

    @Test
    fun `recent honors limit and zero`() {
        val store = InMemoryNotificationStore()
        repeat(4) { i -> store.record(CapturedNotification("p$i", null, null, i.toLong())) }
        assertEquals(2, store.recent(2).size)
        assertTrue(store.recent(0).isEmpty())
    }

    @Test
    fun `clear empties the buffer`() {
        val store = InMemoryNotificationStore()
        store.record(CapturedNotification("p", "t", "x", 1L))
        store.clear()
        assertTrue(store.recent(10).isEmpty())
    }
}

class NotificationPollToolTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun seededStore(): NotificationStore = InMemoryNotificationStore().apply {
        record(CapturedNotification("com.foo", "Hello", "world", 100L))
        record(CapturedNotification("com.bar", "Ping", "now", 200L))
    }

    @Test
    fun `reports SettingsRequired when access not granted`() {
        val tool = NotificationPollTool(seededStore(), isAccessGranted = { false }, json = json)
        val cap = tool.capabilities().single()
        assertEquals(MobileActionCapabilityStatus.SettingsRequired, cap.status)
        assertTrue(
            cap.requiredSettings.contains(
                LettaNotificationListenerService.NOTIFICATION_ACCESS_SETTINGS_ACTION,
            ),
        )
    }

    @Test
    fun `reports Available when access granted`() {
        val tool = NotificationPollTool(seededStore(), isAccessGranted = { true }, json = json)
        assertEquals(MobileActionCapabilityStatus.Available, tool.capabilities().single().status)
    }

    @Test
    fun `handle bails with settings action when access not granted`() {
        val tool = NotificationPollTool(seededStore(), isAccessGranted = { false }, json = json)
        val res = tool.handle(JsonObject(emptyMap()), "act-1")
        assertFalse(res.success)
        assertEquals(MobileActionCapabilityStatus.SettingsRequired, res.status)
        assertTrue(res.requiresUserAction)
        assertEquals(
            LettaNotificationListenerService.NOTIFICATION_ACCESS_SETTINGS_ACTION,
            res.intentAction,
        )
        assertNull(res.payloadJson)
    }

    @Test
    fun `handle returns notifications newest first when granted`() {
        val tool = NotificationPollTool(seededStore(), isAccessGranted = { true }, json = json)
        val res = tool.handle(JsonObject(emptyMap()), "act-2")
        assertTrue(res.success)
        assertNotNull(res.payloadJson)
        val items = json.decodeFromString<List<CapturedNotification>>(res.payloadJson!!)
        assertEquals(2, items.size)
        assertEquals("com.bar", items[0].packageName) // newest first
    }

    @Test
    fun `handle clamps limit`() {
        val store = InMemoryNotificationStore()
        repeat(10) { i -> store.record(CapturedNotification("p$i", null, null, i.toLong())) }
        val tool = NotificationPollTool(store, isAccessGranted = { true }, json = json)
        val input = JsonObject(mapOf("limit" to JsonPrimitive(3)))
        val res = tool.handle(input, "act-3")
        val items = json.decodeFromString<List<CapturedNotification>>(res.payloadJson!!)
        assertEquals(3, items.size)
    }
}
