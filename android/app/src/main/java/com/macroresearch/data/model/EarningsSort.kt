package com.macroresearch.data.model

/** Presentation only: never changes provider snapshots or invents exact release instants. */
enum class EarningsSortField {
    MARKET_CAP, SYMBOL, RELEASE_TIME;

    companion object {
        fun fromStored(value: String?): EarningsSortField = entries.firstOrNull { it.name == value } ?: SYMBOL
    }
}

enum class EarningsSortDirection {
    ASCENDING, DESCENDING;

    companion object {
        fun fromStored(value: String?): EarningsSortDirection = entries.firstOrNull { it.name == value } ?: ASCENDING
    }
}

data class EarningsSort(
    val field: EarningsSortField = EarningsSortField.SYMBOL,
    val direction: EarningsSortDirection = EarningsSortDirection.ASCENDING,
)

/** Missing values stay last in either direction; ties always use the exact stock code. */
fun earningsComparator(sort: EarningsSort): Comparator<EarningsEvent> {
    fun <T : Comparable<T>> compareKnown(left: T?, right: T?): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        sort.direction == EarningsSortDirection.DESCENDING -> right.compareTo(left)
        else -> left.compareTo(right)
    }
    fun sessionRank(event: EarningsEvent): Int? = when (event.session) {
        "pre_market" -> 0
        "after_hours" -> 1
        else -> null
    }
    return Comparator<EarningsEvent> { left, right ->
        when (sort.field) {
            EarningsSortField.MARKET_CAP -> compareKnown(
                left.marketCap?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 },
                right.marketCap?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 },
            )
            EarningsSortField.SYMBOL -> compareKnown(left.symbol, right.symbol)
            EarningsSortField.RELEASE_TIME -> compareKnown(sessionRank(left), sessionRank(right))
        }
    }.thenBy { it.symbol }.thenBy { it.tradingView?.ticker.orEmpty() }.thenBy { it.source }
}

fun sortEarnings(events: List<EarningsEvent>, sort: EarningsSort): List<EarningsEvent> =
    events.sortedWith(earningsComparator(sort))
