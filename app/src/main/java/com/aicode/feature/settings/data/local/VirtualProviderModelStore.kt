package com.aicode.feature.settings.data.local

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件认证虚拟 provider 的模型定制（新增/隐藏）本地持久化（filesDir JSON，跨重启保留）。
 * 虚拟 provider 不落 Room，其模型列表由 models.dev 目录动态填充；本 Store 记录用户相对目录的
 * 增删偏差（added/removed），合并时应用。[changes] 流驱动设置页列表重算。
 */
@Singleton
class VirtualProviderModelStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var cache: Map<String, Overrides> = emptyMap()

    /** 定制变更计数（初始 0 即有值）：供 combine 作第三源，保存/清空时自增触发列表重算。 */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    init {
        cache = read()
    }

    @Serializable
    data class Overrides(
        val added: List<String> = emptyList(),
        val removed: List<String> = emptyList()
    )

    /** 同步读取（merge 用）；无记录返回空定制。 */
    fun get(providerId: String): Overrides = cache[providerId] ?: Overrides()

    /** 保存定制：added/removed 全量替换；均为空时删除条目。 */
    suspend fun save(providerId: String, added: List<String>, removed: List<String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cleanAdded = added.filter { it.isNotBlank() }.distinct()
            val cleanRemoved = removed.filter { it.isNotBlank() }.distinct()
            val map = cache.toMutableMap()
            if (cleanAdded.isEmpty() && cleanRemoved.isEmpty()) {
                map.remove(providerId)
            } else {
                map[providerId] = Overrides(cleanAdded, cleanRemoved)
            }
            cache = map
            write(map)
        }
        _changes.value++
    }

    /** 清空某 provider 的定制（虚拟 provider 删除/重置）。 */
    suspend fun clear(providerId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (cache.containsKey(providerId)) {
                cache = cache - providerId
                write(cache)
            }
        }
        _changes.value++
    }

    private fun read(): Map<String, Overrides> {
        val f = file()
        if (!f.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, Overrides>>(f.readText(Charsets.UTF_8))
        }.getOrElse {
            FileLogger.w(TAG, "读取虚拟 provider 模型定制失败", it)
            emptyMap()
        }
    }

    private fun write(map: Map<String, Overrides>) {
        runCatching {
            file().writeText(json.encodeToString(map), Charsets.UTF_8)
        }.onFailure {
            FileLogger.w(TAG, "写入虚拟 provider 模型定制失败", it)
        }
    }

    private fun file(): File = File(context.filesDir, FILE_NAME)

    private companion object {
        const val TAG = "VirtualProviderModelStore"
        const val FILE_NAME = "virtual-provider-model-overrides.json"
    }
}
