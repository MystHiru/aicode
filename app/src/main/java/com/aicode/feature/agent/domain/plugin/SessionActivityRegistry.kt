package com.aicode.feature.agent.domain.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 运行中（busy）会话 id 注册表：AIAgentViewModel 在启动/结束 AI 任务时登记与注销，
 * 插件侧（PluginHostApiHandler）据此查询 `session.status` 与拒绝向运行中会话重复 prompt。
 *
 * 与 SubAgentEventBus.activeSubSessionIds 分工：后者只跟踪子代理（TaskTool 并发上限用），
 * 本注册表覆盖普通会话与子会话（两者都走 executeAgentRequestStream）。
 */
@Singleton
class SessionActivityRegistry @Inject constructor() {
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    fun add(sessionId: String) {
        _running.value = _running.value + sessionId
    }

    fun remove(sessionId: String) {
        _running.value = _running.value - sessionId
    }

    fun isRunning(sessionId: String): Boolean = sessionId in _running.value

    fun clear() {
        _running.value = emptySet()
    }
}