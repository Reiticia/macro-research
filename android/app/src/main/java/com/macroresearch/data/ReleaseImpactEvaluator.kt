package com.macroresearch.data

import com.macroresearch.data.model.EconomicEvent
import java.math.BigDecimal
import java.util.Locale

enum class ReleaseImpactStatus { WAITING, MISSING_EXPECTATION, INVALID_NUMBER, UNKNOWN_RULE, UNKNOWN_UNIT, EQUAL, DIRECTIONAL }
enum class ReleaseImpactStrength { NONE, LIMITED, MATERIAL }
enum class ReleaseImpactDirection { UP, DOWN, FLAT }
enum class ReleaseImpactUnit { PERCENTAGE_POINTS, PEOPLE, INDEX_POINTS, BARRELS }
enum class ReleaseImpactBasis { CONSENSUS, FORECAST }
enum class ReleaseImpactRationale { HAWKISH, DOVISH, OIL_INVENTORY, EQUAL }

data class AssetReleaseImpact(val symbol: String, val direction: ReleaseImpactDirection)
data class ReleaseImpact(
    val status: ReleaseImpactStatus,
    val basis: ReleaseImpactBasis? = null,
    /** Original source value, for display beside the source's actual value. */
    val expectation: String? = null,
    val surprise: BigDecimal? = null,
    val unit: ReleaseImpactUnit? = null,
    val threshold: BigDecimal? = null,
    val ruleId: String? = null,
    val strength: ReleaseImpactStrength = ReleaseImpactStrength.NONE,
    val assets: List<AssetReleaseImpact> = emptyList(),
    val rationale: ReleaseImpactRationale? = null,
)

/** Instant single-release baseline. Intentionally independent of AI, prices and AnalysisReport. */
object ReleaseImpactEvaluator {
    fun evaluate(event: EconomicEvent): ReleaseImpact {
        if (event.actual.isNullOrBlank()) return ReleaseImpact(ReleaseImpactStatus.WAITING)
        val actual = event.actual.trim().toBigDecimalOrNull()
            ?: return ReleaseImpact(ReleaseImpactStatus.INVALID_NUMBER)
        val consensus = event.consensus?.trim()?.toBigDecimalOrNull()
        val forecast = event.forecast?.trim()?.toBigDecimalOrNull()
        val expectation = consensus ?: forecast
            ?: return ReleaseImpact(ReleaseImpactStatus.MISSING_EXPECTATION)
        val basis = if (consensus != null) ReleaseImpactBasis.CONSENSUS else ReleaseImpactBasis.FORECAST
        val originalExpectation = if (consensus != null) event.consensus else event.forecast
        val base = ReleaseImpact(ReleaseImpactStatus.UNKNOWN_RULE, basis, originalExpectation)
        if (event.country.trim().lowercase(Locale.ROOT) !in setOf("us", "usa", "united states", "united states of america")) return base
        val rule = ReleaseImpactRules.match(event.event) ?: return base
        val unit = ReleaseImpactUnit.valueOf(rule.unit.name)
        val withRule = base.copy(ruleId = rule.id, unit = unit, threshold = rule.threshold)
        // Indicator identity tells us the dimension, not the provider's numeric scale.
        // Missing metadata must not silently multiply employment by 1,000 or oil by 1,000,000.
        val multiplier = multiplier(event, rule)
            ?: return withRule.copy(status = ReleaseImpactStatus.UNKNOWN_UNIT)
        val surprise = (actual - expectation) * multiplier
        val metadata = withRule.copy(surprise = surprise)
        val oil = rule.higher == ReleaseImpactRules.Higher.BEARISH_OIL
        val symbols = if (oil) listOf("wti", "brent") else listOf("dxy", "gold", "nasdaq100", "bitcoin", "us2y", "us10y")
        if (surprise.signum() == 0) return metadata.copy(
            status = ReleaseImpactStatus.EQUAL,
            assets = symbols.map { AssetReleaseImpact(it, ReleaseImpactDirection.FLAT) },
            rationale = ReleaseImpactRationale.EQUAL,
        )
        val sign = surprise.signum()
        val macroDirection = sign * if (rule.higher == ReleaseImpactRules.Higher.HAWKISH) 1 else -1
        fun direction(value: Int) = if (value > 0) ReleaseImpactDirection.UP else ReleaseImpactDirection.DOWN
        return metadata.copy(
            status = ReleaseImpactStatus.DIRECTIONAL,
            strength = if (surprise.abs() < rule.threshold) ReleaseImpactStrength.LIMITED else ReleaseImpactStrength.MATERIAL,
            assets = if (oil) symbols.map { AssetReleaseImpact(it, direction(-sign)) }
                else symbols.map { AssetReleaseImpact(it, direction(if (it in setOf("dxy", "us2y", "us10y")) macroDirection else -macroDirection)) },
            rationale = when {
                oil -> ReleaseImpactRationale.OIL_INVENTORY
                macroDirection > 0 -> ReleaseImpactRationale.HAWKISH
                else -> ReleaseImpactRationale.DOVISH
            },
        )
    }

    private fun multiplier(event: EconomicEvent, rule: ReleaseImpactRules.Rule): BigDecimal? {
        val unit = rule.unit
        val rawUnit = event.unit?.trim().orEmpty()
        if (rawUnit.isEmpty()) return null
        val sourceUnit = rawUnit.lowercase(Locale.ROOT)
        return when (unit) {
            ReleaseImpactRules.Unit.PERCENTAGE_POINTS -> if (sourceUnit in setOf("%", "percent", "percentage", "percentage points", "pp")) BigDecimal.ONE else null
            ReleaseImpactRules.Unit.INDEX_POINTS -> if (sourceUnit in setOf("number", "index", "points", "point", "index points")) BigDecimal.ONE else null
            ReleaseImpactRules.Unit.PEOPLE, ReleaseImpactRules.Unit.BARRELS -> {
                // FF (also through the backend) already expands K/M/B into absolute numbers.
                // Its legacy 'currency' unit is inferred from M, even for oil inventory barrels.
                if (event.provider == "forex_factory" && sourceUnit in setOf("count", "currency")) return BigDecimal.ONE
                // 'number' is a source-normalized absolute quantity (not a currency).
                if (sourceUnit == "number") return BigDecimal.ONE
                // A weekly row hydrated from TradingView may retain its provider id but carry
                // the primary's explicit K/M unit. Those values have not yet been expanded.
                val typedPeople = setOf("thousand persons", "thousand people", "million persons", "million people")
                val typedBarrels = setOf("thousand barrels", "k barrels", "million barrels", "m barrels")
                if (unit == ReleaseImpactRules.Unit.PEOPLE && sourceUnit in typedBarrels ||
                    unit == ReleaseImpactRules.Unit.BARRELS && sourceUnit in typedPeople) return null
                when (sourceUnit) {
                    "count", "people", "persons", "person", "jobs" -> if (unit == ReleaseImpactRules.Unit.PEOPLE) BigDecimal.ONE else null
                    "barrels", "barrel", "bbl" -> if (unit == ReleaseImpactRules.Unit.BARRELS) BigDecimal.ONE else null
                    "k", "thousand", "thousands", "thousand persons", "thousand people", "thousand barrels", "k barrels" -> BigDecimal("1000")
                    "m", "million", "millions", "million persons", "million people", "million barrels", "m barrels" -> BigDecimal("1000000")
                    else -> null // No magnitude guessing from the value itself.
                }
            }
        }
    }
}
