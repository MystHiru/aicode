package com.aicode.feature.agent.domain.plugin

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 插件配置的作用域：全局（跨项目共享）或项目级（仅当前工作区生效）。 */
enum class PluginScope { GLOBAL, PROJECT }

/** 插件配置：npm 包声明列表 + 禁用名单。 */
data class PluginConfig(
    val plugins: List<String> = emptyList(),
    val disabled: List<String> = emptyList()
) {
    /** 合并两个配置：plugins 取并集（项目优先、去重），disabled 取并集。 */
    fun mergedWith(other: PluginConfig): PluginConfig = PluginConfig(
        plugins = (plugins + other.plugins).distinct(),
        disabled = (disabled + other.disabled).distinct()
    )
}

/**
 * 插件配置持久化，支持全局 + 项目级两级（对齐 MCP 配置仓库模式）：
 * - 全局：`filesDir/aicode/plugins.json`（跨项目、跨升级保留）；
 * - 项目级：`workspacePath/.aicode/plugins.json`（随工作区走，可 git 追踪）。
 *
 * 生效配置 = 全局 + 项目合并，项目级优先。同时监听 package.json 变动（npm 依赖声明），
 * 由 PluginManager 据此触发 npm install。
 */
@Singleton
class PluginConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val workspaceRepository: WorkspaceRepository
) {
    private companion object {
        const val TAG = "PluginConfigRepository"
        const val CONFIG_FILE = "plugins.json"
        const val PKG_FILE = "package.json"
        const val AICODE_DIR = ".aicode"
        const val DEFAULT_JSON = """{"plugins":[],"disabled":[]}"""
        /** 配置文件轮询间隔：外部直接编辑后约 2s 内刷新。 */
        const val WATCH_POLL_MS = 2000L
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        val PRETTY_JSON = Json { prettyPrint = true }
    }

    /** 全局配置文件：`filesDir/aicode/plugins.json`。 */
    private val globalFile: File
        get() = File(containerInstaller.aicodeDir, CONFIG_FILE)

    /** 当前工作区的项目级配置文件：`workspacePath/.aicode/plugins.json`。 */
    private fun projectFileForPath(workspacePath: String): File =
        File(File(workspacePath, AICODE_DIR), CONFIG_FILE)

    private val globalState = MutableStateFlow<String?>(null)
    private val projectStates = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val mutex = Mutex()

    // ── 外部修改监听：容器内/手工直接编辑配置文件后，刷新缓存并广播给 PluginManager 重载 ──

    private val _externalChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val externalChanges: SharedFlow<Unit> = _externalChanges.asSharedFlow()

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class FileStamp(val mtime: Long, val size: Long) {
        companion object {
            fun of(file: File): FileStamp? = runCatching {
                FileStamp(file.lastModified(), file.length())
            }.getOrNull()?.takeIf { it.mtime > 0L }
        }
    }

    @Volatile
    private var globalStamp: FileStamp? = null
    @Volatile
    private var globalWatchingInitialized = false
    private val projectStamps = ConcurrentHashMap<String, FileStamp?>()
    private val initializedProjectPaths = ConcurrentHashMap.newKeySet<String>()

    /** 启动监听：2s 轮询 plugins.json 与 package.json 的 mtime，外部修改后刷新并广播。 */
    fun startWatching() {
        watchScope.launch {
            while (true) {
                delay(WATCH_POLL_MS)
                runCatching {
                    checkGlobalChanged()
                    checkProjectChanged()
                    checkPkgChanged()
                }
            }
        }
    }

    private fun checkGlobalChanged() {
        val stamp = FileStamp.of(globalFile)
        if (!globalWatchingInitialized) {
            globalStamp = stamp
            globalWatchingInitialized = true
            return
        }
        if (globalStamp == stamp) return
        val content = if (stamp == null) DEFAULT_JSON else readFileSync(globalFile)
        globalStamp = stamp
        if (content != (globalState.value ?: DEFAULT_JSON)) {
            globalState.value = content
            _externalChanges.tryEmit(Unit)
            FileLogger.i(TAG, "检测到全局插件配置变化，已刷新")
        }
    }

    private fun checkProjectChanged() {
        val path = workspaceRepository.currentPath()
        val file = projectFileForPath(path)
        val stamp = FileStamp.of(file)
        if (!initializedProjectPaths.contains(path)) {
            projectStamps[path] = stamp
            initializedProjectPaths.add(path)
            return
        }
        if (projectStamps[path] == stamp) return
        val content = if (stamp == null) DEFAULT_JSON else readFileSync(file)
        projectStamps[path] = stamp
        val state = getProjectState(path)
        if (content != (state.value ?: DEFAULT_JSON)) {
            state.value = content
            _externalChanges.tryEmit(Unit)
            FileLogger.i(TAG, "检测到项目插件配置变化，已刷新")
        }
    }

    private fun readFileSync(file: File): String {
        if (file.isFile) {
            return runCatching { file.readText() }.getOrElse {
                FileLogger.w(TAG, "读取 ${file.name} 失败，回退默认配置: ${it.message}")
                DEFAULT_JSON
            }
        }
        return DEFAULT_JSON
    }

    /** package.json 变动检测：npm 依赖声明变化时广播（PluginManager 据此触发 npm install）。 */
    private var pkgStamp: FileStamp? = null
    private var pkgInitialized = false

    private fun checkPkgChanged() {
        val stamp = FileStamp.of(globalPkgFile)
        if (!pkgInitialized) {
            pkgStamp = stamp
            pkgInitialized = true
            return
        }
        if (pkgStamp == stamp) return
        pkgStamp = stamp
        _externalChanges.tryEmit(Unit)
        FileLogger.i(TAG, "检测到全局 package.json 变化，触发插件重载")
    }

    private val globalPkgFile: File
        get() = File(containerInstaller.aicodeDir, PKG_FILE)

    private fun getProjectState(workspacePath: String): MutableStateFlow<String?> =
        projectStates.getOrPut(workspacePath) { MutableStateFlow(null) }

    private suspend fun load(file: File): String = withContext(Dispatchers.IO) {
        if (file.isFile) {
            return@withContext runCatching { file.readText() }.getOrElse {
                FileLogger.w(TAG, "读取 ${file.name} 失败，回退默认配置: ${it.message}")
                DEFAULT_JSON
            }
        }
        DEFAULT_JSON
    }

    private fun writeFile(file: File, json: String) {
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    /** 当前工作区生效的合并配置（全局 + 项目，项目优先）。 */
    suspend fun getEffectiveConfig(): PluginConfig {
        val path = workspaceRepository.currentPath()
        ensureGlobalLoaded()
        ensureProjectLoaded(path)
        val global = parse(globalState.value ?: DEFAULT_JSON)
        val project = parse(getProjectState(path).value ?: DEFAULT_JSON)
        return global.mergedWith(project)
    }

    suspend fun getGlobalConfig(): PluginConfig {
        ensureGlobalLoaded()
        return parse(globalState.value ?: DEFAULT_JSON)
    }

    suspend fun getProjectConfig(): PluginConfig {
        val path = workspaceRepository.currentPath()
        ensureProjectLoaded(path)
        return parse(getProjectState(path).value ?: DEFAULT_JSON)
    }

    suspend fun setGlobalConfig(config: PluginConfig) {
        val json = serialize(config)
        mutex.withLock {
            withContext(Dispatchers.IO) { writeFile(globalFile, json) }
            globalState.value = json
            globalStamp = FileStamp.of(globalFile)
        }
    }

    suspend fun setProjectConfig(config: PluginConfig) {
        val path = workspaceRepository.currentPath()
        val json = serialize(config)
        mutex.withLock {
            withContext(Dispatchers.IO) { writeFile(projectFileForPath(path), json) }
            getProjectState(path).value = json
            projectStamps[path] = FileStamp.of(projectFileForPath(path))
        }
    }

    /** 全局 package.json 是否存在（npm 依赖声明）。 */
    fun globalPackageJsonExists(): Boolean = globalPkgFile.isFile

    /** 判断插件是否处于禁用状态。 */
    suspend fun isPluginDisabled(name: String, source: String): Boolean {
        val isGlobal = source.startsWith("global")
        val config = if (isGlobal) getGlobalConfig() else getProjectConfig()
        return name in config.disabled
    }

    /** 启用/禁用插件。 */
    suspend fun setPluginDisabled(name: String, source: String, disabled: Boolean) {
        val isGlobal = source.startsWith("global")
        if (isGlobal) {
            val cfg = getGlobalConfig()
            val newDisabled = if (disabled) (cfg.disabled + name).distinct() else cfg.disabled.filterNot { it == name }
            setGlobalConfig(cfg.copy(disabled = newDisabled))
        } else {
            val cfg = getProjectConfig()
            val newDisabled = if (disabled) (cfg.disabled + name).distinct() else cfg.disabled.filterNot { it == name }
            setProjectConfig(cfg.copy(disabled = newDisabled))
        }
    }

    /** 删除插件：清理配置声明与/或本地插件文件。 */
    suspend fun deletePlugin(name: String, source: String): Boolean = withContext(Dispatchers.IO) {
        var success = true
        when (source) {
            "global-npm" -> {
                val cfg = getGlobalConfig()
                setGlobalConfig(
                    cfg.copy(
                        plugins = cfg.plugins.filterNot { it == name },
                        disabled = cfg.disabled.filterNot { it == name }
                    )
                )
            }
            "project-npm" -> {
                val cfg = getProjectConfig()
                setProjectConfig(
                    cfg.copy(
                        plugins = cfg.plugins.filterNot { it == name },
                        disabled = cfg.disabled.filterNot { it == name }
                    )
                )
            }
            "global-local" -> {
                val cfg = getGlobalConfig()
                if (name in cfg.disabled) {
                    setGlobalConfig(cfg.copy(disabled = cfg.disabled.filterNot { it == name }))
                }
                val dir = File(containerInstaller.aicodeDir, "plugins")
                success = deleteLocalPluginTarget(dir, name)
            }
            "project-local" -> {
                val cfg = getProjectConfig()
                if (name in cfg.disabled) {
                    setProjectConfig(cfg.copy(disabled = cfg.disabled.filterNot { it == name }))
                }
                val projectPath = workspaceRepository.currentPath()
                val dir = File(File(projectPath, AICODE_DIR), "plugins")
                success = deleteLocalPluginTarget(dir, name)
            }
            else -> {
                success = false
            }
        }
        success
    }

    private fun deleteLocalPluginTarget(pluginsDir: File, name: String): Boolean {
        if (!pluginsDir.isDirectory) return false
        val targetDir = File(pluginsDir, name)
        if (targetDir.exists()) {
            return targetDir.deleteRecursively()
        }
        val candidateExts = listOf(".mjs", ".js", ".cjs")
        for (ext in candidateExts) {
            val file = File(pluginsDir, if (name.endsWith(ext)) name else "$name$ext")
            if (file.exists() && file.isFile) {
                return file.delete()
            }
        }
        return false
    }

    private suspend fun ensureGlobalLoaded() {
        if (globalState.value != null) return
        mutex.withLock {
            if (globalState.value != null) return
            globalState.value = load(globalFile)
        }
    }

    private suspend fun ensureProjectLoaded(workspacePath: String) {
        val state = getProjectState(workspacePath)
        if (state.value != null) return
        mutex.withLock {
            if (state.value != null) return
            state.value = load(projectFileForPath(workspacePath))
        }
    }

    private fun serialize(config: PluginConfig): String {
        val root = buildJsonObject {
            putJsonArray("plugins") { config.plugins.forEach { add(it) } }
            if (config.disabled.isNotEmpty()) {
                putJsonArray("disabled") { config.disabled.forEach { add(it) } }
            }
        }
        return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
    }

    private fun parse(raw: String): PluginConfig {
        val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
            FileLogger.w(TAG, "插件配置 JSON 解析失败: ${it.message}")
            return PluginConfig()
        }
        val plugins = (root["plugins"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        } ?: emptyList()
        val disabled = (root["disabled"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        } ?: emptyList()
        return PluginConfig(plugins, disabled)
    }
}