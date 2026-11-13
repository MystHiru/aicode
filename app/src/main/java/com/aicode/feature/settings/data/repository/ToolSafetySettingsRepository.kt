package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.toolSafetyDataStore by preferencesDataStore(
    name = "tool_safety_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「禁用安全拦截」开关，默认关闭。
 *
 * 开启后，AUTO（自动）模式下连灾难性 `rm`（删除根目录、系统关键目录、工作区根目录等）
 * 也不再拦截，命令一律放行，`Shizuku` 与 `Root` 工具也不再逐次弹窗。仅作用于 AUTO 模式：
 * BUILD / PLAN 模式的安全拦截不受影响，宿主命令在 BUILD 下的「不可记忆」也不受影响。
 *
 * 另外两个粒度更细的开关（默认都关闭，保持上游行为）：
 * - 「工作区内写操作自动放行」：路径落在当前工作区内的文件写入不再弹窗；
 * - 「宿主命令自动放行」：AUTO 模式下 Shizuku 与 Root 不再逐次确认。
 */
@Singleton
class ToolSafetySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val DISABLED_KEY = booleanPreferencesKey("disable_safety_interception")
        val WORKSPACE_AUTO_ALLOW_KEY = booleanPreferencesKey("workspace_write_auto_allow")
        val HOST_COMMAND_AUTO_ALLOW_KEY = booleanPreferencesKey("host_command_auto_allow")
    }

    /** 当前开关流；未设置时回退到 false（默认保留安全拦截）。 */
    val disableSafetyInterceptionFlow: Flow<Boolean> =
        context.toolSafetyDataStore.data.map { it[DISABLED_KEY] ?: false }

    suspend fun setDisableSafetyInterception(disabled: Boolean) {
        context.toolSafetyDataStore.edit { it[DISABLED_KEY] = disabled }
    }

    /** 读取一次当前值（权限评估时用）。 */
    suspend fun isSafetyInterceptionDisabled(): Boolean = disableSafetyInterceptionFlow.first()

    /** 工作区内的文件写入是否自动放行；默认关闭。 */
    val workspaceWriteAutoAllowFlow: Flow<Boolean> =
        context.toolSafetyDataStore.data.map { it[WORKSPACE_AUTO_ALLOW_KEY] ?: false }

    suspend fun setWorkspaceWriteAutoAllow(enabled: Boolean) {
        context.toolSafetyDataStore.edit { it[WORKSPACE_AUTO_ALLOW_KEY] = enabled }
    }

    suspend fun isWorkspaceWriteAutoAllowed(): Boolean = workspaceWriteAutoAllowFlow.first()

    /** AUTO 模式下 Shizuku 与 Root 是否免逐次确认；默认关闭。 */
    val hostCommandAutoAllowFlow: Flow<Boolean> =
        context.toolSafetyDataStore.data.map { it[HOST_COMMAND_AUTO_ALLOW_KEY] ?: false }

    suspend fun setHostCommandAutoAllow(enabled: Boolean) {
        context.toolSafetyDataStore.edit { it[HOST_COMMAND_AUTO_ALLOW_KEY] = enabled }
    }

    suspend fun isHostCommandAutoAllowed(): Boolean = hostCommandAutoAllowFlow.first()
}