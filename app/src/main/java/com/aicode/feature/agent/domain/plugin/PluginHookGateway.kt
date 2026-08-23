package com.aicode.feature.agent.domain.plugin

import com.aicode.feature.settings.domain.model.AIProviderConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 插件 Hook 分发的轻量门面：只暴露工作流/工具/适配器需要的分发能力，
 * **不依赖 ToolRegistry**（避免 Hilt 依赖循环：工具 → PluginManager → ToolRegistry → 工具）。
 *
 * 由 [PluginManager] 实现并通过 Hilt 绑定注入。
 */
interface PluginHookGateway {
    /** 分发修改型 hook（chat.headers / tool.execute.before 等）。无插件运行时直接返回原 output。 */
    suspend fun dispatchHook(hook: String, input: JsonObject?, output: JsonObject): HookDispatchResult

    /** 分发返回型 hook（provider.models / auth.loader 等），收集各插件返回值。 */
    suspend fun dispatchReturnHook(hook: String, input: JsonObject?): List<JsonElement>

    /** 派发工作流事件（fire-and-forget，不阻塞工作流）。 */
    fun notifyEvent(type: String, properties: Map<String, JsonElement> = emptyMap())

    /** 查询插件声明的登录方法（auth.methods.list）。无插件运行时返回空。 */
    suspend fun authMethods(): List<ProviderAuthMethods>

    /** 执行登录授权（auth.authorize）。 */
    suspend fun authAuthorize(provider: String, methodIndex: Int): PluginAuthorizeResult

    /** 提交登录回调（auth.callback）。auto 模式 code 传 null。 */
    suspend fun authCallback(provider: String, code: String? = null): PluginAuthCallbackResult

    /** 查询 auth.loader 返回自定义 fetch 的 provider 代理地址（provider → baseUrl）。 */
    suspend fun authProxy(): Map<String, String>

    /** 指定 provider id 是否命中某插件的 auth 声明（auth.provider 匹配）。 */
    fun hasPluginAuth(providerId: String): Boolean

    /** 当前已加载的插件列表（设置页展示）。 */
    fun currentPlugins(): List<PluginDescriptor>

    /** 插件 auth 声明的虚拟 provider 列表（id=插件 auth.provider，apiKey 留空走插件认证）。无插件运行时返回空。 */
    fun pluginProviders(): List<AIProviderConfig>

    /** 运行时是否可用。 */
    fun isRunning(): Boolean
}