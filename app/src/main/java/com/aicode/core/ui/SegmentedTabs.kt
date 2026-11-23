package com.aicode.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors

/**
 * iOS 风分段控件：中性灰轨道 + 白色（深色下为抬起灰）滑块，选中时滑块弹性滑到对应一段。
 *
 * 不用主题色填充，让它与下方的扫描式列表协调，只靠中性色与轻阴影表现层次。
 * 侧边栏顶部与 MCP 编辑弹窗共用同一份，切换手感保持一致。
 *
 * [enabled] == false 时锁定当前选中段：整体置灰且不可点击（用于创建后不可更改的选项，
 * 如容器的本地/远程模式），滑块仍停留在选中位置以展示当前值。
 */
@Composable
fun SegmentedTabs(
    selected: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icons: List<ImageVector> = emptyList()
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackColor = MaterialTheme.semanticColors.mutedSurface
    val thumbColor = if (dark) MaterialTheme.semanticColors.capsuleSurface else MaterialTheme.semanticColors.cardSurface
    // 高度不跟 fontScale 放大：分段控件本身只承载短标签，跟字体等比膨胀会让条变胖、
    // 与抽屉 / MCP 弹窗里的同款控件对不齐。图标模式只多留 2dp 给 16dp 图标。
    val tabHeight = if (icons.isEmpty()) 42.dp else 44.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(tabHeight)
            .clip(RoundedCornerShape(Radius.mdLarge))
            .background(trackColor)
            .padding(3.dp)
            .alpha(if (enabled) 1f else 0.55f)
    ) {
        val thumbWidth = maxWidth / labels.size
        // 选中滑块位移：临界阻尼弹簧（不过冲），切换时滑块顺滑到位，不是硬切。
        val thumbOffset by animateDpAsState(
            targetValue = thumbWidth * selected,
            animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            label = "segmented-thumb"
        )
        Surface(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(thumbWidth)
                .fillMaxHeight(),
            shape = RoundedCornerShape(Radius.sm),
            color = thumbColor,
            shadowElevation = 2.dp,
            content = {}
        )
        Row(modifier = Modifier.fillMaxSize().selectableGroup()) {
            labels.forEachIndexed { index, label ->
                val isSelected = selected == index
                val icon = icons.getOrNull(index)
                val color = when {
                    !isSelected -> MaterialTheme.colorScheme.onSurfaceVariant
                    icon != null -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(Radius.sm))
                        // 禁用点击波纹：选中滑块位移已是反馈，ripple 残留的深色阴影反而扎眼。
                        // 锁定时不暴露无障碍点击操作。
                        .then(
                            if (enabled) {
                                Modifier.selectable(
                                    selected = isSelected,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = { onSelect(index) }
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = if (icon == null) 0.dp else Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally)
                ) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(Spacing.lg))
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = color,
                        textAlign = TextAlign.Center,
                        maxLines = if (icon == null) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
