package com.macroresearch.ui.home

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Never make a previous-day price look like today's price by hiding its date. */
internal fun homeQuoteTime(
    timestamp: String,
    locale: Locale,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? = runCatching {
    val observed = Instant.parse(timestamp).atZone(zone)
    val formatter = if (observed.toLocalDate() == now.atZone(zone).toLocalDate()) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    } else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    formatter.withLocale(locale).format(observed)
}.getOrNull()
