package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.MessageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class A2aReservationTest {
    private val message = IrohAgentMessage("sender", "recipient", "body", "stable", 1)

    @Test
    fun duplicateOutcomesMatchReservationStateAndSenderClassification() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("a2a-duplicate-states").toFile()
        try {
            val sender = IrohAgentMessageSender(
                endpointProvider = { error("no dial") },
                resolver = IrohAgentAddressResolver(HostEndpointAddressStore(java.io.File(dir, "addresses"))),
            )
            for (state in IrohAgentMessageRouter.ReservationState.entries) {
                val router = IrohAgentMessageRouter("self")
                router.route("sender", "stable", emptyList())
                router.settle(AgentId("sender"), MessageId("stable"), state)
                var dispatches = 0
                val receiver = IrohAgentMessageReceiver(router, { emptyList() },
                    { _, _ -> dispatches++; DeliveryOutcome(false) }, { _, _ -> true })
                val outcome = receiver.respond(message, "sender")
                val persisted = state == IrohAgentMessageRouter.ReservationState.PERSISTED
                assertEquals(persisted, outcome.delivered, state.name)
                assertEquals(if (persisted) "duplicate_persisted" else "duplicate_msg_id", outcome.reason, state.name)
                val ack = IrohAgentMessageAck.decode(IrohAgentMessageAck(message.msgId, true, outcome.delivered, outcome.reason).encode())
                val result = sender.classifyAck(message, ack)
                if (persisted) assertEquals(AgentSendResult.Delivered(message.msgId), result)
                else assertEquals(AgentSendResult.Accepted(message.msgId, message.toAgentId, "duplicate_msg_id"), result)
                assertEquals(0, dispatches, state.name)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun persistedReceiptSurvivesJournalRestartWithoutDispatch() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("a2a-persisted").toFile()
        try {
            val file = java.io.File(dir, "receipts.json")
            var attempts = 0
            fun receiver() = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
                { _, _ -> attempts++; DeliveryOutcome(false, "application_input_accepted") }, { _, _ -> true }, file)
            val first = receiver()
            assertEquals(DeliveryOutcome(false, "application_input_accepted"), first.respond(message, "sender"))
            first.markPersisted(AgentId("sender"), MessageId("stable"))
            assertEquals(DeliveryOutcome(true, "duplicate_persisted"), first.respond(message, "sender"))
            assertEquals(DeliveryOutcome(true, "duplicate_persisted"), receiver().respond(message, "sender"))
            assertEquals(1, attempts)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun authenticatedSendersShareMessageIdsWithoutSharingReservationsAcrossRestart() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("a2a-senders").toFile()
        try {
            val file = java.io.File(dir, "receipts.json")
            val attempts = mutableListOf<String>()
            fun receiver() = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
                { msg, _ -> attempts += msg.fromAgentId; DeliveryOutcome(false, "application_input_accepted") },
                { msg, peer -> peer == msg.fromAgentId }, file)
            assertEquals("application_input_accepted", receiver().respond(message, "sender").reason)
            val other = message.copy(fromAgentId = "other")
            assertEquals("application_input_accepted", receiver().respond(other, "other").reason)
            for (msg in listOf(message, other)) {
                assertEquals("duplicate_msg_id", receiver().respond(msg, msg.fromAgentId).reason)
            }
            assertEquals(listOf("sender", "other"), attempts)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun concurrentCrossSenderReleaseDoesNotReleaseAnAcceptedReservation() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("a2a-concurrent-senders").toFile()
        try {
            val file = java.io.File(dir, "receipts.json")
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val attempts = mutableListOf<String>()
            fun receiver() = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
                { msg, _ ->
                    attempts += msg.fromAgentId
                    if (msg.fromAgentId == "sender") {
                        entered.complete(Unit)
                        finish.await()
                        DeliveryOutcome(false, "application_input_accepted")
                    } else DeliveryOutcome(false, "application_input_rejected")
                }, { msg, peer -> msg.fromAgentId == peer }, file)
            val receiver = receiver()
            val first = async(kotlinx.coroutines.Dispatchers.Default) { receiver.respond(message, "sender") }
            entered.await()
            val other = message.copy(fromAgentId = "other")
            repeat(2) { assertEquals("application_input_rejected", receiver.respond(other, "other").reason) }
            assertEquals("duplicate_msg_id", receiver.respond(message, "sender").reason)
            finish.complete(Unit)
            assertEquals("application_input_accepted", first.await().reason)
            assertEquals("duplicate_msg_id", receiver().respond(message, "sender").reason)
            assertEquals("application_input_rejected", receiver().respond(other, "other").reason)
            assertEquals(listOf("sender", "other", "other", "other"), attempts)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun legacySenderlessJournalFailsClosedForEverySender() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("a2a-legacy").toFile()
        try {
            val file = java.io.File(dir, "receipts.json")
            for (state in listOf("RESERVED", "ACCEPTED", "AMBIGUOUS", "PERSISTED")) {
                file.writeText("""{"version":1,"reservations":{"stable":"$state"},"senderAttempts":{"sender":1}}""")
                val original = file.readText()
                var attempts = 0
                val receiver = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
                    { _, _ -> attempts++; DeliveryOutcome(true) }, { _, _ -> true }, file)
                for (sender in listOf("sender", "other")) {
                    assertEquals("reservation_journal_failure", receiver.respond(message.copy(fromAgentId = sender), sender).reason)
                }
                assertEquals(0, attempts)
                assertEquals(original, file.readText())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun failedReleaseAndRestoreBeforeRenameRemainSuppressiveAfterRestart() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("a2a-restore-failure").toFile()
        try {
            var refuseRestore = false
            val file = object : java.io.File(dir, "receipts.json") {
                override fun toPath(): java.nio.file.Path {
                    if (refuseRestore) throw java.io.IOException("restore failed before rename")
                    return super.toPath()
                }
            }
            var attempts = 0
            val receiver = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
                { _, _ -> attempts++; DeliveryOutcome(false, "application_input_rejected") }, { _, _ -> true }, file)
            receiver.journalDirectorySyncSupported = true
            receiver.journalDirectorySync = { _ ->
                if (file.exists() && file.readText().contains("\"reservations\":{}")) {
                    refuseRestore = true
                    throw java.io.IOException("release directory sync failed after rename")
                }
            }
            assertEquals("reservation_journal_failure", receiver.respond(message, "peer").reason)
            assertEquals(true, refuseRestore, "fault must occur after release was renamed")
            val restarted = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
                { _, _ -> attempts++; DeliveryOutcome(true) }, { _, _ -> true }, java.io.File(dir, "receipts.json"))
            assertEquals("reservation_journal_failure", restarted.respond(message, "peer").reason)
            assertEquals(1, attempts, "restart must not execute after the failed release and failed restore")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun cancelledDispatchRemainsAmbiguousAndCannotBeExecutedAgain() = runTest {
        val entered = CompletableDeferred<Unit>()
        var attempts = 0
        val receiver = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
            { _, _ -> attempts++; entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }, { _, _ -> true })
        val dispatch = async { receiver.respond(message, "peer") }
        entered.await()
        dispatch.cancel()
        dispatch.join()
        assertEquals("duplicate_msg_id", receiver.respond(message, "peer").reason)
        assertEquals(1, attempts)
    }

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
                file.writeText("""{"version":2,"reservations":{"sender":{"stable":"RESERVED"}},"senderAttempts":{"sender":1}}""")
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

    @Test
    fun failedDirectorySyncOnReleaseKeepsTheReservationSuppressive() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("a2a-release-sync").toFile()
        try {
            val file = java.io.File(dir, "receipts.json")
            var attempts = 0
            val receiver = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
                { _, _ -> attempts++; DeliveryOutcome(false, "application_input_rejected") }, { _, _ -> true }, file)
            receiver.journalDirectorySyncSupported = true
            receiver.journalDirectorySync = { _ ->
                if (file.exists() && file.readText().contains("\"reservations\":{}")) {
                    throw java.io.IOException("directory sync failed after release rename")
                }
            }
            assertEquals("reservation_journal_failure", receiver.respond(message, "peer").reason)
            val restarted = IrohAgentMessageReceiver(IrohAgentMessageRouter("self"), { emptyList() },
                { _, _ -> attempts++; DeliveryOutcome(false, "application_input_rejected") }, { _, _ -> true }, file)
            assertEquals("duplicate_msg_id", restarted.respond(message, "peer").reason)
            assertEquals(1, attempts)
        } finally { dir.deleteRecursively() }
    }

}
