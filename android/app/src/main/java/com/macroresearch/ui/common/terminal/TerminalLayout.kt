package com.macroresearch.ui.common.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macroresearch.R
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.ResearchType

@Composable
fun Hairline(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

/**
 * 等宽数值列宽：按字号（已含系统字体缩放）换算，每个字符约 0.6em，不低于 [min]，避免大字体下折行。
 */
@Composable
fun monoColumnWidth(chars: Float, min: Dp, style: TextStyle = ResearchType.small): Dp {
    val fontSizeDp = with(LocalDensity.current) { style.fontSize.toDp() }
    return maxOf(fontSizeDp * 0.6f * chars, min)
}

/** 一级 Tab 页标题区：标题、副标题、右侧操作，下方一条细线。 */
@Composable
fun PageHeader(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = ResearchLayout.pagePadding,
                    end = ResearchLayout.denseGap,
                    top = ResearchLayout.smallGap,
                    bottom = ResearchLayout.denseGap,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.semantics { heading() }) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) { title() }
                }
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Hairline()
    }
}

@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) = PageHeader({ Text(title) }, modifier, subtitle, actions)

/** 二级页顶栏：TopAppBar 自行处理状态栏 inset，调用方不要再叠加顶部 padding。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Column {
        TopAppBar(
            title = {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                }
            },
            actions = actions,
            expandedHeight = 48.dp,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Hairline()
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingColor: Color = Color.Unspecified,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = ResearchLayout.pagePadding,
                end = if (action != null) ResearchLayout.denseGap else ResearchLayout.pagePadding,
                top = ResearchLayout.sectionTop,
                bottom = ResearchLayout.denseGap,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = trailingColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
            )
        }
        action?.invoke()
    }
}

/** 强调色带：surfaceContainerLow 底色加左侧 3dp 主色竖线，用于"下一事件"和倒计时。 */
@Composable
fun AccentBand(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
            .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.gap),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap),
        content = content,
    )
}

enum class NoticeTone { Info, Error }

@Composable
fun NoticeStrip(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info,
    action: (@Composable () -> Unit)? = null,
) {
    val accent = if (tone == NoticeTone.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.denseGap)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (tone == NoticeTone.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        action?.invoke()
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = ResearchLayout.pagePadding, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun TerminalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = MaterialTheme.shapes.small, content = content)

@Composable
fun TerminalOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    content = content,
)

@Composable
fun TerminalFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) = FilterChip(
    selected = selected,
    onClick = onClick,
    modifier = modifier,
    label = { Text(label) },
    leadingIcon = leadingIcon,
    border = FilterChipDefaults.filterChipBorder(
        enabled = true,
        selected = selected,
        borderColor = MaterialTheme.colorScheme.outline,
        selectedBorderColor = MaterialTheme.colorScheme.primary,
        selectedBorderWidth = 1.dp,
    ),
)
