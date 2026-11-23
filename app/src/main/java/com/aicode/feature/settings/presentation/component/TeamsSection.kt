package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.presentation.TeamUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Users

/**
 * 团队二级页：已编排团队列表 + 底部「新建团队」入口。
 *
 * 团队只存成员名单与协作说明；成员的能力仍来自各自的子代理定义，
 * 所以这一页刻意做得比子代理页轻——没有启停开关、没有批量操作。
 */
@Composable
internal fun TeamsSection(
    teams: List<TeamUiEntry>,
    deleting: Set<String>,
    onOpenEditor: (TeamUiEntry?) -> Unit,
    onOpenChat: (TeamUiEntry) -> Unit,
    onDelete: (TeamUiEntry) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<TeamUiEntry?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (teams.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(Radius.lg)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            FeatherIcons.Users,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.teams_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.teams_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.teams_list_header))
        SettingsGroup {
            teams.forEach { team ->
                val ready = team.key !in deleting
                SettingsRow(
                    icon = FeatherIcons.Users,
                    title = team.name,
                    subtitle = stringResource(R.string.teams_member_count, team.members.size) +
                        "：" + team.members.joinToString("、") { it.name },
                    // 行点进群聊，改配置走行尾的铅笔，符合「群」的直觉。
                    onClick = { if (ready) onOpenChat(team) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onOpenEditor(team) }, enabled = ready) {
                                Icon(
                                    FeatherIcons.Edit2,
                                    contentDescription = stringResource(R.string.team_editor_edit),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(onClick = { pendingDelete = team }, enabled = ready) {
                                Icon(
                                    FeatherIcons.Trash2,
                                    contentDescription = stringResource(R.string.team_delete),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                )
                SettingsDivider()
            }
            SettingsRow(
                icon = FeatherIcons.Plus,
                title = stringResource(R.string.teams_new),
                onClick = { onOpenEditor(null) }
            )
        }
    }

    pendingDelete?.let { team ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.team_delete)) },
            text = { Text(stringResource(R.string.team_delete_confirm, team.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(team)
                        pendingDelete = null
                    }
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
