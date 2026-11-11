package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.hoststate.HostDirectoryDurability
import org.junit.Assume.assumeTrue

internal fun assumeDurableGrantHost() {
    assumeTrue(
        "This test needs a durable notebook grant store; Windows cannot confirm directory durability",
        HostDirectoryDurability.supported(),
    )
}
