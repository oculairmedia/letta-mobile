package com.letta.mobile.data.context

import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.ServerFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-wdm6i: the chip's last readings survive a restart, bounded and fail-soft. */
class ContextReadingSnapshotsTest {
    private class MemoryStore : SecureSettingsStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
        override fun putString(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun clear() = values.clear()
    }

    private fun usage(conversation: String, tokens: Long) = ServerFrame.UsageStatistics(
        agentId = AGENT,
        conversationId = conversation,
        contextTokens = tokens,
    )

    @Test
    fun aReadingSavedInOneRunSeedsTheNext() {
        val store = MemoryStore()
        val firstRun = ContextTokenReadings(onChange = ContextReadingSnapshots(store)::save)
        firstRun.record(usage("conv-a", 28_864))
        firstRun.record(usage("conv-a", 29_193))

        val nextRun = ContextTokenReadings(initial = ContextReadingSnapshots(store).load())

        assertEquals(29_193, nextRun.latest(AGENT, "conv-a"))
    }

    @Test
    fun aDefaultConversationSavedBareRestoresUnderTheAppsForm() {
        val store = MemoryStore()
        ContextTokenReadings(onChange = ContextReadingSnapshots(store)::save).record(usage("default", 28_864))

        val restored = ContextReadingSnapshots(store).load()

        assertEquals(28_864, restored.readingFor(AGENT, "conv-default-$AGENT"))
    }

    @Test
    fun theLeastRecentlyUpdatedConversationIsEvictedFirst() {
        val store = MemoryStore()
        val snapshots = ContextReadingSnapshots(store, maxEntries = 2)
        val readings = ContextTokenReadings(onChange = snapshots::save)

        readings.record(usage("conv-a", 1_000))
        readings.record(usage("conv-b", 2_000))
        readings.record(usage("conv-a", 1_500))
        readings.record(usage("conv-c", 3_000))

        val restored = snapshots.load()
        assertEquals(setOf("conv-a", "conv-c"), restored.keys.map { it.conversationId }.toSet())
        assertEquals(1_500, restored.readingFor(AGENT, "conv-a"))
    }

    @Test
    fun corruptOrForeignDataReadsAsNoReading() {
        listOf(
            "not json",
            "[1,2,3]",
            "{\"v\":2,\"e\":[{\"a\":\"$AGENT\",\"c\":\"conv-a\",\"t\":1}]}",
            "{\"v\":1,\"e\":\"nope\"}",
            "",
        ).forEach { raw ->
            val store = MemoryStore().apply { values[ContextReadingSnapshots.KEY] = raw }

            assertTrue(ContextReadingSnapshots(store).load().isEmpty(), raw)
        }
    }

    @Test
    fun invalidEntriesAreDroppedAndValidOnesKept() {
        val raw = "{\"v\":1,\"e\":[" +
            "{\"a\":\"\",\"c\":\"conv-a\",\"t\":1}," +
            "{\"a\":\"$AGENT\",\"c\":\"conv-b\",\"t\":-5}," +
            "{\"a\":\"$AGENT\"}," +
            "{\"a\":\"$AGENT\",\"c\":\"conv-c\",\"t\":29193,\"extra\":true}" +
            "]}"
        val store = MemoryStore().apply { values[ContextReadingSnapshots.KEY] = raw }

        assertEquals(mapOf(ContextReadingKey(AGENT, "conv-c") to 29_193), ContextReadingSnapshots(store).load())
    }

    @Test
    fun nothingSavedMeansNoReading() {
        assertTrue(ContextReadingSnapshots(MemoryStore()).load().isEmpty())
    }

    private companion object {
        const val AGENT = "agent-1"
    }
}
