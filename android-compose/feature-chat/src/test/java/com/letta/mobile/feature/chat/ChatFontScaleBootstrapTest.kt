package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.chatFontScaleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class ChatFontScaleBootstrapTest {
    @Test
    fun `stored scale is unresolved until first storage emission then publishes 082 first`() = runTest {
        val storage = PausedFirstEmissionFlow(0.82f)
        val scale = chatFontScaleState(storage, backgroundScope)
        val observations = mutableListOf<Float?>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            scale.collect(observations::add)
        }

        assertEquals(listOf<Float?>(null), observations)

        storage.releaseFirstEmission()
        runCurrent()

        assertEquals(listOf(null, 0.82f), observations)
        collection.cancel()
    }

    @Test
    fun `absent stored scale publishes repository default as first resolved value`() = runTest {
        val scale = chatFontScaleState(flowOf(1f), backgroundScope)

        assertNull(scale.value)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scale.collect { } }
        runCurrent()

        assertEquals(1f, scale.value)
        collection.cancel()
    }

    private class PausedFirstEmissionFlow(
        private val value: Float,
    ) : Flow<Float> {
        private val gate = CompletableDeferred<Unit>()

        override suspend fun collect(collector: FlowCollector<Float>) {
            gate.await()
            collector.emit(value)
        }

        fun releaseFirstEmission() {
            gate.complete(Unit)
        }
    }
}
