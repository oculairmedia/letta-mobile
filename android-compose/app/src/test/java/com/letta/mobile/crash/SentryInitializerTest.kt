package com.letta.mobile.crash

import android.content.Context
import android.content.res.Resources
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.Sentry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SentryInitializerTest {

    private lateinit var context: Context
    private lateinit var resources: Resources
    private lateinit var initializer: SentryInitializer

    @Before
    fun setup() {
        context = mockk()
        resources = mockk()
        every { context.resources } returns resources
        every { context.packageName } returns "com.letta.mobile"

        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0

        mockkStatic(SentryAndroid::class)
        every { SentryAndroid.init(any(), any<Sentry.OptionsConfiguration<SentryAndroidOptions>>()) } answers {
            // Do nothing
        }

        initializer = SentryInitializer()
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun setupStringResource(name: String, value: String?) {
        val id = name.hashCode()
        every { resources.getIdentifier(name, "string", "com.letta.mobile") } returns id
        if (value != null) {
            every { resources.getString(id) } returns value
        } else {
            every { resources.getString(id) } throws Resources.NotFoundException()
        }
    }

    @Test
    fun `missing DSN skips initialization`() {
        setupStringResource("sentry_dsn", null)
        setupStringResource("sentry_env", null)
        setupStringResource("sentry_traces_sample_rate", null)

        initializer.create(context)

        verify(exactly = 0) { SentryAndroid.init(any(), any<Sentry.OptionsConfiguration<SentryAndroidOptions>>()) }
        verify { Log.i("SentryInitializer", "SENTRY_DSN is blank, skipping Sentry init") }
    }

    @Test
    fun `blank DSN skips initialization`() {
        setupStringResource("sentry_dsn", "   ")
        setupStringResource("sentry_env", "production")
        setupStringResource("sentry_traces_sample_rate", "0.5")

        initializer.create(context)

        verify(exactly = 0) { SentryAndroid.init(any(), any<Sentry.OptionsConfiguration<SentryAndroidOptions>>()) }
        verify { Log.i("SentryInitializer", "SENTRY_DSN is blank, skipping Sentry init") }
    }

    @Test
    fun `valid DSN initializes Sentry with correct options`() {
        setupStringResource("sentry_dsn", "https://example.com/sentry")
        setupStringResource("sentry_env", "production")
        setupStringResource("sentry_traces_sample_rate", "0.5")

        val optionsSlot = slot<Sentry.OptionsConfiguration<SentryAndroidOptions>>()
        every { SentryAndroid.init(eq(context), capture(optionsSlot)) } answers {
            // Just capture
        }

        initializer.create(context)

        verify(exactly = 1) { SentryAndroid.init(eq(context), any<Sentry.OptionsConfiguration<SentryAndroidOptions>>()) }

        val capturedOptions = SentryAndroidOptions()
        optionsSlot.captured.configure(capturedOptions)

        assertEquals("https://example.com/sentry", capturedOptions.dsn)
        assertEquals("production", capturedOptions.environment)
        assertEquals(0.5, capturedOptions.tracesSampleRate)
        assertTrue(capturedOptions.isEnableAutoSessionTracking)

        verify { Log.i("SentryInitializer", "Sentry initialized env=production tracesSampleRate=0.5") }
    }

    @Test
    fun `missing environment defaults to development`() {
        setupStringResource("sentry_dsn", "https://example.com/sentry")
        setupStringResource("sentry_env", null) // Missing env
        setupStringResource("sentry_traces_sample_rate", "0.5")

        val optionsSlot = slot<Sentry.OptionsConfiguration<SentryAndroidOptions>>()
        every { SentryAndroid.init(eq(context), capture(optionsSlot)) } answers {}

        initializer.create(context)

        val capturedOptions = SentryAndroidOptions()
        optionsSlot.captured.configure(capturedOptions)

        assertEquals("development", capturedOptions.environment)
    }

    @Test
    fun `missing traces_sample_rate defaults to 0_2`() {
        setupStringResource("sentry_dsn", "https://example.com/sentry")
        setupStringResource("sentry_env", "staging")
        setupStringResource("sentry_traces_sample_rate", null) // Missing sample rate

        val optionsSlot = slot<Sentry.OptionsConfiguration<SentryAndroidOptions>>()
        every { SentryAndroid.init(eq(context), capture(optionsSlot)) } answers {}

        initializer.create(context)

        val capturedOptions = SentryAndroidOptions()
        optionsSlot.captured.configure(capturedOptions)

        assertEquals(0.2, capturedOptions.tracesSampleRate)
    }

    @Test
    fun `invalid traces_sample_rate defaults to 0_2`() {
        setupStringResource("sentry_dsn", "https://example.com/sentry")
        setupStringResource("sentry_env", "staging")
        setupStringResource("sentry_traces_sample_rate", "not_a_number") // Invalid sample rate

        val optionsSlot = slot<Sentry.OptionsConfiguration<SentryAndroidOptions>>()
        every { SentryAndroid.init(eq(context), capture(optionsSlot)) } answers {}

        initializer.create(context)

        val capturedOptions = SentryAndroidOptions()
        optionsSlot.captured.configure(capturedOptions)

        assertEquals(0.2, capturedOptions.tracesSampleRate)
    }
}
