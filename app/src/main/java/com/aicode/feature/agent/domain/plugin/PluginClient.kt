package com.aicode.feature.agent.domain.plugin

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.JsonRpcError
import com.aicode.feature.agent.domain.mcp.JsonRpcResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 宿主 API 处理器：处理 runner 端 plugin client.* 请求（session/config/files/log 等）。
 * 由 PluginManager 实现并注入 PluginClient，使 runner 插件能通过 `client.xxx` API 访问宿主能力。
 */
fun interface HostApiHandler {
    suspend fun handleRequest(method: String, params: JsonObject, plugin: String?): JsonRpcResponse
}

/**
 * 插件运行时客户端：封装与 runner.mjs 的 JSON-RPC 交互。
 *
 * 协议方法（与 runner.mjs 对齐）：
 * - tools.list：列出插件注册的动态工具
 * - tool.call：执行插件工具
 * - hook.dispatch：分发 hook（修改型/返回型），返回合并后的 output 与各插件错误
 * - plugins.list：列出已加载插件
 * - dispose：通知所有插件执行 dispose
 * - event（通知）：派发工作流事件（fire-and-forget）
 *
 * 新增：hostApi 参数提供 runner 侧 plugin client.* API 的反向请求处理（session/config/files/log 等）。
 */
class PluginClient(
    val name: String,
    private val transport: UdsTransport,
    private val hostApi: HostApiHandler? = null,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private companion object {
        const val TAG = "PluginClient"
    }

    @Volatile
    var tools: List<PluginToolDescriptor> = emptyList()
        private set

    @Volatile
    var plugins: List<PluginDescriptor> = emptyList()
        private set

    /** runner 上报的配置文件解析失败（plugins.json JSON 语法错误等），供设置页展示警告。 */
    @Volatile
    var invalidConfigs: List<PluginConfigIssue> = emptyList()
        private set

    /** 建立连接并拉取工具列表与插件列表。失败抛 [PluginException]。 */
    suspend fun connect(): Int {
        // 先注册 runner → Kotlin 反向请求处理器（plugin client.* API），再建立连接：
        // runner 在连接建立瞬间可能已发出插件初始化阶段的 client.* 请求，
        // 若 onRequest 晚于读循环启动注册，这些请求会被回 Method not found。
        if (hostApi != null) {
            transport.onRequest = { request, plugin ->
                val method = (request["method"] as? JsonPrimitive)?.contentOrNull ?: ""
                val params = (request["params"] as? JsonObject) ?: buildJsonObject { }
                hostApi.handleRequest(method, params, plugin)
            }
        }
        FileLogger.i(TAG, "[$name] 开始连接插件运行时")
        transport.connect()
        val toolCount = refreshTools()
        refreshPlugins()
        FileLogger.i(TAG, "[$name] 连接完成，发现 $toolCount 个插件工具、${plugins.size} 个插件")
        return toolCount
    }

    /** 重新拉取工具列表（插件重载后调用）。 */
    suspend fun refreshTools(): Int {
        val response = transport.request("tools.list")
        val result = response.result as? JsonObject ?: throw PluginException(message = "tools.list 无 result")
        tools = runCatching {
            (result["tools"] as? JsonArray)?.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                PluginToolDescriptor(
                    name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    description = (obj["description"] as? JsonPrimitive)?.contentOrNull ?: "",
                    parameters = obj["parameters"] as? JsonObject,
                    plugin = (obj["plugin"] as? JsonPrimitive)?.contentOrNull
                )
            }.orEmpty()
        }.getOrElse {
            throw PluginException(message = "解析工具列表失败: ${it.message}", cause = it)
        }
        return tools.size
    }

    private suspend fun refreshPlugins() {
        val response = transport.request("plugins.list")
        val result = response.result as? JsonObject ?: return
        plugins = runCatching {
            (result["plugins"] as? JsonArray)?.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                PluginDescriptor(
                    name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: "",
                    source = (obj["source"] as? JsonPrimitive)?.contentOrNull ?: "",
                    version = (obj["version"] as? JsonPrimitive)?.contentOrNull,
                    tools = (obj["tools"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                    hooks = (obj["hooks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                    disabled = (obj["disabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    missing = (obj["missing"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    auth = (obj["auth"] as? JsonObject)?.let { a ->
                        PluginAuthDeclaration(
                            provider = (a["provider"] as? JsonPrimitive)?.contentOrNull,
                            methods = (a["methods"] as? JsonArray)?.mapNotNull { el ->
                                val mo = el as? JsonObject ?: return@mapNotNull null
                                PluginAuthMethodInfo(
                                    label = (mo["label"] as? JsonPrimitive)?.contentOrNull ?: "",
                                    type = (mo["type"] as? JsonPrimitive)?.contentOrNull ?: "oauth"
                                )
                            }.orEmpty()
                        )
                    },
                    error = (obj["error"] as? JsonPrimitive)?.contentOrNull
                )
            }.orEmpty()
        }.getOrElse {
            FileLogger.w(TAG, "[$name] 解析插件列表失败: ${it.message}")
            emptyList()
        }
        invalidConfigs = runCatching {
            (result["invalidConfigs"] as? JsonArray)?.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                PluginConfigIssue(
                    scope = (obj["scope"] as? JsonPrimitive)?.contentOrNull ?: "",
                    error = (obj["error"] as? JsonPrimitive)?.contentOrNull ?: ""
                )
            }.orEmpty()
        }.getOrElse {
            FileLogger.w(TAG, "[$name] 解析配置问题列表失败: ${it.message}")
            emptyList()
        }
    }

    /** 分发修改型 hook：插件就地修改 output，返回合并后的 output 与各插件错误。 */
    suspend fun dispatchHook(hook: String, input: JsonObject?, output: JsonObject): HookDispatchResult {
        FileLogger.d(TAG, "分发 hook $hook")
        val params = buildJsonObject {
            put("hook", hook)
            input?.let { put("input", it) }
            put("output", output)
        }
        val response = transport.request("hook.dispatch", params)
        val result = response.result as? JsonObject ?: throw PluginException(message = "hook.dispatch 无 result")
        val mergedOutput = (result["output"] as? JsonObject) ?: output
        val errors = (result["errors"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            HookError(
                plugin = (obj["plugin"] as? JsonPrimitive)?.contentOrNull ?: "",
                error = (obj["error"] as? JsonPrimitive)?.contentOrNull ?: ""
            )
        }.orEmpty()
        return HookDispatchResult(mergedOutput, errors)
    }

    /** 分发返回型 hook（provider.models / auth.loader / small_model 等），收集各插件返回值。 */
    suspend fun dispatchReturnHook(hook: String, input: JsonObject?): List<JsonElement> {
        val params = buildJsonObject {
            put("hook", hook)
            input?.let { put("input", it) }
            put("output", buildJsonObject { })
        }
        val response = transport.request("hook.dispatch", params)
        val result = response.result as? JsonObject ?: throw PluginException(message = "hook.dispatch 无 result")
        return (result["results"] as? JsonArray)?.toList().orEmpty()
    }

    /** 执行插件工具，返回扁平化为文本的结果与是否报错。 */
    suspend fun callTool(toolName: String, arguments: JsonObject, sessionId: String? = null): PluginCallResult {
        val params = buildJsonObject {
            put("name", toolName)
            put("args", arguments)
            if (sessionId != null) put("sessionID", sessionId)
        }
        val response = transport.request("tool.call", params, timeoutMs = null)
        val result = response.result as? JsonObject ?: throw PluginException(message = "tool.call 无 result")
        val call = result["result"] as? JsonObject ?: return PluginCallResult("", true)
        val status = (call["status"] as? JsonPrimitive)?.contentOrNull
        return if (status == "success") {
            val data = call["data"]
            val text = when (data) {
                is JsonPrimitive -> data.contentOrNull ?: ""
                else -> data?.toString() ?: ""
            }
            PluginCallResult(text, false)
        } else {
            val message = (call["message"] as? JsonPrimitive)?.contentOrNull ?: "插件工具执行失败"
            PluginCallResult(message, true)
        }
    }

    /** 通知所有插件执行 dispose。 */
    suspend fun dispose() {
        runCatching { transport.request("dispose") }
            .onFailure { FileLogger.w(TAG, "[$name] 通知插件 dispose 失败: ${it.message}") }
    }

    /** 派发工作流事件（通知，fire-and-forget）。 */
    fun notifyEvent(type: String, properties: Map<String, JsonElement> = emptyMap()) {
        val params = buildJsonObject {
            put("event", buildJsonObject {
                put("type", type)
                put("properties", JsonObject(properties))
            })
        }
        transport.notify("event", params)
    }

    /** 查询插件声明的登录方法（auth.methods.list）。 */
    suspend fun authMethodsList(): List<ProviderAuthMethods> {
        val response = transport.request("auth.methods.list")
        val result = response.result as? JsonObject ?: return emptyList()
        return (result["providers"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val provider = (obj["provider"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val methods = (obj["methods"] as? JsonArray)?.mapNotNull { m ->
                val mo = m as? JsonObject ?: return@mapNotNull null
                PluginAuthMethod(
                    provider = provider,
                    label = (mo["label"] as? JsonPrimitive)?.contentOrNull ?: "",
                    type = (mo["type"] as? JsonPrimitive)?.contentOrNull ?: "oauth",
                    plugin = (mo["plugin"] as? JsonPrimitive)?.contentOrNull ?: ""
                )
            }.orEmpty()
            ProviderAuthMethods(provider, methods)
        }.orEmpty()
    }

    /** 执行登录授权（auth.authorize）：返回 url/instructions/method；requiresKey 表示 api 型需宿主收集 key。 */
    suspend fun authAuthorize(provider: String, methodIndex: Int, inputs: JsonObject? = null): PluginAuthorizeResult {
        val params = buildJsonObject {
            put("provider", provider)
            put("methodIndex", methodIndex)
            inputs?.let { put("inputs", it) }
        }
        val response = transport.request("auth.authorize", params)
        val result = response.result as? JsonObject ?: return PluginAuthorizeResult(error = "auth.authorize 无 result")
        return PluginAuthorizeResult(
            url = (result["url"] as? JsonPrimitive)?.contentOrNull ?: "",
            instructions = (result["instructions"] as? JsonPrimitive)?.contentOrNull ?: "",
            method = (result["method"] as? JsonPrimitive)?.contentOrNull ?: "auto",
            type = (result["type"] as? JsonPrimitive)?.contentOrNull ?: "oauth",
            requiresKey = (result["requiresKey"] as? JsonPrimitive)?.booleanOrNull ?: false,
            completed = (result["completed"] as? JsonPrimitive)?.booleanOrNull ?: false,
            error = (result["error"] as? JsonPrimitive)?.contentOrNull
        )
    }

    /** 提交登录回调（auth.callback）：code 为 code 模式的用户输入，auto 模式传 null。 */
    suspend fun authCallback(provider: String, code: String? = null): PluginAuthCallbackResult {
        val params = buildJsonObject {
            put("provider", provider)
            if (code != null) put("code", code)
        }
        val response = transport.request("auth.callback", params)
        val result = response.result as? JsonObject ?: return PluginAuthCallbackResult("failed", "auth.callback 无 result")
        return PluginAuthCallbackResult(
            type = (result["type"] as? JsonPrimitive)?.contentOrNull ?: "failed",
            error = (result["error"] as? JsonPrimitive)?.contentOrNull
        )
    }

    /** 查询 auth.loader 返回自定义 fetch 的 provider 代理地址（provider → baseUrl）。 */
    suspend fun authProxyInfo(): Map<String, String> {
        val response = transport.request("auth.proxy")
        val result = response.result as? JsonObject ?: return emptyMap()
        val providers = result["providers"] as? JsonObject ?: return emptyMap()
        return providers.mapNotNull { (k, v) ->
            val obj = v as? JsonObject ?: return@mapNotNull null
            val baseUrl = (obj["baseUrl"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            k to baseUrl
        }.toMap()
    }

    fun close() = transport.close()
}

/** 插件工具描述符（来自 runner 的 tools.list）。plugin 为注册该工具的插件名（旧版 runner 缺失时为 null）。 */
data class PluginToolDescriptor(
    val name: String,
    val description: String,
    val parameters: JsonObject?,
    val plugin: String? = null
)

/** 插件描述符（来自 runner 的 plugins.list）。version 为插件包版本（npm 读 package.json，本地目录型读其 package.json，单文件插件为 null）。error 非空表示加载失败；disabled 表示被配置禁用（不加载）；missing 表示 npm 依赖未安装（不加载）。 */
data class PluginDescriptor(
    val name: String,
    val source: String,
    val version: String? = null,
    val tools: List<String>,
    val hooks: List<String>,
    val auth: PluginAuthDeclaration? = null,
    val error: String? = null,
    val disabled: Boolean = false,
    val missing: Boolean = false
)

/** 插件配置文件解析失败信息（scope: global/project）。 */
data class PluginConfigIssue(
    val scope: String,
    val error: String
)

/** 插件声明的 auth（对齐 opencode AuthHook 的可序列化子集）。 */
data class PluginAuthDeclaration(
    val provider: String? = null,
    val methods: List<PluginAuthMethodInfo> = emptyList()
)

/** 插件 auth.methods 中单个登录方法的展示信息。 */
data class PluginAuthMethodInfo(
    val label: String,
    val type: String
)

/** 某 provider 下所有插件声明的登录方法。 */
data class ProviderAuthMethods(
    val provider: String,
    val methods: List<PluginAuthMethod>
)

/** hook 分发结果：合并后的 output + 各插件执行错误。 */
data class HookDispatchResult(
    val output: JsonObject,
    val errors: List<HookError>
)

/** 单个插件执行 hook 时抛出的错误。 */
data class HookError(
    val plugin: String,
    val error: String
)

/** 一次插件工具调用的结果：扁平化文本 + 是否报错。 */
data class PluginCallResult(val text: String, val isError: Boolean)