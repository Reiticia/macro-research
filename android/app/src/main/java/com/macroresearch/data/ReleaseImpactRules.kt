package com.macroresearch.data

import java.math.BigDecimal

/** Versioned, deliberately conservative defaults; these are heuristics, not calibrated statistics. */
internal object ReleaseImpactRules {
    enum class Unit { PERCENTAGE_POINTS, PEOPLE, INDEX_POINTS, BARRELS }
    enum class Higher { HAWKISH, DOVISH, BEARISH_OIL }
    data class Rule(
        val id: String,
        val aliases: List<String>,
        val unit: Unit,
        val threshold: BigDecimal,
        val higher: Higher = Higher.HAWKISH,
    )

    private fun rule(id: String, unit: Unit, threshold: String, vararg aliases: String,
                     higher: Higher = Higher.HAWKISH) =
        Rule(
            id = id,
            aliases = aliases.toList(),
            unit = unit,
            threshold = BigDecimal(threshold),
            higher = higher,
        )

    // Specific/core indicators must win over headline aliases. Only rate releases, not price levels.
    val rules = listOf(
        rule("core_cpi", Unit.PERCENTAGE_POINTS, "0.1", "Core Inflation Rate MoM", "Core Inflation Rate YoY",
            "Core CPI MoM", "Core CPI YoY", "Core Consumer Prices MoM", "Core Consumer Prices YoY"),
        rule("cpi", Unit.PERCENTAGE_POINTS, "0.1", "Inflation Rate MoM", "Inflation Rate YoY", "CPI MoM", "CPI YoY",
            "Consumer Price Index MoM", "Consumer Price Index YoY"),
        rule("core_pce", Unit.PERCENTAGE_POINTS, "0.1", "Core PCE Price Index MoM", "Core PCE Price Index YoY",
            "Core PCE MoM", "Core PCE YoY"),
        rule("pce", Unit.PERCENTAGE_POINTS, "0.1", "PCE Price Index MoM", "PCE Price Index YoY", "PCE MoM", "PCE YoY"),
        rule("core_ppi", Unit.PERCENTAGE_POINTS, "0.1", "Core PPI MoM", "Core PPI YoY",
            "Core Producer Price Index MoM", "Core Producer Price Index YoY"),
        rule("ppi", Unit.PERCENTAGE_POINTS, "0.1", "PPI MoM", "PPI YoY", "Producer Price Index MoM", "Producer Price Index YoY"),
        rule("earnings", Unit.PERCENTAGE_POINTS, "0.1", "Average Hourly Earnings MoM", "Average Hourly Earnings YoY"),
        rule("payrolls", Unit.PEOPLE, "50000", "Non Farm Payrolls", "Nonfarm Payrolls", "Non Farm Employment Change",
            "Nonfarm Employment Change", "Non Farm Payroll", "Nonfarm Payroll", "NFP"),
        rule("unemployment", Unit.PERCENTAGE_POINTS, "0.1", "Unemployment Rate", higher = Higher.DOVISH),
        rule("initial_claims", Unit.PEOPLE, "10000", "Initial Jobless Claims", "Unemployment Claims", higher = Higher.DOVISH),
        rule("gdp", Unit.PERCENTAGE_POINTS, "0.5", "GDP Growth Rate", "GDP QoQ", "GDP Annualized QoQ"),
        rule("ism_manufacturing", Unit.INDEX_POINTS, "1", "ISM Manufacturing PMI", "ISM Manufacturing"),
        rule("ism_services", Unit.INDEX_POINTS, "1", "ISM Services PMI", "ISM Non Manufacturing PMI", "ISM Non Manufacturing"),
        rule("fed_rate", Unit.PERCENTAGE_POINTS, "0.25", "Fed Interest Rate Decision", "FOMC Rate Decision", "Federal Funds Rate"),
        rule("oil_inventory", Unit.BARRELS, "1000000", "EIA Crude Oil Stocks Change", "Crude Oil Stocks Change",
            "Crude Oil Inventories", higher = Higher.BEARISH_OIL),
    )

    internal fun normalize(name: String): String = name.lowercase(java.util.Locale.ROOT)
        .replace("m/m", "mom").replace("y/y", "yoy").replace("q/q", "qoq")
        .replace("non-farm", "non farm").replace(Regex("[^a-z0-9]+"), " ").trim()

    fun match(name: String): Rule? {
        val normalized = " ${normalize(name)} "
        // Prefer the longest explicit alias rather than allowing a generic substring to win.
        val qualifiers = setOf("us", "usa", "united", "states", "mom", "yoy", "qoq", "annualized",
            "adv", "advance", "prel", "prelim", "preliminary", "final", "flash", "revised", "sa", "nsa")
        return rules.flatMap { rule -> rule.aliases.map { rule to normalize(it) } }
            .filter { (_, alias) ->
                val token = " $alias "
                if (!normalized.contains(token)) false
                else normalized.replace(token, " ").trim().split(Regex(" +"))
                    .all { it.isEmpty() || it in qualifiers }
            }
            .maxByOrNull { (_, alias) -> alias.length }?.first
    }
}
