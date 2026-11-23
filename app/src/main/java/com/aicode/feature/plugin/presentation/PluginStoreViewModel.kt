package com.aicode.feature.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.data.SOURCE_DEVELOPMENT
import com.aicode.feature.plugin.data.index.PluginIndexClient
import com.aicode.feature.plugin.data.index.PluginIndexException
import com.aicode.feature.plugin.data.index.PluginIndexPlugin
import com.aicode.feature.plugin.data.index.PluginIndexSource
import com.aicode.feature.plugin.data.index.PluginLogoCacheEntry
import com.aicode.feature.plugin.data.index.PluginIndexSourceRepository
import com.aicode.feature.plugin.data.index.PluginOfficialRepositories
import com.aicode.feature.plugin.domain.PluginInstallOrigin
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.SemVer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PluginStoreItem(
    val plugin: PluginIndexPlugin,
    val source: PluginIndexSource,
    val compatible: Boolean,
    val installedVersion: String? = null,
    val updateAvailable: Boolean = false
) {
    val official: Boolean
        get() = source.official && plugin.packageUrl == null && PluginOfficialRepositories.isOfficial(plugin.repository)
}

enum class PluginStoreErrorCode {
    NETWORK,
    INVALID_INDEX,
    NO_COMPATIBLE_RELEASE,
    DOWNLOAD_FAILED,
    PACKAGE_CHECK_FAILED,
    LISTING_CHANGED
}

data class PluginStoreError(
    val code: PluginStoreErrorCode,
    val detail: String? = null
)

data class PluginStoreUiState(
    /** 首次读取来源前就显示加载态，避免先闪一下空列表。 */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** 递增后商店列表重新读取图标（下拉刷新 / 重新进商店时用）。 */
    val logoRefreshVersion: Long = 0,
    val sources: List<PluginIndexSource> = emptyList(),
    /** 所有来源的候选条目；只展示去重后的 [items]。 */
    val allItems: List<PluginStoreItem> = emptyList(),
    val items: List<PluginStoreItem> = emptyList(),
    /** 可从原来源安装的更新；供已安装页展示「可更新」。 */
    val updatesByPluginId: Map<String, PluginStoreItem> = emptyMap(),
    val error: PluginStoreError? = null
)

@HiltViewModel
class PluginStoreViewModel @Inject constructor(
    private val client: PluginIndexClient,
    private val sourceRepository: PluginIndexSourceRepository,
    private val manager: PluginManager
) : ViewModel() {

    private val _state = MutableStateFlow(PluginStoreUiState())
    val state: StateFlow<PluginStoreUiState> = _state.asStateFlow()

    /** 一次加载结束后要提示的失败来源数；仅在用户下拉刷新或确实有来源失败时发送。 */
    private val _refreshResult = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val refreshResult: SharedFlow<Int> = _refreshResult.asSharedFlow()

    /** 来源地址列表变化时才重新拉取；markFetched 等只改时间戳的写入不会触发加载。 */
    private var loadedUrls: List<String>? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            sourceRepository.sources.collect { sources ->
                _state.update { it.copy(sources = sources) }
                val urls = sources.map { it.indexUrl }
                if (urls != loadedUrls) {
                    loadedUrls = urls
                    loadAll(force = false)
                }
            }
        }
        viewModelScope.launch {
            manager.plugins.collect { installed ->
                val records = installed.associate { it.id to it.record }
                _state.update { it.withCatalog(it.allItems, records) }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(logoRefreshVersion = it.logoRefreshVersion + 1) }
        loadAll(force = true)
    }

    /** 进入商店页时调用：后台拉最新索引，列表先展示本地缓存。 */
    fun openStore() {
        if (_state.value.loading) return
        refresh()
    }

    suspend fun readPluginLogoCache(url: String): PluginLogoCacheEntry? =
        client.readPluginLogoCache(url)

    suspend fun refreshPluginLogo(url: String): PluginLogoCacheEntry? =
        client.refreshPluginLogo(url)

    internal fun isCurrent(item: PluginStoreItem): Boolean = _state.value.isListingCurrent(item)

    private fun loadAll(force: Boolean) {
        val sources = _state.value.sources
        if (sources.isEmpty()) return
        loadJob?.cancel()
        val configured = sources.map { it.indexUrl }.toSet()
        val previous = _state.value.allItems.filter { it.source.indexUrl in configured }.groupBy { it.source.indexUrl }
        publishCatalog(sources.flatMap { previous[it.indexUrl].orEmpty() }, loading = true)
        loadJob = viewModelScope.launch {
            val cached = sources.associate { source ->
                val items = client.readCachedIndex(source)?.let { snapshot ->
                    snapshot.index.plugins.map { PluginStoreItem(it, source, client.isCompatible(it)) }
                }
                source.indexUrl to (items ?: previous[source.indexUrl].orEmpty())
            }
            publishCatalog(sources.flatMap { cached.getValue(it.indexUrl) }, loading = true)
            val items = mutableListOf<PluginStoreItem>()
            var firstError: PluginStoreError? = null
            var failureCount = 0
            for (source in sources) {
                val snapshot = try {
                    client.fetchIndex(source, forceRefresh = force)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.w(TAG, "读取插件索引失败: ${source.id}", e)
                    failureCount++
                    if (firstError == null) {
                        firstError = PluginStoreError(
                            code = if (e is PluginIndexException) PluginStoreErrorCode.INVALID_INDEX
                            else PluginStoreErrorCode.NETWORK,
                            detail = e.message
                        )
                    }
                    items += cached.getValue(source.indexUrl)
                    continue
                }
                items += snapshot.index.plugins.map { PluginStoreItem(it, source, client.isCompatible(it)) }
                try {
                    sourceRepository.markFetched(source.id, snapshot.fetchedAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    FileLogger.w(TAG, "保存索引刷新时间失败: ${source.id}", e)
                }
            }
            ensureActive()
            publishCatalog(items, loading = false, error = firstError)
            if (force || failureCount > 0) _refreshResult.tryEmit(failureCount)
        }
    }

    private fun publishCatalog(items: List<PluginStoreItem>, loading: Boolean, error: PluginStoreError? = null) {
        val records = manager.plugins.value.associate { it.id to it.record }
        _state.update {
            it.withCatalog(items, records).copy(loading = loading, refreshing = loading && items.isNotEmpty(), error = error)
        }
    }

    private companion object {
        const val TAG = "PluginStoreViewModel"
    }
}

