package com.letta.mobile.ui.screens.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOpLog
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.ui.canvas.CanvasWorkspace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel managing the lifecycle and collaborative [CanvasSession] for [CanvasScreen].
 */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    private val store: CanvasDocumentStore,
    val syncTransport: CanvasSyncTransport,
    val presenceTransport: CanvasPresenceTransport,
    private val opLog: CanvasOpLog,
) : ViewModel() {
    private val _session = MutableStateFlow<CanvasSession?>(null)
    val session: StateFlow<CanvasSession?> = _session.asStateFlow()

    fun initSession(canvasId: String, conversationId: String?, agentId: String? = null) {
        if (_session.value != null) return
        viewModelScope.launch {
            val canvasSession = if (!conversationId.isNullOrBlank() && canvasId.isBlank()) {
                CanvasSession.getOrCreateForConversation(
                    store = store,
                    conversationId = conversationId,
                    options = com.letta.mobile.data.canvas.CanvasConversationOptions(
                        agentId = agentId,
                        title = "Conversation Canvas",
                        opLog = opLog,
                        syncTransport = syncTransport,
                    ),
                )
            } else {
                val effectiveId = if (canvasId.isNotBlank()) CanvasId(canvasId) else CanvasId.generate()
                val s = CanvasSession(canvasId = effectiveId, store = store, opLog = opLog, syncTransport = syncTransport)
                s.load()
                s
            }
            _session.value = canvasSession
        }
    }
}

/**
 * Production Android screen hosting [CanvasWorkspace] bound to a persistent [CanvasSession].
 */
@Composable
fun CanvasScreen(
    canvasId: String,
    conversationId: String? = null,
    agentId: String? = null,
    onNavigateBack: () -> Unit,
    onShareToChat: ((ByteArray, String) -> Unit)? = null,
    viewModel: CanvasViewModel = hiltViewModel(),
) {
    LaunchedEffect(canvasId, conversationId, agentId) {
        viewModel.initSession(canvasId, conversationId, agentId)
    }

    val session by viewModel.session.collectAsStateWithLifecycle()
    val activeSession = session
    if (activeSession != null) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val assets = androidx.compose.runtime.remember(context) {
            com.letta.mobile.data.storage.FileAssetStore(java.io.File(context.filesDir, "canvas-assets"))
        }
        CanvasWorkspace(
            session = activeSession,
            presenceTransport = viewModel.presenceTransport,
            assets = assets,
            onNavigateBack = onNavigateBack,
            onShareToChat = onShareToChat,
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
