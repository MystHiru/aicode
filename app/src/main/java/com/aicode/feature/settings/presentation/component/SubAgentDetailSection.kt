package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.agent.presentation.component.MarkdownContent
import com.aicode.feature.agent.presentation.component.MarkdownRenderCache
import com.aicode.feature.settings.presentation.SubAgentUiEntry

/**
 * 子代理详情页：分组卡片——「摘要」描述卡、「配置」（模型/工具集/注入项/定义文件）键值卡、
 * 「agent 提示词」正文卡（Markdown 渲染）。定义为只读展示，编辑请直接改磁盘上的 .md 文件。
 */
@Composable
internal fun SubAgentDetailSection(
    entry: SubAgentUiEntry,
    cache: MarkdownRenderCache? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.skills_summary))
        SettingsGroup {
            Text(
                text = entry.description.ifBlank { stringResource(R.string.mcp_no_description) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }

        SettingsGroup {
            InfoRow(
                label = stringResource(R.string.subagent_model),
                value = modelText(entry)
            )
            SettingsDivider()
            InfoRow(
                label = stringResource(R.string.subagent_tools),
                value = toolsText(entry)
            )
            SettingsDivider()
            InfoRow(
                label = stringResource(R.string.subagent_inject),
                value = injectText(entry.inject)
            )
            entry.filePath?.let { path ->
                SettingsDivider()
                InfoRow(
                    label = stringResource(R.string.subagent_source_file),
                    value = path
                )
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.subagent_prompt))
        SettingsGroup {
            MarkdownContent(
                text = entry.prompt.ifBlank { stringResource(R.string.mcp_no_description) },
                color = MaterialTheme.colorScheme.onSurface,
                cache = cache,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }
    }
}

/** 键值行：左标签右值，值过长时换行。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun modelText(entry: SubAgentUiEntry): String {
    val parts = listOfNotNull(entry.providerId, entry.model, entry.reasoningEffort)
    return if (parts.isEmpty()) stringResource(R.string.subagent_inherit_parent) else parts.joinToString(" / ")
}

@Composable
private fun toolsText(entry: SubAgentUiEntry): String {
    val parts = buildList {
        if (entry.allowedTools.isNotEmpty()) {
            add(stringResource(R.string.subagent_tools_allow, entry.allowedTools.joinToString(", ")))
        }
        if (entry.disallowedTools.isNotEmpty()) {
            add(stringResource(R.string.subagent_tools_deny, entry.disallowedTools.joinToString(", ")))
        }
    }
    return if (parts.isEmpty()) stringResource(R.string.subagent_all_tools) else parts.joinToString("\n")
}

@Composable
private fun injectText(inject: Set<InjectPart>): String =
    if (inject.isEmpty()) {
        stringResource(R.string.subagent_inject_none)
    } else {
        InjectPart.entries.filter { it in inject }.joinToString(", ") { it.token }
    }

/** frontmatter 里对应的写法，详情页直接展示这个写法便于用户照抄。 */
private val InjectPart.token: String
    get() = when (this) {
        InjectPart.BASE -> "base"
        InjectPart.MAIN_RULES -> "mainRules"
        InjectPart.SKILLS -> "skills"
        InjectPart.MEMORY -> "memory"
        InjectPart.PROJECT_RULES -> "projectRules"
    }
