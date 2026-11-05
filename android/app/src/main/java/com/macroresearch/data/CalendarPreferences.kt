package com.macroresearch.data

import android.content.Context
import com.macroresearch.data.model.EarningsSort
import com.macroresearch.data.model.EarningsSortDirection
import com.macroresearch.data.model.EarningsSortField
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
    private val mutableEventTimeDisplay = MutableStateFlow(EventTimeDisplay.fromStored(preferences.getString("event_time_display", null)))
    val eventTimeDisplay = mutableEventTimeDisplay.asStateFlow()

    fun setEventTimeDisplay(mode: EventTimeDisplay) {
        preferences.edit().putString("event_time_display", mode.name).apply()
        mutableEventTimeDisplay.value = mode
    }
    private val mutableEarningsSort = MutableStateFlow(EarningsSort(
        field = EarningsSortField.fromStored(preferences.getString("earnings_sort_field", null)),
        direction = EarningsSortDirection.fromStored(preferences.getString("earnings_sort_direction", null)),
    ))
    val earningsSort = mutableEarningsSort.asStateFlow()

    fun setEarningsSort(sort: EarningsSort) {
        preferences.edit()
            .putString("earnings_sort_field", sort.field.name)
            .putString("earnings_sort_direction", sort.direction.name)
            .apply()
        mutableEarningsSort.value = sort
    }

    fun setMode(mode: CalendarDisplayMode) {
        preferences.edit().putString("mode", mode.name).apply()
        mutable.value = mode
    }
}
