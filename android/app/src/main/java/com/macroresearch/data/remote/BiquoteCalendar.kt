package com.macroresearch.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.releaseStatus
import java.time.Instant
import java.util.Locale

/** https://biquote.io/docs/#calendar: a bare array, capped at 500 rows (no offset). */
internal fun biquoteRows(json: String): JsonArray {
    val payload = JsonParser.parseString(json)
    check(payload.isJsonArray) { "Unexpected BiQuote calendar payload" }
    return payload.asJsonArray
}

internal fun parseBiquoteCalendar(rows: JsonArray, now: Instant): List<EconomicEvent> = rows.map { element ->
    check(element.isJsonObject) { "Invalid BiQuote calendar row" }
    val row = element.asJsonObject
    val id = requireNotNull(row.text("id")) { "Missing BiQuote occurrence id" }
    val title = requireNotNull(row.text("name")) { "Missing BiQuote event name" }
    val time = Instant.parse(requireNotNull(row.text("time")))
    val countryCode = requireNotNull(row.text("countryCode")).uppercase(Locale.ROOT)
    val actual = row.number("actual")
    val forecast = row.number("forecast")
    EconomicEvent(
        // eventId is a recurring series, NOT a release identity.
        id = stableEventId("biquote|$id"), provider = "biquote", providerId = id,
        releaseGroupId = null,
        country = EconomicCalendarClient.countryName(countryCode),
        currency = row.text("currency"),
        category = row.text("sector") ?: title,
        event = title, eventTime = time.toString(),
        importance = when (row.text("importance")) { "high" -> 3; "medium" -> 2; else -> 1 },
        actual = actual, previous = row.number("revisedPrevious") ?: row.number("previous"),
        consensus = forecast, forecast = forecast,
        unit = biquoteUnit(row) ?: indexPointsFor(title), status = releaseStatus(actual, time, now),
        timeExact = row.text("timeMode") == "exact",
    )
}.distinctBy(EconomicEvent::id).sortedBy(EconomicEvent::eventTime)

private fun JsonObject.text(key: String): String? = get(key)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.number(key: String): String? {
    val value = get(key)?.takeUnless(JsonElement::isJsonNull) ?: return null
    check(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid BiQuote calendar number" }
    return value.asBigDecimal.stripTrailingZeros().toPlainString()
}

/**
 * Closed, exact-name list observed in public responses: BiQuote publishes diffusion indices with
 * unit/multiplier "none". Only these two ISM series carry a release-impact rule.
 */
private val ISM_DIFFUSION_INDICES = setOf("ISM Manufacturing PMI", "ISM Non-Manufacturing PMI")

private fun indexPointsFor(name: String): String? =
    "index points".takeIf { name in ISM_DIFFUSION_INDICES }

/** Source values are already display-scaled; never multiply them while retaining a K/M label. */
private fun biquoteUnit(row: JsonObject): String? {
    val scale = when (val value = row.text("multiplier")) {
        null, "none" -> null
        "thousands" -> "K"
        "millions" -> "M"
        "billions" -> "B"
        "trillions" -> "T"
        else -> value
    }
    val dimension = when (val value = row.text("unit")) {
        null, "none" -> null
        "percent" -> "%"
        "job" -> "jobs"
        "barrel" -> "barrels"
        "usd" -> "USD"
        "currency" -> row.text("currency") ?: "currency"
        else -> value
    }
    return listOfNotNull(scale, dimension).joinToString(" ").ifEmpty { null }
}
