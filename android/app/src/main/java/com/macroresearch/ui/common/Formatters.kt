package com.macroresearch.ui.common

import com.macroresearch.data.model.EconomicEvent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun EconomicEvent.localizedName(locale: Locale): String = localizedEventName(
    event,
    eventZhCn,
    eventZhTw,
    locale,
)

private fun localizedEventName(source: String, zhCn: String?, zhTw: String?, locale: Locale): String {
    if (locale.language != "zh") return source
    val traditional = locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")
    return if (traditional) zhTw ?: zhCn ?: source else zhCn ?: zhTw ?: source
}

fun EconomicEvent.localTime(): String = runCatching {
    Instant.parse(eventTime).atZone(ZoneId.systemDefault()).format(timeFormatter)
}.getOrDefault("--:--")

fun EconomicEvent.localDate(locale: Locale, pattern: String): String = runCatching {
    Instant.parse(eventTime).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern, locale))
}.getOrDefault(eventTime)

fun EconomicEvent.value(value: String?, locale: Locale = Locale.ENGLISH): String {
    if (value == null) return "--"
    val number = value.toBigDecimalOrNull() ?: return value
    return when (unit) {
        "%" -> "${number.stripTrailingZeros().toPlainString()}%"
        "count" -> compact(number, locale)
        "currency" -> "$${compact(number, locale)}"
        else -> number.stripTrailingZeros().toPlainString()
    }
}

fun EconomicEvent.surprise(): BigDecimal? {
    val actualValue = actual?.toBigDecimalOrNull() ?: return null
    val consensusValue = (consensus ?: forecast)?.toBigDecimalOrNull() ?: return null
    return actualValue - consensusValue
}

fun signed(value: BigDecimal, suffix: String = ""): String {
    val sign = if (value.signum() > 0) "+" else ""
    return "$sign${value.stripTrailingZeros().toPlainString()}$suffix"
}

fun countdown(eventTime: String, now: Instant = Instant.now(), releasedLabel: String = "Released"): String {
    val target = runCatching { Instant.parse(eventTime) }.getOrNull() ?: return "--:--:--"
    val seconds = Duration.between(now, target).seconds
    if (seconds <= 0) return releasedLabel
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remainder = seconds % 60
    return "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, remainder)
}

fun flag(country: String): String = when (country.lowercase()) {
    "united states", "us", "usa" -> "🇺🇸"
    "euro area", "european union" -> "🇪🇺"
    "china" -> "🇨🇳"
    "japan" -> "🇯🇵"
    "united kingdom", "uk" -> "🇬🇧"
    "australia" -> "🇦🇺"
    "canada" -> "🇨🇦"
    else -> "🌐"
}

fun formatChange(value: Double?, unit: String, locale: Locale = Locale.ENGLISH, basisPoints: String = "bp"): String {
    if (value == null) return "--"
    val suffix = if (unit == "basis_points") basisPoints else "%"
    return "%+.2f%s".format(locale, value, suffix)
}

private fun compact(number: BigDecimal, locale: Locale): String {
    val absolute = number.abs()
    val traditional = locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")
    val units = if (locale.language == "zh") listOf(
        "1000000000000" to if (traditional) "萬億" else "万亿",
        "100000000" to if (traditional) "億" else "亿",
        "10000" to if (traditional) "萬" else "万",
    ) else listOf("1000000000000" to "T", "1000000000" to "B", "1000000" to "M", "1000" to "K")
    val (divisor, suffix) = units.map { (value, suffix) -> BigDecimal(value) to suffix }
        .firstOrNull { (divisor, _) -> absolute >= divisor }
        ?: return number.stripTrailingZeros().toPlainString()
    return number.divide(divisor, 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + suffix
}

