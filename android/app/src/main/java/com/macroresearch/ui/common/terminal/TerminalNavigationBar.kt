package com.macroresearch.ui.common.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class TerminalNavItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/** 紧凑底部导航：内容区 56dp（Material 默认 80dp），自行处理系统导航栏 inset；选中项为主色并在顶部画 2dp 细条。 */
@Composable
fun TerminalNavigationBar(items: List<TerminalNavItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = 56.dp)
                // 高度不定时（大字号）让各项 fillMaxHeight 对齐到最高项。
                .height(IntrinsicSize.Min)
                .selectableGroup(),
        ) {
            items.forEach { item ->
                val accent = MaterialTheme.colorScheme.primary
                val color = if (item.selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick)
                        .drawBehind {
                            if (item.selected) {
                                drawRect(
                                    accent,
                                    topLeft = Offset(size.width * 0.3f, 0f),
                                    size = Size(size.width * 0.4f, 2.dp.toPx()),
                                )
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // 文字已提供朗读内容，图标不重复朗读。
                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = color)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = if (item.selected) FontWeight.SemiBold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
