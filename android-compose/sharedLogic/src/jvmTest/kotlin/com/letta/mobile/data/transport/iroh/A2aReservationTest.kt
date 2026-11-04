package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class A2aReservationTest {
    private val message = IrohAgentMessage("sender", "recipient", "body", "stable", 1)

    @Test
    fun definiteRejectionAllowsRetryOfTheSameIdentity() = runTest {
        var attempts = 0
        val receiver = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
            { _, decision ->
                if (decision is IrohAgentMessageRouter.RoutingDecision.Dropped) DeliveryOutcome(false, decision.reason)
                else { attempts++; DeliveryOutcome(false, "application_input_rejected") }
            }, { _, _ -> true })
        repeat(2) { assertEquals("application_input_rejected", receiver.respond(message, "peer").reason) }
        assertEquals(2, attempts)
    }

    @Test
    fun acceptanceAndAmbiguityNeverExecuteAgain() = runTest {
        for (reason in listOf("application_input_accepted", "application_input_failure", "delivery_timeout")) {
            var attempts = 0
            val receiver = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
                { _, decision ->
                    if (decision !is IrohAgentMessageRouter.RoutingDecision.Dropped) attempts++
                    DeliveryOutcome(false, reason)
                }, { _, _ -> true })
            receiver.respond(message, "peer")
            receiver.respond(message, "peer")
            assertEquals(1, attempts, reason)
        }
    }

    @Test
    fun concurrentDuplicateCannotEnterTheApplication() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var attempts = 0
        val receiver = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
            { _, decision ->
                if (decision is IrohAgentMessageRouter.RoutingDecision.Dropped) DeliveryOutcome(false, decision.reason)
                else { attempts++; entered.complete(Unit); finish.await(); DeliveryOutcome(false, "application_input_accepted") }
            }, { _, _ -> true })
        val first = async { receiver.respond(message, "peer") }
        entered.await()
        assertEquals("duplicate_msg_id", receiver.respond(message, "peer").reason)
        finish.complete(Unit)
        first.await()
        assertEquals(1, attempts)
    }
    @Test
    fun journalRetainsAcceptedAmbiguousAndUnfinishedAcrossRestart() = runTest {
        for (reason in listOf("application_input_accepted", "application_input_failure", "delivery_timeout")) {
            val dir = java.nio.file.Files.createTempDirectory("a2a-journal").toFile()
            try {
                val file = java.io.File(dir, "receipts.json")
                var attempts = 0
                fun receiver() = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
                    { _, _ -> attempts++; DeliveryOutcome(false, reason) }, { _, _ -> true }, file)
                receiver().respond(message, "peer")
                assertEquals("duplicate_msg_id", receiver().respond(message, "peer").reason)
                assertEquals(1, attempts)
                // Simulate a crash between durable reserve and application receipt.
                file.writeText("""{"version":1,"reservations":{"stable":"RESERVED"},"senderAttempts":{"sender":1}}""")
                assertEquals("duplicate_msg_id", receiver().respond(message, "peer").reason)
                assertEquals(1, attempts)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test
    fun definiteFailureReleasesDurablyButSenderBudgetSurvivesRestart() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("a2a-retry").toFile()
        try {
            val file = java.io.File(dir, "receipts.json")
            var attempts = 0
            fun receiver() = IrohAgentMessageReceiver(IrohAgentMessageRouter("self", maxPerSender = 2), { emptyList() },
                { _, _ -> attempts++; DeliveryOutcome(false, "application_input_rejected") }, { _, _ -> true }, file)
            repeat(2) { assertEquals("application_input_rejected", receiver().respond(message, "peer").reason) }
            assertEquals("per_sender_cap_exceeded", receiver().respond(message, "peer").reason)
            assertEquals(2, attempts)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun journalFailureAndCapacityFailClosedWithoutEvictingOldIdentities() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("a2a-capacity").toFile()
        try {
            val file = java.io.File(dir, "receipts.json")
            var attempts = 0
            fun receiver() = IrohAgentMessageReceiver(IrohAgentMessageRouter("self", maxReservations = 1), { emptyList() },
                { _, _ -> attempts++; DeliveryOutcome(false, "application_input_accepted") }, { _, _ -> true }, file)
            receiver().respond(message, "peer")
            assertEquals("reservation_capacity_exceeded", receiver().respond(message.copy(msgId = "other"), "peer").reason)
            assertEquals("duplicate_msg_id", receiver().respond(message, "peer").reason)
            for (corruption in listOf("broken", "{}", "{\"version\":99,\"reservations\":{},\"senderAttempts\":{}}")) {
                file.writeText(corruption)
                assertEquals("reservation_journal_failure", receiver().respond(message, "peer").reason)
            }
            val badPath = java.io.File(dir, "parent-file").apply { writeText("not a directory") }
            val blocked = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
                { _, _ -> attempts++; DeliveryOutcome(true) }, { _, _ -> true }, java.io.File(badPath, "receipts.json"))
            assertEquals("reservation_journal_failure", blocked.respond(message, "peer").reason)
            assertEquals(1, attempts)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun failedAtomicReplacementLeavesNoUnboundedTemporaryFiles() {
        val root = java.nio.file.Files.createTempDirectory("receipt-atomic").toFile()
        try {
            val destination = java.io.File(root, "state").apply { mkdirs() }
            java.io.File(destination, "existing").writeText("preserve")
            kotlin.test.assertFailsWith<Exception> {
                com.letta.mobile.data.controller.node.iroh.AtomicJsonFile.write(destination.toPath(), "{}", requireAtomic = true)
            }
            assertEquals(listOf("state"), root.listFiles()!!.map { it.name })
            assertEquals("preserve", java.io.File(destination, "existing").readText())
        } finally { root.deleteRecursively() }
    }

}
