package com.letta.mobile.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.PendingLocalRecord
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoomPendingLocalStoreTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        LettaDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val store = RoomPendingLocalStore(database.pendingLocalDao())

    @After fun close() = database.close()

    @Test fun newestFailedImageSendSupersedesThePreviousFailure() = runTest {
        val first = imageRecord("first", 1L)
        val second = imageRecord("second", 2L)
        store.save(first)
        store.markFailed(first.otid)
        store.save(second)
        store.markFailed(second.otid)

        val loaded = store.load("conversation")

        assertEquals(1, loaded.size)
        assertEquals("second", loaded.single().otid)
        assertEquals(DeliveryState.FAILED, loaded.single().deliveryState)
        assertEquals(1, loaded.single().attachments.size)
    }

    private fun imageRecord(otid: String, millis: Long) = PendingLocalRecord(
        otid = otid,
        conversationId = "conversation",
        content = "caption",
        attachments = listOf(MessageContentPart.Image("aGVsbG8=", "image/png")),
        sentAt = Instant.ofEpochMilli(millis),
        deliveryState = DeliveryState.SENT,
    )
}
