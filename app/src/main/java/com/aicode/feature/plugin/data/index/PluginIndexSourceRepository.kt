package com.aicode.feature.plugin.data.index

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val Context.pluginIndexDataStore by preferencesDataStore(
    name = "plugin_index_sources",
    corruptionHandler = preferencesCorruptionHandler
)

/** 一个索引来源。身份由规范化地址派生，改显示名称不会改变来源身份。 */
data class PluginIndexSource(
    val id: String,
    val name: String,
    val indexUrl: String,
    val builtIn: Boolean = false,
    val lastFetchedAt: Long = 0
) {
    val official: Boolean get() = builtIn && id == PluginIndexDefaults.OFFICIAL_ID
}

object PluginIndexDefaults {
    const val OFFICIAL_URL = "https://raw.githubusercontent.com/jieapi/AiCode-Plugins-Store/main/index.json"
    const val OFFICIAL_NAME = "AiCode Plugins"
    const val OFFICIAL_ID = "aicode.official"
    const val VANTA_URL = "https://raw.githubusercontent.com/Hye-steam/Vanta-Plugins-Store/main/index.json"
    const val VANTA_NAME = "Vanta Plugin Store"
    const val VANTA_ID = "vanta.community"

    val official = PluginIndexSource(
        id = OFFICIAL_ID,
        name = OFFICIAL_NAME,
        indexUrl = OFFICIAL_URL,
        builtIn = true
    )

    val vanta = PluginIndexSource(
        id = VANTA_ID,
        name = VANTA_NAME,
        indexUrl = VANTA_URL,
        builtIn = true
    )

    val builtIns = listOf(official, vanta)
    val builtInIds = builtIns.map { it.id }.toSet()
    val builtInUrls = builtIns.map { it.indexUrl }.toSet()
}

@Serializable
private data class StoredSource(
    val id: String,
    val name: String,
    val indexUrl: String,
    val lastFetchedAt: Long = 0
)

@Serializable
private data class StoredSources(val sources: List<StoredSource> = emptyList())

/**
 * 内置索引按 AiCode、Vanta 的顺序显示，自定义索引随后按输入顺序排列。
 */
@Singleton
class PluginIndexSourceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val sources: Flow<List<PluginIndexSource>> = context.pluginIndexDataStore.data.map { preferences ->
        val stored = preferences[KEY]?.let { raw ->
            runCatching { PluginIndexJson.decodeFromString<StoredSources>(raw) }.getOrNull()
        } ?: StoredSources()
        buildList {
            for (source in PluginIndexDefaults.builtIns) {
                val fetched = stored.sources.firstOrNull { it.id == source.id }?.lastFetchedAt ?: 0
                add(source.copy(lastFetchedAt = fetched))
            }
            stored.sources
                .filterNot { it.id in PluginIndexDefaults.builtInIds || it.indexUrl in PluginIndexDefaults.builtInUrls }
                .map { it.toModel() }
                .forEach(::add)
        }
    }

    /** 自定义来源地址，顺序即展示顺序；两个内置来源不在其中。 */
    val customIndexUrls: Flow<List<String>> = sources.map { list ->
        list.filterNot { it.builtIn }.map { it.indexUrl }
    }

    /** 用输入行整体替换自定义来源；空行、无效地址与重复地址会被忽略，其余保持输入顺序。 */
    suspend fun setCustomSources(lines: List<String>): Result<Unit> = runCatching {
        val existing = sources.first().filterNot { it.builtIn }.associateBy { it.indexUrl }
        val next = linkedMapOf<String, PluginIndexSource>()
        lines.forEach { raw ->
            val url = runCatching { normalizeIndexUrl(raw) }.getOrNull() ?: return@forEach
            if (url in next || url in PluginIndexDefaults.builtInUrls) return@forEach
            next[url] = existing[url] ?: PluginIndexSource(
                id = sourceId(url),
                name = defaultName(url),
                indexUrl = url
            )
        }
        updateStored { stored ->
            val builtInEntries = stored.sources.filter { it.id in PluginIndexDefaults.builtInIds }
            stored.copy(sources = builtInEntries + next.values.map { it.toStored() })
        }
    }

    /** 记录最近一次成功拉取时间，仅用于离线展示。 */
    suspend fun markFetched(id: String, fetchedAt: Long) {
        updateStored { stored ->
            when {
                stored.sources.any { it.id == id } -> stored.copy(
                    sources = stored.sources.map {
                        if (it.id == id) it.copy(lastFetchedAt = fetchedAt) else it
                    }
                )
                else -> {
                    val source = PluginIndexDefaults.builtIns.firstOrNull { it.id == id } ?: return@updateStored stored
                    stored.copy(sources = stored.sources + source.copy(lastFetchedAt = fetchedAt).toStored())
                }
            }
        }
    }

    private suspend fun updateStored(block: (StoredSources) -> StoredSources) {
        context.pluginIndexDataStore.edit { preferences ->
            val current = preferences[KEY]?.let { raw ->
                runCatching { PluginIndexJson.decodeFromString<StoredSources>(raw) }.getOrNull()
            } ?: StoredSources()
            preferences[KEY] = PluginIndexJson.encodeToString(block(current))
        }
    }

    private fun StoredSource.toModel() = PluginIndexSource(
        id = id,
        name = name,
        indexUrl = indexUrl,
        lastFetchedAt = lastFetchedAt
    )

    private fun PluginIndexSource.toStored() = StoredSource(
        id = id,
        name = name,
        indexUrl = indexUrl,
        lastFetchedAt = lastFetchedAt
    )

    private companion object {
        val KEY = stringPreferencesKey("sources_json")

        fun normalizeIndexUrl(raw: String): String {
            val text = raw.trim()
            val uri = URI(text)
            require(uri.scheme == "https" || uri.scheme == "http") { "scheme" }
            require(uri.host != null) { "host" }
            require(uri.userInfo == null) { "userInfo" }
            require(!text.contains('#')) { "fragment" }
            return text
        }

        /** 自定义来源没有单独的名称输入，用主机名作展示名。 */
        fun defaultName(indexUrl: String): String =
            runCatching { URI(indexUrl).host }.getOrNull() ?: indexUrl

        fun sourceId(indexUrl: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(indexUrl.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}
