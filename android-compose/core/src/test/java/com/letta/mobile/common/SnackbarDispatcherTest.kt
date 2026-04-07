package com.letta.mobile.ui.common

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class SnackbarDispatcherTest : WordSpec({
    "SnackbarDispatcher" should {
        "dispatch a plain string message" {
            runTest {
                val dispatcher = SnackbarDispatcher()
                dispatcher.dispatch("Hello")
                val msg = dispatcher.messages.first()
                msg.message shouldBe "Hello"
                msg.actionLabel.shouldBeNull()
                msg.onAction.shouldBeNull()
            }
        }

        "dispatch a full message with action" {
            runTest {
                val dispatcher = SnackbarDispatcher()
                var actionCalled = false
                dispatcher.dispatch(SnackbarMessage("Deleted", "Undo") { actionCalled = true })
                val msg = dispatcher.messages.first()
                msg.message shouldBe "Deleted"
                msg.actionLabel shouldBe "Undo"
                msg.onAction?.invoke()
                actionCalled shouldBe true
            }
        }

        "queue multiple dispatches in order" {
            runTest {
                val dispatcher = SnackbarDispatcher()
                dispatcher.dispatch("First")
                dispatcher.dispatch("Second")
                dispatcher.messages.first().message shouldBe "First"
                dispatcher.messages.first().message shouldBe "Second"
            }
        }
    }
})
