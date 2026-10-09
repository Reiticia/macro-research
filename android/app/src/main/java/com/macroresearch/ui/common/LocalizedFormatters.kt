package com.macroresearch.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.macroresearch.R
import com.macroresearch.data.model.EconomicEvent
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
@ReadOnlyComposable
fun appLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
fun EconomicEvent.localizedValue(value: String?): String = value(
    value, appLocale(),
    peopleLabel = stringResource(R.string.release_impact_unit_people),
    barrelsLabel = stringResource(R.string.release_impact_unit_barrels),
    indexLabel = stringResource(R.string.release_impact_unit_index),
)

@Composable
fun EconomicEvent.localizedDate(): String = localDate(appLocale(), stringResource(R.string.date_pattern))

@Composable
fun EconomicEvent.localizedShortDate(): String = localDate(appLocale(), stringResource(R.string.short_date_pattern))

@Composable
fun localizedCountdown(eventTime: String, now: Instant = Instant.now()): String =
    countdown(eventTime, now, stringResource(R.string.status_released))

@Composable
fun localizedChange(value: Double?, unit: String): String =
    formatChange(value, unit, appLocale(), stringResource(R.string.basis_points))

@Composable
fun dateLabel(date: LocalDate, monthOnly: Boolean = false): String = date.format(
    DateTimeFormatter.ofPattern(
        stringResource(if (monthOnly) R.string.month_pattern else R.string.date_pattern),
        appLocale(),
    ),
)
