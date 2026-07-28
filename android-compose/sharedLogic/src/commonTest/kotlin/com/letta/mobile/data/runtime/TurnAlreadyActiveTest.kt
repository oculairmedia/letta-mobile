package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnAlreadyActiveTest {
    @Test
    fun detectsBusyRejectionMessages() {
        assertTrue(isTurnAlreadyActiveMessage("An App Server turn is already active for runtime-1."))
        assertTrue(isTurnAlreadyActiveMessage("Iroh App Server turn engine is already busy."))
        assertFalse(isTurnAlreadyActiveMessage("connection interrupted before the turn completed"))
        assertFalse(isTurnAlreadyActiveMessage(null))
        assertFalse(isTurnAlreadyActiveMessage(""))
    }
}
