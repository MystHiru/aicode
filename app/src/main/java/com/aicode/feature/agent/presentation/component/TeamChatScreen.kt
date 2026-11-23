package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.MemberAvatar
import com.aicode.feature.agent.presentation.TeamChatViewModel
import com.aicode.feature.settings.presentation.component.SettingsEmptyState
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Copy
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.Square
import compose.icons.feathericons.Users
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamChatScreen(
    state: TeamChatViewModel.State,
    onPrepare: (String) -> Unit,
    onConfirm: () -> Boolean,
    onDismissPreview: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onUseProposal: suspend (String) -> Boolean,
    onClearHistory: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var handingOff by remember { mutableStateOf(false) }
    val canInteract = state.team != null && !state.busy && !handingOff

    BackHandler(onBack = onNavigateBack)
    LaunchedEffect(state.messages.lastOrNull()?.id, state.streaming?.speaker) {
        val last = state.messages.size - if (state.streaming == null) 1 else 0
        if (last >= 0) listState.scrollToItem(last)
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(state.team?.name ?: stringResource(R.string.team_chat_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsPageBackground()),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = canInteract) {
                            Icon(FeatherIcons.MoreHorizontal, contentDescription = stringResource(R.string.team_chat_more))
                        }
                        DropdownMenu(expanded = menuOpen && canInteract, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.team_chat_edit)) },
                                onClick = { menuOpen = false; onEdit() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.team_chat_clear)) },
                                enabled = state.messages.isNotEmpty(),
                                onClick = { menuOpen = false; confirmClear = true }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.messages.isEmpty() && state.streaming == null -> SettingsEmptyState(
                        icon = FeatherIcons.Users,
                        title = stringResource(R.string.team_chat_empty),
                        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lg)
                    )
                    else -> SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            items(state.messages, key = { it.id }) { message ->
                                val label = if (message.role == TeamChatViewModel.ROLE_PLAN) {
                                    stringResource(R.string.team_chat_proposal)
                                } else message.speaker
                                DiscussionBubble(label, message.avatar, message.content, message.role == TeamChatViewModel.ROLE_OWNER)
                            }
                            state.streaming?.let { turn ->
                                item(key = "streaming") { DiscussionBubble(turn.speaker, turn.avatar, turn.text, false) }
                            }
                        }
                    }
                }
            }

            state.error?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                )
            }
            if (state.failedMembers.isNotEmpty()) {
                Text(
                    stringResource(R.string.team_chat_skipped, state.failedMembers.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                )
            }
            state.proposal?.let { proposal ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        enabled = canInteract,
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                ?: return@IconButton
                            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.team_chat_proposal), proposal))
                            Toast.makeText(context, R.string.common_copy_success, Toast.LENGTH_SHORT).show()
                        }
                    ) { Icon(FeatherIcons.Copy, contentDescription = stringResource(R.string.common_copy)) }
                    TextButton(
                        enabled = canInteract,
                        onClick = {
                            handingOff = true
                            scope.launch {
                                try {
                                    if (!onUseProposal(proposal)) {
                                        Toast.makeText(context, R.string.team_chat_no_draft_target, Toast.LENGTH_LONG).show()
                                    }
                                } finally {
                                    handingOff = false
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.team_chat_append_draft)) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppTextField(
                    value = input,
                    onValueChange = { input = it.take(TeamChatViewModel.MAX_INPUT_CHARS) },
                    enabled = canInteract,
                    placeholder = stringResource(R.string.team_chat_input_hint),
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                if (state.busy) {
                    FilledIconButton(onClick = onStop) {
                        Icon(FeatherIcons.Square, contentDescription = stringResource(R.string.team_chat_stop))
                    }
                } else {
                    FilledIconButton(enabled = canInteract && input.isNotBlank(), onClick = { onPrepare(input) }) {
                        Icon(FeatherIcons.ArrowUp, contentDescription = stringResource(R.string.team_chat_send))
                    }
                }
            }
        }
    }

    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = onDismissPreview,
            title = { Text(stringResource(R.string.team_chat_confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.team_chat_confirm_body))
                    Text(preview.recipients.joinToString("\n"))
                    if (preview.skipped.isNotEmpty()) {
                        Text(stringResource(R.string.team_chat_skipped, preview.skipped.joinToString(", ")))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (onConfirm()) input = "" }) {
                    Text(stringResource(R.string.team_chat_send))
                }
            },
            dismissButton = { TextButton(onClick = onDismissPreview) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.team_chat_clear)) },
            text = { Text(stringResource(R.string.team_chat_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearHistory() }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

@Composable
private fun DiscussionBubble(speaker: String, avatar: String, content: String, owner: Boolean) {
    val tint = if (owner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (owner) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!owner) {
            MemberAvatar(avatar = avatar, name = speaker, tint = tint, size = 30.dp)
            Spacer(Modifier.width(Spacing.sm))
        }
        Column(
            horizontalAlignment = if (owner) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 420.dp).weight(1f, fill = false)
        ) {
            Text(speaker, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(Spacing.xs))
            Surface(
                shape = RoundedCornerShape(Radius.md),
                color = if (owner) tint.copy(alpha = 0.14f) else MaterialTheme.semanticColors.cardSurface
            ) {
                Text(content, modifier = Modifier.padding(Spacing.md), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
