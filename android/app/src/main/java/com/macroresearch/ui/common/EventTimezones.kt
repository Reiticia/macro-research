package com.macroresearch.ui.common

import com.macroresearch.data.model.EconomicEvent
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The feed supplies an instant and a country, not a venue timezone. Use an explicit
 * national macro-release reference zone, never a currency or the device timezone.
 * Multi-zone countries use the named financial/statistical reference city, not a
 * claim about the physical venue of every speech. Unknown/world regions stay unknown.
 */
private val macroRegionZones: Map<String, String> = buildMap {
    fun region(zone: String, vararg names: String) { names.forEach { put(it.lowercase(Locale.ROOT), zone) } }
    region("America/New_York", "United States", "United States of America", "US", "USA")
    region("Europe/Berlin", "Euro Area", "Eurozone", "Euro Zone", "European Union", "EU", "Germany", "DE")
    region("Asia/Shanghai", "China", "CN", "CHN", "People's Republic of China")
    region("Asia/Tokyo", "Japan", "JP", "JPN")
    region("Europe/London", "United Kingdom", "UK", "GB", "GBR", "Great Britain")
    region("Europe/Paris", "France", "FR")
    region("Europe/Rome", "Italy", "IT")
    region("Europe/Madrid", "Spain", "ES")
    region("Europe/Amsterdam", "Netherlands", "NL")
    region("Europe/Lisbon", "Portugal", "PT")
    region("Europe/Athens", "Greece", "GR")
    region("Europe/Dublin", "Ireland", "IE")
    region("Europe/Vienna", "Austria", "AT")
    region("Europe/Brussels", "Belgium", "BE")
    region("Europe/Helsinki", "Finland", "FI")
    region("Europe/Luxembourg", "Luxembourg", "LU")
    region("Europe/Malta", "Malta", "MT")
    region("Asia/Nicosia", "Cyprus", "CY")
    region("Europe/Bratislava", "Slovakia", "SK")
    region("Europe/Ljubljana", "Slovenia", "SI")
    region("Europe/Tallinn", "Estonia", "EE")
    region("Europe/Riga", "Latvia", "LV")
    region("Europe/Vilnius", "Lithuania", "LT")
    region("Australia/Sydney", "Australia", "AU", "AUS")
    region("America/Toronto", "Canada", "CA", "CAN")
    region("Europe/Zurich", "Switzerland", "CH")
    region("Pacific/Auckland", "New Zealand", "NZ", "NZL")
    region("Asia/Seoul", "South Korea", "KR", "Korea, Republic of")
    region("Asia/Kolkata", "India", "IN")
    region("Asia/Singapore", "Singapore", "SG")
    region("Asia/Hong_Kong", "Hong Kong", "HK")
    region("Asia/Taipei", "Taiwan", "TW")
    region("America/Sao_Paulo", "Brazil", "BR")
    region("America/Mexico_City", "Mexico", "MX")
    region("Africa/Johannesburg", "South Africa", "ZA")
    region("Europe/Istanbul", "Turkey", "Türkiye", "TR")
    region("Europe/Moscow", "Russia", "Russian Federation", "RU")
    region("Europe/Stockholm", "Sweden", "SE")
    region("Europe/Oslo", "Norway", "NO")
    region("Europe/Copenhagen", "Denmark", "DK")
    region("Europe/Warsaw", "Poland", "PL")
    region("Europe/Prague", "Czechia", "Czech Republic", "CZ")
    region("Europe/Budapest", "Hungary", "HU")
    region("Europe/Bucharest", "Romania", "RO")
    region("Asia/Jerusalem", "Israel", "IL")
    region("Asia/Riyadh", "Saudi Arabia", "SA")
    region("Asia/Jakarta", "Indonesia", "ID")
    region("Asia/Kuala_Lumpur", "Malaysia", "MY")
    region("Asia/Bangkok", "Thailand", "TH")
    region("Asia/Manila", "Philippines", "PH")
    region("Asia/Ho_Chi_Minh", "Vietnam", "VN")
    region("America/Argentina/Buenos_Aires", "Argentina", "AR")
    region("America/Santiago", "Chile", "CL")
    region("America/Bogota", "Colombia", "CO")
    region("America/Lima", "Peru", "PE")
    region("Africa/Cairo", "Egypt", "EG")
    region("Africa/Lagos", "Nigeria", "NG")
    region("Europe/Kiev", "Ukraine", "UA")
    region("Asia/Dubai", "United Arab Emirates", "AE", "UAE")
    region("Europe/Sofia", "Bulgaria", "BG")
    region("Asia/Tbilisi", "Georgia", "GE")
    region("Europe/Zagreb", "Croatia", "HR")
    region("Asia/Almaty", "Kazakhstan", "KZ")
}

fun macroRegionZone(country: String): ZoneId? = macroRegionZones[country.trim().lowercase(Locale.ROOT)]
    ?.let { runCatching { ZoneId.of(it) }.getOrNull() }

data class MacroEventTimes(
    val deviceZone: ZoneId,
    val regionZone: ZoneId?,
    val deviceTime: ZonedDateTime?,
    val regionTime: ZonedDateTime?,
)

fun EconomicEvent.displayTimes(deviceZone: ZoneId = ZoneId.systemDefault()): MacroEventTimes {
    val region = macroRegionZone(country)
    // A date-only feed value is not a midnight release and must not become a fake local clock.
    val instant = if (timeExact) runCatching { Instant.parse(eventTime) }.getOrNull() else null
    return MacroEventTimes(deviceZone, region, instant?.atZone(deviceZone), region?.let { instant?.atZone(it) })
}

/** In a single-line pair, repeat the regional date only when it differs from the local date. */
fun MacroEventTimes.inlineRegionClockText(locale: Locale = Locale.getDefault()): String? = regionTime?.let {
    eventClockText(it, it.toLocalDate() != deviceTime?.toLocalDate(), locale)
}

fun eventClockText(time: ZonedDateTime, showDate: Boolean, locale: Locale = Locale.getDefault()): String {
    val pattern = if (showDate) "yyyy-MM-dd HH:mm" else "HH:mm"
    val offset = if (time.offset.totalSeconds == 0) "UTC+00:00" else "UTC${time.offset.id}"
    return "${time.format(DateTimeFormatter.ofPattern(pattern, locale))} ($offset)"
}
