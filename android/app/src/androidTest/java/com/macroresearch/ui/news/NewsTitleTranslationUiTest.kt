package com.macroresearch.ui.news

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.NewsPreferences
import com.macroresearch.data.remote.NewsArticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewsTitleTranslationUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val article = NewsArticle("Fed holds rates", "https://publisher.example/report", "Source", null, "Original RSS summary")

    @Test fun newsTranslationPermissionIsSavedAndDoesNotEnableAiNewsOrPreviews() {
        val storage = context.getSharedPreferences("research_preferences", Context.MODE_PRIVATE)
        val original = storage.all["news_title_translation"] as? Boolean
        try {
            storage.edit().remove("news_title_translation").commit()
            val preferences = NewsPreferences(context)
            assertFalse(preferences.titleTranslationEnabled.value)
            val ai = preferences.settings.value
            val reader = preferences.readerEnabled.value
            compose.setContent {
                val enabled by preferences.titleTranslationEnabled.collectAsState()
                MaterialTheme {
                    NewsTitleTranslationControl(enabled, true, "简体中文", false, preferences::setTitleTranslationEnabled)
                }
            }
            compose.onNodeWithTag("news-title-translation").assertIsOff().performClick().assertIsOn()
            compose.runOnIdle {
                assertEquals(true, NewsPreferences(context).titleTranslationEnabled.value)
                assertEquals(ai, preferences.settings.value)
                assertEquals(reader, preferences.readerEnabled.value)
            }
        } finally {
            storage.edit().apply {
                if (original == null) remove("news_title_translation") else putBoolean("news_title_translation", original)
            }.commit()
        }
    }

    @Test fun readerShowsTranslationAndCanRevealOriginalWithoutChangingTheArticle() {
        var translation by mutableStateOf<String?>("美联储维持利率")
        compose.setContent { MaterialTheme { NewsArticleTitle(article.title, translation) } }
        compose.onNodeWithTag("news-reader-title").assertTextEquals("美联储维持利率")
        compose.onNodeWithTag("news-title-original").performClick()
        compose.onNodeWithTag("news-reader-title").assertTextEquals(article.title)
        compose.onNodeWithTag("news-title-original").performClick()
        compose.onNodeWithTag("news-reader-title").assertTextEquals("美联储维持利率")
        compose.runOnIdle { translation = "聯準會維持利率" }
        compose.onNodeWithTag("news-reader-title").assertTextEquals("聯準會維持利率")
        compose.runOnIdle { translation = null }
        compose.onNodeWithTag("news-reader-title").assertTextEquals(article.title)
        compose.onNodeWithTag("news-title-original").assertDoesNotExist()
    }

    @Test fun headlineKeepsTheOriginalSummaryAndClickTarget() {
        var clicked = false
        compose.setContent { MaterialTheme { NewsHeadline(article, "美联储维持利率") { clicked = true } } }
        compose.onNodeWithText("美联储维持利率").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, clicked) }
        compose.onNodeWithText("Original RSS summary").assertIsDisplayed()
        compose.onNodeWithText(article.title).assertDoesNotExist()
    }

    @Test fun missingKeyAndTranslationFailuresAreExplicitInDarkLargeFontLayout() {
        var configured by mutableStateOf(false)
        val missingKey = context.getString(R.string.news_title_translation_key)
        val failure = context.getString(R.string.news_title_translation_failed)
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { fontScale = 1.4f }
            CompositionLocalProvider(LocalConfiguration provides config) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Column { NewsTitleTranslationControl(true, configured, "English", true) {} }
                }
            }
        }
        compose.onNodeWithText(missingKey).assertIsDisplayed()
        compose.runOnIdle { configured = true }
        compose.onNodeWithText(failure).assertIsDisplayed()
        compose.onNodeWithText(missingKey).assertDoesNotExist()
    }
}
