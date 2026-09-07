package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TimelineRoomStrategyTest {
    @Test
    fun `generated suspend keyset query preserves cursor across insert`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (count in listOf(2_106, 28_000)) {
            val queries = CopyOnWriteArrayList<String>()
            val db = Room.inMemoryDatabaseBuilder(context, TimelineStrategyDatabase::class.java)
                .setQueryCallback({ sql, _ -> queries.add(sql) }, java.util.concurrent.Executor { it.run() })
                .build()
            try {
                val dao = db.metadata()
                (0 until count).chunked(128).forEach { batch ->
                    dao.insert(batch.map { StrategyMetadata("scope", it.toString().padStart(8, '0'), it.toLong(), "body") })
                }
                val before = dao.seekPage("scope", 128, "00000128", 64)
                assertEquals(64, before.size)
                assertEquals(before, dao.offsetPage("scope", 64, count - 128))
                val generated = dao.generatedOffsetPages("scope")
                assertTrue(generated is androidx.room.paging.LimitOffsetPagingSource<*>)
                val generatedPage = generated.load(
                    androidx.paging.PagingSource.LoadParams.Refresh(count - 128, 64, false),
                ) as androidx.paging.PagingSource.LoadResult.Page
                assertEquals(before, generatedPage.data)
                assertTrue(queries.any { it.contains("LIMIT") && it.contains("OFFSET") })
                val offsetNanos = ArrayList<Long>()
                val seekNanos = ArrayList<Long>()
                repeat(30) {
                    val source = dao.generatedOffsetPages("scope")
                    val offsetStart = System.nanoTime()
                    source.load(androidx.paging.PagingSource.LoadParams.Refresh(count - 128, 64, false))
                    offsetNanos.add(System.nanoTime() - offsetStart)
                    source.invalidate()
                    val seekStart = System.nanoTime()
                    assertEquals(before, dao.seekPage("scope", 128, "00000128", 64))
                    seekNanos.add(System.nanoTime() - seekStart)
                }
                println("Room strategy rows=$count refresh-offset-p95-ns=${offsetNanos.sorted()[28]} seek-p95-ns=${seekNanos.sorted()[28]}")
                dao.insert(listOf(StrategyMetadata("scope", "live", count.toLong(), "new-body")))
                assertEquals(before, dao.seekPage("scope", 128, "00000128", 64))
                assertNotEquals(before, dao.offsetPage("scope", 64, count - 128))
                assertEquals(emptyList<StrategyMetadata>(), dao.seekPage("other", 128, "00000128", 64))
                queries.clear()
                val writer = async(Dispatchers.IO) {
                    repeat(64) { index ->
                        dao.insert(listOf(StrategyMetadata("scope", "concurrent-$index", count + 1L + index, "body")))
                    }
                }
                repeat(64) { assertEquals(before, dao.seekPage("scope", 128, "00000128", 64)) }
                writer.await()
                db.invalidationTracker.refreshVersionsSync()
                assertTrue("Generated offset source must invalidate after insertion", generated.invalid)
                db.openHelper.readableDatabase.query(
                    "EXPLAIN QUERY PLAN SELECT scope,eventId,orderKey,bodyKey FROM strategy_metadata " +
                        "WHERE scope='scope' AND (orderKey,eventId)<(128,'00000128') " +
                        "ORDER BY orderKey DESC,eventId DESC LIMIT 64",
                ).use { plan ->
                    assertTrue(plan.moveToFirst())
                    val detail = plan.getString(3)
                    assertTrue(detail, detail.contains("USING INDEX index_strategy_metadata_scope_orderKey_eventId"))
                    assertTrue(detail, detail.contains("orderKey"))
                }
                val reads = queries.filter { it.startsWith("SELECT") && it.contains("FROM strategy_metadata") }
                assertEquals(64, reads.size)
                assertTrue(reads.all { it.contains("LIMIT") && it.contains("(orderKey,eventId)<") && !it.contains("OFFSET") })
            } finally {
                db.close()
            }
        }
    }
}
