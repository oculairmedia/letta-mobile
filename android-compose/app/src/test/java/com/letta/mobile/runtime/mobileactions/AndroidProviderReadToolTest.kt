package com.letta.mobile.runtime.mobileactions

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProviderReadToolTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun contextWithPermission(permission: String, granted: Boolean): Context {
        return object : Context() {
            override fun checkSelfPermission(perm: String): Int {
                return if (perm == permission && granted) {
                    PackageManager.PERMISSION_GRANTED
                } else {
                    PackageManager.PERMISSION_DENIED
                }
            }

            override fun getContentResolver(): ContentResolver? = null
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.letta.mobile.test"
        }
    }

    @Test
    fun `contacts read returns permission_required when permission not granted`() {
        // Given: context that denies READ_CONTACTS
        val context = contextWithPermission(Manifest.permission.READ_CONTACTS, granted = false)
        val tool = AndroidProviderReadTool(context)

        // When: contacts.read is called
        val result = tool.handle("contacts.read", JsonObject(emptyMap()))

        // Then: status is permission_required with the permission name
        assertEquals("permission_required", result.status)
        assertEquals(Manifest.permission.READ_CONTACTS, result.permission)
        assertNull(result.contacts)
    }

    @Test
    fun `calendar read returns permission_required when permission not granted`() {
        // Given: context that denies READ_CALENDAR
        val context = contextWithPermission(Manifest.permission.READ_CALENDAR, granted = false)
        val tool = AndroidProviderReadTool(context)

        // When: calendar.read is called
        val result = tool.handle("calendar.read", JsonObject(emptyMap()))

        // Then: status is permission_required with the permission name
        assertEquals("permission_required", result.status)
        assertEquals(Manifest.permission.READ_CALENDAR, result.permission)
        assertNull(result.events)
    }

    @Test
    fun `handleJson returns valid JSON string for denied permission`() {
        // Given: permission not granted
        val context = contextWithPermission(Manifest.permission.READ_CONTACTS, granted = false)
        val tool = AndroidProviderReadTool(context)

        // When: handleJson is called
        val resultJson = tool.handleJson("contacts.read", JsonObject(emptyMap()))

        // Then: result is valid JSON
        val parsed = runCatching { json.parseToJsonElement(resultJson) }.getOrNull()
        assertTrue("Expected valid JSON but parsing failed", parsed != null)
    }

    @Test
    fun `unknown command returns error status`() {
        // Given: any context
        val context = contextWithPermission(Manifest.permission.READ_CONTACTS, granted = true)
        val tool = AndroidProviderReadTool(context)

        // When: unknown command is called
        val result = tool.handle("unknown.command", JsonObject(emptyMap()))

        // Then: status is error
        assertEquals("error", result.status)
        assertTrue("Expected unknown command error message",
            result.error?.contains("Unknown provider read command") == true)
    }

    @Test
    fun `truthful status vocabulary uses expected values`() {
        val context = contextWithPermission(Manifest.permission.READ_CONTACTS, granted = false)
        val tool = AndroidProviderReadTool(context)

        // Test permission_required
        val deniedResult = tool.handle("contacts.read", JsonObject(emptyMap()))
        assertEquals("permission_required", deniedResult.status)

        // Test error
        val errorResult = tool.handle("unknown", JsonObject(emptyMap()))
        assertEquals("error", errorResult.status)
    }

    @Test
    fun `result structure has required fields for permission denied`() {
        val context = contextWithPermission(Manifest.permission.READ_CONTACTS, granted = false)
        val tool = AndroidProviderReadTool(context)

        // When: contacts.read called without permission
        val result = tool.handle("contacts.read", JsonObject(emptyMap()))

        // Then: result has all expected fields
        assertEquals("contacts.read", result.command)
        assertEquals("permission_required", result.status)
        assertEquals(Manifest.permission.READ_CONTACTS, result.permission)
        assertNull(result.count)
        assertNull(result.contacts)
        assertNull(result.events)
        assertNull(result.error)
    }

    @Test
    fun `contacts and calendar use different permissions`() {
        val contactsContext = contextWithPermission(Manifest.permission.READ_CONTACTS, granted = false)
        val calendarContext = contextWithPermission(Manifest.permission.READ_CALENDAR, granted = false)

        val contactsTool = AndroidProviderReadTool(contactsContext)
        val calendarTool = AndroidProviderReadTool(calendarContext)

        val contactsResult = contactsTool.handle("contacts.read", JsonObject(emptyMap()))
        val calendarResult = calendarTool.handle("calendar.read", JsonObject(emptyMap()))

        assertEquals(Manifest.permission.READ_CONTACTS, contactsResult.permission)
        assertEquals(Manifest.permission.READ_CALENDAR, calendarResult.permission)
        assertTrue("Expected different permissions",
            contactsResult.permission != calendarResult.permission)
    }

    @Test
    fun `contacts read accepts query and limit parameters`() {
        val context = contextWithPermission(Manifest.permission.READ_CONTACTS, granted = false)
        val tool = AndroidProviderReadTool(context)

        // When: contacts.read is called with query and limit
        val input = buildJsonObject {
            put("query", "John")
            put("limit", 10)
        }
        val result = tool.handle("contacts.read", input)

        // Then: command processes (will return permission_required before touching params)
        assertEquals("contacts.read", result.command)
        assertEquals("permission_required", result.status)
    }

    @Test
    fun `calendar read accepts time window and limit parameters`() {
        val context = contextWithPermission(Manifest.permission.READ_CALENDAR, granted = false)
        val tool = AndroidProviderReadTool(context)

        // When: calendar.read is called with time window
        val input = buildJsonObject {
            put("startTimeMillis", System.currentTimeMillis())
            put("endTimeMillis", System.currentTimeMillis() + 86400000L) // +1 day
            put("limit", 20)
        }
        val result = tool.handle("calendar.read", input)

        // Then: command processes
        assertEquals("calendar.read", result.command)
        assertEquals("permission_required", result.status)
    }

    @Test
    fun `serialized result is compact`() {
        val context = contextWithPermission(Manifest.permission.READ_CONTACTS, granted = false)
        val tool = AndroidProviderReadTool(context)

        val resultJson = tool.handleJson("contacts.read", JsonObject(emptyMap()))

        // Result should be compact - no unnecessary fields
        val parsed = json.parseToJsonElement(resultJson)
        assertTrue("Expected result to be compact", resultJson.length < 500)
    }
}
