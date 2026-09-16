package com.macroresearch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CachedEventEntity::class, FollowedEventEntity::class, AiAnalysisEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class MacroDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun analysisDao(): AnalysisDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_event ADD COLUMN eventZhCn TEXT")
                db.execSQL("ALTER TABLE cached_event ADD COLUMN eventZhTw TEXT")
            }
        }

        // Client-side calendar IDs use a semantic event key. The legacy cache used
        // provider-specific IDs, so it must be refreshed once to avoid duplicate rows.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM followed_event")
                db.execSQL("DELETE FROM cached_event")
            }
        }

        // Adds the on-device cache for AI market analyses.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_analysis` (" +
                        "`eventId` INTEGER NOT NULL, " +
                        "`revision` INTEGER NOT NULL, " +
                        "`chainJson` TEXT NOT NULL, " +
                        "`dataAnalysis` TEXT NOT NULL, " +
                        "`marketOutlook` TEXT NOT NULL, " +
                        "`risks` TEXT, " +
                        "`model` TEXT NOT NULL, " +
                        "`generatedAt` TEXT NOT NULL, " +
                        "PRIMARY KEY(`eventId`))",
                )
            }
        }
        // v4 kept one analysis per event, so switching the analysis method overwrote the other
        // two. v5 keys the cache by (event, method) and records what a run cost.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_analysis_new` (" +
                        "`eventId` INTEGER NOT NULL, " +
                        "`method` INTEGER NOT NULL, " +
                        "`revision` INTEGER NOT NULL, " +
                        "`chainJson` TEXT NOT NULL, " +
                        "`dataAnalysis` TEXT NOT NULL, " +
                        "`marketOutlook` TEXT NOT NULL, " +
                        "`risks` TEXT, " +
                        "`model` TEXT NOT NULL, " +
                        "`generatedAt` TEXT NOT NULL, " +
                        "`generatedAtEpochMs` INTEGER NOT NULL DEFAULT 0, " +
                        "`usagePromptTokens` INTEGER NOT NULL DEFAULT 0, " +
                        "`usageCompletionTokens` INTEGER NOT NULL DEFAULT 0, " +
                        "`usageTotalTokens` INTEGER NOT NULL DEFAULT 0, " +
                        "`usageCalls` INTEGER NOT NULL DEFAULT 0, " +
                        "`fromCache` INTEGER NOT NULL DEFAULT 1, " +
                        "`rateLimited` INTEGER NOT NULL DEFAULT 0, " +
                        "`retryAfterSeconds` INTEGER, " +
                        "PRIMARY KEY(`eventId`, `method`))",
                )
                // Existing rows were produced by whatever method the user had selected at the
                // time; the default (2) is the best guess and keeps them visible.
                db.execSQL(
                    "INSERT INTO ai_analysis_new " +
                        "(eventId, method, revision, chainJson, dataAnalysis, marketOutlook, risks, model, generatedAt, usagePromptTokens, usageCompletionTokens, usageTotalTokens, usageCalls, fromCache, rateLimited, retryAfterSeconds) " +
                        "SELECT eventId, 2, revision, chainJson, dataAnalysis, marketOutlook, risks, model, generatedAt, " +
                        "CAST(strftime('%s', generatedAt) AS INTEGER) * 1000, " +
                        "0, 0, 0, 0, 1, 0, NULL " +
                        "FROM ai_analysis",
                )
                db.execSQL("DROP TABLE ai_analysis")
                db.execSQL("ALTER TABLE ai_analysis_new RENAME TO ai_analysis")
            }
        }
    }
}
