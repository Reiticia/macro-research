package com.macroresearch.data

import com.macroresearch.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate

internal val stockStart: Instant = Instant.parse("2026-11-03T15:00:00Z")
internal val stockDate: LocalDate = LocalDate.of(2026, 11, 3)

internal fun stockSnapshot(minute: Long, open: Double = 100.0, close: Double = 101.0) = EarningsStockSnapshot(
    timestamp = stockStart.plusSeconds(minute * 60).toString(),
    firstQuoteAt = stockStart.plusSeconds(minute * 60 + 5).toString(),
    lastQuoteAt = stockStart.plusSeconds(minute * 60 + 45).toString(),
    price = close, open = open, high = maxOf(open, close), low = minOf(open, close), close = close,
    provider = "yahoo", timestampBasis = "provider_quote",
)

internal fun stockMarket(
    snapshots: List<EarningsStockSnapshot> = listOf(stockSnapshot(0), stockSnapshot(1, 102.0, 103.0), stockSnapshot(4, 105.0, 106.0)),
    ended: Boolean = false,
) = EarningsMarket(
    date = stockDate.toString(), symbol = "AAPL", timeBasis = "first_detected_actual_eps", releaseTimeExact = false,
    observationStatus = "tracking",
    tracking = EarningsStockTracking(stockStart.toString(), stockStart.plusSeconds(3600).toString(),
        if (ended) "completed" else "collecting", "USD", stockStart.plusSeconds(300).toString(), null),
    snapshots = snapshots,
    coverage = EarningsStockCoverage(snapshots.size, if (ended) 60 else 6, (if (ended) 60 else 6) - snapshots.size, ended),
)

internal fun stockRepository(
    settings: MutableStateFlow<BackendSettings> = MutableStateFlow(BackendSettings(mode = DataSourceMode.BACKEND, baseUrl = "https://one.example", tokenConfigured = true)),
    fetch: suspend (LocalDate, String) -> EarningsMarket,
) = EarningsRepository(
    settings = settings,
    store = object : EarningsStore {
        override suspend fun read(source: String, date: String): String? = null
        override suspend fun write(source: String, date: String, payload: String) = error("Stock history must not overwrite the calendar cache")
        override suspend fun prune(before: String) = Unit
    },
    direct = { _, _ -> error("No direct fallback") },
    backend = { _, _ -> error("No calendar requests") },
    backendMarket = fetch,
)
