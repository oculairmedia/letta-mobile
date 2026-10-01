package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasBatchCheck
import com.letta.mobile.data.canvas.CanvasBatchValidator
import com.letta.mobile.data.canvas.CanvasBatchViolation
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasStateInvariant
import com.letta.mobile.data.canvas.CanvasStateViolation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The compose service (letta-mobile-bglj6.10): compile, the batch validator, publish, receipt; and
 * nothing published unless all of it is.
 */
class CanvasComposeServiceTest {
    private suspend fun ComposeBoard.compose(request: String, toolCallId: String? = "call-1"): ComposeOutcome =
        CanvasComposeService.compose(
            ComposeJson.parse(request), ComposeBoard.CANVAS, sceneJson, revision, toolCallId,
            publish = { publish(it) },
        )

    @Test
    fun aRequestIsCheckedPublishedOnceAndAnsweredWithItsRevision() = runTest {
        val board = ComposeBoard().apply { addNote("mine", com.letta.mobile.data.canvas.CanvasDocumentFrame(80f, 80f, 320f, 200f)) }
        val before = board.sceneJson
        val compiled = readyOf(compileText(WEEKEND_PLAN, before))
        val published = board.published.size

        val receipt = assertIs<ComposeOutcome.Done>(board.compose(WEEKEND_PLAN)).receipt
        assertEquals(published + 1, board.published.size, "publish is called once")
        // Exactly the compiled ops, as the batch validator normalised them.
        val valid = assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(before, compiled.ops))
        assertEquals(valid.ops, board.published.last())
        assertEquals(compiled.ops.size, board.published.last().size)

