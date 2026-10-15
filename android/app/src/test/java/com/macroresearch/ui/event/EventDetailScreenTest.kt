package com.macroresearch.ui.event

import org.junit.Assert.assertEquals
import org.junit.Test

class EventDetailScreenTest {
    @Test fun horizonsCompleteByElapsedMinutesOrCompletedStatus() {
        assertEquals(listOf(false, false, false, false, false), reachedHorizons(0, "analyzing"))
        assertEquals(listOf(true, true, false, false, false), reachedHorizons(5, "collecting_market_data"))
        assertEquals(listOf(true, true, true, true, true), reachedHorizons(60, "analyzing"))
        assertEquals(listOf(true, true, true, true, true), reachedHorizons(-1, "completed"))
    }
}
