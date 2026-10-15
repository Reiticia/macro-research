package com.macroresearch.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.macroresearch.ui.theme.Upcoming

@Composable
fun ImportanceDots(importance: Int, decorative: Boolean = false) {
    val label = importanceLabel(importance)
    Text(
        "●".repeat(importance.coerceIn(0, 3)),
        // decorative：旁边已有同义文字时不重复朗读
        Modifier.clearAndSetSemantics { if (!decorative) contentDescription = label },
        color = if (importance >= 2) Upcoming else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}
