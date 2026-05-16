package com.letta.mobile.feature.chat

import com.letta.mobile.data.repository.MessageRepository
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates chat search UI state, local results, and debounced remote lookup.
 * This keeps search job lifecycle out of [AdminChatViewModel] while preserving
 * the same [ChatUiState] projection contract for the screen.
 */
internal class ChatSearchCoordinator(
    private val scope: CoroutineScope,
    messageRepository: MessageRepository,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val agentId: String,
    private val conversationId: () -> String?,
    private val remoteDebounceMs: Long = DEFAULT_REMOTE_DEBOUNCE_MS,
) {
    private val controller = ChatSearchController(messageRepository)
    private var searchJob: Job? = null

    fun updateQuery(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            clear()
            return
        }

        val localResults = controller.localResults(
            query = query,
            state = uiState.value,
            agentId = agentId,
            conversationId = conversationId(),
        )
        uiState.update {
            it.copy(
                searchQuery = query,
                isSearchActive = true,
                isSearching = true,
                searchResults = localResults,
            )
        }

        searchJob = scope.launch {
            delay(remoteDebounceMs)
            try {
                val parsed = controller.remoteResults(query, agentId)
                uiState.update { current ->
                    if (current.searchQuery == query) {
                        current.copy(
                            searchResults = controller.mergeResults(
                                local = controller.localResults(
                                    query = query,
                                    state = current,
                                    agentId = agentId,
                                    conversationId = conversationId(),
                                ),
                                remote = parsed,
                            ),
                            isSearching = false,
                        )
                    } else {
                        current
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Chat search failed", e)
                uiState.update { current ->
                    if (current.searchQuery == query) current.copy(isSearching = false) else current
                }
            }
        }
    }

    fun clear() {
        searchJob?.cancel()
        searchJob = null
        uiState.update {
            it.copy(
                searchQuery = "",
                isSearchActive = false,
                isSearching = false,
                searchResults = persistentListOf(),
            )
        }
    }

    private companion object {
        const val DEFAULT_REMOTE_DEBOUNCE_MS = 180L
    }
}
