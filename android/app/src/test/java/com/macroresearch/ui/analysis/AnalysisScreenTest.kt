package com.macroresearch.ui.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisScreenTest {
    @Test fun rawSurpriseKeepsSignAndPercentUnit() {
        assertEquals("+0.2%", formatRawSurprise("0.2", "%"))
        assertEquals("-0.1", formatRawSurprise("-0.1", null))
        assertEquals("+0", formatRawSurprise("0", "K"))
        assertEquals("--", formatRawSurprise(null, "%"))
    }
}
