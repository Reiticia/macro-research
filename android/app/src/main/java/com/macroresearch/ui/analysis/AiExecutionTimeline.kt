package com.macroresearch.ui.analysis

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.AiExecutionStep
import com.macroresearch.data.AiOperation
import com.macroresearch.data.AiStepDetail
import com.macroresearch.data.AiStepStatus

/** Compact terminal-style audit trail. No simulated model thinking or raw payloads. */
@Composable
internal fun AiExecutionTimeline(steps: List<AiExecutionStep>) {
    if (steps.isEmpty()) return
    var expanded by remember { mutableStateOf(true) }
    HorizontalDivider()
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ai_execution_path) + if (expanded) " −" else " +")
    }
    Text(stringResource(R.string.ai_execution_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!expanded) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, step ->
            val color = when (step.status) {
                AiStepStatus.FAILED -> MaterialTheme.colorScheme.error
                AiStepStatus.RUNNING -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%02d".format(index + 1), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = color)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(operationLabel(step.operation)), style = MaterialTheme.typography.labelLarge, color = color)
                    val function = when (step.operation) {
                        AiOperation.READ_EVENT -> "read_current_event()"
                        AiOperation.READ_RULES -> "read_rule_assessment()"
                        AiOperation.READ_MARKET -> "read_market_windows()"
                        AiOperation.READ_NEWS -> "read_related_news()"
                        AiOperation.OPEN_ARTICLE -> "open_news_article()"
                        else -> null
                    }
                    function?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                    step.url?.let { url -> SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall) } }
                    step.httpStatus?.let { Text("HTTP $it", style = MaterialTheme.typography.labelSmall) }
                    detailLabel(step.detail)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = color) }
                    step.count?.let { Text(stringResource(R.string.ai_execution_count, it), style = MaterialTheme.typography.labelSmall) }
                }
                Column {
                    Text(stringResource(when (step.status) {
                        AiStepStatus.RUNNING -> R.string.ai_step_running
                        AiStepStatus.SUCCEEDED -> R.string.ai_step_done
                        AiStepStatus.FAILED -> R.string.ai_step_failed
                        AiStepStatus.CANCELLED -> R.string.ai_step_cancelled
                    }), style = MaterialTheme.typography.labelSmall, color = color)
                    step.durationMs?.let { Text("${it}ms", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

private fun operationLabel(operation: AiOperation): Int = when (operation) {
    AiOperation.LOAD_EVENT, AiOperation.READ_EVENT -> R.string.ai_step_event
    AiOperation.LOAD_MARKET, AiOperation.READ_MARKET -> R.string.ai_step_market
    AiOperation.APPLY_RULES, AiOperation.READ_RULES -> R.string.ai_step_rules
    AiOperation.LOAD_NEWS, AiOperation.READ_NEWS -> R.string.ai_step_news
    AiOperation.EXPECTATION -> R.string.ai_step_expectation
    AiOperation.COMPARISON -> R.string.ai_step_comparison
    AiOperation.SINGLE_PASS -> R.string.ai_step_synthesis
    AiOperation.COMPATIBILITY -> R.string.ai_step_compatibility
    AiOperation.VALIDATE -> R.string.ai_step_validate
    AiOperation.SAVE -> R.string.ai_step_save
    AiOperation.FETCH_NEWS -> R.string.ai_step_fetch_news
    AiOperation.OPEN_ARTICLE -> R.string.ai_step_open_article
    AiOperation.RESOLVE_ARTICLE -> R.string.ai_step_resolve_article
}

private fun detailLabel(detail: AiStepDetail): Int? = when (detail) {
    AiStepDetail.NONE -> null
    AiStepDetail.MISSING_DATA -> R.string.ai_step_missing
    AiStepDetail.NEWS_DISABLED -> R.string.ai_step_news_disabled
    AiStepDetail.PARTIAL_NEWS -> R.string.ai_step_partial_news
    AiStepDetail.NO_NEWS -> R.string.ai_step_no_news
    AiStepDetail.UNSUPPORTED_TOOLS -> R.string.ai_step_no_tools
    AiStepDetail.INVALID_TOOL -> R.string.ai_step_invalid_tool
    AiStepDetail.LIMIT_REACHED -> R.string.ai_step_tool_limit
    AiStepDetail.PAGE_UNAVAILABLE -> R.string.ai_article_unavailable
    AiStepDetail.REDIRECT -> R.string.ai_article_redirect
    AiStepDetail.BACKEND_DATA -> R.string.ai_evidence_backend
    AiStepDetail.DIRECT_DATA -> R.string.ai_evidence_direct
    AiStepDetail.INTERFACE_UNAVAILABLE -> R.string.ai_evidence_unsupported
    AiStepDetail.PAGE_HTTP_ERROR -> R.string.ai_page_http_error
    AiStepDetail.PAGE_TOO_LARGE -> R.string.ai_page_too_large
    AiStepDetail.PAGE_NO_TEXT -> R.string.ai_page_no_text
    AiStepDetail.GOOGLE_RESOLUTION_FAILED -> R.string.ai_google_resolve_failed
}
