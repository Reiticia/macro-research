package com.macroresearch.data

/** Changes macro-event clocks only, not date buckets, instants or earnings market dates. */
enum class EventTimeDisplay {
    DEVICE, REGION, BOTH;

    companion object {
        fun fromStored(value: String?): EventTimeDisplay = entries.firstOrNull { it.name == value } ?: BOTH
    }
}
