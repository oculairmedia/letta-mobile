package com.letta.mobile.ui.chat.surface.timeline.rows

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineProbeResidentsTest {
    @Test
    fun heartbeatMovesToNextResidentOnDisposal() {
        val residents = TimelineProbeResidents()
        residents.mount("first")
        residents.mount("second")
        assertTrue(residents.isHeartbeatOwner("first"))
        assertFalse(residents.isHeartbeatOwner("second"))
        residents.dispose("first")
        assertTrue(residents.isHeartbeatOwner("second"))
        assertFalse(residents.isHeartbeatOwner("first"))
        residents.dispose("second")
        assertFalse(residents.isHeartbeatOwner("second"))
    }
    @Test
    fun userResidentCannotOwnAssistantHeartbeat() {
        val residents = TimelineProbeResidents()
        residents.mount("prompt", assistant = false)
        residents.mount("reply", assistant = true)
        assertFalse(residents.isHeartbeatOwner("prompt"))
        assertTrue(residents.isHeartbeatOwner("reply"))
    }

    @Test
    fun overlappingCopiesRemainUntilLastDisposal() {
        val residents = TimelineProbeResidents()
        assertTrue(residents.mount("reply"))
        assertFalse(residents.mount("reply"))
        assertFalse(residents.dispose("reply"))
        assertTrue(residents.isHeartbeatOwner("reply"))
        assertTrue(residents.dispose("reply"))
        assertFalse(residents.isHeartbeatOwner("reply"))
        assertFalse(residents.dispose("reply"))
    }
}
