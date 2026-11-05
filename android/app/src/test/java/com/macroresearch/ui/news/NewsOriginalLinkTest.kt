package com.macroresearch.ui.news

import org.junit.Assert.*
import org.junit.Test

class NewsOriginalLinkTest {
    @Test fun keepsOriginalPublisherPathQueryAndFragmentWithoutResolvingOrFetchingAnything() {
        val url = "https://www.bbc.com/news/articles/report?source=rss#section"
        assertEquals(url, originalNewsBrowserUrl(url))
        assertEquals("https://example.test/report", originalNewsBrowserUrl("https://example.test:443/report"))
    }
    @Test fun refusesCredentialsActiveSchemesNonPublicLiteralHostsAndNonHttpsPorts() {
        for (url in listOf("", "http://publisher.example/report", "javascript:alert(1)", "intent://publisher.example/report",
            "file:///private", "content://private", "https://user:pass@publisher.example/report", "https://publisher.example:8443/a",
            "https://localhost/a", "https://localhost./a", "https://host.local/a", "https://host.internal/a",
            "https://127.0.0.1/a", "https://[::1]/a", "https://8.8.8.8/a", "https://intranet/a")) {
            assertNull(url, originalNewsBrowserUrl(url))
        }
    }
    @Test fun refusesOversizedLinksInsteadOfDispatchingAnOversizedIntent() {
        assertNull(originalNewsBrowserUrl("https://publisher.example/" + "x".repeat(4096)))
    }
}
