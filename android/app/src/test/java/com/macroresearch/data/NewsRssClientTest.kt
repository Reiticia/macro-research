package com.macroresearch.data

import com.macroresearch.data.remote.NewsRssClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsRssClientTest {
    private val client = NewsRssClient(OkHttpClient())

    @Test
    fun parsesRssMetadataAndCleansHtmlWithoutFetchingArticlePages() {
        val articles = client.parseFeed(
            """
                <rss version="2.0"><channel><item>
                  <title>Fed signals <b>rate</b> caution</title>
                  <link>https://example.com/story</link>
                  <description><![CDATA[<p>Markets react to <b>Federal Reserve</b> outlook.</p>]]></description>
                  <pubDate>Wed, 23 Sep 2026 14:00:00 GMT</pubDate>
                  <source>Example News</source>
                </item></channel></rss>
            """.trimIndent(),
            "https://example.com/feed.xml",
        )

        assertEquals(1, articles.size)
        assertEquals("Fed signals rate caution", articles.single().title)
        assertEquals("https://example.com/story", articles.single().url)
        assertEquals("Example News", articles.single().source)
        assertEquals("Wed, 23 Sep 2026 14:00:00 GMT", articles.single().publishedAt)
        assertTrue(articles.single().summary.orEmpty().contains("Federal Reserve"))
    }

    @Test
    fun parsesAtomLinksAndRejectsNonHttpsArticleUrls() {
        val articles = client.parseFeed(
            """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry><title>Central bank update</title>
                    <link href="https://example.com/central-bank" />
                    <updated>2026-09-23T14:00:00Z</updated><summary>Rates and outlook</summary>
                  </entry>
                  <entry><title>Unsafe article</title><link href="http://example.com/unsafe" /></entry>
                </feed>
            """.trimIndent(),
            "https://example.com/atom.xml",
        )

        assertEquals(1, articles.size)
        assertEquals("https://example.com/central-bank", articles.single().url)
        assertEquals("2026-09-23T14:00:00Z", articles.single().publishedAt)
        assertEquals("example.com", articles.single().source)
    }
}
