package com.aicode.feature.plugin.presentation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Github
import compose.icons.feathericons.Package

/** 单次最多逐条列出的导入问题数，其余折叠成「还有 N 个问题」。 */
private const val MAX_LISTED_ERRORS = 5

/** 导入流程的全部弹层：读取中、失败列表、预览与授权确认。放在设置页底部与其他对话框并列。 */
@Composable
internal fun PluginImportHost(viewModel: PluginsViewModel) {
    val state by viewModel.importState.collectAsStateWithLifecycle()
    when (val current = state) {
        PluginImportState.Idle -> Unit
        PluginImportState.Reading -> ProgressDialog(R.string.plugins_reading)
        PluginImportState.Downloading -> ProgressDialog(
            R.string.plugins_store_downloading,
            onCancel = viewModel::dismissImport
        )
        is PluginImportState.StoreFailed -> AlertDialog(
            onDismissRequest = viewModel::dismissImport,
            title = { Text(stringResource(R.string.plugins_import_failed_title)) },
            text = { Text(storeErrorMessage(current.error)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissImport) { Text(stringResource(R.string.common_got_it)) }
            }
        )
        is PluginImportState.Failed -> ImportFailedDialog(current.errors, onDismiss = viewModel::dismissImport)
        is PluginImportState.Preview -> PluginPreviewSheet(
            state = current,
            onInstall = viewModel::confirmInstall,
            onDismiss = viewModel::dismissImport
        )
    }
}

@Composable
private fun ProgressDialog(@StringRes titleRes: Int, onCancel: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = { onCancel?.invoke() },
        title = { Text(stringResource(titleRes)) },
        text = { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) },
        confirmButton = {
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
}

@Composable
private fun ImportFailedDialog(errors: List<PluginError>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugins_import_failed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                errors.take(MAX_LISTED_ERRORS).forEach { error ->
                    Text(text = pluginErrorMessage(error), style = MaterialTheme.typography.bodyMedium)
                }
                val hidden = errors.size - MAX_LISTED_ERRORS
                if (hidden > 0) {
                    Text(
                        text = stringResource(R.string.plugins_import_failed_more, hidden),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginPreviewSheet(
    state: PluginImportState.Preview,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val preview = state.preview
    val manifest = preview.manifest
    val languageTag = currentLanguageTag()
    val source = state.origin.sourceName?.let { name ->
        stringResource(
            if (state.origin.official) R.string.plugins_install_source_official
            else R.string.plugins_install_source_community,
            name
        )
    } ?: stringResource(
        if (state.sourceDirName != null) R.string.plugins_origin_development else R.string.plugins_origin_local
    )
    val sourceChanged = installSourceChanged(preview, state.origin)
    val installing by rememberUpdatedState(state.installing)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !installing }
        ),
        sheetGesturesEnabled = !state.installing
    ) {
        Text(
            text = stringResource(R.string.plugins_preview_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        )
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            PluginHeaderCard(
                name = manifest.name.resolve(languageTag),
                subtitle = stringResource(R.string.plugins_item_subtitle, manifest.version.toString(), manifest.publisher),
                description = manifest.description.resolve(languageTag),
                icon = preview.content.icon
            )
            SettingsGroup {
                SettingsRow(icon = FeatherIcons.Package, title = stringResource(R.string.plugins_info_origin), subtitle = source)
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Github,
                    title = stringResource(R.string.plugins_store_detail_source),
                    subtitle = manifest.repository
                )
            }

            if (!state.origin.official || sourceChanged) {
                val semantic = MaterialTheme.semanticColors
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.mdLarge),
                    color = semantic.warningContainer,
                    contentColor = semantic.onWarningContainer
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        if (!state.origin.official) {
                            Text(
                                text = stringResource(
                                    if (preview.content.providers.isNotEmpty()) R.string.plugins_install_third_party_provider_notice
                                    else R.string.plugins_install_untrusted_notice
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (sourceChanged) {
                            Text(
                                text = stringResource(R.string.plugins_install_source_changed),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.plugins_preview_contents))
            SettingsGroup {
                PluginContentRows(content = preview.content, languageTag = languageTag, active = true)
            }
        }
        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.sm, bottom = Spacing.lg)) {
                state.error?.let { error ->
                    Text(
                        text = pluginErrorMessage(error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, enabled = !state.installing) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(onClick = onInstall, enabled = !state.installing) {
                        if (state.installing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                stringResource(
                                    if (state.isListingCurrent != null && preview.installedVersion != null) R.string.plugins_store_update
                                    else if (preview.installedVersion != null) R.string.plugins_install_replace
                                    else R.string.plugins_install
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 把领域层的错误码翻译成界面文案；[PluginError.detail] 是语言无关的定位信息，原样带入。 */
@Composable
internal fun pluginErrorMessage(error: PluginError): String {
    val detail = error.detail
    return when (error.code) {
        PluginErrorCode.ARCHIVE_UNREADABLE -> stringResource(R.string.plugins_error_archive_unreadable)
        PluginErrorCode.ARCHIVE_TOO_LARGE -> stringResource(R.string.plugins_error_archive_too_large)
        PluginErrorCode.ARCHIVE_TOO_MANY_ENTRIES -> stringResource(R.string.plugins_error_archive_too_many)
        PluginErrorCode.ARCHIVE_ENTRY_TOO_LARGE -> stringResource(R.string.plugins_error_entry_too_large, detail)
        PluginErrorCode.ARCHIVE_UNSAFE_PATH -> stringResource(R.string.plugins_error_unsafe_path, detail)
        PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY -> stringResource(R.string.plugins_error_duplicate_entry, detail)
        PluginErrorCode.MANIFEST_MISSING -> stringResource(R.string.plugins_error_manifest_missing)
        PluginErrorCode.MANIFEST_TOO_LARGE -> stringResource(R.string.plugins_error_manifest_too_large)
        PluginErrorCode.MANIFEST_NOT_JSON -> stringResource(R.string.plugins_error_manifest_not_json)
        PluginErrorCode.FIELD_MISSING -> stringResource(R.string.plugins_error_field_missing, detail)
        PluginErrorCode.FIELD_INVALID -> stringResource(R.string.plugins_error_field_invalid, detail)
        PluginErrorCode.UNSUPPORTED_MANIFEST_VERSION -> stringResource(R.string.plugins_error_manifest_version, detail)
        PluginErrorCode.UNSUPPORTED_FIELD -> stringResource(R.string.plugins_error_unsupported_field, detail)
        PluginErrorCode.UNSUPPORTED_CONTRIBUTION -> stringResource(R.string.plugins_error_unsupported_contribution, detail)
        PluginErrorCode.API_INCOMPATIBLE -> stringResource(R.string.plugins_error_api_incompatible, detail)
        PluginErrorCode.DUPLICATE_ID -> stringResource(R.string.plugins_error_duplicate_id, detail)
        PluginErrorCode.FILE_MISSING -> stringResource(R.string.plugins_error_file_missing, detail)
        PluginErrorCode.CONTENT_INVALID -> stringResource(R.string.plugins_error_content_invalid, detail)
        PluginErrorCode.STORAGE_FAILED -> stringResource(R.string.plugins_error_storage_failed)
        PluginErrorCode.PREVIEW_EXPIRED -> stringResource(R.string.plugins_error_preview_expired)
    }
}
