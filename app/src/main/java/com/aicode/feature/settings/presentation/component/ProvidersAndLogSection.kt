package com.aicode.feature.settings.presentation.component

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.domain.model.AIProviderConfig
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import androidx.compose.ui.res.stringResource
import com.aicode.R

/** 提供商二级页：列表 + 空态提示。新增/编辑由顶栏「+」与点击触发 [ProviderEditorScreen]，左滑删除。 */
@Composable
internal fun ProvidersSection(
    providers: List<AIProviderConfig>,
    onEdit: (AIProviderConfig) -> Unit,
    onDelete: (AIProviderConfig) -> Unit
) {
    if (providers.isEmpty()) {
        EmptyHint(stringResource(R.string.providers_empty))
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        SettingsGroup {
            providers.forEachIndexed { index, provider ->
                if (index > 0) {
                    SettingsDivider()
                }
                ProviderItem(
                    provider = provider,
                    onEdit = { onEdit(provider) },
                    // 插件认证虚拟 provider 不提供删除（生命周期归插件管理，删除语义不清）
                    onDelete = { onDelete(provider) }.takeIf { !provider.isVirtual }
                )
            }
        }
    }
}

/** 居中空态提示。 */
@Composable
internal fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 提供商行：布局与 MCP 列表行一致——左侧品牌 logo，
 * 中部两行（名称 / 类型 + 模型数量 pills），右侧状态 pill + 箭头。
 * 整行点击进入编辑，左滑露出删除按钮；onDelete 为 null 时不可删除（插件认证虚拟 provider）。
 */
@Composable
fun ProviderItem(
    provider: AIProviderConfig,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    // 状态色与 MCP 行一致：启用用主题 tertiary（绿调），停用用 outline（灰）。
    val statusColor = if (provider.isEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    val light = settingsLightMode()

    val rowContent: @Composable () -> Unit = {
        // 行内容自带与 MCP 行一致的内边距（SwipeToDeleteRow 本身无 padding）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧品牌 logo 容器
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                ProviderLogoIcon(
                    provider = provider,
                    size = 22.dp,
                    modifier = Modifier.size(22.dp).align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            // 中间：名称 / 类型 + 模型数量
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
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
                        text = providerTypeLabel(provider.type),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                    McpPill(
                        text = stringResource(R.string.provider_models_count_tag, provider.models.size),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (provider.isVirtual) {
                        McpPill(
                            text = stringResource(R.string.provider_virtual_tag),
                            textColor = MaterialTheme.colorScheme.primary,
                            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // 启用/停用状态 pill + 右箭头
            McpPill(
                text = stringResource(if (provider.isEnabled) R.string.common_enabled else R.string.common_disabled),
                textColor = statusColor,
                backgroundColor = statusColor.copy(alpha = 0.12f)
            )
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = if (light) Color(0xFFC7C7CC) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (onDelete != null) {
        SwipeToDeleteRow(
            onDelete = onDelete,
            onClick = onEdit
        ) {
            rowContent()
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEdit() }
        ) {
            rowContent()
        }
    }
}
