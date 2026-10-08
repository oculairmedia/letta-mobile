package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.RecentModelsPersistence
import com.letta.mobile.data.model.RecentModelsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull

/** letta-mobile-bzvro.18 (F18): the picker's "Recent" group and where it comes from. */
class ModelPickerRecentsTest {
    private val models = MutableStateFlow(
        listOf(
            LlmModel(id = "anthropic/claude", name = "Claude", handle = "anthropic/claude", providerType = "anthropic"),
            LlmModel(id = "openai/gpt", name = "GPT", handle = "openai/gpt", providerType = "openai"),
        ),
    )

    @Test
    fun recentModelsHeadThePickerNewestFirst() = runTest(UnconfinedTestDispatcher()) {
        val recents = MutableStateFlow(listOf("openai/gpt", "anthropic/claude"))
        val picker = ModelPickerController(backgroundScope, ModelPickerSource.of(models) { }.withRecents(recents))

        val first = picker.state.value.groups.first()

        assertEquals(ModelPickerCatalog.RECENTS_GROUP_KEY, first.key)
        assertEquals(listOf("openai/gpt", "anthropic/claude"), first.entries.map { it.handle.value })
        assertEquals(2, picker.state.value.modelCount, "the Recent group repeats rows and is not counted")
    }

    @Test
    fun aRecentModelTheCatalogNoLongerOffersIsSkippedAndNoRecentsMeansNoGroup() = runTest(UnconfinedTestDispatcher()) {
        val recents = MutableStateFlow(listOf("gone/model"))
        val picker = ModelPickerController(backgroundScope, ModelPickerSource.of(models) { }.withRecents(recents))
        assertEquals(listOf("anthropic", "openai"), picker.state.value.groups.map { it.key }.sorted())

        recents.value = listOf("anthropic/claude")
        assertEquals(ModelPickerCatalog.RECENTS_GROUP_KEY, picker.state.value.groups.first().key)
    }

    @Test
    fun aSuccessfulSwitchMakesTheModelRecentAndAFailedOneDoesNot() = runTest {
        val saved = mutableListOf<List<String>>()
        val recents = RecentModelsStore(object : RecentModelsPersistence {
            override fun load(): List<String> = saved.lastOrNull().orEmpty()
            override fun save(models: List<String>) {
                saved += models
            }
        })
        val target = ConversationModelTarget("agent-1", "conv-1")

        ConversationModelRepository(RecordingInvoker { _, _ -> JsonNull }, recents = recents)
            .updateModel(target, ModelHandle("openai/gpt"))
        runCatching {
            ConversationModelRepository(RecordingInvoker { _, _ -> error("rejected") }, recents = recents)
                .updateModel(target, ModelHandle("anthropic/claude"))
        }

        assertEquals(listOf("openai/gpt"), recents.recent.value)
    }
}
