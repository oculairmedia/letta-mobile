package com.letta.mobile.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.Grid2x2
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.LayoutDashboard
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.TextSearch
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Unlink
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Workflow
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.X

/**
 * Centralized icon mapping for Letta Mobile.
 *
 * Uses Lucide icons (stroke style) for a modern, consistent visual identity.
 * All screens should reference [LettaIcons] instead of Material `Icons` directly.
 */
object LettaIcons {
    // Navigation
    val ArrowBack: ImageVector get() = Lucide.ArrowLeft
    val Close: ImageVector get() = Lucide.X
    val Menu: ImageVector get() = Lucide.Menu
    val ChevronRight: ImageVector get() = Lucide.ChevronRight
    val ChevronDown: ImageVector get() = Lucide.ChevronDown
    val ChevronUp: ImageVector get() = Lucide.ChevronUp
    val ExpandMore: ImageVector get() = Lucide.ChevronDown
    val ExpandLess: ImageVector get() = Lucide.ChevronUp
    val ArrowDropDown: ImageVector get() = Lucide.ChevronDown
    val ArrowDropUp: ImageVector get() = Lucide.ChevronUp
    val KeyboardArrowDown: ImageVector get() = Lucide.ChevronDown
    val ListIcon: ImageVector get() = Lucide.List

    // Actions
    val Add: ImageVector get() = Lucide.Plus
    val Edit: ImageVector get() = Lucide.Pencil
    val Delete: ImageVector get() = Lucide.Trash2
    val Save: ImageVector get() = Lucide.Save
    val Search: ImageVector get() = Lucide.Search
    val Clear: ImageVector get() = Lucide.X
    val Refresh: ImageVector get() = Lucide.RefreshCw
    val Send: ImageVector get() = Lucide.Send
    val Copy: ImageVector get() = Lucide.Copy
    val Share: ImageVector get() = Lucide.Share2
    val MoreVert: ImageVector get() = Lucide.Ellipsis
    val Play: ImageVector get() = Lucide.Play
    val ManageSearch: ImageVector get() = Lucide.TextSearch

    // Status
    val Check: ImageVector get() = Lucide.Check
    val CheckCircle: ImageVector get() = Lucide.CircleCheck
    val Error: ImageVector get() = Lucide.CircleAlert
    val Info: ImageVector get() = Lucide.CircleAlert
    val Help: ImageVector get() = Lucide.CircleHelp
    val Circle: ImageVector get() = Lucide.Circle

    // Objects / Domain
    val Agent: ImageVector get() = Lucide.Bot
    val Tool: ImageVector get() = Lucide.Wrench
    val Chat: ImageVector get() = Lucide.MessageSquare
    val ChatOutline: ImageVector get() = Lucide.MessageCircle
    val Settings: ImageVector get() = Lucide.Settings
    val Key: ImageVector get() = Lucide.Key
    val People: ImageVector get() = Lucide.Users
    val Cloud: ImageVector get() = Lucide.Cloud
    val Storage: ImageVector get() = Lucide.HardDrive
    val Code: ImageVector get() = Lucide.Code
    val Schema: ImageVector get() = Lucide.Workflow
    val Dashboard: ImageVector get() = Lucide.LayoutDashboard
    val Apps: ImageVector get() = Lucide.Grid2x2
    val ViewModule: ImageVector get() = Lucide.Grid2x2
    val FileOpen: ImageVector get() = Lucide.FileText
    val Inventory: ImageVector get() = Lucide.Package
    val Archive: ImageVector get() = Lucide.Archive
    val ForkRight: ImageVector get() = Lucide.GitFork
    val Database: ImageVector get() = Lucide.Database

    // Decorative
    val Favorite: ImageVector get() = Lucide.Heart
    val FavoriteBorder: ImageVector get() = Lucide.Heart
    val Star: ImageVector get() = Lucide.Star
    val Sparkles: ImageVector get() = Lucide.Sparkles
    val AutoAwesome: ImageVector get() = Lucide.Sparkles
    val Lightbulb: ImageVector get() = Lucide.Lightbulb
    val Psychology: ImageVector get() = Lucide.Brain
    val AccountCircle: ImageVector get() = Lucide.Circle
    val AccessTime: ImageVector get() = Lucide.Clock

    // Links
    val Link: ImageVector get() = Lucide.Link
    val LinkOff: ImageVector get() = Lucide.Unlink
    val ExternalLink: ImageVector get() = Lucide.ExternalLink
}
