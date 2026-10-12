package com.letta.mobile.ui.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.feature.chat.route.AgentChatRoute
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The compact AdaptiveScaffold pads each destination once (letta-mobile-ckmub.2.1): shared pages in
 * LettaPageScaffold pad themselves, chat and canvas inset only their own controls, everything else is
 * padded by the scaffold. The page routes match by serial name, routes with arguments included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class DestinationInsetPolicyTest {

    private val destinations: Map<String, NavDestination> by lazy {
        val nav = NavHostController(ApplicationProvider.getApplicationContext())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.createGraph(startDestination = ConfigListRoute) {
            composable<ConfigListRoute> {}
            composable<AdminRoute> {}
            composable<ChannelsRoute> {}
            composable<MemoryRoute> {}
            composable<SkillsRoute> {}
            composable<AllToolsRoute> {}
            composable<ConversationsRoute> {}
            composable<AgentChatRoute> {}
        }.associateBy { it.route.orEmpty().substringBefore('/').substringBefore('?').substringAfterLast('.') }
    }

    private fun policyOf(name: String) = destinations.getValue(name).insetPolicy()

    @Test
    fun sharedPagesOwnTheirInsets() {
        listOf("AdminRoute", "ChannelsRoute", "MemoryRoute", "SkillsRoute", "AllToolsRoute").forEach {
            assertEquals(it, DestinationInsetPolicy.PageScaffold, policyOf(it))
        }
    }

    @Test
    fun chatInsetsItsOwnControlsAndLegacyScreensArePaddedByTheScaffold() {
        assertEquals(DestinationInsetPolicy.SelfVertical, policyOf("ConversationsRoute"))
        assertEquals(DestinationInsetPolicy.SelfVertical, policyOf("AgentChatRoute"))
        assertEquals(DestinationInsetPolicy.Host, policyOf("ConfigListRoute"))
        assertEquals(DestinationInsetPolicy.Host, (null as NavDestination?).insetPolicy())
    }
}
