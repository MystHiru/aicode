package com.aicode.feature.agent.domain.plugin

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** 插件运行时状态（供设置页展示）。 */
data class PluginRuntimeStatus(
    val state: State,
    val toolCount: Int = 0,
    val pluginCount: Int = 0,
    val failedCount: Int = 0,
    val error: String? = null
) {
    enum class State { STARTING, RUNNING, FAILED, DISABLED }
}

/**
 * 插件运行时总管：管理容器内 Node 伴生进程（runner.mjs）的生命周期、UDS 连接、
 * 插件工具同步与 Hook 分发。
 *
 * 与 [com.aicode.feature.agent.domain.mcp.McpManager] 同构：
 * - 跟随当前工作区切换自动重载（插件目录与项目级 plugins.json 随工作区变化）；
 * - 监听 plugins.json / package.json 外部编辑自动重载（npm 依赖变化时提示手动安装后重载）；
 * - 工具经 [PluginToolBridge] 注册进 [ToolRegistry]（同名覆盖内置工具，重载时恢复）。
 */
@Singleton
class PluginManager @Inject constructor(
    private val configRepository: PluginConfigRepository,
    private val toolRegistryProvider: Provider<ToolRegistry>,
    private val containerEngine: LinuxContainerEngine,
    private val containerInstaller: ContainerInstaller,
    private val workspaceRepository: WorkspaceRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val hostApiHandler: PluginHostApiHandler
) : PluginHookGateway {

    /** ToolRegistry 懒加载：打破「工具 → PluginManager → ToolRegistry → 工具」的 Hilt 依赖循环。
     *  工具经 PluginHookGateway 依赖本类，本类构造不触发 ToolRegistry，运行时 reload 才 get()。 */
    private val toolRegistry: ToolRegistry get() = toolRegistryProvider.get()
    private companion object {
        const val TAG = "PluginManager"

        /** 容器内插件运行时路径（aicodeDir 绑定到容器 /root/.aicode）。 */
        const val CONTAINER_RUNTIME_DIR = "/root/.aicode/plugin-runtime"

        /** socket 文件独立子目录（与运行时脚本分开，便于识别与清理）。 */
        const val CONTAINER_SOCKET_DIR = "$CONTAINER_RUNTIME_DIR/socket"
        const val RUNNER_FILE = "runner.mjs"

        /** 容器内工作区路径（工作区绑定到容器 /root/workspace）。 */
        const val CONTAINER_WORKSPACE = "/root/workspace"

        /** 等待 socket 文件就绪的最长时间（runner 启动 + 插件加载 + npm 包解析）。 */
        const val SOCKET_WAIT_MS = 20_000L
        const val SOCKET_POLL_MS = 200L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reloadMutex = Mutex()

    private var client: PluginClient? = null
    private var process: Process? = null
    private var socketHostFile: File? = null

    /** 插件工具名 → 注册前被覆盖的内置工具（null 表示此前无同名工具），重载时恢复。 */
    private val replacedTools = ConcurrentHashMap<String, AgentTool?>()
    private val registeredToolNames = mutableSetOf<String>()

    private val _status = MutableStateFlow(PluginRuntimeStatus(PluginRuntimeStatus.State.DISABLED))
    val status: StateFlow<PluginRuntimeStatus> = _status.asStateFlow()

    fun start() {
        // 跟随当前工作区切换自动重载（首帧立即发射当前值，等价启动即加载）。
        scope.launch {
            workspaceRepository.current.collectLatest {
                reload()
            }
        }
        // 配置文件被外部（容器内/手工）直接编辑：数秒内自动重载（npm 依赖变化时提示手动安装）。
        scope.launch {
            configRepository.externalChanges.collect {
                FileLogger.i(TAG, "检测到插件配置外部变更，自动重载")
                reload()
            }
        }
        // 容器 profile 切换：插件进程跑在旧容器的 rootfs 上，必须重建才能用新容器。
        scope.launch {
            containerSettingsRepository.activeProfileIdFlow.drop(1).collect {
                reload()
            }
        }
        // 默认容器变化：远程模式下插件运行时跑在默认容器上，需要重建。
        scope.launch {
            containerSettingsRepository.defaultContainerIdFlow.drop(1).collect {
                if (currentActiveProfile().mode == ExecutionMode.REMOTE_SSH) {
                    reload()
                }
            }
        }
    }

    /** 重新加载插件运行时：检测 npm 依赖（缺失仅提示，需手动安装）→ 重启进程 → 连接 → 同步工具。 */
    suspend fun reload() = reloadMutex.withLock {
        teardown()
        val cfg = configRepository.getEffectiveConfig()
        val projectPath = workspaceRepository.currentPath()
        if (cfg.plugins.isEmpty() && !configRepository.globalPackageJsonExists() && !hasLocalPluginFiles(projectPath)) {
            // 无任何插件来源（npm 声明 / 依赖 / 本地插件文件）：不启动运行时（保持 DISABLED）。
            _status.value = PluginRuntimeStatus(PluginRuntimeStatus.State.DISABLED)
            return@withLock
        }

        _status.value = PluginRuntimeStatus(PluginRuntimeStatus.State.STARTING)
        try {
            val runtimeProfile = resolveRuntimeProfile()
            containerEngine.notReadyHintFor(runtimeProfile)?.let {
                throw IllegalStateException(it)
            }
            val projectPath = workspaceRepository.currentPath()

            // 1. 手动安装模式：npm 插件依赖需用户在容器内自行 npm install，此处仅检测缺失并提示
            missingNpmDeps(projectPath, cfg.plugins).takeIf { it.isNotEmpty() }?.let { missing ->
                FileLogger.w(TAG, "npm 插件依赖未安装：$missing，请在容器终端执行 cd /root/.aicode && npm install（项目级为 cd .aicode && npm install）后重载插件")
            }

            // 2. 启动插件伴生进程（优先 bun，容器内未安装则回退 node）
            // socket 落在 plugin-runtime/socket 子目录：与容器内 AICODE_SOCK（/root/.aicode/plugin-runtime/socket/plugin.sock）对齐。
            // 固定文件名而非 UUID：宿主侧 LocalSocket 路径受 AF_UNIX sun_path 108 字节限制，
            // 带 36 位 UUID 的宿主路径（/data/user/0/... 前缀已 66 字节）会超出；单例运行时无并发冲突。
            val socketName = "plugin.sock"
            val hostSocket = File(File(File(containerInstaller.aicodeDir, "plugin-runtime"), "socket"), socketName)
            hostSocket.parentFile?.mkdirs()
            // 清掉上次异常退出的残留 sock，避免 waitForSocket 把旧文件误判为就绪
            hostSocket.delete()
            socketHostFile = hostSocket
            val env = mapOf(
                "AICODE_SOCK" to "$CONTAINER_SOCKET_DIR/$socketName",
                "AICODE_WORKSPACE" to CONTAINER_WORKSPACE
            )
            val runtimeBin = resolveRuntimeBin(projectPath, runtimeProfile)
            FileLogger.i(TAG, "启动插件运行时: $runtimeBin $CONTAINER_RUNTIME_DIR/$RUNNER_FILE sock=$hostSocket")
            val p = containerEngine.startStdioProcess(
                program = runtimeBin,
                programArgs = listOf("$CONTAINER_RUNTIME_DIR/$RUNNER_FILE"),
                projectPath = projectPath,
                extraEnv = env,
                profile = runtimeProfile
            )
            process = p
            scope.launch { drainProcessLogs(p) }

            // 3. 等待 socket 文件就绪并建立连接
            val connected = waitForSocket(hostSocket)
            if (!connected) {
                throw PluginException(message = "插件运行时 socket 未就绪（${SOCKET_WAIT_MS}ms 超时）")
            }

            val c = PluginClient("plugin-runtime", UdsTransport(hostSocket.absolutePath), hostApi = hostApiHandler)
            val toolCount = c.connect()
            client = c

            // 4. 同步插件工具到 ToolRegistry（同名覆盖内置工具，记录原工具以便恢复）
            synchronized(registeredToolNames) {
                c.tools.forEach { desc ->
                    val bridge = PluginToolBridge(c, desc)
                    val replaced = toolRegistry.getTool(desc.name)
                    // ConcurrentHashMap 不允许 null value：无同名内置工具时不记录，teardown 时 remove 返回 null 走 unregister。
                    if (replaced != null) replacedTools[desc.name] = replaced
                    toolRegistry.register(desc.name, bridge)
                    registeredToolNames.add(desc.name)
                }
            }
            val okCount = c.plugins.count { it.error == null }
            val failedCount = c.plugins.count { it.error != null }
            _status.value = PluginRuntimeStatus(
                PluginRuntimeStatus.State.RUNNING,
                toolCount = toolCount,
                pluginCount = okCount,
                failedCount = failedCount
            )
            val pluginDetail = c.plugins.filter { it.error == null }.joinToString { "${it.name}@${it.source}" }
            FileLogger.i(TAG, "插件运行时就绪：$okCount 个插件（$pluginDetail）、$toolCount 个工具" + if (failedCount > 0) "、$failedCount 个加载失败" else "")
        } catch (e: CancellationException) {
            // 协程取消（如工作区切换触发 collectLatest 重载）：不吞取消，交由上层感知。
            teardown()
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "插件运行时启动失败", e)
            teardown()
            _status.value = PluginRuntimeStatus(
                PluginRuntimeStatus.State.FAILED,
                error = e.message
            )
        }
    }

    /** 分发修改型 hook（chat.headers / tool.execute.before 等）。无插件运行时直接返回原 output。 */
    override suspend fun dispatchHook(hook: String, input: JsonObject?, output: JsonObject): HookDispatchResult {
        val c = client ?: return HookDispatchResult(output, emptyList())
        return runCatching { c.dispatchHook(hook, input, output) }
            .getOrElse {
                FileLogger.w(TAG, "分发 hook $hook 失败: ${it.message}")
                HookDispatchResult(output, emptyList())
            }
    }

    /** 分发返回型 hook（provider.models / auth.loader 等），收集各插件返回值。无插件运行时返回空。 */
    override suspend fun dispatchReturnHook(hook: String, input: JsonObject?): List<JsonElement> {
        val c = client ?: return emptyList()
        return runCatching { c.dispatchReturnHook(hook, input) }
            .getOrElse {
                FileLogger.w(TAG, "分发返回型 hook $hook 失败: ${it.message}")
                emptyList()
            }
    }

    /** 派发工作流事件（fire-and-forget，不阻塞工作流）。 */
    override fun notifyEvent(type: String, properties: Map<String, JsonElement>) {
        client?.notifyEvent(type, properties)
    }

    /** 当前已加载的插件列表（设置页展示）。 */
    override fun currentPlugins(): List<PluginDescriptor> = client?.plugins ?: emptyList()

    /** 获取指定插件对应的工具描述列表。 */
    fun getPluginTools(pluginName: String): List<PluginToolDescriptor> {
        val plugin = currentPlugins().firstOrNull { it.name == pluginName } ?: return emptyList()
        val c = client ?: return emptyList()
        val set = plugin.tools.toSet()
        return c.tools.filter { it.name in set }
    }

    /** 删除指定插件并重载。 */
    suspend fun deletePlugin(plugin: PluginDescriptor): Boolean {
        val success = configRepository.deletePlugin(plugin.name, plugin.source)
        reload()
        return success
    }

    /** 启用/禁用指定插件并重载。 */
    suspend fun setPluginDisabled(plugin: PluginDescriptor, disabled: Boolean) {
        configRepository.setPluginDisabled(plugin.name, plugin.source, disabled)
        reload()
    }

    /** 检查指定插件是否被禁用。 */
    suspend fun isPluginDisabled(plugin: PluginDescriptor): Boolean =
        configRepository.isPluginDisabled(plugin.name, plugin.source)

    /** 当前已注册的插件工具名列表。 */
    fun currentToolNames(): List<String> = registeredToolNames.toList()

    /** 运行时是否可用（已连接且状态 RUNNING）。 */
    override fun isRunning(): Boolean = client != null && _status.value.state == PluginRuntimeStatus.State.RUNNING

    /** plugins.json 声明的 npm 插件是否已在全局/项目 node_modules 安装（手动安装模式下仅提示，不自动装）。
     *  以包目录内存在 package.json 判定已安装，避免 node_modules 存在但缺包时误判。 */
    private fun missingNpmDeps(projectPath: String, plugins: List<String>): List<String> {
        if (plugins.isEmpty()) return emptyList()
        val globalNm = File(containerInstaller.aicodeDir, "node_modules")
        val projectNm = File(File(projectPath, ".aicode"), "node_modules")
        return plugins.filter { pkg ->
            val parts = pkg.split('/')
            val pkgDir = { nm: File ->
                if (pkg.startsWith("@") && parts.size >= 2) File(File(nm, parts[0]), parts[1]) else File(nm, parts[0])
            }
            !File(pkgDir(globalNm), "package.json").isFile && !File(pkgDir(projectNm), "package.json").isFile
        }
    }

    /** 检测容器内 bun 是否可用（手动安装模式：bun 由用户自行装入 PATH），可用则用 bun 运行，否则回退 node。 */
    private suspend fun resolveRuntimeBin(projectPath: String, profile: ContainerProfile): String {
        val p = containerEngine.startStdioProcess(
            program = "/bin/sh",
            programArgs = listOf("-c", "command -v bun"),
            projectPath = projectPath,
            extraEnv = emptyMap(),
            profile = profile
        )
        val found = runCatching {
            p.inputStream.bufferedReader().use { it.readText().isNotBlank() }
        }.getOrDefault(false)
        p.destroy()
        if (found) FileLogger.i(TAG, "检测到容器内 bun，插件运行时使用 bun")
        return if (found) "bun" else "node"
    }

    private suspend fun waitForSocket(socketFile: File): Boolean {
        val deadline = System.currentTimeMillis() + SOCKET_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (socketFile.exists()) return true
            delay(SOCKET_POLL_MS)
        }
        return false
    }

    /** 排空进程 stdout/stderr 日志（并行读两个流，避免 stderr 被 stdout 阻塞饿死）。
     *  stdout 仅用于 READY 信号与插件 console 输出，不进协议流。 */
    private suspend fun drainProcessLogs(p: Process) = coroutineScope {
        launch {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank() && line != "AICODE_PLUGIN_READY") {
                        FileLogger.i(TAG, "plugin-runner: $line")
                    }
                }
            }
        }
        launch {
            runCatching {
                p.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) FileLogger.w(TAG, "plugin-runner stderr: $line")
                }
            }
        }
    }

    /** 关闭运行时：通知插件 dispose、销毁进程、反注册工具（恢复被覆盖的内置工具）、清理 socket。 */
    private suspend fun teardown() {
        val c = client
        if (c != null) {
            runCatching { c.dispose() }
            runCatching { c.close() }
            client = null
        }
        synchronized(registeredToolNames) {
            registeredToolNames.forEach { name ->
                val replaced = replacedTools.remove(name)
                if (replaced != null) {
                    toolRegistry.register(name, replaced)
                } else {
                    toolRegistry.unregister(name)
                }
            }
            registeredToolNames.clear()
        }
        replacedTools.clear()
        runCatching { process?.destroy() }
        process = null
        // 清理本次 socket（固定名 plugin.sock；异常退出残留由下次启动前删除）。
        socketHostFile?.let { runCatching { it.delete() } }
        socketHostFile = null
    }

    /** 本地插件目录是否含可加载的插件（.mjs/.js/.cjs 文件，或含 index 的目录）。 */
    private fun hasLocalPluginFiles(projectPath: String): Boolean {
        val dirs = listOf(
            File(containerInstaller.aicodeDir, "plugins"),
            File(File(projectPath, ".aicode"), "plugins")
        )
        return dirs.any { dir ->
            if (!dir.isDirectory) return@any false
            dir.listFiles().orEmpty().any { f ->
                (f.isFile && (f.name.endsWith(".mjs") || f.name.endsWith(".js") || f.name.endsWith(".cjs"))) ||
                    (f.isDirectory && (File(f, "index.js").isFile || File(f, "index.mjs").isFile || File(f, "index.cjs").isFile))
            }
        }
    }

    /** 解析插件运行时的容器：本地模式用当前 profile，远程 SSH 模式用默认容器（与 MCP stdio 策略一致）。 */
    private suspend fun resolveRuntimeProfile(): ContainerProfile {
        val active = currentActiveProfile()
        return if (active.mode == ExecutionMode.REMOTE_SSH) resolveDefaultContainerProfile() else active
    }

    private suspend fun currentActiveProfile(): ContainerProfile {
        val id = containerSettingsRepository.activeProfileIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id } ?: ContainerProfile.BUILTIN_ALPINE
    }

    private suspend fun resolveDefaultContainerProfile(): ContainerProfile {
        val id = containerSettingsRepository.defaultContainerIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
            ?: ContainerProfile.BUILTIN_ALPINE
    }
}