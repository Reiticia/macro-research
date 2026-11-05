package com.macroresearch.ui.news

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.remote.ArticleReading
import com.macroresearch.data.remote.NewsArticle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewsOriginalBrowserUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val original = "https://publisher.example/original?source=rss#part"
    private fun state(backend: Boolean) = NewsDeskState(
        article = NewsArticle("Policy announcement",original,"Publisher","2026-11-05T01:31:00Z","RSS summary"),
        reading = ArticleReading("https://publisher.example/resolved","Policy announcement","Saved public text.",false,"2026-11-05T01:32:00Z"),
        backendNews = backend,
    )
    private fun assertSafeIntent(intent: Intent) {
        assertEquals(Intent.ACTION_VIEW,intent.action)
        assertEquals(original,intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.extras == null || intent.extras!!.isEmpty)
        assertNull(intent.component)
        assertNull(intent.`package`)
    }
    @Test fun backendReadingOpensOnlyTheOriginalUrlOnExplicitClickWithoutHeadersOrPageReadback() {
        val intents = mutableListOf<Intent>()
        val browser = object : ContextWrapper(context) { override fun startActivity(intent: Intent) { intents += intent } }
        compose.setContent { CompositionLocalProvider(LocalContext provides browser) { MaterialTheme {
            NewsReaderScreen(state(true),false,{},{},{_,_,_->},{},{})
        } } }
        compose.runOnIdle { assertTrue(intents.isEmpty()) }
        compose.onNodeWithTag("news-reader-list").performScrollToNode(hasTestTag("news-original-browser"))
        compose.onNodeWithTag("news-original-browser").performClick()
        compose.runOnIdle { assertSafeIntent(intents.single()) }
        compose.onNodeWithText(context.getString(R.string.news_web)).assertDoesNotExist()
    }
    @Test fun directReadingKeepsInAppModeAndAlsoOpensOriginalNotTheExtractedRedirectUrl() {
        val intents = mutableListOf<Intent>()
        val browser = object : ContextWrapper(context) { override fun startActivity(intent: Intent) { intents += intent } }
        compose.setContent { CompositionLocalProvider(LocalContext provides browser) { MaterialTheme {
            NewsReaderScreen(state(false),false,{},{},{_,_,_->},{},{})
        } } }
        compose.runOnIdle { assertTrue(intents.isEmpty()) }
        compose.onNodeWithText(context.getString(R.string.news_web)).assertExists()
        compose.onNodeWithTag("news-reader-list").performScrollToNode(hasTestTag("news-original-browser"))
        compose.onNodeWithTag("news-original-browser").performClick()
        compose.runOnIdle { assertSafeIntent(intents.single()) }
    }
    @Test fun missingBrowserAndUnsafeUrlsShowErrorsWithoutCrashingOrDispatchingUnsafeIntents() {
        var attempts = 0
        val missing = object : ContextWrapper(context) { override fun startActivity(intent: Intent) { attempts++; throw ActivityNotFoundException() } }
        var url by mutableStateOf(original)
        compose.setContent { CompositionLocalProvider(LocalContext provides missing) { MaterialTheme { NewsOriginalLink(url) } } }
        compose.onNodeWithTag("news-original-browser").performClick()
        compose.onNodeWithText(context.getString(R.string.news_browser_failed)).assertIsDisplayed()
        compose.runOnIdle {
            url = "javascript:alert(1)"
            assertFalse(openOriginalNewsInBrowser(missing,"https://user:pass@publisher.example/report"))
            assertEquals(1,attempts)
        }
        compose.onNodeWithTag("news-original-browser").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.news_browser_invalid)).assertIsDisplayed()
        val denied = object : ContextWrapper(context) { override fun startActivity(intent: Intent) { throw SecurityException() } }
        assertFalse(openOriginalNewsInBrowser(denied,original))
    }
    @Test fun browserActionRemainsReachableInDarkNarrowLargeFontReading() {
        val intents = mutableListOf<Intent>()
        val browser = object : ContextWrapper(context) { override fun startActivity(intent: Intent) { intents += intent } }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides browser, LocalDensity provides Density(density.density,2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Column(Modifier.width(320.dp).fillMaxHeight()) { NewsReaderScreen(state(true),false,{},{},{_,_,_->},{},{}) }
                }
            }
        }
        compose.onNodeWithTag("news-reader-list").performScrollToNode(hasTestTag("news-original-browser"))
        compose.onNodeWithTag("news-original-browser").assertIsDisplayed().performClick()
        compose.runOnIdle { assertSafeIntent(intents.single()) }
    }
}
