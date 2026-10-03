package com.letta.mobile.ui.chat.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatDockGeometryMathTest {
    private val limits = ChatDockLimits(
        minWidthDp = 300f,
        minHeightDp = 200f,
        maxWidthDp = 1000f,
        maxHeightDp = 900f,
        marginDp = 10f,
        defaultWidthDp = 760f,
        defaultHeightFraction = 0.45f,
    )

    private fun frame(width: Float = 1200f, height: Float = 800f, collapsed: Float = 60f) =
        ChatDockFrame(width, height, limits, collapsed)

    private fun assertInside(rect: ChatDockRect, frame: ChatDockFrame) {
        assertTrue(rect.left >= limits.marginDp - EPS, "left $rect")
        assertTrue(rect.top >= limits.marginDp - EPS, "top $rect")
        assertTrue(rect.right <= frame.containerWidthDp - limits.marginDp + EPS, "right $rect")
        assertTrue(rect.bottom <= frame.containerHeightDp - limits.marginDp + EPS, "bottom $rect")
    }

    @Test
    fun aTopInsetKeepsRoomAboveThePanelOnly() {
        val inset = ChatDockFrame(1200f, 800f, limits.copy(topInsetDp = 30f), 60f)
        val top = ChatDockGeometryMath.rect(ChatDockGeometry(anchorY = 0f, heightDp = 2000f), inset)
        assertEquals(40f, top.top, EPS)
        assertEquals(790f, top.bottom, EPS)
        val dragged = ChatDockGeometryMath.drag(ChatDockGeometry.Default, 0f, -5000f, inset)
        assertEquals(40f, ChatDockGeometryMath.rect(dragged, inset).top, EPS)
        // The bottom keeps the plain margin: the default panel sits where it always did.
        assertEquals(790f, ChatDockGeometryMath.rect(ChatDockGeometry.Default, inset).bottom, EPS)
    }

    @Test
    fun theDefaultIsBottomCentreAtTheDefaultSize() {
        val rect = ChatDockGeometryMath.rect(ChatDockGeometry.Default, frame())
        assertEquals(760f, rect.width, EPS)
        assertEquals(360f, rect.height, EPS)
        assertEquals(220f, rect.left, EPS) // (1200 - 760) / 2
        assertEquals(790f, rect.bottom, EPS)
    }

    @Test
    fun onAPhoneTheDefaultIsAFullWidthBottomPanel() {
        val phone = frame(width = 400f, height = 800f)
        val rect = ChatDockGeometryMath.rect(ChatDockGeometry.Default, phone)
        assertEquals(380f, rect.width, EPS)
        assertEquals(10f, rect.left, EPS)
        assertEquals(790f, rect.bottom, EPS)
    }

    @Test
    fun aShrinkingContainerClampsThePanelInsideWithoutRewritingTheGeometry() {
        val geometry = ChatDockGeometry(anchorX = 1f, anchorY = 0f, widthDp = 900f, heightDp = 700f)
        val small = frame(width = 500f, height = 400f)
        val rect = ChatDockGeometryMath.rect(geometry, small)
        assertInside(rect, small)
        assertEquals(480f, rect.width, EPS)
        assertEquals(380f, rect.height, EPS)
        // Growing back restores the chosen size.
        val big = frame(width = 1600f, height = 1000f)
        val restored = ChatDockGeometryMath.rect(geometry, big)
        assertEquals(900f, restored.width, EPS)
        assertEquals(700f, restored.height, EPS)
        assertEquals(1590f, restored.right, EPS)
        assertEquals(10f, restored.top, EPS)
    }

    @Test
    fun dragMovesThePanelAndStopsAtTheEdges() {
        val f = frame()
        val start = ChatDockGeometryMath.rect(ChatDockGeometry.Default, f)
        val moved = ChatDockGeometryMath.drag(ChatDockGeometry.Default, -100f, -200f, f)
        val rect = ChatDockGeometryMath.rect(moved, f)
        assertEquals(start.left - 100f, rect.left, EPS)
        assertEquals(start.top - 200f, rect.top, EPS)
        assertEquals(start.width, rect.width, EPS)

        val flung = ChatDockGeometryMath.drag(moved, -5000f, -5000f, f)
        assertEquals(0f, flung.anchorX, EPS)
        assertEquals(0f, flung.anchorY, EPS)
        assertInside(ChatDockGeometryMath.rect(flung, f), f)
    }

    @Test
    fun dragOfACollapsedBarUsesItsMeasuredHeight() {
        val f = frame(collapsed = 60f)
        val collapsed = ChatDockGeometryMath.collapse(ChatDockGeometry.Default)
        val rect = ChatDockGeometryMath.rect(collapsed, f)
        assertEquals(60f, rect.height, EPS)
        assertEquals(790f, rect.bottom, EPS)
        val up = ChatDockGeometryMath.rect(ChatDockGeometryMath.drag(collapsed, 0f, -300f, f), f)
        assertEquals(rect.top - 300f, up.top, EPS)
    }

    @Test
    fun aDraggedCollapsedBarOpensWithItsComposerBarWhereItWas() {
        val f = frame(collapsed = 60f)
        val sized = ChatDockGeometry(anchorX = 0.5f, anchorY = 1f, heightDp = 400f)
        val collapsed = ChatDockGeometryMath.collapse(sized, f)
        val bar = ChatDockGeometryMath.rect(collapsed, f)

        val moved = ChatDockGeometryMath.drag(collapsed, 0f, -200f, f)
        val movedBar = ChatDockGeometryMath.rect(moved, f)
        assertEquals(bar.top - 200f, movedBar.top, EPS)

        // Opening it again puts the panel's composer bar exactly where the collapsed bar was.
        val open = ChatDockGeometryMath.rect(ChatDockGeometryMath.expand(moved, f), f)
        assertEquals(400f, open.height, EPS)
        assertEquals(movedBar.bottom, open.bottom, EPS)
        assertInside(open, f)
    }

    @Test
    fun collapsingLeavesTheBarWhereTheOpenPanelsBarWas() {
        val f = frame(collapsed = 60f)
        val sized = ChatDockGeometry(anchorX = 0.2f, anchorY = 0.3f, heightDp = 400f)
        val open = ChatDockGeometryMath.rect(sized, f)
        val bar = ChatDockGeometryMath.rect(ChatDockGeometryMath.collapse(sized, f), f)
        assertEquals(60f, bar.height, EPS)
        assertEquals(open.bottom, bar.bottom, EPS)
        assertEquals(open.left, bar.left, EPS)
        assertEquals(open.width, bar.width, EPS)
    }

    @Test
    fun aCollapsedBarMovesAllTheWayToTheTop() {
        val f = frame(collapsed = 60f)
        val collapsed = ChatDockGeometryMath.collapse(ChatDockGeometry(heightDp = 400f), f)
        val top = ChatDockGeometryMath.rect(ChatDockGeometryMath.drag(collapsed, 0f, -5000f, f), f)
        // Its own top edge stops at the margin, not the expanded panel's.
        assertEquals(limits.marginDp, top.top, EPS)
        assertInside(top, f)
    }

    @Test
    fun aBarNearTheTopOpensDownwardOnTheCanvas() {
        val f = frame(collapsed = 60f)
        val collapsed = ChatDockGeometryMath.collapse(ChatDockGeometry(heightDp = 400f), f)
        val atTop = ChatDockGeometryMath.drag(collapsed, 0f, -5000f, f)
        val bar = ChatDockGeometryMath.rect(atTop, f)
        val opened = ChatDockGeometryMath.expand(atTop, f)
        assertFalse(opened.collapsed)
        assertEquals(0f, opened.anchorY, EPS)
        val open = ChatDockGeometryMath.rect(opened, f)
        // No room above the bar: the panel's top stays at the bar's and it grows down.
        assertEquals(bar.top, open.top, EPS)
        assertEquals(400f, open.height, EPS)
        assertInside(open, f)
    }

    @Test
    fun resizeKeepsTheOppositeEdgeAndHonoursMinAndMax() {
        val f = frame()
        val start = ChatDockGeometryMath.rect(ChatDockGeometry.Default, f)
        val wider = ChatDockGeometryMath.resize(ChatDockGeometry.Default, ChatDockEdge.Right, ChatDockDelta(100f, 0f), f)
        val widerRect = ChatDockGeometryMath.rect(wider, f)
        assertEquals(start.left, widerRect.left, EPS)
        assertEquals(860f, widerRect.width, EPS)

        val tiny = ChatDockGeometryMath.resize(ChatDockGeometry.Default, ChatDockEdge.TopLeft, ChatDockDelta(2000f, 2000f), f)
        val tinyRect = ChatDockGeometryMath.rect(tiny, f)
        assertEquals(300f, tinyRect.width, EPS)
        assertEquals(200f, tinyRect.height, EPS)
        assertEquals(start.right, tinyRect.right, EPS)
        assertEquals(start.bottom, tinyRect.bottom, EPS)

        val huge = ChatDockGeometryMath.resize(ChatDockGeometry.Default, ChatDockEdge.TopLeft, ChatDockDelta(-5000f, -5000f), f)
        val hugeRect = ChatDockGeometryMath.rect(huge, f)
        assertEquals(970f, hugeRect.width, EPS) // the right edge stays; the left stops at the margin
        assertEquals(780f, hugeRect.height, EPS) // the container, less margins, before the max
        assertInside(hugeRect, f)
    }

    @Test
    fun aCollapsedPanelOnlyResizesItsWidth() {
        val f = frame()
        val collapsed = ChatDockGeometryMath.collapse(ChatDockGeometry(heightDp = 400f))
        val resized = ChatDockGeometryMath.resize(collapsed, ChatDockEdge.BottomRight, ChatDockDelta(50f, 300f), f)
        assertEquals(810f, resized.widthDp!!, EPS)
        assertEquals(400f, resized.heightDp!!, EPS)
    }

    @Test
    fun collapseThenExpandRestoresTheLastSizeAndPlace() {
        val f = frame()
        val sized = ChatDockGeometry(anchorX = 0.2f, anchorY = 0.7f, widthDp = 640f, heightDp = 480f)
        val collapsed = ChatDockGeometryMath.collapse(sized, f)
        assertTrue(collapsed.collapsed)
        assertEquals(60f, ChatDockGeometryMath.rect(collapsed, f).height, EPS)
        val expanded = ChatDockGeometryMath.expand(collapsed, f)
        assertFalse(expanded.collapsed)
        assertEquals(sized.anchorY, expanded.anchorY, EPS)
        val rect = ChatDockGeometryMath.rect(expanded, f)
        assertEquals(ChatDockGeometryMath.rect(sized, f).top, rect.top, EPS)
        assertEquals(640f, rect.width, EPS)
        assertEquals(480f, rect.height, EPS)
        // Without a frame (nothing laid out yet) only the flag changes.
        assertEquals(sized, ChatDockGeometryMath.expand(ChatDockGeometryMath.collapse(sized)))
    }

    @Test
    fun resetReturnsToTheDefaultPlacement() {
        assertEquals(ChatDockGeometry.Default, ChatDockGeometryMath.reset())
        assertNull(ChatDockGeometryMath.reset().widthDp)
        assertFalse(ChatDockGeometryMath.reset().collapsed)
    }

    @Test
    fun sanitizeRepairsGarbageFromDisk() {
        val repaired = ChatDockGeometryMath.sanitize(
            ChatDockGeometry(anchorX = Float.NaN, anchorY = 7f, widthDp = -3f, heightDp = Float.POSITIVE_INFINITY),
        )
        assertEquals(ChatDockGeometry(anchorX = 0.5f, anchorY = 1f, widthDp = null, heightDp = null), repaired)
    }

    private companion object {
        const val EPS = 0.01f
    }
}
