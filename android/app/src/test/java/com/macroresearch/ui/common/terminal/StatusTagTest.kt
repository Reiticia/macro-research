package com.macroresearch.ui.common.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTagTest {
    @Test fun everyEventStatusMapsToOneTone() {
        assertEquals(StatusTone.Pending, statusTone("scheduled"))
        assertEquals(StatusTone.Pending, statusTone("watching"))
        assertEquals(StatusTone.InFlight, statusTone("released"))
        assertEquals(StatusTone.InFlight, statusTone("collecting_market_data"))
        assertEquals(StatusTone.InFlight, statusTone("analyzing"))
        assertEquals(StatusTone.Done, statusTone("completed"))
        assertEquals(StatusTone.Failed, statusTone("timeout"))
        assertEquals(StatusTone.Neutral, statusTone("data_unavailable"))
        assertEquals(StatusTone.Neutral, statusTone("historical"))
        assertEquals(StatusTone.Neutral, statusTone("unknown-from-newer-backend"))
    }
}
