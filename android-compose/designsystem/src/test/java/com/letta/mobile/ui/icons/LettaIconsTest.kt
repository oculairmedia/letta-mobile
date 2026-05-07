package com.letta.mobile.ui.icons

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class LettaIconsTest {

    @Test
    fun `all navigation icons are non-null`() {
        assertNotNull(LettaIcons.ArrowBack)
        assertNotNull(LettaIcons.Close)
        assertNotNull(LettaIcons.Menu)
        assertNotNull(LettaIcons.ChevronRight)
        assertNotNull(LettaIcons.ChevronDown)
        assertNotNull(LettaIcons.ChevronUp)
        assertNotNull(LettaIcons.ExpandMore)
        assertNotNull(LettaIcons.ExpandLess)
        assertNotNull(LettaIcons.ArrowDropDown)
        assertNotNull(LettaIcons.ArrowDropUp)
        assertNotNull(LettaIcons.KeyboardArrowDown)
        assertNotNull(LettaIcons.ListIcon)
    }

    @Test
    fun `all action icons are non-null`() {
        assertNotNull(LettaIcons.Add)
        assertNotNull(LettaIcons.Edit)
        assertNotNull(LettaIcons.Delete)
        assertNotNull(LettaIcons.Save)
        assertNotNull(LettaIcons.Search)
        assertNotNull(LettaIcons.Clear)
        assertNotNull(LettaIcons.Refresh)
        assertNotNull(LettaIcons.Send)
        assertNotNull(LettaIcons.Copy)
        assertNotNull(LettaIcons.Share)
        assertNotNull(LettaIcons.MoreVert)
        assertNotNull(LettaIcons.Play)
        assertNotNull(LettaIcons.ManageSearch)
    }

    @Test
    fun `all status icons are non-null`() {
        assertNotNull(LettaIcons.Check)
        assertNotNull(LettaIcons.CheckCircle)
        assertNotNull(LettaIcons.Error)
        assertNotNull(LettaIcons.Info)
        assertNotNull(LettaIcons.Help)
        assertNotNull(LettaIcons.Circle)
    }

    @Test
    fun `all domain icons are non-null`() {
        assertNotNull(LettaIcons.Agent)
        assertNotNull(LettaIcons.Tool)
        assertNotNull(LettaIcons.Chat)
        assertNotNull(LettaIcons.ChatOutline)
        assertNotNull(LettaIcons.Settings)
        assertNotNull(LettaIcons.Key)
        assertNotNull(LettaIcons.People)
        assertNotNull(LettaIcons.Cloud)
        assertNotNull(LettaIcons.Storage)
        assertNotNull(LettaIcons.Code)
        assertNotNull(LettaIcons.Schema)
        assertNotNull(LettaIcons.Dashboard)
        assertNotNull(LettaIcons.Apps)
        assertNotNull(LettaIcons.ViewModule)
        assertNotNull(LettaIcons.FileOpen)
        assertNotNull(LettaIcons.Inventory)
        assertNotNull(LettaIcons.Archive)
        assertNotNull(LettaIcons.ForkRight)
        assertNotNull(LettaIcons.Database)
    }

    @Test
    fun `all decorative icons are non-null`() {
        assertNotNull(LettaIcons.Favorite)
        assertNotNull(LettaIcons.FavoriteBorder)
        assertNotNull(LettaIcons.Star)
        assertNotNull(LettaIcons.Sparkles)
        assertNotNull(LettaIcons.AutoAwesome)
        assertNotNull(LettaIcons.Lightbulb)
        assertNotNull(LettaIcons.Psychology)
        assertNotNull(LettaIcons.AccountCircle)
        assertNotNull(LettaIcons.AccessTime)
    }

    @Test
    fun `all link icons are non-null`() {
        assertNotNull(LettaIcons.Link)
        assertNotNull(LettaIcons.LinkOff)
        assertNotNull(LettaIcons.ExternalLink)
    }

    @Test
    fun `all pin icons are non-null`() {
        assertNotNull(LettaIcons.Pin)
        assertNotNull(LettaIcons.PinOff)
    }

    @Test
    fun `ChevronRight is not the same object as ChevronDown`() {
        // They map to different Lucide icons
        assertTrue(LettaIcons.ChevronRight !== LettaIcons.ChevronDown)
    }

    @Test
    fun `Check and CheckCircle use different icons`() {
        assertTrue(LettaIcons.Check !== LettaIcons.CheckCircle)
    }

    @Test
    fun `Favorite and FavoriteBorder are the same Lucide Heart`() {
        // By design, both map to Heart for simplicity
        assertTrue(LettaIcons.Favorite === LettaIcons.FavoriteBorder)
    }
}
