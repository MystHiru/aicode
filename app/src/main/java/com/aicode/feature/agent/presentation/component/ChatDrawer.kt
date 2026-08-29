package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.aicode.core.ui.AppTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.isValidFileEntryName
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Download
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2
import androidx.compose.ui.res.painterResource
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
 * 侧边栏内容：顶部 Tab 切换「会话」/「文件」，底部「设置」入口卡片。
 * Tab0 为根会话列表，带子代理的会话行可就地展开；Tab1 为当前工作区的单层文件浏览。
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
    subSessionsByParent: Map<String, List<ChatSession>> = emptyMap(),
    browsePath: String,
    browseState: FileBrowseState,
    onOpenDir: (String) -> Unit,
    onBrowseUp: () -> Unit,
    onOpenFile: (String) -> Unit,
    onRefreshBrowse: () -> Unit,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
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
                    subSessionsByParent = subSessionsByParent,
                    listState = listState,
                    onSelect = onSelect,
                    onLongClick = { menuSession = it }
                )
                1 -> FileBrowserTab(
                    path = browsePath,
                    state = browseState,
                    onOpenDir = onOpenDir,
                    onBrowseUp = onBrowseUp,
                    onOpenFile = onOpenFile,
                    onRefresh = onRefreshBrowse,
                    onCreateFile = onCreateFile,
                    onCreateFolder = onCreateFolder,
                    onRenameEntry = onRenameEntry,
                    onDeleteEntry = onDeleteEntry
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
                AppTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = stringResource(R.string.chat_session_name),
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

/** 侧边栏顶部 Tab 切换条（会话 / 文件），胶囊选中样式。 */
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
            label = stringResource(R.string.drawer_tab_files),
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

/** Tab0：根会话列表（按最后回复时间分组）。带子代理的会话行尾有展开箭头，展开后在其下方缩进列出子代理。 */
@Composable
private fun SessionListTab(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    listState: LazyListState,
    onSelect: (ChatSession) -> Unit,
    onLongClick: (ChatSession) -> Unit
) {
    if (sessions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.chat_no_sessions_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md)
            )
        }
        return
    }
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }
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
                    val subSessions = subSessionsByParent[session.id].orEmpty()
                    val expanded = session.id in expandedIds
                    ChatSessionRow(
                        session = session,
                        selected = session.id == currentSessionId,
                        isExecuting = isExecuting,
                        pinned = session.isPinned,
                        onClick = { onSelect(session) },
                        onLongClick = { onLongClick(session) },
                        trailing = if (subSessions.isEmpty()) null else {
                            {
                                SubAgentExpandToggle(
                                    expanded = expanded,
                                    count = subSessions.size,
                                    onToggle = {
                                        expandedIds = if (expanded) {
                                            expandedIds - session.id
                                        } else {
                                            expandedIds + session.id
                                        }
                                    }
                                )
                            }
                        }
                    )
                    if (expanded) {
                        subSessions.forEach { sub ->
                            val subState = agentStates[sub.id]
                            Row(modifier = Modifier.padding(start = Spacing.lg)) {
                                ChatSessionRow(
                                    session = sub,
                                    selected = sub.id == currentSessionId,
                                    isExecuting = subState is AgentUIState.Loading ||
                                        subState is AgentUIState.Streaming,
                                    pinned = false,
                                    onClick = { onSelect(sub) },
                                    onLongClick = { onLongClick(sub) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 会话行尾的子代理展开开关：显示数量与箭头，自己消费点击，不触发整行选中。 */
@Composable
private fun SubAgentExpandToggle(
    expanded: Boolean,
    count: Int,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
            contentDescription = stringResource(
                if (expanded) R.string.drawer_collapse_subagents else R.string.drawer_expand_subagents
            ),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Tab1：当前工作区的单层文件浏览。
 * 不做缩进树：抽屉只有 300dp 宽，本项目自身路径就深达十层，缩进到四五层文件名就全是省略号。
 */
@Composable
private fun FileBrowserTab(
    path: String,
    state: FileBrowseState,
    onOpenDir: (String) -> Unit,
    onBrowseUp: () -> Unit,
    onOpenFile: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit
) {
    var creating by remember { mutableStateOf<CreateKind?>(null) }
    var menuEntry by remember { mutableStateOf<FileEntry?>(null) }
    var pendingRename by remember { mutableStateOf<FileEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<FileEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileBreadcrumb(
                path = path,
                onNavigate = onOpenDir,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { creating = CreateKind.FILE },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.FilePlus,
                    contentDescription = stringResource(R.string.file_browser_new_file),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { creating = CreateKind.FOLDER },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.FolderPlus,
                    contentDescription = stringResource(R.string.file_browser_new_folder),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = FeatherIcons.RefreshCw,
                    contentDescription = stringResource(R.string.file_browser_refresh),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (path != WorkspacePathMapper.CONTAINER_ROOT) {
            FileBrowserRow(
                icon = FileTypeIcon.Mono(FeatherIcons.ArrowUp),
                label = stringResource(R.string.file_browser_up),
                emphasized = true,
                onClick = onBrowseUp
            )
        }
        when (state) {
            is FileBrowseState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            is FileBrowseState.Error -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.detail ?: stringResource(R.string.file_browser_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )
            }

            is FileBrowseState.Success -> if (state.entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.file_browser_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.name }) { entry ->
                        FileBrowserRow(
                            icon = if (entry.isDirectory) {
                                FileTypeIcon.Mono(FeatherIcons.Folder)
                            } else {
                                fileTypeIconFor(entry.name)
                            },
                            label = entry.name,
                            emphasized = entry.isDirectory,
                            onClick = {
                                val child = "$path/${entry.name}"
                                if (entry.isDirectory) onOpenDir(child) else onOpenFile(child)
                            },
                            onLongClick = { menuEntry = entry }
                        )
                    }
                }
            }
        }
    }

    creating?.let { kind ->
        FileNameInputDialog(
            title = stringResource(
                if (kind == CreateKind.FILE) R.string.file_browser_new_file else R.string.file_browser_new_folder
            ),
            confirmLabel = stringResource(R.string.common_create),
            initialName = "",
            onConfirm = { name ->
                if (kind == CreateKind.FILE) onCreateFile(name) else onCreateFolder(name)
                creating = null
            },
            onDismiss = { creating = null }
        )
    }

    menuEntry?.let { entry ->
        FileEntryActionSheet(
            name = entry.name,
            onRename = {
                menuEntry = null
                pendingRename = entry
            },
            onDelete = {
                menuEntry = null
                pendingDelete = entry
            },
            onDismiss = { menuEntry = null }
        )
    }

    pendingRename?.let { entry ->
        FileNameInputDialog(
            title = stringResource(R.string.common_rename),
            confirmLabel = stringResource(R.string.common_rename),
            initialName = entry.name,
            onConfirm = { name ->
                onRenameEntry("$path/${entry.name}", name)
                pendingRename = null
            },
            onDismiss = { pendingRename = null }
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = {
                Text(
                    stringResource(
                        if (entry.isDirectory) {
                            R.string.file_browser_delete_folder_confirm
                        } else {
                            R.string.file_browser_delete_file_confirm
                        },
                        entry.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEntry("$path/${entry.name}")
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 新建对象类型，决定确认后调创建文件还是创建文件夹。 */
private enum class CreateKind { FILE, FOLDER }

/** 新建 / 重命名共用的名称输入弹窗：名称非法或与原名相同时禁用确认。 */
@Composable
private fun FileNameInputDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = stringResource(R.string.file_browser_name_label),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = isValidFileEntryName(name) && name.trim() != initialName
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 文件/目录行长按弹出的功能菜单：重命名 / 删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEntryActionSheet(
    name: String,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            SheetActionRow(
                icon = FeatherIcons.Edit2,
                label = stringResource(R.string.common_rename),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onRename
            )
            SheetActionRow(
                icon = FeatherIcons.Trash2,
                label = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete
            )
        }
    }
}

/** 面包屑：每一段可点回该层，横向可滚以容纳深路径。 */
@Composable
private fun FileBreadcrumb(
    path: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val segments = remember(path) {
        val relative = path.removePrefix(WorkspacePathMapper.CONTAINER_ROOT).trim('/')
        buildList {
            add(WORKSPACE_LABEL to WorkspacePathMapper.CONTAINER_ROOT)
            if (relative.isNotEmpty()) {
                var accumulated = WorkspacePathMapper.CONTAINER_ROOT
                relative.split('/').forEach { name ->
                    accumulated = "$accumulated/$name"
                    add(name to accumulated)
                }
            }
        }
    }
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(segments) { index, segment ->
            val (label, target) = segment
            val isCurrent = index == segments.lastIndex
            if (index > 0) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .then(
                        if (isCurrent) Modifier else Modifier.clickable { onNavigate(target) }
                    )
                    .padding(horizontal = Spacing.sm, vertical = 2.dp)
            )
        }
    }
}

/** 文件浏览的单行：目录 / 文件 / 上一级均复用。[onLongClick] 为 null 时不响应长按（如「上一级」）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileBrowserRow(
    icon: FileTypeIcon,
    label: String,
    emphasized: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (icon) {
            is FileTypeIcon.Colored -> Icon(
                painter = painterResource(icon.res),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                // 彩色文件类型图标保留原色，不随主题染色
                tint = Color.Unspecified
            )
            is FileTypeIcon.Mono -> Icon(
                imageVector = icon.vector,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (emphasized) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 面包屑根节点显示名，对应容器路径 `~/workspace`。 */
private const val WORKSPACE_LABEL = "workspace"

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
