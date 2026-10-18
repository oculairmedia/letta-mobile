package com.letta.mobile.ui.theme

import kotlin.test.Test
import kotlin.test.assertFalse
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
