package com.letta.mobile.ui.mascot

import androidx.compose.ui.graphics.ImageBitmap
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A mascot's still is captured once per identity, then comes from memory or disk. */
class MascotStillsTest {

    private class MemoryStore : MascotStillStore {
        val files = mutableMapOf<String, ByteArray>()
        override suspend fun read(key: String) = files[key]
        override suspend fun write(key: String, png: ByteArray) { files[key] = png }
    }

    private val bitmap = ImageBitmap(2, 2)
    private val violet = MascotIdentity(MascotShape.entries.first(), argb = 0xFF7C3AED.toInt())
    private val amber = MascotIdentity(MascotShape.entries.first(), argb = 0xFFF59E0B.toInt())

    private fun stills(store: MascotStillStore?, version: String = "a1", captured: MutableList<MascotIdentity>, result: ByteArray? = byteArrayOf(1)) =
        MascotStills(
            assetVersion = version,
            store = store,
            capture = { captured += it; result },
            decode = { bitmap },
        )

    @Test
    fun eachIdentityIsCapturedOnceAndSaved() = runTest {
        val store = MemoryStore()
        val captured = mutableListOf<MascotIdentity>()
        val stills = stills(store, captured = captured)
        assertNull(stills.get(violet))

        // A screen of tiles asking at once still captures once.
        (1..5).map { async { stills.ensure(violet) } }.awaitAll()
        stills.ensure(amber)
        assertEquals(listOf(violet, amber), captured)
        assertNotNull(stills.get(violet))
        assertEquals(setOf(stills.keyOf(violet), stills.keyOf(amber)), store.files.keys)
    }

    @Test
    fun aLaterLaunchReadsTheSavedStillInsteadOfCapturing() = runTest {
        val store = MemoryStore()
        stills(store, captured = mutableListOf()).ensure(violet)

        val captured = mutableListOf<MascotIdentity>()
        val relaunched = stills(store, captured = captured)
        relaunched.ensure(violet)
        assertNotNull(relaunched.get(violet))
        assertTrue(captured.isEmpty(), "a saved still is not captured again")
    }

    @Test
    fun aNewMascotAssetMeansNewStills() = runTest {
        val store = MemoryStore()
        stills(store, version = "a1", captured = mutableListOf()).ensure(violet)
        val captured = mutableListOf<MascotIdentity>()
        stills(store, version = "b2", captured = captured).ensure(violet)
        assertEquals(listOf(violet), captured, "a still of the old mascot is not reused")
    }

    @Test
    fun aFailedCaptureIsNotRetriedThisSession() = runTest {
        val captured = mutableListOf<MascotIdentity>()
        val stills = stills(null, captured = captured, result = null)
        stills.ensure(violet)
        stills.ensure(violet)
        assertNull(stills.get(violet))
        assertEquals(1, captured.size)
    }

    @Test
    fun keysAreSafeFileNames() {
        val key = stills(null, captured = mutableListOf()).keyOf(violet.copy(rotationDegrees = 90))
        assertTrue(key.all { it.isLetterOrDigit() || it == '-' || it == '_' }, key)
    }
}
