package com.letta.mobile.wrapper.notebook

import com.letta.mobile.data.hoststate.HostDirectoryDurability
import org.junit.jupiter.api.Assumptions.assumeTrue

internal fun assumeDurableGrantHost() {
    assumeTrue(
        HostDirectoryDurability.supported(),
        "This test needs a durable notebook grant store; Windows cannot confirm directory durability",
    )
}
