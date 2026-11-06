package com.macroresearch.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.macroresearch.data.NewsReaderCache
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NewsServerTitleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val gson = Gson()
    private fun page(translations: String? = null) = JsonParser.parseString("""{
        "updatedAt":"${Instant.now()}","stale":false,"partial":false,
        "sources":[{"id":"fed","name":"Federal Reserve","status":"fresh","updatedAt":"${Instant.now()}"}],
        "articles":[{"archiveId":"${"a".repeat(64)}","title":"Original title","source":"Federal Reserve","feedId":"fed",
        "url":"https://www.federalreserve.gov/newsevents/test.htm","publishedAt":"${Instant.now().minusSeconds(20)}","summary":"Original summary"
        ${if (translations == null) "" else ",\"titleTranslations\":$translations"}}]
    }""").asJsonObject

    @Test fun serverContractHasAllThreeLanguagesAndKeepsOriginalEvidence() {
        val rows = decodeReaderNews(page("""{"en":"English","zh-CN":"简体","zh-TW":"繁體"}"""), gson)
        val row = rows.articles.single()
        assertEquals(mapOf("en" to "English", "zh-CN" to "简体", "zh-TW" to "繁體"), row.titleTranslations)
        assertEquals("Original title", row.title)
        assertEquals("Original summary", row.summary)
        assertEquals("https://www.federalreserve.gov/newsevents/test.htm", row.url)
        val cache = NewsReaderCache(temporary.newFile("headlines.json"), gson)
        cache.save(rows)
        assertEquals(row.titleTranslations, cache.load().articles.single().titleTranslations)
    }

    @Test fun oldServersDisabledTranslationAndMalformedOptionalValuesStillShowOriginalNews() {
        for (value in listOf(null, "null", "[]", "{}", """{"en":null,"zh-CN":{},"zh-TW":4,"fr":"unsupported"}""",
            """{"en":"<script>bad</script>","zh-CN":"","zh-TW":"line\nline"}""")) {
            val row = decodeReaderNews(page(value), gson).articles.single()
            assertEquals("Original title", row.title)
            assertTrue(row.titleTranslations.orEmpty().isEmpty())
        }
    }

    @Test fun onlySupportedBoundedPlainTextTitlesAreAccepted() {
        val row = decodeReaderNews(page("""{"en":"valid","zh-CN":"${"x".repeat(601)}","fr":"unsupported"}"""), gson).articles.single()
        assertEquals(mapOf("en" to "valid"), row.titleTranslations)
        assertEquals(emptyMap<String,String>(), safeNewsTitleTranslations(mapOf("en" to null, "zh-CN" to 4)))
    }
}
