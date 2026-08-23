package com.aicode.feature.settings.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.feature.agent.domain.plugin.PluginAuthCallbackResult
import com.aicode.feature.agent.domain.plugin.PluginAuthMethod
import com.aicode.feature.agent.domain.plugin.PluginAuthorizeResult
import kotlinx.coroutines.launch

/**
 * 插件认证（登录）弹窗：列出插件的登录方法（`auth.methods`），执行 OAuth（url + code/auto 回调）或 API Key 流程。
 * 所有交互操作经 suspend 回调交给宿主（SettingsViewModel → PluginManager → runner 插件），弹窗本身只做展示与输入收集。
 */
@Composable
fun PluginAuthDialog(
    provider: String,
    methods: List<PluginAuthMethod>,
    loggedIn: Boolean,
    busy: Boolean,
    onAuthorize: suspend (Int) -> PluginAuthorizeResult,
    onSubmit: suspend (String?) -> PluginAuthCallbackResult,
    onSaveApiKey: suspend (String) -> Boolean,
    onLogout: suspend () -> Unit,
    onDismiss: () -> Unit
) {
    var oauthResult by remember { mutableStateOf<PluginAuthorizeResult?>(null) }
    var apiLabel by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun showMsg(msg: String, isError: Boolean) {
        message = msg
        messageIsError = isError
    }

    fun submitCallback(code: String?) {
        scope.launch {
            val r = onSubmit(code)
            if (r.isSuccess) {
                showMsg(context.getString(R.string.plugins_auth_success), false)
                oauthResult = null
            } else {
                showMsg(context.getString(R.string.plugins_auth_failed) + (r.error?.let { "：$it" } ?: ""), true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugins_auth_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.plugins_auth_provider, provider),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (loggedIn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(if (loggedIn) R.string.plugins_auth_logged_in else R.string.plugins_auth_not_logged_in),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (loggedIn && oauthResult == null && apiLabel == null) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                onLogout()
                                showMsg(context.getString(R.string.plugins_auth_logged_out), false)
                            }
                        },
                        enabled = !busy
                    ) { Text(stringResource(R.string.plugins_auth_logout)) }
                }

                val activeOAuth = oauthResult
                val activeApiLabel = apiLabel
                when {
                    activeOAuth != null -> {
                        activeOAuth.url.takeIf { it.isNotBlank() }?.let { url ->
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openUrl(url) }
                            )
                            Text(
                                text = stringResource(R.string.plugins_auth_open_url),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        activeOAuth.instructions.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (activeOAuth.method == "code") {
                            OutlinedTextField(
                                value = codeInput,
                                onValueChange = { codeInput = it },
                                label = { Text(stringResource(R.string.plugins_auth_enter_code)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { submitCallback(codeInput.trim().ifBlank { null }) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (busy) stringResource(R.string.plugins_auth_busy) else stringResource(R.string.plugins_auth_submit))
                            }
                        } else {
                            Button(
                                onClick = { submitCallback(null) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (busy) stringResource(R.string.plugins_auth_busy) else stringResource(R.string.plugins_auth_done))
                            }
                        }
                    }
                    activeApiLabel != null -> {
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            label = { Text(stringResource(R.string.plugins_auth_enter_key)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    if (onSaveApiKey(keyInput)) {
                                        showMsg(context.getString(R.string.plugins_auth_success), false)
                                        apiLabel = null
                                    } else {
                                        showMsg(context.getString(R.string.plugins_auth_key_blank), true)
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busy) stringResource(R.string.plugins_auth_busy) else stringResource(R.string.plugins_auth_save))
                        }
                    }
                    methods.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.plugins_auth_no_methods),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        methods.forEachIndexed { index, method ->
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val result = onAuthorize(index)
                                        if (result.isError) {
                                            showMsg(context.getString(R.string.plugins_auth_error, result.error ?: "?"), true)
                                        } else if (result.completed) {
                                            showMsg(context.getString(R.string.plugins_auth_success), false)
                                        } else if (result.requiresKey || result.type == "api") {
                                            apiLabel = method.label
                                            keyInput = ""
                                            oauthResult = null
                                        } else {
                                            oauthResult = result
                                            codeInput = ""
                                            apiLabel = null
                                        }
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (busy) stringResource(R.string.plugins_auth_busy) else method.label)
                            }
                        }
                    }
                }

                message?.let { msg ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}