internal fun PluginStoreUiState.withCatalog(
    candidates: List<PluginStoreItem>,
    records: Map<String, PluginRecord>
): PluginStoreUiState {
    val visible = candidates.groupBy { it.plugin.id }.map { (id, entries) ->
        val installed = records[id]
        val item = entries.firstOrNull { it.matchesInstalledSource(installed) } ?: entries.first()
        item.copy(installedVersion = installed?.version, updateAvailable = item.canUpdate(installed))
    }
    return copy(
        allItems = candidates,
        items = visible,
        updatesByPluginId = visible.filter { it.updateAvailable }.associateBy { it.plugin.id }
    )
}

internal fun PluginStoreItem.matchesInstalledSource(installed: PluginRecord?): Boolean =
    installed != null && installed.id == plugin.id && installed.sourceKind != SOURCE_DEVELOPMENT &&
        installed.origin == PluginInstallOrigin.KIND_INDEX && installed.indexUrl == source.indexUrl &&
        installed.repository == plugin.repository

internal fun PluginStoreItem.canUpdate(installed: PluginRecord?): Boolean {
    if (!compatible || installed == null || !matchesInstalledSource(installed)) return false
    val installedVersion = SemVer.parse(installed.version) ?: return false
    val availableVersion = plugin.version?.let(SemVer::parse) ?: return false
    return availableVersion > installedVersion
}

internal fun PluginStoreUiState.currentListing(selection: PluginStoreItem): PluginStoreItem? =
    items.firstOrNull {
        it.source.indexUrl == selection.source.indexUrl && it.plugin.id == selection.plugin.id &&
            it.plugin.repository == selection.plugin.repository
    }

internal fun PluginStoreUiState.isListingCurrent(selection: PluginStoreItem): Boolean =
    currentListing(selection)?.let {
        it.plugin == selection.plugin && it.compatible && it.official == selection.official
    } == true

internal fun PluginStoreItem.matchesPackage(manifest: PluginManifest, version: String): Boolean {
    // 只硬校验 id 与 version：社区商店索引普遍把 repository 写成商店地址，包内
    // manifest 写的却是作者自己的仓库（实测同一插件两处不同），用 repository 做
    // 硬条件会把正常插件全部挡下。publisher 仅在双方都非空时忽略大小写比对，
    // 作为轻量防错位检查，作者别名不一致不阻断安装。
    if (manifest.id != plugin.id) return false
    if (manifest.version != SemVer.parse(version)) return false
    return manifest.publisher.isBlank() || plugin.author.isBlank() ||
        manifest.publisher.equals(plugin.author, ignoreCase = true)
}
