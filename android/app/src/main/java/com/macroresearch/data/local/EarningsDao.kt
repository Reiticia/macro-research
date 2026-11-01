package com.macroresearch.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.macroresearch.data.EarningsStore

@Entity(tableName = "earnings_calendar", primaryKeys = ["sourceKey", "date"])
data class EarningsCacheEntity(val sourceKey: String, val date: String, val payload: String)

@Dao
interface EarningsDao {
    @Query("SELECT payload FROM earnings_calendar WHERE sourceKey = :sourceKey AND date = :date")
    suspend fun payload(sourceKey: String, date: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: EarningsCacheEntity)
    @Query("DELETE FROM earnings_calendar WHERE date < :before")
    suspend fun prune(before: String)
}

class RoomEarningsStore(private val dao: EarningsDao) : EarningsStore {
    override suspend fun read(source: String, date: String) = dao.payload(source, date)
    override suspend fun write(source: String, date: String, payload: String) = dao.save(EarningsCacheEntity(source, date, payload))
    override suspend fun prune(before: String) = dao.prune(before)
}
