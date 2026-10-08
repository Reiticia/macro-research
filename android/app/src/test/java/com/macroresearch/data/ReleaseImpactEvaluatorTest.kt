package com.macroresearch.data

import com.macroresearch.data.model.EconomicEvent
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ReleaseImpactEvaluatorTest {
    private fun event(name: String = "Core CPI m/m", actual: String? = "0.4", expectation: String? = "0.3", unit: String? = "%") = EconomicEvent(
        id = 1, provider = "trading_view", providerId = "1", releaseGroupId = null,
        country = "United States", currency = "USD", category = "inflation", event = name,
        eventTime = "2026-09-10T12:30:00Z", importance = 3, actual = actual, previous = "0.2",
        consensus = expectation, forecast = expectation, unit = unit, status = "released",
    )

    @Test fun everyRuleHandlesPositiveNegativeZeroAndThresholdBoundaries() {
        ReleaseImpactRules.rules.forEach { rule ->
            val unit = when (rule.unit) {
                ReleaseImpactRules.Unit.PERCENTAGE_POINTS -> "%"
                ReleaseImpactRules.Unit.PEOPLE -> "people"
                ReleaseImpactRules.Unit.INDEX_POINTS -> "points"
                ReleaseImpactRules.Unit.BARRELS -> "barrels"
            }
            val baseline = BigDecimal("10")
            val small = rule.threshold.divide(BigDecimal.TEN)
            val material = rule.threshold
            for ((delta, expectedStrength) in listOf(
                small to ReleaseImpactStrength.LIMITED,
                material to ReleaseImpactStrength.MATERIAL,
                (material + small) to ReleaseImpactStrength.MATERIAL,
            )) for (sign in listOf(1, -1)) {
                val result = ReleaseImpactEvaluator.evaluate(event(rule.aliases.first(),
                    (baseline + delta * sign.toBigDecimal()).toPlainString(), baseline.toPlainString(), unit))
                assertEquals(rule.id, result.ruleId)
                assertEquals(ReleaseImpactStatus.DIRECTIONAL, result.status)
                assertEquals(expectedStrength, result.strength)
                val symbol = if (rule.higher == ReleaseImpactRules.Higher.BEARISH_OIL) "wti" else "dxy"
                val up = sign * (if (rule.higher == ReleaseImpactRules.Higher.HAWKISH) 1 else -1) > 0
                assertEquals(if (up) ReleaseImpactDirection.UP else ReleaseImpactDirection.DOWN,
                    result.assets.single { it.symbol == symbol }.direction)
            }
            val equal = ReleaseImpactEvaluator.evaluate(event(rule.aliases.first(), "10.00", "10", unit))
            assertEquals(rule.id, equal.ruleId)
            assertEquals(ReleaseImpactStatus.EQUAL, equal.status)
            assertEquals(ReleaseImpactStrength.NONE, equal.strength)
            assertTrue(equal.assets.all { it.direction == ReleaseImpactDirection.FLAT })
        }
    }

    @Test fun hawkishPrintHasSeparateAssetDirectionsNotAnOverallBullishLabel() {
        val result = ReleaseImpactEvaluator.evaluate(event())
        assertEquals(listOf("dxy", "gold", "nasdaq100", "bitcoin", "us2y", "us10y"), result.assets.map { it.symbol })
        assertEquals(ReleaseImpactDirection.UP, result.assets.first().direction)
        assertTrue(result.assets.filter { it.symbol in listOf("gold", "nasdaq100", "bitcoin") }
            .all { it.direction == ReleaseImpactDirection.DOWN })
        assertTrue(result.assets.filter { it.symbol.startsWith("us") }.all { it.direction == ReleaseImpactDirection.UP })
    }

    @Test fun weakPrintRetainsDirectionWithLimitedStrength() {
        val result = ReleaseImpactEvaluator.evaluate(event(actual = "0.31"))
        assertEquals(ReleaseImpactStrength.LIMITED, result.strength)
        assertEquals(ReleaseImpactDirection.DOWN, result.assets.single { it.symbol == "gold" }.direction)
    }

    @Test fun consensusWinsAndInvalidConsensusFallsBackToForecastNeverPrevious() {
        val source = event(actual = "0.4", expectation = "0.5").copy(forecast = "0.3")
        assertEquals(ReleaseImpactBasis.CONSENSUS, ReleaseImpactEvaluator.evaluate(source).basis)
        val fallback = ReleaseImpactEvaluator.evaluate(source.copy(consensus = " N/A "))
        assertEquals(ReleaseImpactBasis.FORECAST, fallback.basis)
        assertEquals("0.3", fallback.expectation)
        assertEquals(0, BigDecimal("0.1").compareTo(fallback.surprise))
        assertEquals(ReleaseImpactStatus.MISSING_EXPECTATION,
            ReleaseImpactEvaluator.evaluate(source.copy(consensus = "", forecast = null)).status)
        assertEquals(ReleaseImpactStatus.DIRECTIONAL, ReleaseImpactEvaluator.evaluate(event(actual = "0.1", expectation = "0")).status)
    }

    @Test fun unavailableValuesAndUncoveredEventsAreNotNeutral() {
        assertEquals(ReleaseImpactStatus.WAITING, ReleaseImpactEvaluator.evaluate(event(actual = " ")).status)
        assertEquals(ReleaseImpactStatus.INVALID_NUMBER, ReleaseImpactEvaluator.evaluate(event(actual = "text")).status)
        assertEquals(ReleaseImpactStatus.UNKNOWN_RULE, ReleaseImpactEvaluator.evaluate(event().copy(country = "Canada")).status)
        assertEquals(ReleaseImpactStatus.UNKNOWN_RULE, ReleaseImpactEvaluator.evaluate(event("Speech", "1", "1")).status)
        assertEquals(ReleaseImpactStatus.UNKNOWN_UNIT, ReleaseImpactEvaluator.evaluate(event(unit = null)).status)
        assertEquals(ReleaseImpactStatus.UNKNOWN_UNIT, ReleaseImpactEvaluator.evaluate(event("Non Farm Payrolls", "250", "200", null)).status)
    }

    @Test fun countryCodesAliasesAndTranslationsDoNotChangeMatching() {
        assertEquals("core_cpi", ReleaseImpactEvaluator.evaluate(event("US Core CPI y/y").copy(country = "US", eventZhCn = "其他译名")).ruleId)
        assertEquals("core_pce", ReleaseImpactEvaluator.evaluate(event("Core PCE Price Index MoM")).ruleId)
        assertEquals("core_ppi", ReleaseImpactEvaluator.evaluate(event("Core PPI m/m")).ruleId)
        assertEquals("gdp", ReleaseImpactEvaluator.evaluate(event("GDP Growth Rate QoQ Adv")).ruleId)
        for (name in listOf("ISM Manufacturing Prices", "ISM Manufacturing Employment", "ADP Nonfarm Employment Change", "Continuing Jobless Claims", "Core CPI Index Level", "NFPfake")) {
            assertEquals(name, ReleaseImpactStatus.UNKNOWN_RULE, ReleaseImpactEvaluator.evaluate(event(name)).status)
        }
    }

    @Test fun countScalesAreNormalizedExactlyOnceAcrossDirectAndBackendProviders() {
        val scaled = ReleaseImpactEvaluator.evaluate(event("Non Farm Payrolls", "250", "200", "K"))
        val expanded = ReleaseImpactEvaluator.evaluate(event("Non Farm Payrolls", "250000", "200000", "count").copy(provider = "forex_factory"))
        assertEquals(0, scaled.surprise!!.compareTo(BigDecimal("50000")))
        assertEquals(0, scaled.surprise.compareTo(expanded.surprise))
        assertEquals(scaled.strength, expanded.strength)
        val hydratedWeekly = ReleaseImpactEvaluator.evaluate(event("Non Farm Payrolls", "250", "200", "K").copy(provider = "forex_factory"))
        assertEquals(0, scaled.surprise.compareTo(hydratedWeekly.surprise))
        assertEquals(ReleaseImpactStatus.UNKNOWN_UNIT,
            ReleaseImpactEvaluator.evaluate(event("Non Farm Payrolls", "250", "200", "Million Barrels")).status)
        assertEquals(ReleaseImpactStrength.LIMITED, ReleaseImpactEvaluator.evaluate(event("Initial Jobless Claims", "211", "210", "Thousand")).strength)
    }

    @Test fun negativeOilInventoryOnlyAffectsOilAndSupportsLegacyExpandedUnits() {
        val result = ReleaseImpactEvaluator.evaluate(event("Crude Oil Inventories", "-2000000", "-500000", "currency").copy(provider = "forex_factory"))
        assertEquals(ReleaseImpactStrength.MATERIAL, result.strength)
        assertEquals(listOf("wti", "brent"), result.assets.map { it.symbol })
        assertTrue(result.assets.all { it.direction == ReleaseImpactDirection.UP })
        val raw = ReleaseImpactEvaluator.evaluate(event("EIA Crude Oil Stocks Change", "-2", "-0.5", "Million Barrels"))
        assertEquals(0, raw.surprise!!.compareTo(result.surprise))
    }
}
