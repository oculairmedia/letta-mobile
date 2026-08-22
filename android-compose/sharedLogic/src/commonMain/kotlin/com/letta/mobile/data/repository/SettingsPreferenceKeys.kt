package com.letta.mobile.data.repository

/** Non-secret preference keys persisted via [com.letta.mobile.data.storage.SettingsPreferencesStore]. */
internal object SettingsPreferenceKeys {
    const val THEME = "theme"
    const val THEME_PRESET = "theme_preset"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val AMOLED_DARK_MODE = "amoled_dark_mode"
    const val CHAT_BACKGROUND = "chat_background"
    const val PINNED_CONVERSATION_IDS = "pinned_conversation_ids"
    const val PINNED_AGENT_IDS = "pinned_agent_ids"
    const val PINNED_AGENT_ORDER = "pinned_agent_order"
    const val PINNED_PROJECT_IDS = "pinned_project_ids"
    const val CHAT_FONT_SCALE = "chat_font_scale"
    const val ENABLE_PROJECTS = "enable_projects"
    const val PINNED_SHORTCUT_ORDER = "pinned_shortcut_order"
    const val PINNED_ITEMS_ORDER = "pinned_items_order"
    const val PINNED_AGENT_NAMES = "pinned_agent_names"
    const val RESUME_RECENT_CONVERSATION = "resume_recent_conversation"
    const val HAPTICS_ENABLED = "haptics_enabled"
}

/** Secret / identity keys persisted via [com.letta.mobile.data.storage.SecureSettingsStore]. */
internal object SettingsSecureKeys {
    const val CONFIGS = "configs"
    const val ACTIVE_CONFIG_ID = "active_config_id"
    const val FAVORITE_AGENT_ID = "favorite_agent_id"
    const val ADMIN_AGENT_ID = "admin_agent_id"
    const val LAST_CHAT_SELECTION = LastChatSelectionStorage.KEY
    const val LAST_CHAT_AGENT_ID = LastChatSelectionStorage.LEGACY_AGENT_ID_KEY
    const val LAST_CHAT_AGENT_NAME = LastChatSelectionStorage.LEGACY_AGENT_NAME_KEY
    const val LAST_CHAT_CONVERSATION_ID = LastChatSelectionStorage.LEGACY_CONVERSATION_ID_KEY
    const val HUGGING_FACE_TOKEN = "hugging_face_token"
}
