package com.macroresearch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the AI briefing is allowed to use the observed market moves.
 *
 * The moves happen after the release, so feeding them to the model in the same pass invites
 * explaining the chain backwards. [EX_ANTE_THEN_COMPARE] asks for the expectation first and only
 * then reveals the moves, which keeps the first pass honest.
 */
enum class AnalysisMethod(val key: String, val wireValue: Int) {
    /** Released numbers and rule signal only; market moves are never sent. */
    NUMBERS_ONLY("numbers_only", 1),

    /** First pass without moves, second pass comparing that expectation with the moves. */
    EX_ANTE_THEN_COMPARE("ex_ante_then_compare", 2),

    /** One pass with numbers, rules and moves together. */
    SINGLE_PASS("single_pass", 3),

    ;

    companion object {
        val DEFAULT = EX_ANTE_THEN_COMPARE

        fun fromKey(value: String?): AnalysisMethod =
            entries.firstOrNull { it.key == value } ?: DEFAULT
    }
}

class AnalysisPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _method = MutableStateFlow(AnalysisMethod.fromKey(preferences.getString(METHOD_KEY, null)))
    val method: StateFlow<AnalysisMethod> = _method.asStateFlow()

    fun setMethod(method: AnalysisMethod) {
        preferences.edit().putString(METHOD_KEY, method.key).apply()
        _method.value = method
    }

    companion object {
        private const val PREFERENCES_NAME = "research_preferences"
        private const val METHOD_KEY = "ai_analysis_method"
    }
}
