package com.macroresearch.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.macroresearch.R
import com.macroresearch.ui.common.appLocale

@Composable
fun LanguageSettings(chineseEnabled: Boolean) {
    val selected = AppLanguage.fromLocale(appLocale())
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.language), fontWeight = FontWeight.Bold)
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { language ->
                    val enabled = language == AppLanguage.English || chineseEnabled
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = selected == language,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = {
                                    if (selected != language) {
                                        AppCompatDelegate.setApplicationLocales(
                                            LocaleListCompat.forLanguageTags(language.tag),
                                        )
                                    }
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = selected == language,
                            onClick = null,
                            enabled = enabled,
                        )
                        Text(
                            language.nativeName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                            },
                        )
                    }
                }
            }
            Text(
                stringResource(
                    if (chineseEnabled) R.string.language_note
                    else R.string.language_key_required_note,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
