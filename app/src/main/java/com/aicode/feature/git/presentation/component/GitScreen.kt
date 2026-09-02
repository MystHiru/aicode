package com.aicode.feature.git.presentation.component

import android.content.ClipData
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.GitStatusColors
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.FloatingTabBar
import com.aicode.core.ui.FloatingTabItem
import com.aicode.feature.settings.presentation.component.settingsLightMode
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import com.aicode.feature.git.domain.model.GitStatus
import com.aicode.feature.git.domain.model.GitTab
import com.aicode.feature.git.presentation.GitViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.GitCommit
import compose.icons.feathericons.Key
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.X
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    viewModel: GitViewModel,
    onNavigateToCredentials: () -> Unit,
    onNavigateBack: () -> Unit,
    embedded: Boolean = false
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // toast → Snackbar 一次性消费。
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    var showCommitDialog by remember { mutableStateOf(false) }
    var showPullConfirm by remember { mutableStateOf(false) }

    // 三个 tab 的滚动状态统一提升到页面层，聚合出「是否正在滚动」用于底部 tab 栏滚动弱化。
    val statusScrollState = rememberScrollState()
    val branchesListState = rememberLazyListState()
    val logListState = rememberLazyListState()

    val pagerState = rememberPagerState { GitTab.entries.size }

    val tabsScrolling by remember {
        derivedStateOf {
            statusScrollState.isScrollInProgress ||
                branchesListState.isScrollInProgress ||
                logListState.isScrollInProgress
        }
    }

    // diff 视图：独立全屏页，不进入下方 GitScreen 的 Scaffold，避免双层顶栏。
    if (state.diffVisible) {
        DiffViewerScreen(
            diffData = state.diffData,
            filePath = state.diffPath,
            onBack = { viewModel.clearDiff() }
        )
        return
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.git_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            if (embedded) FeatherIcons.X else FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(
                                if (embedded) R.string.common_close else R.string.common_back
                            )
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToCredentials) {
                        Icon(FeatherIcons.Key, contentDescription = stringResource(R.string.git_credentials_and_identity))
                    }
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.busy) {
                        Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.git_refresh))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.notARepo -> NotARepoState(onInit = viewModel::initRepo)
                else -> HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (GitTab.entries[page]) {
                        GitTab.STATUS -> StatusTab(
                            status = state.status,
                            busy = state.busy,
                            hasRemote = state.hasRemote,
                            hasIdentity = state.hasIdentity,
                            untrackedDirFiles = state.untrackedDirFiles,
                            untrackedDirLoading = state.untrackedDirLoading,
                            scrollState = statusScrollState,
                            onStage = viewModel::stage,
                            onUnstage = viewModel::unstage,
                            onStageAll = viewModel::stageAll,
                            onUnstageAll = viewModel::unstageAll,
                            onCommit = { showCommitDialog = true },
                            onPull = {
                                if (state.status?.hasChanges == true) showPullConfirm = true else viewModel.pull()
                            },
                            onPush = viewModel::push,
                            onFileDiff = viewModel::loadWorktreeDiff,
                            onStagedFileDiff = viewModel::loadStagedDiff,
                            onUntrackedDiff = viewModel::loadUntrackedDiff,
                            onRevert = viewModel::revertFile,
                            onRevertAll = viewModel::revertAllUnstaged,
                            onDeleteUntracked = viewModel::deleteUntracked,
                            onToggleUntrackedDir = viewModel::toggleUntrackedDir,
                            onCopyPath = { path ->
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("git-path", path)))
                                    snackbarHostState.showSnackbar(context.getString(R.string.git_toast_path_copied))
                                }
                            }
                        )
                        GitTab.BRANCHES -> BranchesTab(
                            branches = state.branches,
                            tags = state.tags,
                            branchesLoading = state.branchesLoading,
                            branchesLoaded = state.branchesLoaded,
                            checkoutLoading = state.checkoutLoading,
                            listState = branchesListState,
                            onLoadBranches = viewModel::loadBranches,
                            onCheckout = viewModel::checkoutBranch,
                            onCreateBranch = viewModel::createBranch,
                            onDeleteBranch = viewModel::deleteBranch,
                            onDeleteRemoteBranch = viewModel::deleteRemoteBranch,
                            onRenameBranch = viewModel::renameBranch,
                            onCreateTag = viewModel::createTag,
                            onDeleteTag = viewModel::deleteTag
                        )
                        GitTab.LOG -> LogTab(
                            graph = state.graph,
                            commitFiles = state.commitFiles,
                            loadingCommit = state.loadingCommit,
                            graphLoadingMore = state.graphLoadingMore,
                            listState = logListState,
                            detailHash = state.commitDetailHash,
                            onOpenCommit = viewModel::openCommitDetail,
                            onCloseCommit = viewModel::closeCommitDetail,
                            onFileDiff = viewModel::loadCommitFileDiff,
                            onLoadMore = viewModel::loadMoreCommits
                        )
                    }
                }
            }
        }

        // 底部渐变蒙版 + 悬浮 tab 组：内容可滚动到屏幕底部穿过 tab 栏，被渐变遮罩（同主页输入框）。
        // 仅仓库态显示：加载中/非仓库态时 HorizontalPager 未组合，点击 tab 无内容可切换。
        if (!state.loading && !state.notARepo) {
            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.Activity, stringResource(R.string.git_tab_status)),
                    FloatingTabItem(FeatherIcons.GitBranch, stringResource(R.string.git_tab_branches)),
                    FloatingTabItem(FeatherIcons.GitCommit, stringResource(R.string.git_tab_commits))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
    }

    if (showPullConfirm) {
        AlertDialog(
            onDismissRequest = { showPullConfirm = false },
            title = { Text(stringResource(R.string.git_pull)) },
            text = { Text(stringResource(R.string.git_pull_dirty_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showPullConfirm = false
                    viewModel.pull()
                }) { Text(stringResource(R.string.git_pull_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showPullConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showCommitDialog) {
        CommitDialog(
            onDismiss = { showCommitDialog = false },
            onConfirm = { msg ->
                showCommitDialog = false
                viewModel.commit(msg)
            }
        )
    }
}

@Composable
internal fun StatusMetric(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.semanticColors.mutedSurface,
        shape = RoundedCornerShape(Radius.md),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, top = Spacing.lg, end = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 状态码 → 配色（容器色 + 前景色）。
 *
 * 与多数 Git 客户端约定一致：新增=绿、修改=琥珀、删除=红、重命名/复制=蓝、未跟踪=灰、
 * 冲突=紫红、类型变更=青。仅取首字符判定，porcelain 的 X/Y 两列统一映射。
 */
private fun statusColor(code: String): Pair<Color, Color> = when (code.firstOrNull()) {
    'A' -> GitStatusColors.Added to Color.White            // 新增
    'M' -> GitStatusColors.Modified to Color.White         // 修改
    'D' -> GitStatusColors.Deleted to Color.White          // 删除
    'R', 'C' -> GitStatusColors.Renamed to Color.White     // 重命名/复制
    '?' -> GitStatusColors.Untracked to Color.White        // 未跟踪
    'U' -> GitStatusColors.Conflict to Color.White         // 冲突
    'T' -> GitStatusColors.TypeChanged to Color.White      // 类型变更
    else -> GitStatusColors.Default to Color.White         // 兜底
}

@Composable
internal fun StatusChip(text: String) {
    val (bg, fg) = statusColor(text)
    Surface(
        color = bg,
        shape = RoundedCornerShape(Radius.pill),
        modifier = Modifier.size(width = 32.dp, height = 20.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text.take(2),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = fg
            )
        }
    }
}

@Composable
internal fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 非仓库态：文案 + 「初始化 Git 仓库」按钮（跑 `git init`，成功后自动刷新进仓库态）。 */
@Composable
private fun NotARepoState(onInit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                stringResource(R.string.git_not_a_repo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.git_init_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = onInit) {
                Icon(FeatherIcons.GitBranch, contentDescription = null)
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.git_init_repo))
            }
        }
    }
}

@Composable
private fun CommitDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_commit_dialog_title)) },
        text = {
            AppTextField(
                value = message,
                onValueChange = { message = it },
                label = stringResource(R.string.git_commit_message),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (message.isNotBlank()) onConfirm(message.trim()) },
                enabled = message.isNotBlank()
            ) { Text(stringResource(R.string.git_action_commit)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
