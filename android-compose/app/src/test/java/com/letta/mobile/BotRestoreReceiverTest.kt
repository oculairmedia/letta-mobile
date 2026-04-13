package com.letta.mobile

import android.content.Intent
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe

class BotRestoreReceiverTest : WordSpec({
    "shouldRestoreBotFromBroadcast" should {
        "accept boot completed and package replaced broadcasts" {
            shouldRestoreBotFromBroadcast(Intent.ACTION_BOOT_COMPLETED) shouldBe true
            shouldRestoreBotFromBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED) shouldBe true
        }

        "ignore unrelated broadcasts" {
            shouldRestoreBotFromBroadcast(Intent.ACTION_SCREEN_ON) shouldBe false
            shouldRestoreBotFromBroadcast(null) shouldBe false
        }
    }
})
