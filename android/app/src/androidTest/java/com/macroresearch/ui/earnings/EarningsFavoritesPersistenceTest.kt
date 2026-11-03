package com.macroresearch.ui.earnings

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.macroresearch.data.FavoriteEarnings
import com.macroresearch.data.local.MacroDatabase
import com.macroresearch.data.local.RoomEarningsFavoritesStore
import com.macroresearch.data.model.EarningsEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class EarningsFavoritesPersistenceTest {
    @Test fun upgradesFromExportedSchemasPreserveOldDataAndFavoritesSurviveReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (version in listOf(8, 9, 10)) {
            val name = "earnings-favorites-$version-${System.nanoTime()}.db"
            val schema = instrumentation.context.assets.open("com.macroresearch.data.local.MacroDatabase/$version.json")
                .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("database") }
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.getAsJsonArray("entities").forEach { raw ->
                            val entity = raw.asJsonObject
                            db.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}", entity.get("tableName").asString))
                        }
                        schema.getAsJsonArray("setupQueries").forEach { db.execSQL(it.asString) }
                        db.execSQL("INSERT INTO followed_event VALUES (7, 123)")
                        db.execSQL("INSERT INTO cached_event(id,provider,providerId,country,category,event,eventTime,importance,status) VALUES(7,'tradingview','7','united states','inflation','CPI','2026-11-03T15:00:00Z',3,'scheduled')")
                        if (version == 10) db.execSQL("INSERT INTO earnings_calendar VALUES ('direct','2026-11-03','preserved')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Use Room migrations")
                }).build())
            fun open() = Room.databaseBuilder(context, MacroDatabase::class.java, name)
                .addMigrations(MacroDatabase.MIGRATION_8_9, MacroDatabase.MIGRATION_9_10, MacroDatabase.MIGRATION_10_11).build()
            val favorite = FavoriteEarnings(LocalDate.of(2026,11,3), EarningsEvent("AAPL", "Apple", "nasdaq"))
            try {
                helper.use { it.writableDatabase }
                val db = open()
                try {
                    val sql = db.openHelper.writableDatabase // Room validates the full migrated schema.
                    sql.query("SELECT event FROM cached_event WHERE id=7").use { assertTrue(it.moveToFirst()); assertEquals("CPI", it.getString(0)) }
                    sql.query("SELECT COUNT(*) FROM followed_event").use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
                    if (version == 10) assertEquals("preserved", db.earningsDao().payload("direct", "2026-11-03"))
                    val store = RoomEarningsFavoritesStore(db.earningsDao())
                    store.set("direct", favorite, true)
                    store.set("direct", favorite, true)
                    store.set("backend:fixture", favorite, true)
                    assertEquals(listOf(favorite), store.observe("direct").first())
                } finally { db.close() }
                val reopened = open()
                try {
                    val store = RoomEarningsFavoritesStore(reopened.earningsDao())
                    assertEquals(listOf(favorite), store.observe("direct").first())
                    store.set("direct", favorite, false)
                    assertTrue(store.observe("direct").first().isEmpty())
                    assertEquals(listOf(favorite), store.observe("backend:fixture").first())
                } finally { reopened.close() }
            } finally { context.deleteDatabase(name) }
        }
    }
}
