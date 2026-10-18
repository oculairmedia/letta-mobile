package com.letta.mobile.platform

import android.content.ComponentCallbacks2
import com.letta.mobile.data.platform.MemoryPressureLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidMemoryPressureTest {
    @Test
    fun goingToTheBackgroundIsNotMemoryPressure() {
        assertNull(AndroidMemoryPressure.levelFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertNull(AndroidMemoryPressure.levelFor(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertNull(AndroidMemoryPressure.levelFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }

    @Test
    fun aDeviceRunningLowIsModerate() {
        assertEquals(MemoryPressureLevel.MODERATE, AndroidMemoryPressure.levelFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(MemoryPressureLevel.MODERATE, AndroidMemoryPressure.levelFor(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
    }

    @Test
    fun aDeviceAboutToKillTheProcessIsCritical() {
        assertEquals(MemoryPressureLevel.CRITICAL, AndroidMemoryPressure.levelFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(MemoryPressureLevel.CRITICAL, AndroidMemoryPressure.levelFor(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
