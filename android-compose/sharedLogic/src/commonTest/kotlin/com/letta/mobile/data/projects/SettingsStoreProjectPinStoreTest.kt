package com.letta.mobile.data.projects

import com.letta.mobile.data.chat.runtime.MapSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** letta-mobile-ckmub.2.6: the desktop keeps project pins in its settings store, across restarts. */
class SettingsStoreProjectPinStoreTest {
    @Test
    fun pinsPersistAndUnpinsDrop() = runTest {
        val store = MapSettingsStore()
        val pins = SettingsStoreProjectPinStore(store)
        pins.set(ProjectKey("alpha"), ProjectPin.Pinned)
        pins.set(ProjectKey("beta"), ProjectPin.Pinned)
        pins.set(ProjectKey("alpha"), ProjectPin.Unpinned)
        assertEquals(setOf(ProjectKey("beta")), pins.pinned.first())

        val reopened = SettingsStoreProjectPinStore(store)
        assertEquals(setOf(ProjectKey("beta")), reopened.pinned.first())
    }
}
