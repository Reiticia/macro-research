package com.macroresearch.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.macroresearch.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class EventTimestampMigrationTest {
    @Test fun v11UpgradePreservesDataAndQueriesMixedPrecisionChronologically() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "event-time-${System.nanoTime()}.db"
        val schema = instrumentation.context.assets.open("com.macroresearch.data.local.MacroDatabase/11.json")
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("database") }
        val times = listOf(
            "2026-11-13T00:00:00Z", "2026-11-13T00:00:00.000000001Z",
            "2026-11-13T00:00:00.500Z", "2026-11-13T23:59:59Z",
            "2026-11-13T23:59:59.999999999Z", "2026-11-14T00:00:00Z",
            "2026-11-13T08:00:00+08:00", "invalid",
        )
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getAsJsonArray("entities").forEach { raw ->
                        val entity = raw.asJsonObject
                        db.execSQL(entity["createSql"].asString.replace("\${TABLE_NAME}", entity["tableName"].asString))
                    }
                    schema.getAsJsonArray("setupQueries").forEach { db.execSQL(it.asString) }
                    times.forEachIndexed { index, time ->
                        db.execSQL("INSERT INTO cached_event(id,provider,providerId,country,category,event,eventTime,importance,status,actual) VALUES(?,'test',?,'US','inflation','CPI',?,3,'released','2.5')",
                            arrayOf<Any>(index.toLong(), index.toString(), time))
                    }
                    // More than one migration batch, signed IDs, and invalid values must survive.
                    for (id in -505L..-1L) db.execSQL("INSERT INTO cached_event(id,provider,providerId,country,category,event,eventTime,importance,status) VALUES(?,'test',?,'GB','test','Other','2000-01-01T00:00:00Z',1,'scheduled')", arrayOf<Any>(id,id.toString()))
                    db.execSQL("INSERT INTO followed_event VALUES(0,123)")
                    db.execSQL("INSERT INTO ai_conversation VALUES(0,'[]',123)")
                    db.execSQL("INSERT INTO ai_auto_refresh VALUES(0,2,'en',123)")
                    db.execSQL("INSERT INTO ai_analysis VALUES(0,2,1,'[]','preserved','outlook',NULL,'fixture','2026-11-13T00:00:00Z',123,0,0,0,0,1,0,NULL)")
                    db.execSQL("INSERT INTO earnings_calendar VALUES('direct','2026-11-13','preserved')")
                    db.execSQL("INSERT INTO earnings_favorite VALUES('direct','2026-11-13','AAPL','preserved')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Room migrates")
            }).build())
        fun open() = Room.databaseBuilder(context, MacroDatabase::class.java, name)
            .addMigrations(MacroDatabase.MIGRATION_11_12).build()
        try {
            helper.use { it.writableDatabase }
            val db = open()
            try {
                val dao = db.eventDao()
                times.forEachIndexed { index, raw ->
                    val row = dao.event(index.toLong())!!
                    assertEquals(raw, row.eventTime)
                    val parsed = runCatching { Instant.parse(raw) }.getOrNull()
                    assertEquals(parsed?.epochSecond, row.eventEpochSecond)
                    assertEquals(parsed?.nano, row.eventNano)
                    assertEquals("2.5", row.actual)
                }
                assertNotNull(dao.event(-505))
                assertNotNull(dao.event(-1)?.eventEpochSecond)
                assertTrue(dao.isFollowed(0))
                assertEquals("preserved", db.analysisDao().analysis(0,2)?.dataAnalysis)
                assertNotNull(db.analysisDao().conversation(0))
                assertNotNull(db.analysisDao().autoRefreshAttempt(0,2,"en"))
                assertEquals("preserved", db.earningsDao().payload("direct","2026-11-13"))
                assertEquals(1, db.earningsDao().favorites("direct").first().size)
                val from = "2026-11-13T00:00:00Z"; val until = "2026-11-14T00:00:00Z"
                val ids = listOf(0L,6L,1L,2L,3L,4L)
                assertEquals(ids, dao.cachedCalendarRange(from,until).map { it.id })
                assertEquals(ids, dao.observeRange(from,until).first().map { it.id })
                assertEquals(listOf(0L,6L), dao.cachedRange(from,from).map { it.id })
                assertEquals(listOf(1L,2L,3L,4L,5L), dao.observeUpcoming("2026-11-13T00:00:00.000000001Z").first().map { it.id })
                assertEquals(listOf(1L,6L,0L), dao.history("2026-11-13T00:00:00.500Z",listOf("US"),null,8,0).map { it.id })
                val shifted = dao.event(0)!!.copy(eventTime = until)
                dao.upsert(listOf(shifted))
                assertEquals(Instant.parse(until).epochSecond, dao.event(0)!!.eventEpochSecond)
                dao.deleteOlderThan("2026-11-13T00:00:00.000000001Z")
                assertNull(dao.event(6)); assertNotNull(dao.event(1)); assertNotNull(dao.event(7))
            } finally { db.close() }
            val reopened = open()
            try { assertTrue(reopened.eventDao().isFollowed(0)); assertNotNull(reopened.eventDao().event(7)) }
            finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
