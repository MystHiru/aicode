package com.aicode.feature.settings.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.plugin.PluginDescriptor
import com.aicode.feature.agent.domain.plugin.PluginRuntimeStatus
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Package

/**
 * 插件管理二级页（MCP 风格）：
 * 顶部为运行时状态概览，下方为分组插件列表。
 * 支持左滑删除（SwipeToDeleteRow），点击插件项打开详情弹窗。
 */
@Composable
internal fun PluginsSection(
    status: PluginRuntimeStatus,
    plugins: List<PluginDescriptor>,
    onOpenDetail: (PluginDescriptor) -> Unit,
    onDelete: (PluginDescriptor) -> Unit
) {
    if (plugins.isEmpty() && status.state == PluginRuntimeStatus.State.DISABLED) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.padding(horizontal = Spacing.xl)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.lg)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        FeatherIcons.Package,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.plugins_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 1. 运行时状态概览
        SettingsGroupHeader(text = stringResource(R.string.plugins_runtime_status))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            ) {
                // 状态行：状态圆点 + 状态名 + 运行时 Pill（右侧）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(runtimeStatusColor(status), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = runtimeStatusText(status),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = runtimeStatusColor(status),
                        modifier = Modifier.weight(1f)
                    )
                    if (status.state == PluginRuntimeStatus.State.RUNNING) {
                        McpPill(
                            text = status.runtimeBin ?: "node",
                            textColor = MaterialTheme.colorScheme.primary,
                            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    }
                }

                // 统计 Pills：插件数 / 工具数 / 失败数
                if (status.pluginCount > 0 || status.toolCount > 0 || status.failedCount > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        McpPill(
                            text = stringResource(R.string.plugins_count_pill, status.pluginCount),
                            textColor = MaterialTheme.colorScheme.primary,
                            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                        McpPill(
                            text = stringResource(R.string.plugins_tools_count, status.toolCount),
                            textColor = MaterialTheme.colorScheme.secondary,
                            backgroundColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                        )
                        if (status.failedCount > 0) {
                            McpPill(
                                text = stringResource(R.string.plugins_failed_count, status.failedCount),
                                textColor = MaterialTheme.colorScheme.error,
                                backgroundColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            )
                        }
                    }
                }

                status.socketPath?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.plugins_runtime_socket, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                status.error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    PluginErrorText(
                        text = it,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 2. 插件列表
        SettingsGroupHeader(text = stringResource(R.string.plugins_list_header, plugins.size))
        SettingsGroup {
            if (plugins.isEmpty()) {
                Text(
                    text = if (status.state == PluginRuntimeStatus.State.RUNNING)
                        stringResource(R.string.plugins_empty_running)
                    else
                        stringResource(R.string.plugins_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            } else {
                plugins.forEachIndexed { index, plugin ->
                    if (index > 0) {
                        SettingsDivider()
                    }
                    PluginRow(
                        plugin = plugin,
                        onClick = { onOpenDetail(plugin) },
                        onDelete = { onDelete(plugin) }
                    )
                }
            }
        }
    }
}

/** 单个插件行：MCP 风格图标 + 标题 + Pills 标签 + 状态 + 箭头，支持左滑删除与点击详情。 */
@Composable
private fun PluginRow(
    plugin: PluginDescriptor,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val light = settingsLightMode()
    val rowBackground = if (light) Color.White else MaterialTheme.colorScheme.surface

    val isFailed = plugin.error != null
    val statusText = if (isFailed) stringResource(R.string.plugins_load_failed) else stringResource(R.string.plugins_loaded)
    val statusColor = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackground)
                .padding(start = Spacing.lg, end = Spacing.sm, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧方形图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Package,
                    contentDescription = null,
                    tint = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            // 中间：名称 / 胶囊标签 (来源、工具数、Hooks数)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (light) Color(0xFF0F0F0F) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McpPill(
                        text = sourceLabel(plugin.source),
                        textColor = sourcePillPalette(plugin.source).first,
                        backgroundColor = sourcePillPalette(plugin.source).second
                    )
                    if (plugin.tools.isNotEmpty()) {
                        McpPill(
                            text = stringResource(R.string.plugins_tools_count, plugin.tools.size),
                            textColor = MaterialTheme.colorScheme.primary,
                            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    }
                    if (plugin.hooks.isNotEmpty()) {
                        McpPill(
                            text = stringResource(R.string.plugins_hooks_count, plugin.hooks.size),
                            textColor = MaterialTheme.colorScheme.secondary,
                            backgroundColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                        )
                    }
                }

                if (isFailed) {
                    Spacer(modifier = Modifier.height(4.dp))
                    PluginErrorText(
                        text = plugin.error.orEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // 右侧：状态 Pill + Chevron 箭头
            McpPill(
                text = statusText,
                textColor = statusColor,
                backgroundColor = statusColor.copy(alpha = 0.12f)
            )

            Spacer(modifier = Modifier.width(Spacing.xs))

            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = if (light) Color(0xFFC7C7CC) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun runtimeStatusText(status: PluginRuntimeStatus): String = when (status.state) {
    PluginRuntimeStatus.State.RUNNING -> stringResource(R.string.plugins_state_running)
    PluginRuntimeStatus.State.STARTING -> stringResource(R.string.plugins_state_starting)
    PluginRuntimeStatus.State.FAILED -> stringResource(R.string.plugins_state_failed)
    PluginRuntimeStatus.State.DISABLED -> stringResource(R.string.plugins_state_disabled)
}

@Composable
private fun runtimeStatusColor(status: PluginRuntimeStatus) = when (status.state) {
    PluginRuntimeStatus.State.RUNNING -> MaterialTheme.colorScheme.primary
    PluginRuntimeStatus.State.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun sourceLabel(source: String): String = when (source) {
    "global-npm" -> stringResource(R.string.plugins_source_global_npm)
    "project-npm" -> stringResource(R.string.plugins_source_project_npm)
    "global-local" -> stringResource(R.string.plugins_source_global_local)
    "project-local" -> stringResource(R.string.plugins_source_project_local)
    else -> source
}

/** 来源 Pill 配色：文字区分 npm/本地，颜色区分作用域（项目级用主题色突出，全局为中性灰）。 */
@Composable
internal fun sourcePillPalette(source: String): Pair<Color, Color> {
    val isProject = source.startsWith("project")
    return if (isProject) {
        MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
}

/** 可点击复制的错误文本：点击把完整错误信息写入剪贴板并提示。 */
@Composable
internal fun PluginErrorText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.error,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    val context = LocalContext.current
    Text(
        text = text,
        modifier = modifier.clickable {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("plugin_error", text))
            Toast.makeText(context, context.getString(R.string.crash_copied), Toast.LENGTH_SHORT).show()
        },
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow
    )
}
