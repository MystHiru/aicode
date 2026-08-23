package com.aicode.feature.agent.domain.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 运行中（busy）会话 id 注册表：AIAgentViewModel 在启动/结束 AI 任务时登记与注销，
 * 插件侧（PluginHostApiHandler）据此查询 `session.status` 与拒绝向运行中会话重复 prompt。
 *
 * 与 SubAgentEventBus.activeSubSessionIds 分工：后者只跟踪子代理（TaskTool 并发上限用），
 * 本注册表覆盖普通会话与子会话（两者都走 executeAgentRequestStream）。
 *
 * 事件派发：add/remove 时向插件派发 `session.status` 事件（对齐 opencode：type 为 "busy"/"idle"）。
 * 依赖注意：用 [Provider] 懒加载 PluginHookGateway，避免与 PluginManager 构成 Hilt 循环依赖。
 */
@Singleton
class SessionActivityRegistry @Inject constructor(
    private val pluginGatewayProvider: Provider<PluginHookGateway>
) {
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    fun add(sessionId: String) {
        _running.value = _running.value + sessionId
        pluginGatewayProvider.get().notifyEvent("session.status", buildJsonObject {
            put("sessionID", sessionId)
            put("type", "busy")
        })
    }

    fun remove(sessionId: String) {
        _running.value = _running.value - sessionId
        pluginGatewayProvider.get().notifyEvent("session.status", buildJsonObject {
            put("sessionID", sessionId)
            put("type", "idle")
        })
    }

    fun isRunning(sessionId: String): Boolean = sessionId in _running.value

    fun clear() {
        _running.value = emptySet()
    }
}