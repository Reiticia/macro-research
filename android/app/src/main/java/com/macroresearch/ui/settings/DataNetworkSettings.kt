package com.macroresearch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.common.terminal.TerminalButton
import com.macroresearch.ui.theme.ResearchLayout

@Composable
fun DataNetworkSettings(repository: MacroRepository) {
    val configured by repository.proxyAddress.collectAsStateWithLifecycle()
    var address by rememberSaveable(configured) { mutableStateOf(configured) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ResearchLayout.pagePadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.data_proxy_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; invalid = false; saved = false },
            label = { Text(stringResource(R.string.data_proxy)) },
            placeholder = { Text("host:port") },
            singleLine = true,
            isError = invalid,
            modifier = Modifier.fillMaxWidth(),
        )
        if (invalid) Text(stringResource(R.string.data_proxy_invalid), color = MaterialTheme.colorScheme.error)
        if (saved) Text(stringResource(R.string.data_proxy_saved), color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                repository.saveCalendarProxy("")
                address = ""
                invalid = false
                saved = true
            }) { Text(stringResource(R.string.data_proxy_clear)) }
            TerminalButton(onClick = {
                runCatching { repository.saveCalendarProxy(address) }
                    .onSuccess { invalid = false; saved = true }
                    .onFailure { invalid = true }
            }) { Text(stringResource(R.string.save)) }
        }
    }
}
