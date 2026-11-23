package com.aicode.feature.plugin.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pluginStoreSettingsDataStore by preferencesDataStore(
    name = "plugin_store_settings",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 插件商店的通用设置。
 * 镜像加速地址是 URL 前缀，只作用于 GitHub 托管的索引与插件包，留空时直连。
 */
@Singleton
class PluginStoreSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val downloadMirror: Flow<String> = context.pluginStoreSettingsDataStore.data.map { it[KEY].orEmpty() }

    /**
     * 自动更新：打开商店拉到新版本后，对「贡献未变化」的插件自动安装。
     * 新增/删除工具、hook、MCP 等贡献时仍需人工确认，避免静默扩权限。
     */
    val autoUpdatePlugins: Flow<Boolean> =
        context.pluginStoreSettingsDataStore.data.map { it[AUTO_UPDATE_KEY] ?: false }

    /** 只接受 http(s) 前缀；空白表示关闭加速，非法输入直接忽略。 */
    suspend fun setDownloadMirror(url: String) {
        val text = url.trim()
        if (text.isNotEmpty() && !isValidMirror(text)) return
        context.pluginStoreSettingsDataStore.edit { it[KEY] = text }
    }

    suspend fun setAutoUpdatePlugins(enabled: Boolean) {
        context.pluginStoreSettingsDataStore.edit { it[AUTO_UPDATE_KEY] = enabled }
    }

    private fun isValidMirror(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") && uri.host != null && uri.userInfo == null
    }

    private companion object {
        val KEY = stringPreferencesKey("download_mirror")
        val AUTO_UPDATE_KEY = booleanPreferencesKey("auto_update_plugins")
    }
}
