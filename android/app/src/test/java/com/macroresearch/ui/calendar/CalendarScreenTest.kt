package com.macroresearch.ui.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarScreenTest {
    @Test fun weekStartsOnMondayAndContainsSelectedDay() {
        val wednesday = LocalDate.of(2026, 10, 14)
        val week = weekOf(wednesday)
        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 10, 12), week.first())
        assertEquals(DayOfWeek.SUNDAY, week.last().dayOfWeek)
        assertEquals(week, weekOf(LocalDate.of(2026, 10, 18)))
        assertEquals(LocalDate.of(2026, 10, 19), weekOf(LocalDate.of(2026, 10, 19)).first())
    }
}
