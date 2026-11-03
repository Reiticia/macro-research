package com.macroresearch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistent calendar visibility, independent of data source and temporary agenda filters. */
enum class CalendarDisplayMode {
    ALL, EARNINGS, ECONOMIC;

    companion object {
        fun fromStored(value: String?): CalendarDisplayMode = entries.firstOrNull { it.name == value } ?: ALL
    }
}

class CalendarPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("calendar_display", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(CalendarDisplayMode.fromStored(preferences.getString("mode", null)))
    val mode = mutable.asStateFlow()

    fun setMode(mode: CalendarDisplayMode) {
        preferences.edit().putString("mode", mode.name).apply()
        mutable.value = mode
    }
}
