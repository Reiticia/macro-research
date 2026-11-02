package com.macroresearch.ui.earnings

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.data.local.MacroDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EarningsMigrationTest {
    @Test fun migrationAddsIsolatedCompositeKeyWithoutDeletingExistingRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "earnings-migration-${System.nanoTime()}.db"
        fun helper(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE macro_sentinel (id INTEGER PRIMARY KEY, title TEXT NOT NULL)")
                    db.execSQL("INSERT INTO macro_sentinel VALUES (7, 'preserved')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    assertEquals(9, oldVersion); assertEquals(10, newVersion)
                    MacroDatabase.MIGRATION_9_10.migrate(db)
                }
            }).build(),
        )
        try {
            helper(9).use { it.writableDatabase }
            helper(10).use { connection ->
                val db = connection.writableDatabase
                db.query("SELECT title FROM macro_sentinel WHERE id = 7").use { assertTrue(it.moveToFirst()); assertEquals("preserved", it.getString(0)) }
                db.execSQL("INSERT INTO earnings_calendar VALUES ('direct', '2026-11-02', '{}')")
                db.execSQL("INSERT INTO earnings_calendar VALUES ('backend:hash', '2026-11-02', '{}')")
                db.query("SELECT COUNT(*) FROM earnings_calendar").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
                db.query("PRAGMA table_info(earnings_calendar)").use { cursor ->
                    val keys = mutableMapOf<String, Int>()
                    while (cursor.moveToNext()) keys[cursor.getString(1)] = cursor.getInt(5)
                    assertEquals(mapOf("sourceKey" to 1, "date" to 2, "payload" to 0), keys)
                }
            }
        } finally { context.deleteDatabase(name) }
    }
}
