package com.letta.mobile.ui.screens.dashboard

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.letta.mobile.R
import com.letta.mobile.ui.icons.LettaIcons

/**
 * Every destination reachable from the Home drawer.
 *
 * Enum ordinal determines default drawer order.
 * [group] controls divider placement (matches existing drawer layout).
 * [descriptionResId] provides a short subtitle for widget tiles (0 = use live count instead).
 * Enum [name] is the serialization key for persistence.
 */
enum class DashboardShortcut(
    val icon: ImageVector,
    @StringRes val labelResId: Int,
    val group: Group,
    @StringRes val descriptionResId: Int = 0,
) {
    // --- Group PRIMARY (above first divider) ---
    // descriptionResId = 0 → contextual info comes from live counts in DashboardUiState
    CONVERSATIONS(LettaIcons.Chat, R.string.common_conversations, Group.PRIMARY),
    AGENTS(LettaIcons.People, R.string.common_agents, Group.PRIMARY),
    TOOLS(LettaIcons.Tool, R.string.common_tools, Group.PRIMARY),
    BLOCKS(LettaIcons.ViewModule, R.string.screen_nav_blocks, Group.PRIMARY),

    // --- Group SECONDARY (between dividers) ---
    TEMPLATES(LettaIcons.Dashboard, R.string.screen_nav_templates, Group.SECONDARY, R.string.widget_desc_templates),
    ARCHIVES(LettaIcons.Storage, R.string.screen_nav_archives, Group.SECONDARY, R.string.widget_desc_archives),
    FOLDERS(LettaIcons.ManageSearch, R.string.screen_nav_folders, Group.SECONDARY, R.string.widget_desc_folders),
    GROUPS(LettaIcons.ForkRight, R.string.screen_nav_groups, Group.SECONDARY, R.string.widget_desc_groups),
    PROVIDERS(LettaIcons.Cloud, R.string.screen_nav_providers, Group.SECONDARY, R.string.widget_desc_providers),
    IDENTITIES(LettaIcons.AccountCircle, R.string.screen_nav_identities, Group.SECONDARY, R.string.widget_desc_identities),
    SCHEDULES(LettaIcons.AccessTime, R.string.screen_nav_schedules, Group.SECONDARY, R.string.widget_desc_schedules),
    RUNS(LettaIcons.ChatOutline, R.string.screen_nav_runs, Group.SECONDARY, R.string.widget_desc_runs),
    JOBS(LettaIcons.AccessTime, R.string.screen_nav_jobs, Group.SECONDARY, R.string.widget_desc_jobs),
    MESSAGE_BATCHES(LettaIcons.ChatOutline, R.string.screen_nav_message_batches, Group.SECONDARY, R.string.widget_desc_message_batches),
    MCP_SERVERS(LettaIcons.Cloud, R.string.screen_nav_mcp_servers, Group.SECONDARY, R.string.widget_desc_mcp_servers),
    BOT_SETTINGS(LettaIcons.Agent, R.string.screen_nav_bot_settings, Group.SECONDARY, R.string.widget_desc_bot_settings),
    PROJECTS(LettaIcons.Apps, R.string.screen_projects_title, Group.SECONDARY, R.string.widget_desc_projects),
    MODELS(LettaIcons.Sparkles, R.string.screen_models_title, Group.SECONDARY, R.string.widget_desc_models),

    USAGE(LettaIcons.Database, R.string.screen_nav_usage, Group.SECONDARY, R.string.widget_desc_usage),
    FAVORITE_AGENT(LettaIcons.Star, R.string.screen_nav_favorite_agent, Group.SECONDARY, R.string.widget_desc_favorite_agent),

    // --- Group UTILITY (below second divider) ---
    SETTINGS(LettaIcons.Settings, R.string.common_settings, Group.UTILITY, R.string.widget_desc_settings),
    TELEMETRY(LettaIcons.Database, R.string.screen_nav_telemetry, Group.UTILITY, R.string.widget_desc_telemetry),
    SYSTEM_ACCESS(
        LettaIcons.Key,
        R.string.screen_system_access_title,
        Group.UTILITY,
        R.string.widget_desc_system_access,
    ),
    ABOUT(LettaIcons.Info, R.string.screen_about_title, Group.UTILITY, R.string.widget_desc_about);

    enum class Group { PRIMARY, SECONDARY, UTILITY }
}
