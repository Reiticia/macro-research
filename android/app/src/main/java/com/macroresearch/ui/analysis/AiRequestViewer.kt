package com.macroresearch.ui.analysis

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.google.gson.JsonParser
import com.macroresearch.data.AiRequestPhase
import com.macroresearch.data.AiRequestRecord
import com.macroresearch.data.AiStepStatus

/** Read-only, user-opened inspector of locally retained redacted requests; never copies automatically. */
@Composable
internal fun AiRequestViewer(records: List<AiRequestRecord>, followUp: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (followUp) R.string.ai_followup_requests else R.string.ai_view_requests, records.size))
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.ai_request_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.ai_request_privacy), style = MaterialTheme.typography.bodySmall) }
                if (records.isEmpty()) item { Text(stringResource(R.string.ai_requests_empty)) }
                if (records.size >= 64) item { Text(stringResource(R.string.ai_requests_limit), color = MaterialTheme.colorScheme.error) }
                items(records, key = { it.id }) { record -> RequestRound(record) }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ai_request_close)) } },
    )
}

@Composable
private fun RequestRound(record: AiRequestRecord) {
    var expanded by remember(record.id, record.startedAt) { mutableStateOf(false) }
    var showJson by remember(record.id, record.startedAt) { mutableStateOf(false) }
    val messages = remember(record.requestJson) {
        record.requestJson?.let { json -> runCatching {
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("messages")?.mapNotNull { element ->
                val message = element.asJsonObject
                val role = message.get("role")?.asString ?: return@mapNotNull null
                val content = message.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                val calls = message.get("tool_calls")?.toString()
                Triple(role, content, calls)
            }.orEmpty()
        }.getOrDefault(emptyList()) }.orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val phase = stringResource(when (record.phase) {
            AiRequestPhase.EXPECTATION -> R.string.ai_step_expectation
            AiRequestPhase.COMPARISON -> R.string.ai_step_comparison
            AiRequestPhase.SINGLE_PASS -> R.string.ai_step_synthesis
            AiRequestPhase.FOLLOW_UP -> R.string.ai_conversation
        })
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(R.string.ai_request_round, record.id + 1, phase) + if (expanded) " −" else " +")
        }
        val status = stringResource(when (record.status) {
            AiStepStatus.RUNNING -> R.string.ai_step_running
            AiStepStatus.SUCCEEDED -> R.string.ai_step_done
            AiStepStatus.FAILED -> R.string.ai_step_failed
            AiStepStatus.CANCELLED -> R.string.ai_step_cancelled
        })
        Text(listOfNotNull(status, record.httpStatus?.let { "HTTP $it" }, record.durationMs?.let { "${it}ms" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
        if (expanded) {
            Text(record.startedAt, style = MaterialTheme.typography.labelSmall)
            if (record.requestJson == null) {
                Text(stringResource(R.string.ai_request_omitted), color = MaterialTheme.colorScheme.error)
            } else {
                messages.forEachIndexed { index, (role, content, calls) ->
                    Text("${index + 1}. $role", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text(listOfNotNull(content, calls).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { showJson = !showJson }) { Text(stringResource(R.string.ai_request_json)) }
                if (showJson) SelectionContainer {
                    Text(record.requestJson, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp))
                }
            }
        }
    }
}
