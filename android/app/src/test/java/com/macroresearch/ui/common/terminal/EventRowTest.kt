package com.macroresearch.ui.common.terminal

import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.ui.common.localNumericDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class EventRowTest {
    private fun event(actual: String?, consensus: String? = "0.3", forecast: String? = "0.3") = EconomicEvent(
        1, "trading_view", "1", null, "United States", "USD", "inflation", "Core CPI MoM",
        eventTime = "2026-10-14T12:30:00Z", importance = 3, actual = actual, previous = "0.2",
        consensus = consensus, forecast = forecast, unit = "%", status = "released",
    )

    @Test fun surpriseMarkerComparesActualWithMarketExpectation() {
        assertEquals(SurpriseMarker.Above, surpriseMarker(event("0.4")))
        assertEquals(SurpriseMarker.Below, surpriseMarker(event("0.2")))
        assertEquals(SurpriseMarker.None, surpriseMarker(event("0.3")))
        assertEquals(SurpriseMarker.None, surpriseMarker(event(null)))
        // 预期缺失时回退到预测值，与列表"预期"列一致。
        assertEquals(SurpriseMarker.Below, surpriseMarker(event("0.4", consensus = null, forecast = "0.5")))
        assertEquals(SurpriseMarker.None, surpriseMarker(event("0.4", consensus = null, forecast = null)))
    }

    @Test fun numericDateUsesDeviceZoneAndFallsBack() {
        assertEquals("10/14", event(null).localNumericDate(ZoneId.of("UTC")))
        assertEquals("10/15", event(null).copy(eventTime = "2026-10-14T20:30:00Z").localNumericDate(ZoneId.of("Asia/Shanghai")))
        assertEquals("--/--", event(null).copy(eventTime = "not-a-time").localNumericDate(ZoneId.of("UTC")))
    }
}
