package com.aicode.feature.agent.domain.plugin

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 插件认证凭据存储：读写宿主 `filesDir/aicode/auth.json`（容器内映射 `/root/.aicode/auth.json`）。
 * 格式对齐 opencode `auth.json`：`{ providerId: Auth }`（见 [PluginAuth]）。
 * 与 AiCode 用户 API Key（Room `ai_providers` 表）完全独立——这是插件自有的 OAuth/API 凭据，
 * 仅供插件 `auth.loader` 的 `getAuth()` 与登录流程读写，不暴露 AiCode 用户配置的 Key。
 */
@Singleton
class PluginAuthStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val file = File(context.filesDir, "aicode/auth.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private var cache: Map<String, PluginAuth>? = null

    /** 读取指定 provider 的凭据；未配置返回 null。 */
    suspend fun get(providerId: String): PluginAuth? = mutex.withLock {
        readLocked()[providerId]
    }

    /** 读取全部凭据（providerId → Auth）。 */
    suspend fun all(): Map<String, PluginAuth> = mutex.withLock { readLocked() }

    /** 写入/更新凭据；auth 为 null 时删除该 provider 的凭据。 */
    suspend fun set(providerId: String, auth: PluginAuth?) = mutex.withLock {
        val current = readLocked().toMutableMap()
        if (auth == null) current.remove(providerId) else current[providerId] = auth
        writeLocked(current)
    }

    /** 删除指定 provider 的凭据。 */
    suspend fun remove(providerId: String) = set(providerId, null)

    private fun readLocked(): Map<String, PluginAuth> {
        cache?.let { return it }
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PluginAuth>>(file.readText()).also { cache = it }
        }.getOrElse {
            FileLogger.w(TAG, "解析 auth.json 失败: ${it.message}")
            emptyMap()
        }
    }

    private fun writeLocked(map: Map<String, PluginAuth>) {
        cache = map
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "auth.json.tmp")
        runCatching {
            tmp.writeText(json.encodeToString(map))
            if (!tmp.renameTo(file)) {
                // renameTo 失败（被占用/跨设备）回退直接写原文件
                file.writeText(json.encodeToString(map))
                tmp.delete()
            }
        }.onFailure {
            FileLogger.w(TAG, "写入 auth.json 失败: ${it.message}")
        }
    }

    private companion object {
        const val TAG = "PluginAuthStore"
    }
}
