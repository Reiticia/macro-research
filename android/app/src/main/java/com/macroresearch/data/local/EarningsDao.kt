package com.macroresearch.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.macroresearch.data.EarningsStore
import com.macroresearch.data.EarningsFavoritesStore
import com.macroresearch.data.FavoriteEarnings
import com.macroresearch.data.model.EarningsEvent
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

@Entity(tableName = "earnings_calendar", primaryKeys = ["sourceKey", "date"])
data class EarningsCacheEntity(val sourceKey: String, val date: String, val payload: String)

@Entity(tableName = "earnings_favorite", primaryKeys = ["sourceKey", "date", "symbol"])
data class EarningsFavoriteEntity(val sourceKey: String, val date: String, val symbol: String, val payload: String)

@Dao
interface EarningsDao {
    @Query("SELECT payload FROM earnings_calendar WHERE sourceKey = :sourceKey AND date = :date")
    suspend fun payload(sourceKey: String, date: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: EarningsCacheEntity)
    @Query("DELETE FROM earnings_calendar WHERE date < :before")
    suspend fun prune(before: String)
    @Query("SELECT * FROM earnings_favorite WHERE sourceKey = :sourceKey ORDER BY date DESC, symbol")
    fun favorites(sourceKey: String): Flow<List<EarningsFavoriteEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFavorite(entity: EarningsFavoriteEntity)
    @Query("DELETE FROM earnings_favorite WHERE sourceKey = :sourceKey AND date = :date AND symbol = :symbol")
    suspend fun removeFavorite(sourceKey: String, date: String, symbol: String)
}

class RoomEarningsFavoritesStore(private val dao: EarningsDao, private val gson: Gson = Gson()) : EarningsFavoritesStore {
    override fun observe(source: String) = dao.favorites(source).map { rows ->
        rows.mapNotNull { row -> runCatching {
            val event = gson.fromJson(row.payload, EarningsEvent::class.java)
            require(event.symbol == row.symbol && event.name.isNotBlank())
            FavoriteEarnings(LocalDate.parse(row.date), event)
        }.getOrNull() }
    }
    override suspend fun set(source: String, favorite: FavoriteEarnings, enabled: Boolean) {
        if (enabled) dao.saveFavorite(EarningsFavoriteEntity(source, favorite.date.toString(), favorite.event.symbol, gson.toJson(favorite.event)))
        else dao.removeFavorite(source, favorite.date.toString(), favorite.event.symbol)
    }
}

class RoomEarningsStore(private val dao: EarningsDao) : EarningsStore {
    override suspend fun read(source: String, date: String) = dao.payload(source, date)
    override suspend fun write(source: String, date: String, payload: String) = dao.save(EarningsCacheEntity(source, date, payload))
    override suspend fun prune(before: String) = dao.prune(before)
}
