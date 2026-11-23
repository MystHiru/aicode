package com.aicode.feature.settings.presentation.component

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.MemberAvatar
import com.aicode.feature.agent.domain.team.TeamDefinition
import com.aicode.feature.agent.domain.team.TeamMember
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.presentation.TeamSaveState
import com.aicode.feature.settings.presentation.TeamUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.User
import compose.icons.feathericons.Users
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 头像弹窗里的备选表情。 */
private val avatarEmojis = listOf(
    "🦊", "🐼", "🐧", "🐙", "🦉", "🤖", "🧠", "🔍", "🛠️", "📐", "🧪", "🎯", "😀", "🙂"
)

/**
 * 团队（群）编辑页：群名、职责、群规，以及成员管理。
 *
 * 成员有两种加进来的方式：拉现成的子代理定义，或直接挑一个已配置的模型；
 * 每个成员都能改显示名、换头像、踢出群。用户自己（群主）也有名字与头像。
 *
 * @param availableAgents 可加入的子代理名（已启用）
 * @param providers 已配置的供应商，供「从模型添加」挑选
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TeamEditorScreen(
    initial: TeamUiEntry?,
    availableAgents: List<String>,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    saveState: TeamSaveState,
    composeState: com.aicode.feature.settings.presentation.TeamComposeState,
    onCompose: (String, String) -> Unit,
    onClearCompose: () -> Unit,
    onLoadMetadata: () -> Unit,
    onSave: (TeamDefinition.Form) -> Unit,
    onSaved: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var description by rememberSaveable { mutableStateOf(initial?.description ?: "") }
    var ownerName by rememberSaveable {
        mutableStateOf(initial?.ownerName ?: TeamDefinition.DEFAULT_OWNER_NAME)
    }
    var ownerAvatar by rememberSaveable { mutableStateOf(initial?.ownerAvatar ?: "") }
    var strategy by rememberSaveable { mutableStateOf(initial?.strategy ?: "") }
    // TeamMember 不是 Parcelable，旋转后不还原；编辑中的成员列表是短生命周期状态，可接受。
    var members by remember { mutableStateOf(initial?.members ?: emptyList()) }

    var hint by rememberSaveable { mutableStateOf("") }
    var showAgentPicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var renamingIndex by remember { mutableStateOf<Int?>(null) }
    var avatarTarget by remember { mutableStateOf<AvatarTarget?>(null) }

    LaunchedEffect(Unit) { onLoadMetadata() }
    LaunchedEffect(composeState) {
        val done = composeState as? com.aicode.feature.settings.presentation.TeamComposeState.Done
            ?: return@LaunchedEffect
        description = done.form.description
        members = done.form.members
        strategy = done.form.strategy
        ownerName = done.form.ownerName.ifBlank { TeamDefinition.DEFAULT_OWNER_NAME }
        onClearCompose()
    }
    LaunchedEffect(saveState) {
        if (saveState is TeamSaveState.Saved) onSaved()
    }
    BackHandler { onNavigateBack() }

    val canSave = name.isNotBlank() && members.isNotEmpty()

    // 从相册选头像：复制到 teams/avatars 下，存容器路径，和团队文件一起走。
    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = avatarTarget ?: return@rememberLauncherForActivityResult
        avatarTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val stored = withContext(Dispatchers.IO) { importAvatar(context, uri) }
            if (stored != null) {
                when (target) {
                    is AvatarTarget.Owner -> ownerAvatar = stored
                    is AvatarTarget.Member -> members = members.mapIndexed { index, member ->
                        if (index == target.index) member.copy(avatar = stored) else member
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (initial == null) R.string.team_editor_new else R.string.team_editor_edit
                        )
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = canSave && saveState !is TeamSaveState.Saving,
                        onClick = {
                            onSave(
                                TeamDefinition.Form(
                                    name = name.trim(),
                                    description = description.trim(),
                                    ownerName = ownerName.trim(),
                                    ownerAvatar = ownerAvatar,
                                    members = members,
                                    strategy = strategy.trim()
                                )
                            )
                        }
                    ) {
                        if (saveState is TeamSaveState.Saving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            (saveState as? TeamSaveState.Failed)?.let { failed ->
                Text(
                    text = stringResource(failed.error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.team_editor_basic))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.common_name),
                        placeholder = stringResource(R.string.team_name_hint),
                        singleLine = true
                    )
                    AppTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = stringResource(R.string.team_editor_description),
                        placeholder = stringResource(R.string.team_description_hint),
                        singleLine = false,
                        minLines = 2
                    )
                }
            }

            // 自动组队：只填群名，成员与分工由模型安排
            SettingsGroupHeader(text = stringResource(R.string.team_editor_autocompose))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    AppTextField(
                        value = hint,
                        onValueChange = { hint = it },
                        label = stringResource(R.string.team_editor_autocompose_hint),
                        placeholder = stringResource(R.string.team_editor_autocompose_placeholder),
                        singleLine = true
                    )
                    Text(
                        text = stringResource(R.string.team_editor_compose_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val composing = composeState is com.aicode.feature.settings.presentation.TeamComposeState.Composing
                        TextButton(
                            enabled = name.isNotBlank() && !composing,
                            onClick = { onCompose(name.trim(), hint) }
                        ) {
                            if (composing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(Spacing.sm))
                            }
                            Text(
                                stringResource(
                                    if (composing) R.string.team_editor_autocomposing
                                    else R.string.team_editor_autocompose_action
                                )
                            )
                        }
                        (composeState as? com.aicode.feature.settings.presentation.TeamComposeState.Failed)?.let { failed ->
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = failed.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // 群主（用户本人）
            SettingsGroupHeader(text = stringResource(R.string.team_editor_owner))
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MemberAvatar(
                        avatar = ownerAvatar,
                        name = ownerName,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 40.dp,
                        modifier = Modifier.clickable {
                            avatarTarget = AvatarTarget.Owner
                        }
                    )
                    Spacer(Modifier.width(Spacing.md))
                    AppTextField(
                        value = ownerName,
                        onValueChange = { ownerName = it },
                        label = stringResource(R.string.team_editor_owner_name),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.team_editor_members))
            SettingsGroup {
                if (members.isEmpty()) {
                    Text(
                        text = stringResource(R.string.team_editor_no_members),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 14.dp)
                    )
                    SettingsDivider()
                }
                members.forEachIndexed { index, member ->
                    MemberRow(
                        member = member,
                        tint = memberTint(index),
                        onClickAvatar = { avatarTarget = AvatarTarget.Member(index) },
                        onClickName = { renamingIndex = index },
                        onRemove = { members = members.filterIndexed { i, _ -> i != index } }
                    )
                    SettingsDivider()
                }
                SettingsRow(
                    icon = FeatherIcons.Users,
                    title = stringResource(R.string.team_editor_add_agent),
                    onClick = { showAgentPicker = true }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Plus,
                    title = stringResource(R.string.team_editor_add_model),
                    onClick = { showModelPicker = true }
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.team_editor_strategy))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    AppTextField(
                        value = strategy,
                        onValueChange = { strategy = it },
                        label = stringResource(R.string.team_editor_strategy),
                        placeholder = stringResource(R.string.team_strategy_hint),
                        singleLine = false,
                        minLines = 6
                    )
                }
            }
        }
    }

    if (showAgentPicker) {
        AgentPickerDialog(
            agents = availableAgents,
            chosen = members.map { it.agent }.filter { it.isNotBlank() }.toSet(),
            onPick = { agent ->
                if (members.none { it.agent.equals(agent, ignoreCase = true) }) {
                    members = members + TeamMember(name = agent, agent = agent)
                }
                showAgentPicker = false
            },
            onDismiss = { showAgentPicker = false }
        )
    }

    if (showModelPicker) {
        ModelSelectionSheet(
            title = stringResource(R.string.team_editor_pick_model),
            noModelsText = stringResource(R.string.team_editor_no_models),
            providers = providers,
            currentProviderId = "",
            currentModel = "",
            modelMetadata = modelMetadata,
            onSelect = { providerId, model ->
                val providerName = providers.firstOrNull { it.id == providerId }?.name.orEmpty()
                val display = model.substringAfterLast('/').ifBlank { model }
                members = members + TeamMember(
                    name = display,
                    avatar = "logo:$model",
                    providerId = providerId,
                    model = model,
                    prompt = "你是「$display」（来自 $providerName 的模型），就讨论的问题给出你自己的判断。"
                )
                showModelPicker = false
            },
            onClear = null,
            onDismiss = { showModelPicker = false }
        )
    }

    renamingIndex?.let { index ->
        val member = members.getOrNull(index)
        if (member != null) {
            RenameMemberDialog(
                current = member.name,
                onConfirm = { newName ->
                    members = members.mapIndexed { i, m ->
                        if (i == index) m.copy(name = newName) else m
                    }
                    renamingIndex = null
                },
                onDismiss = { renamingIndex = null }
            )
        } else {
            renamingIndex = null
        }
    }

    avatarTarget?.let { target ->
        AvatarPickerDialog(
            onPickEmoji = { emoji ->
                when (target) {
                    is AvatarTarget.Owner -> ownerAvatar = "emoji:$emoji"
                    is AvatarTarget.Member -> members = members.mapIndexed { i, m ->
                        if (i == target.index) m.copy(avatar = "emoji:$emoji") else m
                    }
                }
                avatarTarget = null
            },
            onPickImage = { avatarLauncher.launch("image/*") },
            onDismiss = { avatarTarget = null }
        )
    }
}

private sealed interface AvatarTarget {
    data object Owner : AvatarTarget
    data class Member(val index: Int) : AvatarTarget
}

/** 成员行：头像可点、名字可点改名、右侧踢出。 */
@Composable
private fun MemberRow(
    member: TeamMember,
    tint: androidx.compose.ui.graphics.Color,
    onClickAvatar: () -> Unit,
    onClickName: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MemberAvatar(
            avatar = member.avatar,
            name = member.name,
            tint = tint,
            size = 36.dp,
            modifier = Modifier.clickable { onClickAvatar() },
            modelName = member.model.ifBlank { null }
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f).clickable { onClickName() }) {
            Text(
                text = member.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = member.sourceLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                FeatherIcons.Trash2,
                contentDescription = stringResource(R.string.team_editor_remove_member),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AgentPickerDialog(
    agents: List<String>,
    chosen: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.team_editor_add_agent)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (agents.isEmpty()) {
                    Text(
                        text = stringResource(R.string.team_editor_no_agents),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                agents.forEach { agent ->
                    val disabled = chosen.any { it.equals(agent, ignoreCase = true) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !disabled) { onPick(agent) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            FeatherIcons.User,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            text = agent,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (disabled) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun RenameMemberDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.team_editor_rename_member)) },
        text = {
            AppTextField(
                value = value,
                onValueChange = { value = it },
                label = stringResource(R.string.common_name),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) }
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvatarPickerDialog(
    onPickEmoji: (String) -> Unit,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.team_editor_pick_avatar)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    avatarEmojis.forEach { emoji ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(Radius.md)
                                )
                                .clickable { onPickEmoji(emoji) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                TextButton(onClick = onPickImage) {
                    Text(stringResource(R.string.team_editor_pick_image))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 把相册里的图片复制到 `~/.aicode/teams/avatars/`，返回容器路径；失败返回 null。 */
private fun importAvatar(context: android.content.Context, uri: Uri): String? = runCatching {
    val dir = File(File(context.filesDir, "aicode/teams/avatars").apply { mkdirs() }, "${UUID.randomUUID()}.img")
    context.contentResolver.openInputStream(uri)?.use { input ->
        dir.outputStream().use { output -> input.copyTo(output) }
    } ?: return@runCatching null
    "~/.aicode/teams/avatars/${dir.name}"
}.getOrNull()

/** 成员配色按位置轮换，同一成员在编辑页与群聊卡片里颜色保持一致的观感。 */
@Composable
private fun memberTint(index: Int): androidx.compose.ui.graphics.Color = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.secondary,
    MaterialTheme.colorScheme.error
)[index % 4]

/** 保存失败 → 文案。 */
private fun TeamDefinition.SaveError.messageRes(): Int = when (this) {
    TeamDefinition.SaveError.INVALID_NAME -> R.string.team_error_invalid_name
    TeamDefinition.SaveError.NO_MEMBERS -> R.string.team_error_no_members
    TeamDefinition.SaveError.NAME_CONFLICT -> R.string.team_error_name_conflict
    TeamDefinition.SaveError.MEMBER_LIMIT -> R.string.team_error_member_limit
    TeamDefinition.SaveError.IO_FAILED -> R.string.team_chat_storage_error
}
