package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.plugin.PluginAuthCallbackResult
import com.aicode.feature.agent.domain.plugin.PluginAuthMethod
import com.aicode.feature.agent.domain.plugin.PluginAuthorizeResult
import com.aicode.feature.agent.domain.plugin.PluginDescriptor
import com.aicode.feature.agent.domain.plugin.PluginToolDescriptor
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Key
import compose.icons.feathericons.Package
import compose.icons.feathericons.Tool
import compose.icons.feathericons.Zap
import kotlinx.serialization.json.JsonObject

/**
 * 插件详情底部弹窗（MCP 风格）：展示插件概览、注册的 Tools 与 Hooks 列表，支持删除操作。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PluginDetailDialog(
    plugin: PluginDescriptor,
    tools: List<PluginToolDescriptor> = emptyList(),
    authMethods: List<PluginAuthMethod> = emptyList(),
    authLoggedIn: Boolean = false,
    authBusy: Boolean = false,
    disabled: Boolean = false,
    onToggleDisabled: (Boolean) -> Unit = {},
    onAuthorize: suspend (Int) -> PluginAuthorizeResult = { PluginAuthorizeResult(error = "未实现") },
    onSubmit: suspend (String?) -> PluginAuthCallbackResult = { PluginAuthCallbackResult("failed") },
    onSaveApiKey: suspend (String) -> Boolean = { false },
    onLogout: suspend () -> Unit = {},
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(if (plugin.tools.isNotEmpty()) 0 else 1) }
    var showAuthDialog by remember { mutableStateOf(false) }
    // 乐观更新：点击立即切换视觉状态，配置修改与 reload 异步执行（耗时数秒不阻塞 UI）；
    // 用 remember 而非参数派生，避免 reload 完成后列表刷新导致开关回跳。
    var enabledState by remember { mutableStateOf(!disabled) }
    val authProvider = plugin.auth?.provider

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val flingFix = rememberSheetFlingFix(sheetState)

    val isFailed = plugin.error != null
    val statusText = when {
        plugin.missing -> stringResource(R.string.plugins_missing)
        plugin.disabled -> stringResource(R.string.plugins_disabled_title)
        isFailed -> stringResource(R.string.plugins_load_failed)
        else -> stringResource(R.string.plugins_loaded)
    }
    val statusColor = when {
        plugin.missing -> MaterialTheme.colorScheme.error
        plugin.disabled -> MaterialTheme.colorScheme.onSurfaceVariant
        isFailed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.88f)
        ) {
            // ── 顶部标题栏（无关闭/删除按钮，靠下滑或点遮罩关闭）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.plugins_detail_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            ) {
                // ── 基础信息卡片 ──
                Card(
                    shape = RoundedCornerShape(Radius.lg),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Package,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(Spacing.md))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = plugin.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    McpPill(
                                        text = sourceLabel(plugin.source),
                                        textColor = sourcePillPalette(plugin.source).first,
                                        backgroundColor = sourcePillPalette(plugin.source).second
                                    )
                                    plugin.version?.let {
                                        McpPill(
                                            text = stringResource(R.string.plugins_version, it),
                                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    McpPill(
                                        text = statusText,
                                        textColor = statusColor,
                                        backgroundColor = statusColor.copy(alpha = 0.12f)
                                    )
                                }
                            }
                        }

                        if (isFailed) {
                            Spacer(modifier = Modifier.height(10.dp))
                            PluginErrorText(
                                text = plugin.error.orEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // ── 启用/禁用开关（切换后重载运行时）──
                Spacer(modifier = Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(if (disabled) R.string.plugins_disabled_title else R.string.plugins_enabled_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.plugins_toggle_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabledState,
                        onCheckedChange = { newEnabled ->
                            enabledState = newEnabled
                            onToggleDisabled(!newEnabled)
                        },
                        enabled = !plugin.missing
                    )
                }

                // ── 插件认证卡片（插件声明 auth 时显示）──
                if (authProvider != null) {
                    Spacer(modifier = Modifier.height(Spacing.md))
                    PluginAuthCard(
                        provider = authProvider,
                        loggedIn = authLoggedIn,
                        methodCount = authMethods.size,
                        onClick = { showAuthDialog = true }
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                // ── Tab 切换（工具 / Hooks） ──
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Tool,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.plugins_tools_header, plugin.tools.size),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Zap,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.plugins_hooks_header, plugin.hooks.size),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                // ── Tab 内容 ──
                when (selectedTab) {
                    0 -> {
                        // 工具列表
                        if (plugin.tools.isEmpty()) {
                            PluginEmptyTabHint(
                                icon = FeatherIcons.Tool,
                                message = stringResource(R.string.plugins_no_tools)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                plugin.tools.forEach { toolName ->
                                    val descriptor = tools.firstOrNull { it.name == toolName }
                                    PluginToolCard(
                                        toolName = toolName,
                                        description = descriptor?.description,
                                        parameters = descriptor?.parameters
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        // Hooks 列表
                        if (plugin.hooks.isEmpty()) {
                            PluginEmptyTabHint(
                                icon = FeatherIcons.Zap,
                                message = stringResource(R.string.plugins_no_hooks)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                plugin.hooks.forEach { hookName ->
                                    PluginHookCard(hookName = hookName)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.lg))
            }
        }
    }

    if (showAuthDialog && authProvider != null) {
        PluginAuthDialog(
            provider = authProvider,
            methods = authMethods,
            loggedIn = authLoggedIn,
            busy = authBusy,
            onAuthorize = onAuthorize,
            onSubmit = onSubmit,
            onSaveApiKey = onSaveApiKey,
            onLogout = onLogout,
            onDismiss = { showAuthDialog = false }
        )
    }
}

/** 插件认证入口卡片：展示 provider 与登录状态，点击打开登录弹窗。 */
@Composable
private fun PluginAuthCard(
    provider: String,
    loggedIn: Boolean,
    methodCount: Int,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(Radius.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = if (loggedIn) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Key,
                    contentDescription = null,
                    tint = if (loggedIn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.plugins_auth_card_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        if (loggedIn) R.string.plugins_auth_card_logged_in
                        else if (methodCount > 0) R.string.plugins_auth_card_not_logged_in
                        else R.string.plugins_auth_card_no_methods,
                        provider
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 单个插件工具卡片展示 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginToolCard(
    toolName: String,
    description: String?,
    parameters: JsonObject?
) {
    var descriptionExpanded by remember(toolName) { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Tool,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = toolName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            val descText = description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mcp_no_description)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = descText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )

            if (descText.length > 50) {
                Text(
                    text = stringResource(if (descriptionExpanded) R.string.common_collapse else R.string.common_expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { descriptionExpanded = !descriptionExpanded }
                        .padding(top = 2.dp)
                )
            }

            val paramKeys = remember(parameters) {
                (parameters?.get("properties") as? JsonObject)?.keys ?: emptySet()
            }
            if (paramKeys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    paramKeys.forEach { key ->
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单个 Hook 卡片展示 */
@Composable
private fun PluginHookCard(hookName: String) {
    Card(
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                        RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Zap,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hookName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = hookDescription(hookName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 选项卡内空状态提示 */
@Composable
private fun PluginEmptyTabHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun hookDescription(hookName: String): String = when (hookName) {
    "chat.headers" -> stringResource(R.string.plugins_hook_chat_headers_desc)
    "chat.params" -> stringResource(R.string.plugins_hook_chat_params_desc)
    "chat.message" -> stringResource(R.string.plugins_hook_chat_message_desc)
    "auth.loader" -> stringResource(R.string.plugins_hook_auth_loader_desc)
    "provider.models" -> stringResource(R.string.plugins_hook_provider_models_desc)
    "tool.execute.before" -> stringResource(R.string.plugins_hook_tool_before_desc)
    "tool.execute.after" -> stringResource(R.string.plugins_hook_tool_after_desc)
    "tool.definition" -> stringResource(R.string.plugins_hook_tool_definition_desc)
    "experimental.chat.system.transform" -> stringResource(R.string.plugins_hook_system_transform_desc)
    "experimental.chat.messages.transform" -> stringResource(R.string.plugins_hook_messages_transform_desc)
    "experimental.session.compacting" -> stringResource(R.string.plugins_hook_compacting_desc)
    "shell.env" -> stringResource(R.string.plugins_hook_shell_env_desc)
    "permission.ask" -> stringResource(R.string.plugins_hook_permission_ask_desc)
    "command.execute.before" -> stringResource(R.string.plugins_hook_command_before_desc)
    "event" -> stringResource(R.string.plugins_hook_event_desc)
    "experimental.provider.small_model" -> stringResource(R.string.plugins_hook_small_model_desc)
    "dispose" -> stringResource(R.string.plugins_hook_dispose_desc)
    else -> hookName
}
