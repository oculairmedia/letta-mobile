package com.letta.mobile.ui.shell.acceptance

import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import kotlin.test.Test
import kotlin.test.assertEquals

class MissingContributionRecoveryTest {
    @Test
    fun unknownSlotSurvivesSavingAndUpgrading() {
        val page = ModuleSlot(ShellViews.PAGE)
        val unknown = ModuleSlot(RuntimeViewId("missing.contribution"), "object")
        val saved = ShellLayoutConfig(version = 0, lanes = mapOf(ShellLanes.MAIN to listOf(page, unknown)))

        val restored = ShellLayoutConfig.decode(saved.encode()).upgraded()

        assertEquals(listOf(page, unknown), restored.mainLane())
    }
}
