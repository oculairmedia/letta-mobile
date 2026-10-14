package com.letta.mobile

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.feature.chat.route.AgentChatRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-by4vm.31: notification taps navigate only through a token issued when the
 * notification was posted; raw extras from another app never open a conversation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class NotificationLaunchTokensTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("letta_notification_launch_tokens", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun postedIntentCarriesOnlyTheToken() {
        val intent = NotificationNavigationTarget("agent-1", "conv-1", "Ada").createIntent(context)

        assertEquals(setOf(NotificationNavigationTarget.EXTRA_LAUNCH_TOKEN), intent.extras?.keySet())
    }

    @Test
    fun validTokenResolvesToChatRouteAndIsConsumedOnce() {
        val target = NotificationNavigationTarget("agent-1", "conv-1", "Ada")
        val token = target.createIntent(context).getStringExtra(NotificationNavigationTarget.EXTRA_LAUNCH_TOKEN)

        val first = tokenIntent(token)
        val resolved = AppLaunchTarget.fromIntent(first, context, automationExtrasEnabled = false)

        assertEquals(target, resolved)
        assertEquals(AgentChatRoute(agentId = "agent-1", agentName = "Ada", conversationId = "conv-1"), resolved?.toRoute())
        assertFalse(first.hasExtra(NotificationNavigationTarget.EXTRA_LAUNCH_TOKEN))
        assertNull(AppLaunchTarget.fromIntent(tokenIntent(token), context, automationExtrasEnabled = false))
    }

    @Test
    fun unknownTokenDoesNotNavigateEvenWithRawExtras() {
        val forged = tokenIntent("not-a-real-token").apply {
            putExtra(NotificationNavigationTarget.EXTRA_AGENT_ID, "agent-x")
            putExtra(NotificationNavigationTarget.EXTRA_CONVERSATION_ID, "conv-x")
        }

        assertNull(AppLaunchTarget.fromIntent(forged, context, automationExtrasEnabled = true))
    }

    @Test
    fun rawConversationExtrasIgnoredWhenAutomationDisabled() {
        assertNull(AppLaunchTarget.fromIntent(rawExtrasIntent(), context, automationExtrasEnabled = false))
    }

    @Test
    fun rawConversationExtrasHonouredForAutomationBuilds() {
        assertEquals(
            NotificationNavigationTarget("agent-x", "conv-x"),
            AppLaunchTarget.fromIntent(rawExtrasIntent(), context, automationExtrasEnabled = true),
        )
    }

    @Test
    fun tokenStoreStaysBoundedAndKeepsNewest() {
        val tokens = (1..70).map { i ->
            NotificationLaunchTokens.issue(context, NotificationNavigationTarget("agent-$i", "conv-$i"))
        }

        assertNull(NotificationLaunchTokens.redeem(context, tokens.first()))
        assertEquals(NotificationNavigationTarget("agent-70", "conv-70"), NotificationLaunchTokens.redeem(context, tokens.last()))
    }

    private fun tokenIntent(token: String?) = Intent(context, MainActivity::class.java)
        .putExtra(NotificationNavigationTarget.EXTRA_LAUNCH_TOKEN, token)

    private fun rawExtrasIntent() = Intent(context, MainActivity::class.java)
        .putExtra(NotificationNavigationTarget.EXTRA_AGENT_ID, "agent-x")
        .putExtra(NotificationNavigationTarget.EXTRA_CONVERSATION_ID, "conv-x")
}
