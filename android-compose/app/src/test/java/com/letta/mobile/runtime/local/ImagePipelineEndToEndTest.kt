package com.letta.mobile.runtime.local

import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.controller.node.iroh.MessagePage
import com.letta.mobile.runtime.TurnImagePart
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.util.Telemetry
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * END-TO-END regression harness for the image send/receive pipeline
 * (letta-mobile-iej8j, absorbing the E2E acceptance criteria of
 * letta-mobile-lgns8.20).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The image pipeline has broken SILENTLY twice while every unit test stayed
 * green:
 *   • xybm2 — a `{type:"image_ref"}` part written into `messages.jsonl`; letta.js
 *     replayed it into `data:undefined;base64,…` and strict providers rejected
 *     it (code 2013). Caught only by manual device testing.
 *   • #1021 — a same-week regression OF #1017: the ~8MB per-value cap in
 *     [BoundedTranscriptReader] collapsed the just-shared image BEFORE
 *     [LocalImageContextStripper] could preserve it, so the "preserved" row held
 *     a placeholder and the model went blind.
 *
 * Both slipped through because the existing tests are DISCONNECTED slices: each
 * one exercises a single class against a hand-made fixture. This file walks the
 * WHOLE pipe instead, and — critically — asserts on the PROVIDER-BOUND REQUEST
 * SHAPE (what actually reaches the model), not just internal state:
 *
 *   attachment → [encodeUserTurnWireLine] (the real send encoder)
 *     → letta.js inbound gate (`isBase64ImageContentPart`, mirrored verbatim)
 *     → persisted `messages.jsonl` in the REAL on-disk session-log v3 format
 *     → pre-turn [LocalImageContextStripper] / [BoundedTranscriptReader] pass
 *     → hydration on conversation re-entry ([LocalBackendAdminStore.listMessagesProjected])
 *     → provider request rebuilt from the transcript ([LettaJs.buildProviderUserContent])
 *
 * GROUND TRUTH FOR EVERY MIRRORED SHAPE
 * -------------------------------------
 * Verified against the letta-code bundle
 * `@letta-ai/letta-code/letta.js` (0.29.x):
 *
 *   • inbound gate:
 *     `candidate.type === "image" && candidate.source.type === "base64" &&
 *      typeof candidate.source.media_type === "string" && media_type.length > 0 &&
 *      typeof candidate.source.data === "string" && data.length > 0`
 *   • provider request builder (chat/completions):
 *     `content.map(item => item.type === "text"
 *        ? { type:"text", text: sanitizeSurrogates(item.text) }
 *        : { type:"image_url", image_url:{ url: `data:${item.mimeType};base64,${item.data}` } })`
 *     NOTE THE `else` BRANCH: ANY non-text part becomes an image_url built from
 *     `item.mimeType`/`item.data`. That is precisely why a `{type:"image_ref"}`
 *     part produced `data:undefined;base64,undefined`. [LettaJs] mirrors this
 *     rule exactly (including JS `undefined` interpolation), so the harness has
 *     real teeth instead of a friendly re-implementation.
 *   • on-disk transcript writer (`localTranscriptSessionEntries`):
 *     a `{"type":"session","version":3,…}` header followed by
 *     `{"type":"message","id":…,"parentId":…,"timestamp":…,"message":{…}}`
 *     envelopes. Confirmed against the live store at
 *     `/root/.letta/lc-local-backend`: 89,991 v3-envelope rows vs 27 legacy flat
 *     rows.
 *   • persisted image part shape: FLAT `{type:"image", mimeType, data}`
 *     (the inbound nested `source` shape is normalized away before persistence)
 *     — read off real rows in that same live store.
 */
class ImagePipelineEndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun resetTelemetry() {
        Telemetry.clear()
    }

    @After
    fun clearTelemetry() {
        Telemetry.clear()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 1. Full pipe: attach → send-encode → persist → hydrate → provider request
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `attachment survives send encode, persistence, hydration and provider rebuild`() {
        val base = tmp.newFolder("store")

        // (a) SEND: the real production encoder, not a fixture.
        val wireLine = encodeUserTurnWireLine(
            TurnInput.UserMessage(
                localMessageId = "otid-1",
                text = "what is in this image?",
                imageParts = listOf(TurnImagePart(base64 = TINY_PNG_BASE64, mediaType = "image/png")),
            ),
        )
        val wireContent = json.parseToJsonElement(wireLine)
            .jsonObject["message"]!!.jsonObject["content"]!!.jsonArray

        // (b) INBOUND GATE: letta.js drops any image part that fails
        // isBase64ImageContentPart. If the encoder ever regresses to the flat
        // shape the image is silently never forwarded (proven on device:
        // captured=0), so this gate is asserted, not assumed.
        val gated = wireContent.filter { LettaJs.isBase64ImageContentPart(it as JsonObject) }
        assertEquals("exactly one image part must pass letta.js's inbound gate", 1, gated.size)

        // (c) PERSIST: normalize + write the REAL session-log v3 transcript.
        val persistedParts = LettaJs.persistUserContent(wireContent)
        writeTranscript(
            base,
            DEFAULT_CONV_KEY,
            listOf(
                sessionHeader(),
                messageEnvelope("m1", userRow("ui-1", persistedParts)),
            ),
        )

        // The persisted image part must be the FLAT shape letta.js reads back.
        val persistedImage = persistedParts.single { (it as JsonObject).typeIs("image") }.jsonObject
        assertEquals("image/png", persistedImage["mimeType"]!!.jsonPrimitive.content)
        assertEquals(TINY_PNG_BASE64, persistedImage["data"]!!.jsonPrimitive.content)

        // (d) HYDRATE: conversation re-entry through the on-disk reader that
        // every non-sending client uses.
        val hydrated = readConversation(base, DEFAULT_CONV_ID)
        val userMessage = hydrated.single { it["message_type"]!!.jsonPrimitive.content == "user_message" }
        val hydratedImage = userMessage["content"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["type"]!!.jsonPrimitive.content == "image" }
        val source = hydratedImage["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertEquals(TINY_PNG_BASE64, source["data"]!!.jsonPrimitive.content)

        // (e) PROVIDER REQUEST rebuilt from the transcript on disk — the shape
        // that actually reaches the model. This is the assertion the pipeline
        // never had, and the one that would have gone red on xybm2.
        val urls = providerImageUrlsFromDisk(base, DEFAULT_CONV_KEY)
        assertEquals(listOf("data:image/png;base64,$TINY_PNG_BASE64"), urls)
        urls.forEach { assertValidProviderImageUrl(it) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. The original lgns8.20 repro: send image in A → switch to B → re-enter A
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `conversation switch and re-entry preserves recent messages including the image`() {
        val base = tmp.newFolder("store")

        val imageParts = LettaJs.persistUserContent(
            json.parseToJsonElement(
                encodeUserTurnWireLine(
                    TurnInput.UserMessage(
                        localMessageId = "otid-img",
                        text = "look at this",
                        imageParts = listOf(TurnImagePart(base64 = TINY_PNG_BASE64, mediaType = "image/png")),
                    ),
                ),
            ).jsonObject["message"]!!.jsonObject["content"]!!.jsonArray,
        )

        // Conversation A: chatter, then the image, then MORE chatter — the
        // messages after the image are exactly what went missing on re-entry.
        writeTranscript(
            base,
            DEFAULT_CONV_KEY,
            listOf(
                sessionHeader(),
                messageEnvelope("a1", userRow("ui-a1", textParts("first"))),
                messageEnvelope("a2", assistantRow("ui-a2", "ack first")),
                messageEnvelope("a3", userRow("ui-a3", imageParts)),
                messageEnvelope("a4", assistantRow("ui-a4", "i see the image")),
                messageEnvelope("a5", userRow("ui-a5", textParts("and after the image"))),
            ),
        )

        // Conversation B (a different on-disk conversation, switched into).
        writeConversationJson(base, "conv-b", "agent-1")
        writeTranscript(
            base,
            "conversation:conv-b",
            listOf(
                sessionHeader(),
                messageEnvelope("b1", userRow("ui-b1", textParts("other conversation"))),
            ),
        )

        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://e/v1")

        fun readA() = store.listMessagesProjected(DEFAULT_CONV_ID, null, ALL_MESSAGES)!!.map { it.jsonObject }
        fun readB() = store.listMessagesProjected("conv-b", "agent-1", ALL_MESSAGES)!!.map { it.jsonObject }

        val firstVisit = readA()
        assertEquals(listOf("ui-a1", "ui-a2", "ui-a3", "ui-a4", "ui-a5"), firstVisit.map { it.id() })

        // Switch away…
        assertEquals(listOf("ui-b1"), readB().map { it.id() })

        // …a turn lands in A while we are away (letta.js appends to the file
        // it owns; the reader's cache MUST invalidate on the new signature)…
        appendEnvelope(base, DEFAULT_CONV_KEY, messageEnvelope("a6", assistantRow("ui-a6", "while you were gone")))

        // …and switch back. NOTHING may be missing.
        val reentry = readA()
        assertEquals(
            "re-entry must preserve every message, including everything after the image",
            listOf("ui-a1", "ui-a2", "ui-a3", "ui-a4", "ui-a5", "ui-a6"),
            reentry.map { it.id() },
        )
        val image = reentry.single { it.id() == "ui-a3" }["content"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["type"]!!.jsonPrimitive.content == "image" }
        assertEquals(TINY_PNG_BASE64, image["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)

        // And B is still intact after the round trip.
        assertEquals(listOf("ui-b1"), readB().map { it.id() })
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. #1021 regression canary: oversized transcript, image in the TAIL
    // ─────────────────────────────────────────────────────────────────────

    /**
     * THE #1021 CLASS. A real phone photo's base64 (10-20MB) exceeds
     * [BoundedTranscriptReader.DEFAULT_MAX_INLINE_VALUE_CHARS] (~8MB), so the
     * bounded pre-turn read collapses it to a marker. Without the targeted
     * uncapped re-read, the stripper "preserves" a row that no longer contains
     * the image — and then REWRITES the transcript with that placeholder,
     * permanently destroying the image the user just sent.
     *
     * FAIL-ON-REVERT: delete the `readSingleLineFull` branch in
     * [LocalImageContextStripper.stripTranscript] and this test goes red (the
     * tail image is replaced by a `[image omitted from context: …]` text part).
     *
     * Runs on the LEGACY flat row shape deliberately — the stripper handles
     * both shapes since letta-mobile-6ppdr (see
     * [stripper fires on real session-log v3 transcripts]), and the flat shape
     * keeps this canary focused on the cap/re-read path.
     */
    @Test
    fun `oversized transcript still hydrates the image in the tail past the 8MB cap`() {
        val base = tmp.newFolder("store")
        val dir = conversationDir(base, DEFAULT_CONV_KEY)
        val transcript = File(dir, "messages.jsonl")

        // The cap is injected (production keeps the ~8MB default) so this
        // canary trips the SAME collapse → targeted-re-read path without
        // allocating tens of MB in the shared unit-test JVM — heap churn that
        // measurably starved a neighbouring 20MB transcript test into a
        // coroutine timeout. Divisible by 4 so it stays valid base64.
        val testCapChars = 4 * 1024
        val hugeBase64 = "A".repeat(testCapChars + 1024)
        transcript.writeText(
            listOf(
                // An OLDER image that legitimately gets stripped — this is what
                // makes the pass rewrite the file, which is when a collapsed
                // tail image would be destroyed for good.
                flatUserImageRow("u1", "an earlier photo", "image/jpeg", SMALL_JPEG_BASE64),
                // The just-shared image, in the tail, over the cap.
                flatUserImageRow("u2", "what is this", "image/png", hugeBase64),
            ).joinToString("\n") + "\n",
        )

        // Precondition: the bounded read really does collapse the tail row —
        // otherwise the canary would pass vacuously.
        val bounded = BoundedTranscriptReader.readLines(transcript, testCapChars)
        assertTrue("tail image must exceed the cap for this canary to mean anything", bounded[1].collapsedValueChars > 0L)

        LocalImageContextStripper(blobStore = LocalImageBlobStore(dir), maxInlineValueChars = testCapChars)
            .stripTranscript(transcript)

        val rows = transcript.readLines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }
        // The older image was stripped to a harmless text placeholder…
        val older = rows[0]["content"]!!.jsonArray.map { it.jsonObject }
        assertTrue("older image must be stripped to a text part", older.all { it["type"]!!.jsonPrimitive.content == "text" })
        // …and the tail image survived IN FULL, byte for byte.
        val tailImage = rows[1]["content"]!!.jsonArray.map { it.jsonObject }
            .single { it["type"]!!.jsonPrimitive.content == "image" }
        assertEquals(
            "the just-shared image must survive the cap uncollapsed (#1021)",
            hugeBase64,
            tailImage["data"]!!.jsonPrimitive.content,
        )

        // And the provider request rebuilt from that file is still schema-valid.
        val urls = rows.flatMap { LettaJs.providerImageUrls(it["content"]!!.jsonArray) }
        assertEquals(1, urls.size)
        assertValidProviderImageUrl(urls.single())

        // BOUNDARY TELEMETRY: cap-hit and the targeted re-read must both be
        // observable. The whole point of iej8j is that a break in this pipe
        // must be visible without a human staring at a device.
        val events = Telemetry.snapshot().filter { it.tag == LocalImageContextStripper.IMAGE_PIPELINE_TAG }
        val collapsed = events.single { it.name == "transcript.value_collapsed" }
        assertEquals(1, collapsed.attrs["rows"])
        assertEquals(testCapChars, collapsed.attrs["capChars"])
        val reread = events.single { it.name == "latest_image.uncapped_reread" }
        assertEquals(true, reread.attrs["recovered"])
        assertEquals(1, reread.attrs["lineIndex"])
        assertNotNull("the strip pass itself must be observable", events.firstOrNull { it.name == "strip.parts_stripped" })
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. The xybm2 class: provider request must never contain data:undefined
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `second image after strip yields exactly one valid image_url and no data undefined`() {
        val base = tmp.newFolder("store")
        val dir = conversationDir(base, DEFAULT_CONV_KEY)
        val transcript = File(dir, "messages.jsonl")

        // Turn 1 lands on disk with an image, turn 2 attaches another.
        transcript.writeText(
            flatUserImageRow("u1", "one", "image/jpeg", SMALL_JPEG_BASE64) + "\n",
        )
        LocalImageContextStripper(blobStore = LocalImageBlobStore(dir)).stripTranscript(transcript)
        // (single image row = the preserved latest, so nothing is stripped yet)
        transcript.appendText(
            flatUserImageRow("u2", "two", "image/png", TINY_PNG_BASE64) + "\n",
        )
        val report = LocalImageContextStripper(blobStore = LocalImageBlobStore(dir)).stripTranscript(transcript)
        assertEquals("the older image must now be stripped", 1, report.partsStripped)

        val rows = transcript.readLines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }

        // EVERY part letta.js will replay, run through its REAL mapping rule.
        val providerContent = rows.flatMap { LettaJs.buildProviderUserContent(it["content"]!!.jsonArray) }
        val urls = providerContent
            .filter { it["type"]!!.jsonPrimitive.content == "image_url" }
            .map { it["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content }

        assertEquals("only the fresh image may be sent as an image_url", 1, urls.size)
        assertValidProviderImageUrl(urls.single())
        assertTrue(
            "no provider part may carry a data:undefined url (the xybm2 2013 break)",
            providerContent.none { part ->
                part.toString().contains("data:undefined") || part.toString().contains("data:null")
            },
        )
        // The stripped image must degrade to a TEXT part, never to an unknown
        // part type — an unknown type falls into letta.js's `else` branch and
        // becomes data:undefined.
        assertTrue(
            "stripped image must be replayed as text",
            providerContent.any {
                it["type"]!!.jsonPrimitive.content == "text" &&
                    it["text"]!!.jsonPrimitive.content.contains("image omitted from context")
            },
        )
    }

    /**
     * Teeth check for the mirror itself: the BROKEN xybm2 on-disk shape must
     * still be rejected by [LettaJs]. If someone "simplifies" the mirror to only
     * map `type == "image"` parts, this test fails and the harness cannot go
     * quietly blind again.
     */
    @Test
    fun `letta js mirror still turns an unknown image_ref part into an invalid data url`() {
        // (a) the xybm2 shape that kept a mimeType: the DATA is what goes
        // missing, so a media-type-only regex would wave it through — the
        // validator must decode the payload.
        val withMimeType = LettaJs.providerImageUrls(
            json.parseToJsonElement(
                """[{"type":"image_ref","image_ref":"sha256:abc","mimeType":"image/jpeg"}]""",
            ).jsonArray,
        ).single()
        assertEquals("data:image/jpeg;base64,undefined", withMimeType)
        assertFalse("undefined base64 must NOT validate", isValidProviderImageUrl(withMimeType))

        // (b) the shape that produced the literal provider 2013 message
        // ("image data url media type undefined").
        val withoutMimeType = LettaJs.providerImageUrls(
            json.parseToJsonElement("""[{"type":"image_ref","image_ref":"sha256:abc"}]""").jsonArray,
        ).single()
        assertEquals("data:undefined;base64,undefined", withoutMimeType)
        assertFalse("data:undefined must NOT validate", isValidProviderImageUrl(withoutMimeType))

        // (c) an empty image shell left by a buggy strip pass.
        val emptyShell = LettaJs.providerImageUrls(
            json.parseToJsonElement("""[{"type":"image","mimeType":"image/png","data":""}]""").jsonArray,
        ).single()
        assertFalse("empty base64 must NOT validate", isValidProviderImageUrl(emptyShell))
    }

    // ─────────────────────────────────────────────────────────────────────
    // 5. Documented residual gaps — current behavior asserted ON PURPOSE
    // ─────────────────────────────────────────────────────────────────────

    /**
     * RESIDUAL GAP 1 (known, carried from lgns8.20): an image that has already
     * been collapsed to a rehydration pointer stays DROPPED on the on-disk
     * hydration path. [LocalBackendMessageProjection] cannot resolve blob refs,
     * so a client re-entering the conversation sees the text placeholder only.
     *
     * TODO(letta-mobile-iej8j): when blob-ref resolution is wired into the
     * on-disk projection, FLIP the assertions below (image present, drop
     * telemetry absent) rather than deleting them — the point is that the fix
     * changes a documented expectation instead of silently changing behavior.
     */
    @Test
    fun `documented gap - image_ref collapsed images stay dropped on hydration`() {
        val base = tmp.newFolder("store")
        writeTranscript(
            base,
            DEFAULT_CONV_KEY,
            listOf(
                sessionHeader(),
                // (a) the CURRENT stripper output: a text part carrying the pointer.
                messageEnvelope(
                    "m1",
                    userRow(
                        "ui-1",
                        json.parseToJsonElement(
                            """[{"type":"text","text":"[image omitted from context: image/png]",""" +
                                """"stripped":true,"image_ref":"sha256:abc","mediaType":"image/png"}]""",
                        ).jsonArray,
                    ),
                ),
                // (b) a legacy image part whose data was already collapsed away.
                messageEnvelope(
                    "m2",
                    userRow(
                        "ui-2",
                        json.parseToJsonElement(
                            """[{"type":"text","text":"legacy"},{"type":"image","image_ref":"sha256:def","mimeType":"image/png"}]""",
                        ).jsonArray,
                    ),
                ),
            ),
        )

        val hydrated = readConversation(base, DEFAULT_CONV_ID)

        // CURRENT (documented-as-broken) behavior: no image survives.
        assertTrue(
            "CURRENT BEHAVIOR (gap): image_ref-collapsed images do not hydrate",
            hydrated.none { msg ->
                (msg["content"] as? JsonArray)?.any { (it as JsonObject).typeIs("image") } == true
            },
        )
        assertEquals(
            "the placeholder text is all that survives",
            "[image omitted from context: image/png]",
            hydrated.first { it.id() == "ui-1" }["content"]!!.jsonPrimitive.content,
        )

        // …but it is now OBSERVABLE: the drop fires boundary telemetry for the
        // image-typed pointer instead of vanishing silently.
        val drop = Telemetry.snapshot().single { it.name == "hydrate.image_dropped" }
        assertEquals("image_ref_pointer", drop.attrs["reason"])
        assertEquals(Telemetry.Level.WARN, drop.level)
    }

    /**
     * RESIDUAL GAP 2 (FOUND BY THIS HARNESS, letta-mobile-iej8j) — NOW FIXED
     * (letta-mobile-6ppdr): [LocalImageContextStripper] and
     * [LocalConversationHealer] used to read `role` / `content` at the TOP
     * LEVEL of each transcript row, but letta-code 0.29.x writes session-log
     * **v3 envelopes** —
     * `{"type":"message","id":…,"parentId":…,"timestamp":…,"message":{role,content}}`
     * (`localTranscriptSessionEntries` in the bundle). Every unit test for those
     * two classes used the legacy FLAT shape, so the mismatch was invisible.
     *
     * Evidence from the live store (`/root/.letta/lc-local-backend`,
     * 2026-07-31): 89,991 v3-envelope rows, 27 legacy flat rows, 124 image
     * parts, and ZERO `stripped` placeholders — i.e. the stripper had never
     * once fired on real data, leaving the context-bloat protection of 87itk
     * and the tool-call healing of lgns8.20 inert on 0.29.x transcripts.
     *
     * `SessionLogEnvelope` now unwraps the envelope on read and restores it on
     * write, so the pre-turn pass fires on real transcripts.
     *
     * FAIL-ON-REVERT: revert the `SessionLogEnvelope.body`/`withBody` calls in
     * [LocalImageContextStripper] and this test goes red — `partsStripped`
     * drops back to 0 and no `strip.parts_stripped` telemetry fires. Envelope
     * shape and unit-level behaviour are covered in depth by
     * `SessionLogV3TranscriptTest`; this asserts it end-to-end on a transcript
     * written by the real persist path.
     */
    @Test
    fun `stripper fires on real session-log v3 transcripts`() {
        val base = tmp.newFolder("store")
        val dir = conversationDir(base, DEFAULT_CONV_KEY)
        val transcript = File(dir, "messages.jsonl")

        val v3 = listOf(
            sessionHeader(),
            messageEnvelope("m1", userRow("ui-1", imagePartsFlat(SMALL_JPEG_BASE64, "image/jpeg"))),
            messageEnvelope("m2", userRow("ui-2", imagePartsFlat(TINY_PNG_BASE64, "image/png"))),
        ).joinToString("\n") { it.toString() } + "\n"
        transcript.writeText(v3)

        val report = LocalImageContextStripper(blobStore = LocalImageBlobStore(dir)).stripTranscript(transcript)

        // Exactly ONE part strips: the LATEST user image row is deliberately
        // preserved for the turn in flight (the 87itk / PR #481 policy). This
        // asserts that policy survives the envelope unwrap — the pass is no
        // longer inert on v3, and it still does not eat the just-shared image.
        assertEquals("FIXED (6ppdr): the older v3 image part is stripped", 1, report.partsStripped)
        assertTrue("stripping frees transcript bytes", report.bytesFreed > 0)
        assertNotEquals("the transcript is rewritten, not left byte-identical", v3, transcript.readText())
        val stripEvent = Telemetry.snapshot().single { it.name == "strip.parts_stripped" }
        assertEquals(1, stripEvent.attrs["parts"])

        // The envelope survives the rewrite and the stripped image became a
        // placeholder INSIDE message.content.
        val rows = transcript.readLines()
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
        val messageRows = rows.mapNotNull { row -> (row["message"] as? JsonObject)?.let { row to it } }
        assertEquals("both message rows keep their v3 envelope", 2, messageRows.size)
        messageRows.forEach { (row, body) ->
            assertEquals("message", row["type"]!!.jsonPrimitive.content)
            assertEquals("user", body["role"]!!.jsonPrimitive.content)
        }
        val contents = messageRows.mapNotNull { (_, body) -> body["content"] as? JsonArray }

        // Only the PRESERVED latest image is still a provider image_url — and it
        // is still schema-valid, so bloat protection cannot regress correctness.
        val urls = contents.flatMap { LettaJs.providerImageUrls(it) }
        assertEquals("only the latest image survives as a provider image_url", 1, urls.size)
        urls.forEach { assertValidProviderImageUrl(it) }

        val placeholders = contents
            .flatMap { parts -> parts.mapNotNull { it as? JsonObject } }
            .filter { it["stripped"]?.jsonPrimitive?.content?.toBoolean() == true }
        assertEquals("the stripped image left a placeholder", 1, placeholders.size)
        assertTrue(placeholders.single()["text"]!!.jsonPrimitive.content.contains("image omitted"))
    }

    // ─────────────────────────────────────────────────────────────────────
    // letta.js mirror (ground truth: the 0.29.x bundle — see the class KDoc)
    // ─────────────────────────────────────────────────────────────────────

    private object LettaJs {
        /** Verbatim mirror of `isBase64ImageContentPart` (message-image-normalization.ts). */
        fun isBase64ImageContentPart(part: JsonObject): Boolean {
            val source = part["source"] as? JsonObject
            return part["type"]?.str() == "image" &&
                source?.get("type")?.str() == "base64" &&
                (source["media_type"]?.str()?.isNotEmpty() == true) &&
                (source["data"]?.str()?.isNotEmpty() == true)
        }

        /**
         * What ends up in `messages.jsonl` after inbound normalization: the
         * nested wire image is flattened to `{type:"image", mimeType, data}`
         * (the shape read off real rows in the live store); text passes through.
         */
        fun persistUserContent(wireContent: JsonArray): JsonArray = JsonArray(
            wireContent.map { part ->
                val p = part as JsonObject
                if (!isBase64ImageContentPart(p)) {
                    p
                } else {
                    val source = p["source"]!!.jsonObject
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("image"),
                            "mimeType" to JsonPrimitive(source["media_type"]!!.jsonPrimitive.content),
                            "data" to JsonPrimitive(source["data"]!!.jsonPrimitive.content),
                        ),
                    )
                }
            },
        )

        /**
         * Verbatim mirror of the chat/completions content mapper. The `else`
         * branch is the load-bearing part: ANY non-text part becomes an
         * image_url interpolated from `item.mimeType`/`item.data`, and a missing
         * field interpolates as the JS string "undefined".
         */
        fun buildProviderUserContent(content: JsonArray): List<JsonObject> = content.map { item ->
            val p = item as JsonObject
            if (p["type"]?.str() == "text") {
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive(p["text"]?.str().orEmpty()),
                    ),
                )
            } else {
                val url = "data:${p["mimeType"]?.str() ?: "undefined"};base64,${p["data"]?.str() ?: "undefined"}"
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("image_url"),
                        "image_url" to JsonObject(mapOf("url" to JsonPrimitive(url))),
                    ),
                )
            }
        }

        fun providerImageUrls(content: JsonArray): List<String> =
            buildProviderUserContent(content)
                .filter { it["type"]!!.jsonPrimitive.content == "image_url" }
                .map { it["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content }

        private fun kotlinx.serialization.json.JsonElement.str(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /**
     * The provider-side contract the 2013 rejection encodes: a real `image/…`
     * media type and non-empty base64. Rejects `data:undefined`, `data:;`,
     * and empty payloads.
     */
    private fun isValidProviderImageUrl(url: String): Boolean {
        val match = Regex("^data:(image/[^;]+);base64,(.+)$").matchEntire(url) ?: return false
        val mediaType = match.groupValues[1]
        // A JS-interpolated `undefined`/`null` must never read as a media type.
        if (mediaType.contains("undefined") || mediaType.contains("null")) return false
        // The payload must be REAL base64, not the string "undefined" — a regex
        // alone would happily accept `data:image/jpeg;base64,undefined`, which
        // is exactly the half-broken shape a partial regression produces.
        val payload = match.groupValues[2]
        return runCatching { Base64.getDecoder().decode(payload) }.getOrNull()?.isNotEmpty() == true
    }

    private fun assertValidProviderImageUrl(url: String) {
        assertTrue(
            "provider image_url must be schema-valid (media type + base64), was: ${url.take(64)}",
            isValidProviderImageUrl(url),
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Real on-disk store helpers (session-log v3 + the shim's dir layout)
    // ─────────────────────────────────────────────────────────────────────

    private fun conversationDir(base: File, key: String): File =
        File(File(base, "conversations"), b64Url(key)).apply { mkdirs() }

    private fun writeTranscript(base: File, key: String, rows: List<JsonObject>) {
        File(conversationDir(base, key), "messages.jsonl")
            .writeText(rows.joinToString("\n") { it.toString() } + "\n")
    }

    private fun appendEnvelope(base: File, key: String, row: JsonObject) {
        File(conversationDir(base, key), "messages.jsonl").appendText(row.toString() + "\n")
    }

    private fun writeConversationJson(base: File, convId: String, agentId: String) {
        File(conversationDir(base, "conversation:$convId"), "conversation.json")
            .writeText("""{"id":"$convId","agent_id":"$agentId","last_message_at":"2026-07-31T00:00:00.000Z"}""")
    }

    private fun readConversation(base: File, convId: String): List<JsonObject> =
        LocalBackendAdminStore(base, lmstudioBaseUrl = "http://e/v1")
            .listMessagesProjected(convId, null, ALL_MESSAGES)!!
            .map { it.jsonObject }

    private fun providerImageUrlsFromDisk(base: File, key: String): List<String> =
        File(conversationDir(base, key), "messages.jsonl").readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            .mapNotNull { it["message"] as? JsonObject }
            .filter { it["role"]?.jsonPrimitive?.content == "user" }
            .mapNotNull { it["content"] as? JsonArray }
            .flatMap { LettaJs.providerImageUrls(it) }

    /** `{"type":"session","version":3,…}` — the header letta.js writes first. */
    private fun sessionHeader(): JsonObject = json.parseToJsonElement(
        """{"type":"session","version":3,"id":"default","timestamp":"2026-07-31T00:00:00.000Z","cwd":"/tmp"}""",
    ).jsonObject

    /** The v3 envelope wrapper: `{type:"message", id, parentId, timestamp, message}`. */
    private fun messageEnvelope(entryId: String, message: JsonObject): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("message"),
            "id" to JsonPrimitive(entryId),
            "parentId" to JsonPrimitive(null as String?),
            "timestamp" to JsonPrimitive("2026-07-31T00:00:00.000Z"),
            "message" to message,
        ),
    )

    private fun userRow(id: String, content: JsonArray): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "role" to JsonPrimitive("user"),
            "content" to content,
        ),
    )

    private fun assistantRow(id: String, text: String): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "role" to JsonPrimitive("assistant"),
            "content" to textParts(text),
        ),
    )

    private fun textParts(text: String): JsonArray = json.parseToJsonElement(
        """[{"type":"text","text":"$text"}]""",
    ).jsonArray

    private fun imagePartsFlat(data: String, mimeType: String): JsonArray = json.parseToJsonElement(
        """[{"type":"text","text":"see"},{"type":"image","mimeType":"$mimeType","data":"$data"}]""",
    ).jsonArray

    /** A legacy FLAT transcript row (the only shape [LocalImageContextStripper] reads today). */
    private fun flatUserImageRow(id: String, text: String, mimeType: String, data: String): String =
        """{"id":"$id","role":"user","content":[""" +
            """{"type":"text","text":"$text"},""" +
            """{"type":"image","mimeType":"$mimeType","data":"$data"}]}"""

    private fun b64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun JsonObject.id(): String = this["id"]!!.jsonPrimitive.content

    private fun JsonObject.typeIs(type: String): Boolean =
        this["type"]?.jsonPrimitive?.content == type

    private companion object {
        /** The reader's dir key for an agent's default conversation. */
        private const val DEFAULT_CONV_KEY = "default:agent-1"
        private const val DEFAULT_CONV_ID = "conv-default-agent-1"
        private val ALL_MESSAGES = MessagePage(limit = null, before = null, after = null, order = null)

        /** A real 1x1 PNG. */
        private const val TINY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

        /** A short, decodable stand-in for an older (already-sent) photo. */
        private const val SMALL_JPEG_BASE64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAg="
    }
}
