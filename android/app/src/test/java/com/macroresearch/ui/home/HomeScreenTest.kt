package com.macroresearch.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenTest {
    @Test
    fun marketOverviewAlwaysUsesTwoColumns() {
        assertEquals(listOf(listOf("a", "b")), marketOverviewRows(listOf("a", "b")))
        assertEquals(listOf(listOf("a", "b"), listOf("c")), marketOverviewRows(listOf("a", "b", "c")))
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d")),
            marketOverviewRows(listOf("a", "b", "c", "d")),
        )
    }
}
