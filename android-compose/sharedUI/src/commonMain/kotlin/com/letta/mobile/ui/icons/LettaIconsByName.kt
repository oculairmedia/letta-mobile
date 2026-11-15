package com.letta.mobile.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The glyph an agent-authored surface names by string (`"name": "ChevronRight"`), or null when the
 * name is not one Letta draws. Names match case- and punctuation-insensitively, so `chevron_right`
 * and `ChevronRight` are the same glyph. The set is the public A2UI icon vocabulary; add a name here
 * (and to the a2ui README) to make it available to agents.
 */
internal fun lettaIconByName(name: String): ImageVector? = LettaIconsByName[name.iconNameKey()]

private val LettaIconsByName = mapOf(
    "ArrowBack" to LettaIcons.ArrowBack,
    "Close" to LettaIcons.Close,
    "Menu" to LettaIcons.Menu,
    "ChevronRight" to LettaIcons.ChevronRight,
    "ChevronDown" to LettaIcons.ChevronDown,
    "ChevronUp" to LettaIcons.ChevronUp,
    "ExpandMore" to LettaIcons.ExpandMore,
    "ExpandLess" to LettaIcons.ExpandLess,
    "ArrowDropDown" to LettaIcons.ArrowDropDown,
    "ArrowDropUp" to LettaIcons.ArrowDropUp,
    "KeyboardArrowDown" to LettaIcons.KeyboardArrowDown,
    "ListIcon" to LettaIcons.ListIcon,
    "Add" to LettaIcons.Add,
    "Edit" to LettaIcons.Edit,
    "Delete" to LettaIcons.Delete,
    "Save" to LettaIcons.Save,
    "Search" to LettaIcons.Search,
    "Clear" to LettaIcons.Clear,
    "Refresh" to LettaIcons.Refresh,
    "Send" to LettaIcons.Send,
    "Copy" to LettaIcons.Copy,
    "Share" to LettaIcons.Share,
    "MoreVert" to LettaIcons.MoreVert,
    "Play" to LettaIcons.Play,
    "ManageSearch" to LettaIcons.ManageSearch,
    "Check" to LettaIcons.Check,
    "CheckCircle" to LettaIcons.CheckCircle,
    "Error" to LettaIcons.Error,
    "Info" to LettaIcons.Info,
    "Help" to LettaIcons.Help,
    "Circle" to LettaIcons.Circle,
    "Agent" to LettaIcons.Agent,
    "Tool" to LettaIcons.Tool,
    "Chat" to LettaIcons.Chat,
    "ChatOutline" to LettaIcons.ChatOutline,
    "Settings" to LettaIcons.Settings,
    "Key" to LettaIcons.Key,
    "People" to LettaIcons.People,
    "Cloud" to LettaIcons.Cloud,
    "Storage" to LettaIcons.Storage,
    "Code" to LettaIcons.Code,
    "Schema" to LettaIcons.Schema,
    "Dashboard" to LettaIcons.Dashboard,
    "Apps" to LettaIcons.Apps,
    "ViewModule" to LettaIcons.ViewModule,
    "FileOpen" to LettaIcons.FileOpen,
    "Inventory" to LettaIcons.Inventory,
    "Archive" to LettaIcons.Archive,
    "ForkRight" to LettaIcons.ForkRight,
    "Database" to LettaIcons.Database,
    "Favorite" to LettaIcons.Favorite,
    "FavoriteBorder" to LettaIcons.FavoriteBorder,
    "Star" to LettaIcons.Star,
    "Sparkles" to LettaIcons.Sparkles,
    "AutoAwesome" to LettaIcons.AutoAwesome,
    "Lightbulb" to LettaIcons.Lightbulb,
    "Psychology" to LettaIcons.Psychology,
    "AccountCircle" to LettaIcons.AccountCircle,
    "AccessTime" to LettaIcons.AccessTime,
    "Link" to LettaIcons.Link,
    "LinkOff" to LettaIcons.LinkOff,
    "ExternalLink" to LettaIcons.ExternalLink,
    "Pin" to LettaIcons.Pin,
    "PinOff" to LettaIcons.PinOff,
).mapKeys { (name, _) -> name.iconNameKey() }

private fun String.iconNameKey(): String = filter { it.isLetterOrDigit() }.lowercase()
