package com.letta.mobile.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetainedContentRefreshTest {
    @Test
    fun `begin loads when no content exists`() {
        val result = RetainedContentRefresh.begin<String>(requestId = 1, retainedContent = null)

        assertEquals(RetainedContentRefresh.Start.Loading(1), result)
    }

    @Test
    fun `begin retains content unless refresh is blocked`() {
        val retained = RetainedContentRefresh.begin(requestId = 4, retainedContent = "draft")
        val skipped = RetainedContentRefresh.begin(
            requestId = 5,
            retainedContent = "draft",
            canRefresh = { false },
        )

        assertEquals(RetainedContentRefresh.Start.Retaining(4, "draft"), retained)
        assertEquals(RetainedContentRefresh.Start.Skip, skipped)
    }

    @Test
    fun `failure retains content or exposes initial load error`() {
        val retained = RetainedContentRefresh.failure(retainedContent = "content", message = "offline")
        val initial = RetainedContentRefresh.failure<String>(retainedContent = null, message = "offline")

        assertEquals(RetainedContentRefresh.Failure.Retain("content", "offline"), retained)
        assertEquals(RetainedContentRefresh.Failure.ShowError("offline"), initial)
    }

    @Test
    fun `only current request may publish a response`() {
        assertTrue(RetainedContentRefresh.isCurrent(requestId = 3, latestRequestId = 3))
        assertTrue(!RetainedContentRefresh.isCurrent(requestId = 2, latestRequestId = 3))
    }
}
