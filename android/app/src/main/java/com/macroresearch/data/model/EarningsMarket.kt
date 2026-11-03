package com.macroresearch.data.model

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Read-only backend samples. Detection is not a company-confirmed release instant. */
data class EarningsMarket(
    val date: String,
    val symbol: String,
    val timeBasis: String,
    val releaseTimeExact: Boolean,
    val observationStatus: String,
    val tracking: EarningsStockTracking?,
    val snapshots: List<EarningsStockSnapshot>,
    val coverage: EarningsStockCoverage,
)

data class EarningsStockTracking(
    val detectedAt: String,
    val endsAt: String,
    val status: String,
    val currency: String?,
    val lastSuccessAt: String?,
    val lastError: String?,
)

data class EarningsStockSnapshot(
    val timestamp: String,
    val firstQuoteAt: String,
    val lastQuoteAt: String,
    val price: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val provider: String,
    val timestampBasis: String,
)

data class EarningsStockCoverage(
    val minuteCount: Int,
    val expectedMinuteCount: Int,
    val missingMinuteCount: Int,
    val windowEnded: Boolean,
)

data class EarningsPricePoint(val at: Instant, val price: Double)

/** Only prices with their own observed timestamps; never interpolate a missing minute. */
fun EarningsMarket.pricePoints(): List<EarningsPricePoint> = snapshots.flatMap {
    listOf(EarningsPricePoint(Instant.parse(it.firstQuoteAt), it.open), EarningsPricePoint(Instant.parse(it.lastQuoteAt), it.close))
}.sortedBy { it.at }.associateBy { it.at }.values.toList()

fun EarningsMarket.firstPricePoint(): EarningsPricePoint? = snapshots.minByOrNull { Instant.parse(it.firstQuoteAt) }
    ?.let { EarningsPricePoint(Instant.parse(it.firstQuoteAt), it.open) }

fun EarningsMarket.priceAt(minute: Int, now: Instant): EarningsPricePoint? {
    val start = tracking?.detectedAt?.let(Instant::parse) ?: return null
    val target = start.plusSeconds(minute.coerceIn(0, 60) * 60L)
    if (target > now) return null
    return pricePoints().lastOrNull { it.at <= target }
        ?.takeIf { Duration.between(it.at, target).seconds <= 90 }
}

fun EarningsMarket.changeFromFirst(point: EarningsPricePoint?): Double? {
    val baseline = firstPricePoint()?.price ?: return null
    return point?.let { (it.price / baseline - 1) * 100 }?.takeIf { it.isFinite() }
}

internal fun decodeEarningsMarket(raw: String, date: LocalDate, symbol: String, gson: Gson = Gson()): EarningsMarket {
    val root = JsonParser.parseString(raw).asJsonObject
    require(root.get("date")?.asString == date.toString() && root.get("symbol")?.asString == symbol)
    require(root.get("timeBasis")?.asString == "first_detected_actual_eps")
    require(root.has("releaseTimeExact") && !root.get("releaseTimeExact").asBoolean)
    require(root.get("observationStatus")?.isJsonPrimitive == true)
    require(root.has("tracking") && root.get("snapshots")?.isJsonArray == true && root.get("coverage")?.isJsonObject == true)
    root.getAsJsonObject("coverage").let { coverage ->
        listOf("minuteCount", "expectedMinuteCount", "missingMinuteCount", "windowEnded").forEach { require(coverage.has(it)) }
    }
    val result = gson.fromJson(root, EarningsMarket::class.java)
    val coverage = result.coverage
    require(coverage.minuteCount in 0..61 && coverage.expectedMinuteCount in 0..61 && coverage.missingMinuteCount in 0..61)
    require(coverage.minuteCount == result.snapshots.size)
    require(coverage.missingMinuteCount == (coverage.expectedMinuteCount - coverage.minuteCount).coerceAtLeast(0))
    val tracking = result.tracking
    if (tracking == null) {
        require(result.snapshots.isEmpty() && coverage.expectedMinuteCount == 0 && !coverage.windowEnded)
    } else {
        val start = Instant.parse(tracking.detectedAt)
        val end = Instant.parse(tracking.endsAt)
        require(Duration.between(start, end).seconds == 3600L)
        require(tracking.status in setOf("collecting", "completed"))
        tracking.lastSuccessAt?.let(Instant::parse)
        val minutes = mutableSetOf<Instant>()
        result.snapshots.forEach { row ->
            val minute = Instant.parse(row.timestamp)
            val first = Instant.parse(row.firstQuoteAt)
            val last = Instant.parse(row.lastQuoteAt)
            require(minute.epochSecond % 60 == 0L && minutes.add(minute))
            require(first >= start && first <= last && last < end)
            require(first.epochSecond / 60 == minute.epochSecond / 60 && last.epochSecond / 60 == minute.epochSecond / 60)
            require(listOf(row.price, row.open, row.high, row.low, row.close).all { it.isFinite() && it > 0 })
            require(row.price == row.close && row.low <= minOf(row.open, row.close) && row.high >= maxOf(row.open, row.close) && row.low <= row.high)
            require(row.provider == "yahoo" && row.timestampBasis in setOf("provider_quote", "minute_bar_close", "mixed"))
        }
    }
    return result
}
