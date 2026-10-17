package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.HistoryBudget
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.storage.InMemoryKeyValueStore
import com.letta.mobile.data.storage.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A relay stream to a [CanvasRelayHost] in memory, as a binding carries it: protocol text frames
 * both ways, so the shared encode/decode runs as it does over Iroh.
 */
private class InMemoryRelayLink private constructor(override val hostId: HostId) : CanvasRelayLink {
    private lateinit var session: CanvasRelayHost.Session
    private val inbox = Channel<String?>(Channel.UNLIMITED)

    override suspend fun send(frame: String) {
        val decoded = CanvasRelayProtocol.decode(frame)
        session.receive((decoded as CanvasRelayDecoded.Message).message)
    }

    override suspend fun receive(): String? = inbox.receive()

    override suspend fun close() {
        inbox.trySend(null)
        session.close()
    }

    companion object {
        fun dialing(host: CanvasRelayHost, origin: String) = CanvasRelayDial {
            InMemoryRelayLink(HostId(HOST)).also { link ->
                link.session = host.connect(origin) { link.inbox.trySend(CanvasRelayProtocol.encode(it)) }
            }
        }
    }
}

private const val HOST = "host-1"
private const val CONVERSATION = "conv-web"

/**
 * The web's canvas, as the browser binds it: the shared relay client over [KeyValueCanvasStorage]
 * (IndexedDB in the browser, in memory here), carried by the shared [CanvasRelayAttachment].
 */
private class WebPeer(val store: KeyValueStore, val scope: CoroutineScope, budget: HistoryBudget = KeyValueCanvasStorage.DEFAULT_BUDGET) {
    val storage = KeyValueCanvasStorage(store, budget)
    val relay = storage.relayClient()
    val dials = MutableStateFlow<CanvasRelayDial?>(null)
    lateinit var session: CanvasSession

    suspend fun open(): WebPeer = apply {
        session = CanvasSession.getOrCreateForConversation(
            storage.documents,
            ConversationId(CONVERSATION),
            CanvasConversationOptions(opLog = storage.opLog, syncTransport = relay),
        )
        session.startSync(scope)
    }

    fun follow(host: CanvasRelayHost) {
        dials.value = InMemoryRelayLink.dialing(host, "web")
        CanvasRelayAttachment(scope, relay).attach(dials)
    }

    suspend fun scene(): String = storage.documents.get(session.canvasId)!!.sceneJson
}

private fun note(id: String, lamport: Long) =
    CanvasOp.AddElementOp(id, CanvasSession.LOCAL_USER_ACTOR_ID, lamport, "el-$id", """{"id":"el-$id","type":"Text","text":"$id"}""")

/** letta-mobile-ckmub.17.9: the web and desktop share one board through the host, both ways. */
class CanvasRelayAttachmentSyncTest {
    private val topic = CanvasRelayProtocol.conversationTopic(ConversationId(CONVERSATION))

    @Test
    fun editsCrossBetweenTheWebAndDesktopBothWays() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { HOST })
        val desktop = TestApp("desktop", backgroundScope).open(CONVERSATION)
        desktop.connect(host)
        val web = WebPeer(InMemoryKeyValueStore(), backgroundScope).open()
        web.follow(host)
        runCurrent()

        desktop.edit(note("from-desktop", 3))
        runCurrent()
        assertTrue("el-from-desktop" in web.scene(), "a desktop edit lands on the web board")

        web.session.applyLocal(note("from-web", 4))
        runCurrent()
        assertTrue("el-from-web" in desktop.scene(), "a web edit lands on the desktop board")
        assertEquals(CanvasSyncHealth.Synced, web.relay.health(web.session.canvasId).value)
    }

    @Test
    fun theWebsCursorShowsOnDesktop() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { HOST })
        val desktop = TestApp("desktop", backgroundScope).open(CONVERSATION)
        desktop.connect(host)
        val web = WebPeer(InMemoryKeyValueStore(), backgroundScope).open()
        web.follow(host)
        runCurrent()

        web.relay.updatePresence(web.session.canvasId, CanvasPresence("web-peer", "Web", "#336699", 10f, 20f))
        runCurrent()

        val seen = desktop.client.observePresence(desktop.canvasId).first { cursors -> cursors.any { it.displayName == "Web" } }
        assertEquals(10f, seen.first { it.displayName == "Web" }.cursorX)
    }

    @Test
    fun aReloadKeepsTheBoardAndItsPlaceOnTheHost() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { HOST })
        val desktop = TestApp("desktop", backgroundScope).open(CONVERSATION)
        desktop.connect(host)
        val browser = InMemoryKeyValueStore()
        val web = WebPeer(browser, backgroundScope).open()
        web.follow(host)
        runCurrent()
        desktop.edit(note("before-reload", 3))
        runCurrent()
        web.dials.value = null
        runCurrent()

        val reloaded = WebPeer(browser, backgroundScope).open()

        assertTrue("el-before-reload" in reloaded.scene(), "the board is still in the browser")
        assertNotNull(reloaded.storage.delivery.cursor(HostId(HOST), topic), "and so is how far it has caught up")
        desktop.edit(note("after-reload", 5))
        reloaded.follow(host)
        runCurrent()
        assertTrue("el-after-reload" in reloaded.scene(), "it catches up from there")
    }

    @Test
    fun aNeverSharedBoardPastItsHistoryBudgetJoinsFromItsSnapshot() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { HOST })
        val web = WebPeer(InMemoryKeyValueStore(), backgroundScope, HistoryBudget(maxOps = 4, warnFraction = 0.5)).open()
        (1..6).forEach { web.session.applyLocal(note("old-$it", it.toLong())) }
        assertTrue(web.storage.opLog.hasDroppedHistory(web.session.canvasId), "the log let go of the oldest edits")
        assertFalse(web.storage.opLog.getOps(web.session.canvasId, 0L).any { it.opId == "old-1" })

        web.follow(host)
        runCurrent()
        val desktop = TestApp("desktop", backgroundScope).open(CONVERSATION)
        desktop.connect(host)
        runCurrent()

        (1..6).forEach { assertTrue("el-old-$it" in desktop.scene(), "old-$it reaches the host from the board itself") }
    }

    @Test
    fun aLogThatKeepsEverythingSeedsNothing() = runTest {
        val storage = KeyValueCanvasStorage(InMemoryKeyValueStore())
        val canvas = CanvasId("c-1")
        storage.opLog.append(canvas, note("kept", 1))

        assertTrue(StoreCanvasSnapshotSeed(storage.documents, storage.opLog).seed(canvas).isEmpty())
    }
}
