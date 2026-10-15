package com.macroresearch.ui.common.terminal

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalNavigationBarUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsItemsSelectsAndStaysCompact() {
        var clicks = 0
        var density = Density(1f)
        compose.setContent {
            density = LocalDensity.current
            MaterialTheme {
                TerminalNavigationBar(
                    listOf(
                        TerminalNavItem("Home", Icons.Outlined.Home, selected = true, onClick = {}),
                        TerminalNavItem("Settings", Icons.Outlined.Settings, selected = false, onClick = { clicks++ }),
                    ),
                )
            }
        }
        compose.onNodeWithText("Home").assertIsDisplayed().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        compose.onNodeWithText("Settings").assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithText("Settings").performClick()
        assertEquals(1, clicks)

        // 56dp 内容 + 1dp 细线 + 容差；测试环境没有系统导航栏 inset。
        val heightDp = compose.onRoot().fetchSemanticsNode().size.height / density.density
        assertTrue("height=$heightDp", heightDp in 48f..58f)
    }
}
