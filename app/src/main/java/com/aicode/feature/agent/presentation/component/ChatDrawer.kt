package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.presentation.AgentUIState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Download
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2
import androidx.compose.ui.res.stringResource
import com.aicode.R
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * 侧边栏内容：顶部 Tab 切换「会话」/「子代理」，底部「设置」入口卡片。
 * Tab0 为现有会话列表（已过滤掉子会话）；Tab1 为当前会话的子代理列表。
 */
@Composable
fun ChatDrawerContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    onSelect: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onRename: (ChatSession, String) -> Unit,
    onTogglePin: (ChatSession) -> Unit,
    onExport: (ChatSession) -> Unit,
    subSessions: List<ChatSession> = emptyList(),
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var pendingRename by remember { mutableStateOf<ChatSession?>(null) }
    var menuSession by remember { mutableStateOf<ChatSession?>(null) }
    val listState = rememberLazyListState()

    // 点击会话/重开侧边栏保持原滚动位置；仅当同一会话的最后回复时间变化（发消息/收到回复）时滚回顶部。
    var lastTouched by remember { mutableStateOf<Pair<String?, Long?>?>(null) }
    val currentUpdatedAt = sessions.firstOrNull { it.id == currentSessionId }?.updatedAt
    LaunchedEffect(currentSessionId, currentUpdatedAt) {
        val cur = currentSessionId to currentUpdatedAt
        val prev = lastTouched
        lastTouched = cur
        if (prev != null && prev.first == cur.first && prev.second != cur.second) {
            listState.scrollToItem(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(settingsPageBackground())
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 顶部 Tab 切换
        DrawerTopTabs(
            selected = selectedTab,
            onSelect = { selectedTab = it }
        )

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> SessionListTab(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    agentStates = agentStates,
                    listState = listState,
                    onSelect = onSelect,
                    onLongClick = { menuSession = it }
                )
                1 -> SubAgentListTab(
                    subSessions = subSessions,
                    currentSessionId = currentSessionId,
                    agentStates = agentStates,
                    onSelect = onSelect,
                    onLongClick = { menuSession = it }
                )
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Settings,
                title = stringResource(R.string.chat_settings),
                onClick = onNavigateToSettings
            )
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_delete_session)) },
            text = { Text(stringResource(R.string.chat_delete_session_confirm, session.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    menuSession?.let { session ->
        SessionActionSheet(
            session = session,
            onTogglePin = {
                menuSession = null
                onTogglePin(session)
            },
            onRename = {
                menuSession = null
                pendingRename = session
            },
            onExport = {
                menuSession = null
                onExport(session)
            },
            onDelete = {
                menuSession = null
                pendingDelete = session
            },
            onDismiss = { menuSession = null }
        )
    }

    pendingRename?.let { session ->
        var renameText by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.chat_rename_session)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.chat_session_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(session, renameText)
                        pendingRename = null
                    },
                    enabled = renameText.isNotBlank() && renameText != session.title
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 侧边栏顶部 Tab 切换条（会话 / 子代理），胶囊选中样式。 */
@Composable
private fun DrawerTopTabs(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                RoundedCornerShape(Radius.pill)
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        DrawerTabItem(
            label = stringResource(R.string.subagent_tab_sessions),
            selected = selected == 0,
            onClick = { onSelect(0) },
            modifier = Modifier.weight(1f)
        )
        DrawerTabItem(
            label = stringResource(R.string.subagent_tab_subagents),
            selected = selected == 1,
            onClick = { onSelect(1) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DrawerTabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.pill),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
    }
}

/** Tab0：根会话列表（按最后回复时间分组），与旧版侧边栏一致。 */
@Composable
private fun SessionListTab(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    listState: LazyListState,
    onSelect: (ChatSession) -> Unit,
    onLongClick: (ChatSession) -> Unit
) {
    if (sessions.isEmpty()) {
        Text(
            stringResource(R.string.chat_no_sessions_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)
        )
        return
    }
    val groups = remember(sessions) {
        val now = System.currentTimeMillis()
        val pinned = sessions.filter { it.isPinned }
        val unpinned = sessions.filterNot { it.isPinned }
        buildList {
            if (pinned.isNotEmpty()) add(SessionGroup("pinned", pinned))
            addAll(buildSessionGroups(unpinned, now))
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        items(groups, key = { it.groupKey }) { group ->
            SettingsGroupHeader(
                text = sessionGroupLabel(group.groupKey, group.sessions.first())
            )
            Column {
                group.sessions.forEachIndexed { index, session ->
                    if (index > 0) {
                        if (group.groupKey == "pinned") Spacer(Modifier.height(Spacing.sm)) else SettingsDivider()
                    }
                    val state = agentStates[session.id]
                    val isExecuting = state is AgentUIState.Loading || state is AgentUIState.Streaming
                    ChatSessionRow(
                        session = session,
                        selected = session.id == currentSessionId,
                        isExecuting = isExecuting,
                        pinned = session.isPinned,
                        onClick = { onSelect(session) },
                        onLongClick = { onLongClick(session) }
                    )
                }
            }
        }
    }
}

/** Tab1：当前会话的子代理列表。点击进入子会话（与普通会话一样），长按删除。
 * 使用与主会话列表相同的 [ChatSessionRow] 风格。
 */
@Composable
private fun SubAgentListTab(
    subSessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    onSelect: (ChatSession) -> Unit,
    onLongClick: (ChatSession) -> Unit
) {
    if (subSessions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.subagent_no_subagents),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.sm)
    ) {
        items(subSessions, key = { it.id }) { session ->
            val state = agentStates[session.id]
            val isExecuting = state is AgentUIState.Loading || state is AgentUIState.Streaming
            ChatSessionRow(
                session = session,
                selected = session.id == currentSessionId,
                isExecuting = isExecuting,
                pinned = false,
                onClick = { onSelect(session) },
                onLongClick = { onLongClick(session) }
            )
            SettingsDivider()
        }
    }
}

/**
 * 会话行长按弹出的功能菜单：置顶 / 重命名 / 导出 / 删除。底部 sheet 样式参照 git 分支的 RefActionSheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActionSheet(
    session: ChatSession,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            SheetActionRow(
                icon = Icons.Outlined.PushPin,
                label = stringResource(if (session.isPinned) R.string.chat_unpin_session else R.string.chat_pin_session),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onTogglePin()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Edit2,
                label = stringResource(R.string.common_rename),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onRename()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Download,
                label = stringResource(R.string.chat_export_session),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onExport()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Trash2,
                label = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    onDismiss()
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
            Spacer(Modifier.width(Spacing.lg))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint
            )
        }
    }
}

/** 侧边栏会话分组：同一时间组（今天 / 昨天 / 7天内 / 30天内 / 月份）内的会话，按最后回复时间降序。 */
internal data class SessionGroup(
    val groupKey: String,
    val sessions: List<ChatSession>
)

/**
 * 按最后回复时间（updatedAt）降序的会话列表分组：今天 / 昨天 / 7天内 / 30天内 / 更早按月。
 */
internal fun buildSessionGroups(sessions: List<ChatSession>, now: Long): List<SessionGroup> {
    val groups = mutableListOf<SessionGroup>()
    for (session in sessions) {
        val groupKey = sessionGroupKey(session.updatedAt, now)
        val lastIndex = groups.lastIndex
        if (lastIndex >= 0 && groups[lastIndex].groupKey == groupKey) {
            groups[lastIndex] = groups[lastIndex].copy(sessions = groups[lastIndex].sessions + session)
        } else {
            groups += SessionGroup(groupKey, listOf(session))
        }
    }
    return groups
}

/** 返回会话所属分组 key；月份分组为 ISO 年月（如 2026-05），其余为固定字面量。 */
internal fun sessionGroupKey(updatedAt: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days <= 7L -> "7d"
        days <= 30L -> "30d"
        else -> YearMonth.from(day).toString()
    }
}

@Composable
private fun sessionGroupLabel(groupKey: String, anchorSession: ChatSession): String = when (groupKey) {
    "pinned" -> stringResource(R.string.session_group_pinned)
    "today" -> stringResource(R.string.session_group_today)
    "yesterday" -> stringResource(R.string.session_group_yesterday)
    "7d" -> stringResource(R.string.session_group_last_7_days)
    "30d" -> stringResource(R.string.session_group_last_30_days)
    else -> SimpleDateFormat(
        stringResource(R.string.session_group_month_format),
        Locale.getDefault()
    ).format(Date(anchorSession.updatedAt))
}
