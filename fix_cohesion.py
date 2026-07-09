import re

with open("android-compose/core/data/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopTest.kt", "r") as f:
    content = f.read()

# Remove the @Suppress from TimelineSyncLoopTest class
content = content.replace("""@Tag("integration")
@Suppress("Low Cohesion", "LargeClass")
class TimelineSyncLoopTest {""", """@Tag("integration")
class TimelineSyncLoopTest {""")

# Separate TimelineSyncLoopImageRestoreTest to a new file to fix the cohesion issue
match = re.search(r'class TimelineSyncLoopImageRestoreTest \{.*', content, re.DOTALL)
if match:
    image_restore_content = match.group(0)

    # We will just write a hardcoded set of imports to avoid conflicting imports error
    imports = """package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.DeliveryState
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UserMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
"""

    # Remove TimelineSyncLoopImageRestoreTest from the original file
    content = content[:match.start()]
    content = content.rstrip() + "\n"

    # We need to extract the parts that TimelineSyncLoopImageRestoreTest depends on, OR we can just keep them private in TimelineSyncLoopTest.kt and remove the private keyword if they need to be shared.
    # What does TimelineSyncLoopImageRestoreTest use?
    # - FakeSyncApi (private class in TimelineSyncLoopTest.kt)
    # - FakeStore (nested class) -> This is fine
    #
    # Wait! FakeSyncApi is private in TimelineSyncLoopTest.kt, so moving it to another file will cause a compile error, as we saw!
    # I should change `private class FakeSyncApi` to `internal class FakeSyncApi` in TimelineSyncLoopTest.kt to fix this!

content = content.replace("private class FakeSyncApi : MessageApi(mockk(relaxed = true))", "internal class FakeSyncApi : MessageApi(mockk(relaxed = true))")

with open("android-compose/core/data/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopTest.kt", "w") as f:
    f.write(content)

with open("android-compose/core/data/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopImageRestoreTest.kt", "w") as f:
    f.write(imports + "\n")
    f.write(image_restore_content)
