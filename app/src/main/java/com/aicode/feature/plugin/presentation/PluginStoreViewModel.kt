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
import com.aicode.feature.plugin.data.index.PluginIndexSnapshot
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    val detail: String? = null,
    val sourceId: String? = null
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
    /** 已成功拉到本轮网络索引的来源；只允许这些条目参与自动安装。 */
    val freshSourceIds: Set<String> = emptySet(),
    /** 读取失败并使用本地缓存回退的来源。 */
    val failedSourceIds: Set<String> = emptySet(),
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
    private var loadGeneration = 0L

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
        loadAll(force = true, notifyOnCompletion = true)
    }

    /** 进入商店页时静默拉最新索引，列表先显示本地缓存。 */
    fun openStore() {
        if (_state.value.loading) return
        _state.update { it.copy(logoRefreshVersion = it.logoRefreshVersion + 1) }
        loadAll(force = true, notifyOnCompletion = false)
    }

    suspend fun readPluginLogoCache(url: String): PluginLogoCacheEntry? =
        client.readPluginLogoCache(url)

    suspend fun refreshPluginLogo(url: String): PluginLogoCacheEntry? =
        client.refreshPluginLogo(url)

    internal fun isCurrent(item: PluginStoreItem): Boolean = _state.value.isListingCurrent(item)

    internal fun isFreshCurrent(item: PluginStoreItem): Boolean = _state.value.isFreshListing(item)

    private fun loadAll(force: Boolean, notifyOnCompletion: Boolean = false) {
        val sources = _state.value.sources
        if (sources.isEmpty()) return
        loadJob?.cancel()
        val generation = ++loadGeneration
        val configured = sources.map { it.indexUrl }.toSet()
        val previous = _state.value.allItems.filter { it.source.indexUrl in configured }.groupBy { it.source.indexUrl }
        _state.update {
            it.withCatalog(sources.flatMap { source -> previous[source.indexUrl].orEmpty() }, manager.plugins.value.associate { plugin -> plugin.id to plugin.record })
                .copy(
                    loading = true,
                    refreshing = previous.isNotEmpty(),
                    freshSourceIds = emptySet(),
                    failedSourceIds = emptySet(),
                    error = null
                )
        }
        loadJob = viewModelScope.launch {
            val entriesBySource = linkedMapOf<String, List<PluginStoreItem>>()
            sources.forEach { source ->
                val entries = client.readCachedIndex(source)?.let { snapshot ->
                    snapshot.index.plugins.map { PluginStoreItem(it, source, client.isCompatible(it)) }
                } ?: previous[source.indexUrl].orEmpty()
                entriesBySource[source.id] = entries
            }
            _state.update { it.withCatalog(orderedItems(sources, entriesBySource), manager.plugins.value.associate { plugin -> plugin.id to plugin.record }).copy(loading = true, refreshing = entriesBySource.values.any { it.isNotEmpty() }) }
            val semaphore = Semaphore(MAX_PARALLEL_INDEX_FETCHES)
            val completed = Channel<Pair<PluginIndexSource, Result<PluginIndexSnapshot>>>(sources.size)
            val workers = sources.map { source ->
                launch {
                    val result = runCatching {
                        semaphore.withPermit { client.fetchIndex(source, forceRefresh = force) }
                    }
                    completed.send(source to result)
                }
            }
            var failureCount = 0
            var firstError: PluginStoreError? = null
            val freshSourceIds = mutableSetOf<String>()
            val failedSourceIds = mutableSetOf<String>()
            repeat(sources.size) {
                val (source, result) = completed.receive()
                if (generation != loadGeneration) return@launch
                result.onSuccess { snapshot ->
                    freshSourceIds += source.id
                    entriesBySource[source.id] = snapshot.index.plugins.map { PluginStoreItem(it, source, client.isCompatible(it)) }
                    try {
                        sourceRepository.markFetched(source.id, snapshot.fetchedAt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: java.io.IOException) {
                        FileLogger.w(TAG, "保存索引刷新时间失败: ${source.id}", e)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    FileLogger.w(TAG, "读取插件索引失败: ${source.id}", error)
                    failureCount++
                    failedSourceIds += source.id
                    if (firstError == null) {
                        firstError = PluginStoreError(
                            code = if (error is PluginIndexException) PluginStoreErrorCode.INVALID_INDEX
                            else PluginStoreErrorCode.NETWORK,
                            detail = error.message,
                            sourceId = source.id
                        )
                    }
                }
                ensureActive()
                _state.update {
                    it.withCatalog(orderedItems(sources, entriesBySource), manager.plugins.value.associate { plugin -> plugin.id to plugin.record })
                        .copy(
                            loading = true,
                            refreshing = true,
                            freshSourceIds = freshSourceIds.toSet(),
                            failedSourceIds = failedSourceIds.toSet(),
                            error = firstError
                        )
                }
            }
            workers.joinAll()
            completed.close()
            ensureActive()
            _state.update {
                it.withCatalog(orderedItems(sources, entriesBySource), manager.plugins.value.associate { plugin -> plugin.id to plugin.record })
                    .copy(
                        loading = false,
                        refreshing = false,
                        freshSourceIds = freshSourceIds.toSet(),
                        failedSourceIds = failedSourceIds.toSet(),
                        error = firstError
                    )
            }
            if (notifyOnCompletion) _refreshResult.tryEmit(failureCount)
        }
    }

    private fun orderedItems(
        sources: List<PluginIndexSource>,
        entriesBySource: Map<String, List<PluginStoreItem>>
    ): List<PluginStoreItem> = sources.flatMap { entriesBySource[it.id].orEmpty() }

    private companion object {
        const val TAG = "PluginStoreViewModel"
        const val MAX_PARALLEL_INDEX_FETCHES = 4
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

/** 自动安装的前提：条目必须来自本轮网络刷新到达的来源，不能用缓存回退结果静默升级。 */
internal fun PluginStoreUiState.isFreshListing(item: PluginStoreItem): Boolean =
    item.source.id in freshSourceIds && isListingCurrent(item)

internal fun PluginStoreUiState.freshUpdates(): List<PluginStoreItem> =
    updatesByPluginId.values.filter { isFreshListing(it) }

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
