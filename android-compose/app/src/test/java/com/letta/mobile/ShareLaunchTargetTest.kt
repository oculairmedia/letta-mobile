package com.letta.mobile

import android.content.Intent
import android.net.Uri
import com.letta.mobile.ui.navigation.ShareToAgentRoute
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareLaunchTargetTest {
    @Test
    fun `creates share launch target from text send intent`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Interesting article")
            putExtra(Intent.EXTRA_TEXT, "https://example.com/post")
        }

        val target = ShareLaunchTarget.fromIntent(intent)

        target shouldNotBe null
        target?.toRoute() shouldBe ShareToAgentRoute(
            sharedText = "Interesting article\n\nhttps://example.com/post",
        )
    }

    @Test
    fun `creates share launch target from stream send intent`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example/shared-note/1"))
        }

        val target = ShareLaunchTarget.fromIntent(intent)

        target shouldNotBe null
        target?.toRoute() shouldBe ShareToAgentRoute(
            sharedText = "Shared file:\n- content://example/shared-note/1 (text/plain)",
        )
    }

    @Test
    fun `ignores unrelated intent`() {
        ShareLaunchTarget.fromIntent(Intent(Intent.ACTION_VIEW)) shouldBe null
    }
}
