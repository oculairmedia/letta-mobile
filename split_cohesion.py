import re

with open("android-compose/core/data/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopTest.kt", "r") as f:
    content = f.read()

# 1. Extract FakeSyncApi
fake_sync_api_match = re.search(r'private class FakeSyncApi : MessageApi\(mockk\(relaxed = true\)\) \{.*?\n\}', content, re.DOTALL)
fake_sync_api_code = fake_sync_api_match.group(0).replace("private class FakeSyncApi", "internal class FakeSyncApi") if fake_sync_api_match else ""
content = content.replace(fake_sync_api_match.group(0), "") if fake_sync_api_match else content

# 2. Extract RecordingConversationCursorStore
record_store_match = re.search(r'private class RecordingConversationCursorStore : ConversationCursorStore \{.*?\n\}', content, re.DOTALL)
record_store_code = record_store_match.group(0).replace("private class RecordingConversationCursorStore", "internal class RecordingConversationCursorStore") if record_store_match else ""
content = content.replace(record_store_match.group(0), "") if record_store_match else content

# 3. Extract randomOrNull
random_match = re.search(r'private fun <T> List<T>\.randomOrNull\(random: Random\): T\? =.*?\n', content, re.DOTALL)
random_code = random_match.group(0).replace("private fun", "internal fun") if random_match else ""
content = content.replace(random_match.group(0), "") if random_match else content

# 4. Extract contentOrNull
content_or_null_match = re.search(r'private val kotlinx\.serialization\.json\.JsonPrimitive\.contentOrNull: String\?.*?\n', content, re.DOTALL)
content_or_null_code = content_or_null_match.group(0).replace("private val", "internal val") if content_or_null_match else ""
content = content.replace(content_or_null_match.group(0), "") if content_or_null_match else content

# 5. Extract TimelineSyncLoopImageRestoreTest
image_restore_match = re.search(r'/\*\*\n \* mge5\.24: image attachments.*@Suppress\("Low Cohesion"\)\nclass TimelineSyncLoopImageRestoreTest \{.*?^\}', content, re.DOTALL | re.MULTILINE)
if not image_restore_match:
    image_restore_match = re.search(r'/\*\*\n \* mge5\.24: image attachments.*class TimelineSyncLoopImageRestoreTest \{.*?^\}', content, re.DOTALL | re.MULTILINE)

image_restore_code = image_restore_match.group(0) if image_restore_match else ""
content = content.replace(image_restore_match.group(0), "") if image_restore_match else content

utils_imports = """package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.awaitCancellation
"""

utils_code = utils_imports + "\n" + fake_sync_api_code + "\n\n" + record_store_code + "\n\n" + random_code + "\n\n" + content_or_null_code

image_restore_imports = """package com.letta.mobile.data.timeline

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

image_restore_file_content = image_restore_imports + "\n" + image_restore_code

# Write the new files
with open("android-compose/core/data/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopTest.kt", "w") as f:
    f.write(content)

with open("android-compose/core/data/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopTestUtils.kt", "w") as f:
    f.write(utils_code)

with open("android-compose/core/data/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopImageRestoreTest.kt", "w") as f:
    f.write(image_restore_file_content)
