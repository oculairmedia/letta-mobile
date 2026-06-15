package com.letta.mobile.runtime.mobileactions

import android.content.Intent
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MobileIntentActionToolTest {
    @Test
    fun `open wifi settings maps to settings intent with new task flag`() {
        val intent = openWifiSettingsIntent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        assertEquals(Settings.ACTION_WIFI_SETTINGS, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `show location maps to geo query intent`() {
        val intent = showLocationOnMapIntent("Coffee near Toronto, ON")

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo:0,0?q=Coffee%20near%20Toronto%2C%20ON", intent.data.toString())
    }

    @Test
    fun `compose email uses sendto mailto and never send action`() {
        val intent = composeEmailIntent("ada@example.com", "Hello", "Body")

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:", intent.data.toString())
        assertEquals("ada@example.com", intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.single())
        assertEquals("Hello", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("Body", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `insert contact uses insert action and raw contact type`() {
        val intent = insertContactIntent("Ada", "Lovelace", "+15551234567", "ada@example.com")

        assertEquals(ContactsContract.Intents.Insert.ACTION, intent.action)
        assertEquals(ContactsContract.RawContacts.CONTENT_TYPE, intent.type)
        assertEquals("Ada Lovelace", intent.getStringExtra(ContactsContract.Intents.Insert.NAME))
        assertEquals("+15551234567", intent.getStringExtra(ContactsContract.Intents.Insert.PHONE))
        assertEquals("ada@example.com", intent.getStringExtra(ContactsContract.Intents.Insert.EMAIL))
    }

    @Test
    fun `insert calendar event uses insert action and event uri`() {
        val intent = insertCalendarEventIntent("2030-01-02T03:04:05Z", "Planning")

        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("Planning", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(1893553445000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L))
    }
}
