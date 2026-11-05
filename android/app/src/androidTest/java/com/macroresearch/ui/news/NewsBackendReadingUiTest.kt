package com.macroresearch.ui.news

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.remote.NewsArticle
import com.macroresearch.data.remote.ArticleReading
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewsBackendReadingUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun backendReadingLabelsSummaryAndOffersNoPublisherWebViewOrPageRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val article = NewsArticle("Policy announcement","https://publisher.example/report","Federal Reserve",
            "2026-11-05T01:31:00Z","Public RSS summary, not a full article.")
        compose.setContent { MaterialTheme {
            NewsReaderScreen(NewsDeskState(article = article,backendNews = true),false,{},{},{_,_,_->},{},{})
        } }
        compose.onNodeWithTag("news-reader-list").performScrollToNode(hasText(context.getString(R.string.news_backend_reading)))
        compose.onNodeWithText(context.getString(R.string.news_backend_reading)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.news_retry_body)).assertDoesNotExist()
        compose.onNodeWithTag("news-reader-list").performScrollToIndex(0)
        compose.onNodeWithText(context.getString(R.string.news_web)).assertDoesNotExist()
    }
    @Test fun savedBackendBodyIsReadableAndKeepsTheOriginalLinkWithoutOpeningMediaSites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val article = NewsArticle("Policy announcement","https://www.federalreserve.gov/newsevents/original.htm","Federal Reserve",
            "2026-11-05T01:31:00Z","RSS summary","a".repeat(64))
        val body = ArticleReading(article.url,article.title,"Saved public article text from the backend.",false,"2026-11-05T01:32:00Z")
        compose.setContent { MaterialTheme {
            NewsReaderScreen(NewsDeskState(article = article,reading = body,backendNews = true),false,{},{},{_,_,_->},{},{})
        } }
        compose.onNodeWithTag("news-reader-list").performScrollToNode(hasText(body.text))
        compose.onNodeWithText(body.text).assertIsDisplayed()
        compose.onNodeWithTag("news-reader-list").performScrollToNode(hasText(article.url))
        compose.onNodeWithText(article.url).assertIsDisplayed()
        compose.onNodeWithTag("news-reader-list").performScrollToIndex(0)
        compose.onNodeWithText(context.getString(R.string.news_web)).assertDoesNotExist()
    }
}
