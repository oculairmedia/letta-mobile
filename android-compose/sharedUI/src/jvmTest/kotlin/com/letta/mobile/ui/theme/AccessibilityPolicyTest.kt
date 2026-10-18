package com.letta.mobile.ui.theme

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessibilityPolicyTest {
    @Test
    fun jvmPropertyForcesReducedMotion() {
        withProperty(REDUCED_MOTION, "true") {
            assertTrue(jvmOsReducedMotion())
        }
    }

    @Test
    fun jvmPropertyForcesHighContrast() {
        withProperty(HIGH_CONTRAST, "true") {
            assertTrue(jvmOsHighContrast())
        }
    }

    @Test
    fun defaultPolicyIsOff() {
        val policy = AccessibilityPolicy()
        assertFalse(policy.reducedMotion)
        assertFalse(policy.highContrast)
    }

    @Test
    fun highContrastPreferencePersistsAndReloadsFromStore() {
        val store = InMemorySecureSettingsStore()
        val first = HighContrastPreference(store)
        assertNull(first.override.value)

        first.set(true)
        assertEquals(true, first.override.value)
        assertEquals(true, HighContrastPreference(store).override.value)

        first.set(false)
        assertEquals(false, HighContrastPreference(store).override.value)

        first.clear()
        assertNull(first.override.value)
        assertNull(HighContrastPreference(store).override.value)
    }

    @Test
    fun macOsDetectionMatchesDarwinAndMacNames() {
        assertTrue(isMacOs("Mac OS X"))
        assertTrue(isMacOs("Darwin"))
        assertFalse(isMacOs("Windows 11"))
        assertFalse(isMacOs("Linux"))
    }

    private fun withProperty(key: String, value: String, block: () -> Unit) {
        val previous = System.getProperty(key)
        System.setProperty(key, value)
        try {
            block()
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}

private const val REDUCED_MOTION = "letta.reducedMotion"
private const val HIGH_CONTRAST = "letta.highContrast"
