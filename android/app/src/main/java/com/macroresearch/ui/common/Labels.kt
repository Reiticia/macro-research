package com.macroresearch.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.macroresearch.R
import java.util.Locale

// Translate only display labels. API identifiers and filter values stay intact.
// Unknown provider values remain verbatim rather than being guessed.
@Composable fun countryLabel(value: String): String = displayLabel(countryLabelResource(value), value)
@Composable fun assetLabel(value: String): String = displayLabel(assetLabelResource(value), value)
@Composable fun categoryLabel(value: String): String = displayLabel(categoryLabelResource(value), value)
@Composable fun macroSignalLabel(value: String): String = displayLabel(macroSignalResource(value), value)
@Composable fun statusLabel(value: String): String = displayLabel(statusLabelResource(value), value)
@Composable fun importanceLabel(value: Int): String = stringResource(importanceLabelResource(value))
@Composable fun expectedRationaleLabel(value: String): String = displayLabel(expectedRationaleResource(value), value)
@Composable fun analysisSummaryLabel(value: String): String = displayLabel(analysisSummaryResource(value), value)

@Composable
private fun displayLabel(@StringRes resource: Int?, fallback: String): String =
    if (resource == null) fallback else stringResource(resource)

@StringRes
internal fun countryLabelResource(country: String): Int? = when (country.trim().lowercase(Locale.ROOT)) {
    "united states", "us", "usa" -> R.string.country_us
    "euro area", "eurozone" -> R.string.country_euro_area
    "european union", "eu" -> R.string.country_eu
    "china", "cn" -> R.string.country_china
    "japan", "jp" -> R.string.country_japan
    "united kingdom", "uk", "gb" -> R.string.country_uk
    "australia", "au" -> R.string.country_australia
    "canada", "ca" -> R.string.country_canada
    "new zealand", "nz" -> R.string.country_nz
    "germany" -> R.string.country_germany
    "france" -> R.string.country_france
    "italy" -> R.string.country_italy
    "spain" -> R.string.country_spain
    "switzerland" -> R.string.country_switzerland
    "sweden" -> R.string.country_sweden
    "norway" -> R.string.country_norway
    "south korea" -> R.string.country_korea
    "india" -> R.string.country_india
    "singapore" -> R.string.country_singapore
    "hong kong" -> R.string.country_hk
    "taiwan" -> R.string.country_tw
    "brazil" -> R.string.country_brazil
    "mexico" -> R.string.country_mexico
    "south africa" -> R.string.country_south_africa
    "turkey", "türkiye" -> R.string.country_turkey
    "russia" -> R.string.country_russia
    else -> null
}

@StringRes
internal fun assetLabelResource(symbol: String): Int? = when (symbol.trim().lowercase(Locale.ROOT)) {
    "nasdaq100", "nasdaq" -> R.string.asset_nasdaq
    "sp500", "s&p 500" -> R.string.asset_sp500
    "gold" -> R.string.asset_gold
    "silver" -> R.string.asset_silver
    "dxy" -> R.string.asset_dxy
    "eur_usd", "eurusd", "eur/usd" -> R.string.asset_eurusd
    "us2y", "us 2y" -> R.string.asset_us2y
    "us10y", "us 10y" -> R.string.asset_us10y
    "wti", "oil", "crude_oil" -> R.string.asset_wti
    "natural_gas", "natural gas", "natgas" -> R.string.asset_natural_gas
    "bitcoin", "btc" -> R.string.asset_bitcoin
    "ethereum", "eth" -> R.string.asset_ethereum
    else -> null
}

@StringRes
internal fun categoryLabelResource(category: String): Int? = when (category.trim().lowercase(Locale.ROOT)) {
    "inflation" -> R.string.category_inflation
    "employment", "labour", "labor" -> R.string.category_employment
    "growth", "gdp" -> R.string.category_growth
    "interest rate", "interest rates", "monetary policy" -> R.string.category_rates
    "consumer", "consumption" -> R.string.category_consumption
    "consumer confidence" -> R.string.category_consumer_confidence
    "business confidence" -> R.string.category_business_confidence
    "retail sales" -> R.string.category_retail
    "trade", "balance of trade" -> R.string.category_trade
    "housing" -> R.string.category_housing
    "manufacturing" -> R.string.category_manufacturing
    "services" -> R.string.category_services
    "government", "government budget" -> R.string.category_government
    "bonds" -> R.string.category_bonds
    "foreign exchange reserves" -> R.string.category_reserves
    else -> null
}

@StringRes
internal fun macroSignalResource(signal: String): Int? = when (signal.trim().lowercase(Locale.ROOT)) {
    "strong_hawkish" -> R.string.signal_strong_hawkish
    "hawkish" -> R.string.signal_hawkish
    "neutral" -> R.string.signal_neutral
    "dovish" -> R.string.signal_dovish
    "strong_dovish" -> R.string.signal_strong_dovish
    else -> null
}

@StringRes
internal fun importanceLabelResource(importance: Int): Int = when (importance) {
    3 -> R.string.importance_high
    2 -> R.string.importance_medium
    1 -> R.string.importance_low
    else -> R.string.importance_unrated
}

@StringRes
internal fun statusLabelResource(status: String): Int? = when (status) {
    "scheduled" -> R.string.status_scheduled
    "data_unavailable" -> R.string.status_data_unavailable
    "watching" -> R.string.status_watching
    "released" -> R.string.status_released
    "collecting_market_data" -> R.string.status_collecting
    "analyzing" -> R.string.status_analyzing
    "completed" -> R.string.status_completed
    "timeout" -> R.string.status_timeout
    "historical" -> R.string.status_historical
    else -> null
}

// The rule engine emits keys, never prose, so the same release reads identically in every
// language without spending an AI call on fixed text. Unknown values fall back verbatim.
@StringRes
internal fun expectedRationaleResource(value: String): Int? = when (value.trim().lowercase(Locale.ROOT)) {
    "tighter_policy_baseline" -> R.string.rationale_tighter_baseline
    "easier_policy_baseline" -> R.string.rationale_easier_baseline
    "no_directional_signal" -> R.string.rationale_no_directional_signal
    else -> null
}

@StringRes
internal fun analysisSummaryResource(value: String): Int? = when (value.trim()) {
    "rule_engine_summary" -> R.string.analysis_rule_summary
    else -> null
}
