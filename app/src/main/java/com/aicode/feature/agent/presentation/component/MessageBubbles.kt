package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.ContentWidth
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.hasVisibleContent
import com.aicode.feature.agent.presentation.MessageRole
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Copy
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.RotateCcw
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 落库思考气泡保持展开的窗口（ms）：刚结束的思考不立即折叠回缩，防止高度骤变抽搐。 */
private const val REASONING_FRESH_WINDOW_MS = 5_000L

@Composable
internal fun AgentMessageItem(
    message: AgentUIMessage,
    liveOutput: String? = null,
    markdownCache: MarkdownRenderCache? = null,
    onRewindClick: ((String) -> Unit)? = null,
    onMoreClick: ((AgentUIMessage) -> Unit)? = null,
    onToolToggle: (() -> Unit)? = null,
    /** 新消息入场动画延迟（ms）：null 表示历史消息直接显示；非 null 时首次组合延迟后淡入展开。 */
    entryDelayMs: Long? = null
) {
    if (message.isCompactionMarker) {
        // 压缩内部锚点不再渲染分隔线：摘要卡片已提供压缩反馈，避免与卡片重复。
        return
    }

    if (message.isContextSummary) {
        CompactionSummaryCard(message, markdownCache)
        return
    }

    if (message.isCompactionFailure) {
        CompactionFailureCard(message)
        return
    }

    if (message.isBackgroundNotification) {
        BackgroundNotificationBar(message)
        return
    }

    val hasReasoning = message.role == MessageRole.ASSISTANT && !message.reasoning.isNullOrEmpty()
    val hasContent = message.content.hasVisibleContent()
    val hasAttachments = message.attachments.isNotEmpty()
    if (message.role == MessageRole.ASSISTANT && !hasContent && !hasReasoning) return

    val isUser = message.role == MessageRole.USER
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // 用户气泡随文字撑开，最大撑到与 AI 气泡同宽（消息列宽 - 列表两侧 padding）。
    // 大屏下消息列已限宽居中，气泡上限跟着收窄，不能再拿整个屏宽算。
    val maxUserBubbleWidth = remember(screenWidthDp) {
        minOf(screenWidthDp.dp, ContentWidth.readable) - Spacing.lg * 2
    }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()

    // 工具调用卡片入场动画：仅流式期间新插入时淡入展开；历史/落库后直接显示不重播。
    // item 滚出视口重新组合时若消息已过新消息窗口，entryDelayMs 为 null，同样直接显示。
    // 播完即进 saveable：仍在新消息窗口内（entryDelayMs 非 null）时切页返回或 item 回收
    // 重挂载，不会再播一遍入场。
    var entered by rememberSaveable(message.id) { mutableStateOf(entryDelayMs == null) }
    LaunchedEffect(entryDelayMs) {
        val delayMs = entryDelayMs
        if (delayMs != null) {
            delay(delayMs)
            entered = true
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (hasReasoning) {
            // 刚结束思考落库的消息保持展开（流式思考展开→落库折叠会高度骤变抽搐）；稍后/历史默认折叠
            val reasoningJustFinished = System.currentTimeMillis() - message.timestamp < REASONING_FRESH_WINDOW_MS
            ReasoningBubble(text = message.reasoning.orEmpty(), initiallyExpanded = reasoningJustFinished, cache = markdownCache)
        }
        if (hasContent || hasAttachments || message.role != MessageRole.ASSISTANT) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                // 助手消息左对齐，用户消息右对齐
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                if (hasContent || message.role == MessageRole.TOOL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.role == MessageRole.TOOL) {
                            // 工具调用卡片：仅流式期间新插入时淡入展开；落库后的历史直接显示不重播
                            AnimatedVisibility(
                                visible = entered,
                                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 }
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(Radius.md, Radius.md, Radius.md, Radius.xs),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    ToolMessageBody(message, liveOutput = liveOutput, onToggle = onToolToggle)
                                }
                            }
                        } else {
                            Surface(
                                shape = if (isUser) {
                                    RoundedCornerShape(Radius.md, Radius.md, Radius.xs, Radius.md)
                                } else {
                                    RoundedCornerShape(Radius.md, Radius.md, Radius.md, Radius.xs)
                                },
                                color = when (message.role) {
                                    MessageRole.USER -> MaterialTheme.colorScheme.primary
                                    MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surface
                                    MessageRole.TOOL -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                border = if (message.role == MessageRole.ASSISTANT) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                } else null,
                                // 用户气泡随内容自适应宽度，最大撑到与 AI 气泡同宽；AI/工具气泡填满可用宽度
                                modifier = if (isUser) {
                                    Modifier.widthIn(max = maxUserBubbleWidth)
                                } else {
                                    Modifier.fillMaxWidth()
                                }
                            ) {
                                val textColor = when (message.role) {
                                    MessageRole.USER -> MaterialTheme.colorScheme.onPrimary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                SelectionContainer {
                                    val selectionColors = if (isUser) {
                                        TextSelectionColors(
                                            handleColor = MaterialTheme.colorScheme.onPrimary,
                                            backgroundColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.28f),
                                        )
                                    } else {
                                        TextSelectionColors(
                                            handleColor = MaterialTheme.colorScheme.primary,
                                            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                        )
                                    }
                                    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                                        if (isUser) {
                                            Text(
                                                text = message.content,
                                                color = textColor,
                                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)
                                            )
                                        } else {
                                            MarkdownContent(
                                                text = message.content,
                                                color = textColor,
                                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                                                cache = markdownCache
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (isUser && hasAttachments) {
                    MessageAttachmentPreviewRow(attachments = message.attachments)
                }
                // 气泡下方复制按钮（工具消息不显示）
                if (message.content.hasVisibleContent() && message.role != MessageRole.TOOL) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                        MessageActionIconButton(
                            icon = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                            contentDescription = if (copied) stringResource(R.string.chat_copied) else stringResource(R.string.chat_copy),
                            tint = iconTint,
                            onClick = {
                                copyScope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("message", message.content))
                                    )
                                    copied = true
                                }
                            }
                        )
                        if (isUser && onRewindClick != null) {
                            MessageActionIconButton(
                                icon = FeatherIcons.RotateCcw,
                                contentDescription = stringResource(R.string.checkpoint_rewind_title),
                                tint = iconTint,
                                onClick = { onRewindClick(message.id) }
                            )
                        }
                        if (onMoreClick != null) {
                            MessageActionIconButton(
                                icon = FeatherIcons.MoreHorizontal,
                                contentDescription = stringResource(R.string.chat_more_options),
                                tint = iconTint,
                                onClick = { onMoreClick(message) }
                            )
                        }
                        if (message.role == MessageRole.ASSISTANT && (message.inputTokens > 0 || message.outputTokens > 0)) {
                            val inStr = formatTokenCount(message.inputTokens)
                            val outStr = formatTokenCount(message.outputTokens)
                            Text(
                                text = "↑$inStr ↓$outStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 复制成功 1.5s 后恢复图标
                    if (copied) {
                        LaunchedEffect(copied) {
                            delay(1500)
                            copied = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(28.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(14.dp))
    }
}

/**
 * 后台任务完成通知的轻量提示条：不作为普通用户气泡展示，仅以紧凑横条形式告知用户
 * 哪个后台命令结束了、成功与否。从通知文本里提取 <status>/<summary> 字段。
 */
@Composable
private fun BackgroundNotificationBar(message: AgentUIMessage) {
    val content = message.content
    val statuses = Regex("<status>(.*?)</status>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim()?.lowercase() }.filterNotNull().toList()
    val summaries = Regex("<summary>(.*?)</summary>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim() }.filterNotNull().toList()
    val isSuccess = statuses.all { it == "completed" }
    val dotColor = if (isSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    val label = when {
        summaries.size <= 1 -> summaries.firstOrNull() ?: stringResource(R.string.chat_bg_command_done)
        else -> {
            val failedCount = statuses.count { it != "completed" }
            val namePart = summaries.joinToString("、") { s -> s.removePrefix("后台任务「").substringBefore("」") }
            if (failedCount > 0) {
                stringResource(R.string.chat_bg_commands_partial_failed, summaries.size, failedCount, namePart)
            } else {
                stringResource(R.string.chat_bg_commands_done, summaries.size, namePart)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 上下文压缩成功卡片：默认折叠为「圆点 + 上下文已压缩 + 箭头」，点击展开查看摘要全文。
 * 用 primary 色系与工具调用（surfaceVariant + 绿/红点）区分。
 */
@Composable
private fun CompactionSummaryCard(message: AgentUIMessage, markdownCache: MarkdownRenderCache?) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_context_compressed),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (expanded && message.content.hasVisibleContent()) {
                Spacer(Modifier.height(Spacing.sm))
                MarkdownContent(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    cache = markdownCache
                )
            }
        }
    }
}

/**
 * 上下文压缩失败卡片：默认折叠为「圆点 + 压缩失败 + 原因首行 + 箭头」，点击展开查看完整原因。
 * 用 error 色系与工具调用失败（surfaceVariant + 红点）区分。
 */
@Composable
private fun CompactionFailureCard(message: AgentUIMessage) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val reason = message.content.ifBlank { stringResource(R.string.chat_compaction_failed) }
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_compaction_failed),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                if (!expanded) {
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = reason.replace("\n", " ").trim(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Icon(
                    if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (expanded && reason.isNotBlank()) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = reason,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}