        assertEquals(ComposeStatus.PUBLISHED, receipt.status)
        assertEquals(board.revision, receipt.revision)
        assertEquals(ComposeBoard.CANVAS, receipt.canvasId)
        assertEquals(compiled.receipt(ComposeBoard.CANVAS, ComposeStatus.PUBLISHED, board.revision), receipt)
    }

    @Test
    fun aDryRunPublishesNothing() = runTest {
        val board = ComposeBoard()
        val receipt = assertIs<ComposeOutcome.Done>(
            CanvasComposeService.compose(
                ComposeJson.parse("""{"dry_run":true,"artifact_id":"x","items":[{"kind":"NOTE","markdown":"x"}]}"""),
                ComposeBoard.CANVAS, board.sceneJson, 9, null,
                publish = { error("a dry run must not publish") },
            ),
        ).receipt
        assertEquals(ComposeStatus.DRY_RUN, receipt.status)
        assertNull(receipt.revision)
        assertEquals("", board.sceneJson)
    }

    @Test
    fun aDryRunTheBoardWouldRefuseIsRefused() = runTest {
        val board = ComposeBoard().apply { addBox("cmp-x-i0", 0f, 0f, 10f, 10f) }
        val outcome = CanvasComposeService.compose(
            ComposeJson.parse("""{"dry_run":true,"artifact_id":"x","items":[{"kind":"TEXT","text":"t","size":"body"}]}"""),
            ComposeBoard.CANVAS, board.sceneJson, board.revision, null,
            publish = { error("a dry run must not publish") },
        )
        assertEquals(ComposeErrorCode.BOARD_REFUSED, assertIs<ComposeOutcome.Refused>(outcome).refusal.code)
    }

    @Test
    fun aRefusedRequestPublishesNothingAndLeavesTheSceneAsItWas() = runTest {
        val board = ComposeBoard().apply { addNote("mine", com.letta.mobile.data.canvas.CanvasDocumentFrame(80f, 80f, 320f, 200f)) }
        val before = board.sceneJson
        val published = board.published.size
        val refusal = assertIs<ComposeOutcome.Refused>(board.compose(WEEKEND_PLAN.replace("Finish *Dune*", "Finish <i>Dune</i>"))).refusal
        assertEquals(ComposeErrorCode.VALIDATION_FAILED, refusal.code)
        assertEquals(setOf("/items/3/children/1/markdown"), refusal.problems.map { it.path }.toSet())
        assertEquals(published, board.published.size)
        assertEquals(before, board.sceneJson)
    }

    @Test
    fun aBoardRefusalNamesTheItemWhoseOpBrokeTheRuleAndTheRule() = runTest {
        // Someone already drew an element under the id the heading would take.
        val board = ComposeBoard().apply { addBox("cmp-weekend-plan-heading", 2000f, 2000f, 2100f, 2100f) }
        val before = board.sceneJson
        val published = board.published.size
        val refusal = assertIs<ComposeOutcome.Refused>(board.compose(WEEKEND_PLAN)).refusal
        assertEquals(ComposeErrorCode.BOARD_REFUSED, refusal.code)
        val problem = refusal.problems.single()
        assertEquals("/items/0", problem.path)
        assertEquals(CanvasStateInvariant.ELEMENT_DUPLICATE_ID.wire, problem.code)
        assertTrue("cmp-weekend-plan-heading" in problem.message, problem.message)
        assertEquals(CanvasComposeContract.REFUSAL_HINT, refusal.hint)
        assertEquals(published, board.published.size)
        assertEquals(before, board.sceneJson)
    }

    @Test
    fun aCorruptedCompileIsRefusedByTheBatchValidatorAndNothingIsPublished() = runTest {
        val board = ComposeBoard()
        val outcome = CanvasComposeService.compose(
            ComposeJson.parse(WEEKEND_PLAN), ComposeBoard.CANVAS, board.sceneJson, 0, null,
            check = { CanvasBatchValidator.check(board.sceneJson, it) },
            publish = { board.publish(it) },
        ) { input, scene, fallback ->
            // The test seam: the meals note's codec output is not a block document.
            val ready = readyOf(CanvasComposeCompiler.compile(input, scene, fallback))
            ready.copy(
                ops = ready.ops.map { op ->
                    if (op is CanvasOp.SetDocumentOp && op.documentId == "cmp-weekend-plan-meals") op.copy(documentJson = """{"type":"doc"}""") else op
                },
            )
        }
        val refusal = assertIs<ComposeOutcome.Refused>(outcome).refusal
        assertEquals(ComposeErrorCode.BOARD_REFUSED, refusal.code)
        assertEquals(listOf("/items/2" to CanvasStateInvariant.DOCUMENT_DECODES.wire), refusal.problems.map { it.path to it.code })
        assertEquals(emptyList(), board.published)
        assertEquals("", board.sceneJson)
    }

    @Test
    fun violationsMapToTheirItemsPaths() {
        val violation = { index: String -> CanvasBatchViolation(index, "op", CanvasStateViolation(CanvasStateInvariant.DOCUMENT_SIZE, "d", "too big")) }
        val refusal = CanvasComposeService.boardRefusal(listOf(violation("1"), violation("2.0"), violation("9")), listOf("/items/0", "/items/1", "/items/2/children/0"))
        assertEquals(listOf("/items/1", "/items/2/children/0", ""), refusal.problems.map { it.path })
        assertEquals(setOf("document.size"), refusal.problems.map { it.code }.toSet())
    }

    @Test
    fun aPublishThatFailsIsARefusal() = runTest {
        val board = ComposeBoard()
        val failed = CanvasComposeService.compose(
            ComposeJson.parse(WEEKEND_PLAN), ComposeBoard.CANVAS, "", 0, null,
            publish = { throw IllegalStateException("relay gone") },
        )
        val refusal = assertIs<ComposeOutcome.Refused>(failed).refusal
        assertEquals(ComposeErrorCode.BOARD_REFUSED, refusal.code)
        assertEquals(CanvasComposeService.PUBLISH_FAILED, refusal.problems.single().code)
        assertTrue("relay gone" in refusal.problems.single().message)

        val denied = CanvasComposeService.compose(
            ComposeJson.parse(WEEKEND_PLAN), ComposeBoard.CANVAS, "", 0, null,
            publish = { throw ComposePublishException(ComposeErrorCode.UNAUTHORIZED, "actor cannot write") },
        )
        assertEquals(ComposeErrorCode.UNAUTHORIZED, assertIs<ComposeOutcome.Refused>(denied).refusal.code)
        assertEquals("", board.sceneJson)
    }

    @Test
    fun cancellationIsNotARefusal() = runTest {
        assertFailsWith<CancellationException> {
            CanvasComposeService.compose(
                ComposeJson.parse(WEEKEND_PLAN), ComposeBoard.CANVAS, "", 0, null,
                publish = { throw CancellationException("cancelled") },
            )
        }
    }

    @Test
    fun aRetryPublishesNothingAndAnswersTheSameArtifact() = runTest {
        val board = ComposeBoard()
        val first = assertIs<ComposeOutcome.Done>(board.compose(WEEKEND_PLAN)).receipt
        val scene = board.sceneJson
        val published = board.published.size

        val again = assertIs<ComposeOutcome.Done>(board.compose(WEEKEND_PLAN)).receipt
        assertEquals(published, board.published.size)
        assertEquals(scene, board.sceneJson)
        assertEquals(first.copy(warnings = listOf(CanvasComposeService.ALREADY_PUBLISHED_WARNING)), again)
    }

    @Test
    fun aRetriedToolCallWithoutAnArtifactIdLandsOnTheSameArtifact() = runTest {
        val board = ComposeBoard()
        val request = """{"items":[{"kind":"NOTE","markdown":"x"},{"kind":"TEXT","text":"t","size":"body"}]}"""
        val first = assertIs<ComposeOutcome.Done>(board.compose(request, toolCallId = "call-42")).receipt
        assertEquals(CanvasComposeIds.derived("call-42"), first.artifactId)
        val published = board.published.size
        val again = assertIs<ComposeOutcome.Done>(board.compose(request, toolCallId = "call-42")).receipt
        assertEquals(first.artifactId, again.artifactId)
        assertEquals(first.items, again.items)
        assertEquals(published, board.published.size)
    }

    @Test
    fun theReceiptOfTwentyFourItemsStaysUnderFourKiB() = runTest {
        // The longest artifact id, and long keys on every item and a checklist count on each.
        val items = buildJsonArray {
            repeat(CanvasComposeContract.MAX_ITEMS) { i ->
                add(
                    buildJsonObject {
                        put("kind", "CHECKLIST")
                        put("key", "item-$i-" + "k".repeat(16))
                        put("items", buildJsonArray { add(buildJsonObject { put("text", "x") }) })
                    },
                )
            }
        }
        val request = buildJsonObject {
            put("artifact_id", "a" + "b".repeat(47))
            put("title", "T".repeat(CanvasComposeContract.MAX_TITLE_CHARS))
            put("items", items)
        }.toString()
        val outcome = assertIs<ComposeOutcome.Done>(ComposeBoard().compose(request))
        val size = outcome.json.encodeToByteArray().size
        assertTrue(size < 4 * 1024, "the receipt is $size bytes")
    }

    @Test
    fun theOutcomeIsTheWireReceiptOrRefusal() = runTest {
        val done = assertIs<ComposeOutcome.Done>(ComposeBoard().compose(WEEKEND_PLAN))
        assertEquals(done.receipt, CanvasComposeContract.json.decodeFromString(ComposeReceipt.serializer(), done.json))
        val refused = assertIs<ComposeOutcome.Refused>(ComposeBoard().compose("""{"items":[]}"""))
        assertEquals(refused.refusal, CanvasComposeContract.json.decodeFromString(ComposeRefusal.serializer(), refused.json))
        assertTrue("\"ok\":false" in refused.json)
    }
}
