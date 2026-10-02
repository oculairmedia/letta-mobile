package com.letta.mobile.ui.canvas.plugin

import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.storage.InMemoryAssetStore
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One card of the `canvas/plugin/v1/fallback-card.json` fixture: an element and what the board knows of it. */
internal data class FixtureCard(val element: CanvasPluginElement, val availability: PluginAvailability, val snapshot: String)

/** A board holding the fixture's cards, with the asset store their snapshots are in. */
internal class FixtureBoard(val session: CanvasSession, val assets: InMemoryAssetStore, val cards: List<FixtureCard>) {
    val availability: PluginAvailabilitySource = PluginAvailabilitySource { element ->
        cards.firstOrNull { it.element.id == element.id }?.availability ?: PluginAvailability.Unknown
    }
}

internal object PluginCardFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    /** The fixture's cards as written. */
    fun cards(): List<FixtureCard> {
        val text = checkNotNull(javaClass.classLoader.getResource("canvas/plugin/v1/fallback-card.json")).readText()
        return Json.parseToJsonElement(text).jsonObject.getValue("cards").jsonArray.map { entry ->
            val card = entry.jsonObject
            FixtureCard(
                element = json.decodeFromJsonElement(CanvasPluginElement.serializer(), card.getValue("element")),
                availability = PluginAvailability(
                    install = PluginInstallState.valueOf(card.getValue("install").jsonPrimitive.content.uppercase()),
                    offline = card.getValue("offline").jsonPrimitive.boolean,
                ),
                snapshot = card.getValue("snapshot").jsonPrimitive.content,
            )
        }
    }

    /** A session holding the fixture's elements; an `image` snapshot is a picture put in the board's asset store. */
    fun board(cards: List<FixtureCard> = cards()): FixtureBoard {
        val assets = InMemoryAssetStore()
        val picture = assets.put("image/png", picturePng())
        val placed = cards.map { card ->
            val snapshot = card.element.snapshot
            if (card.snapshot == "image" && snapshot != null) card.copy(element = card.element.copy(snapshot = snapshot.copy(assetRef = picture.ref))) else card
        }
        val session = session()
        placed.forEach { place(session, it.element) }
        return FixtureBoard(session, assets, placed)
    }

    fun session(): CanvasSession = runBlocking {
        CanvasSession.create(store = InMemoryCanvasDocumentStore(), options = CanvasCreateOptions(title = "Plugins", initialSceneJson = ""))
    }

    /** Writes [element] to [session] as its first write (by the local person: a new board's ACL admits no agent). */
    fun place(session: CanvasSession, element: CanvasPluginElement) = runBlocking {
        session.applyLocalStamped(
            listOf(
                CanvasOp.SetPluginElementOp(
                    opId = "",
                    actorId = CanvasSession.LOCAL_USER_ACTOR_ID,
                    lamport = 0L,
                    elementId = element.id,
                    elementType = element.type,
                    v = element.v,
                    frame = element.frame,
                    owner = element.owner,
                    ref = element.ref,
                    props = element.props,
                    snapshot = element.snapshot,
                    fallback = element.fallback,
                    meta = element.meta,
                ),
            ),
        )
    }

    fun element(id: String, type: String = "ext:letta.example/widget", props: JsonObject = JsonObject(emptyMap())): CanvasPluginElement =
        CanvasPluginElement(
            id = id,
            type = type,
            frame = com.letta.mobile.data.canvas.CanvasDocumentFrame(40f, 40f, 300f, 240f),
            props = props,
            fallback = com.letta.mobile.data.canvas.plugin.CanvasPluginFallback(title = "Card $id", subtitle = "Example", icon = "box", openUrl = "https://example.test/$id"),
        )

    /** A small landscape picture: sky over a green field with a sun, so a snapshot reads as one. */
    fun picturePng(): ByteArray {
        val image = BufferedImage(PICTURE_WIDTH, PICTURE_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(0x8EC5FF)
        g.fillRect(0, 0, PICTURE_WIDTH, PICTURE_HEIGHT)
        g.color = Color(0x4CAF50)
        g.fillRect(0, PICTURE_HEIGHT * 2 / 3, PICTURE_WIDTH, PICTURE_HEIGHT / 3)
        g.color = Color(0xFFC107)
        g.fillOval(PICTURE_WIDTH * 3 / 4, PICTURE_HEIGHT / 8, PICTURE_HEIGHT / 4, PICTURE_HEIGHT / 4)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private const val PICTURE_WIDTH = 640
    private const val PICTURE_HEIGHT = 400
}
