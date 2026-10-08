package com.macroresearch.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.ReleaseImpactBasis
import com.macroresearch.data.ReleaseImpactDirection
import com.macroresearch.data.ReleaseImpactEvaluator
import com.macroresearch.data.ReleaseImpactRationale
import com.macroresearch.data.ReleaseImpactStatus
import com.macroresearch.data.ReleaseImpactStrength
import com.macroresearch.data.ReleaseImpactUnit
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp

/** Detail-only impact report: directions first, shared strength once, evidence beneath. */
@Composable
fun ReleaseImpactLabels(event: EconomicEvent, modifier: Modifier = Modifier, detailed: Boolean = false) {
    val impact = remember(event) { ReleaseImpactEvaluator.evaluate(event) }
    var evidenceExpanded by rememberSaveable(event.id) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.release_impact_title), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            if (impact.status == ReleaseImpactStatus.DIRECTIONAL) {
                Text(stringResource(if (impact.strength == ReleaseImpactStrength.LIMITED)
                    R.string.release_impact_limited else R.string.release_impact_rule_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val message = when (impact.status) {
            ReleaseImpactStatus.WAITING -> R.string.release_impact_waiting
            ReleaseImpactStatus.MISSING_EXPECTATION -> R.string.release_impact_missing_expectation
            ReleaseImpactStatus.INVALID_NUMBER -> R.string.release_impact_invalid_number
            ReleaseImpactStatus.UNKNOWN_RULE -> R.string.release_impact_unknown_rule
            ReleaseImpactStatus.UNKNOWN_UNIT -> R.string.release_impact_unknown_unit
            ReleaseImpactStatus.EQUAL -> R.string.release_impact_equal
            ReleaseImpactStatus.DIRECTIONAL -> null
        }
        message?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (impact.status == ReleaseImpactStatus.DIRECTIONAL) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth / LocalDensity.current.fontScale < 280.dp) 1 else 2
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    impact.assets.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { asset ->
                                val up = asset.direction == ReleaseImpactDirection.UP
                                val weak = impact.strength == ReleaseImpactStrength.LIMITED
                                val yield = asset.symbol in setOf("us2y", "us10y")
                                val direction = when {
                                    yield && weak && up -> R.string.release_impact_yield_weak_up
                                    yield && weak -> R.string.release_impact_yield_weak_down
                                    yield && up -> R.string.release_impact_yield_up
                                    yield -> R.string.release_impact_yield_down
                                    weak && up -> R.string.release_impact_weak_up
                                    weak -> R.string.release_impact_weak_down
                                    up -> R.string.release_impact_up
                                    else -> R.string.release_impact_down
                                }
                                val color = if (up) AssetUp else AssetDown
                                Surface(
                                    Modifier.weight(1f).testTag("release-impact-${asset.symbol}")
                                        .semantics(mergeDescendants = true) {},
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (weak) MaterialTheme.colorScheme.outlineVariant else color.copy(alpha = 0.2f)),
                                    color = color.copy(alpha = if (weak) 0.025f else 0.06f),
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(assetLabel(asset.symbol), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(stringResource(direction), style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (weak) MaterialTheme.colorScheme.onSurfaceVariant else color)
                                    }
                                }
                            }
                            if (row.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (detailed) {
            if (impact.surprise != null && impact.threshold != null && impact.unit != null) {
                val unit = stringResource(when (impact.unit) {
                    ReleaseImpactUnit.PERCENTAGE_POINTS -> R.string.release_impact_unit_pp
                    ReleaseImpactUnit.PEOPLE -> R.string.release_impact_unit_people
                    ReleaseImpactUnit.INDEX_POINTS -> R.string.release_impact_unit_index
                    ReleaseImpactUnit.BARRELS -> R.string.release_impact_unit_barrels
                })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImpactMetric(stringResource(R.string.release_impact_surprise_label), "${signed(impact.surprise)} $unit")
                    ImpactMetric(stringResource(R.string.release_impact_threshold_label),
                        "≥ ${impact.threshold.stripTrailingZeros().toPlainString()} $unit")
                }
            }
            if (impact.basis != null) {
                Text(stringResource(R.string.release_impact_basis_source,
                    stringResource(if (impact.basis == ReleaseImpactBasis.CONSENSUS) R.string.consensus else R.string.forecast)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { evidenceExpanded = !evidenceExpanded },
                modifier = Modifier.fillMaxWidth().testTag("release-impact-evidence"), contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.release_impact_evidence), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge)
                Icon(if (evidenceExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(evidenceExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    impact.rationale?.takeUnless { it == ReleaseImpactRationale.EQUAL }?.let { rationale ->
                        Text(stringResource(when (rationale) {
                            ReleaseImpactRationale.HAWKISH -> R.string.release_impact_hawkish
                            ReleaseImpactRationale.DOVISH -> R.string.release_impact_dovish
                            ReleaseImpactRationale.OIL_INVENTORY -> R.string.release_impact_oil
                            ReleaseImpactRationale.EQUAL -> R.string.release_impact_equal
                        }), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.release_impact_disclaimer), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ImpactMetric(label: String, value: String) {
    // Separate lines never squeeze long count values/units against a second metric on a small screen.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
