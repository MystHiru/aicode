package com.aicode.feature.agent.domain.team

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 团队执行登记簿 + 运行中执行的单一真源。
 *
 * 1. 记住某个子代理会话替哪个群、以哪个成员身份干活——完成时据此把结果写回群聊。
 * 2. 维护「当前有哪些执行在跑」。这份状态放在进程级单例里，而不是群聊窗口的 ViewModel 里：
 *    ViewModel 跟着导航销毁，退出群聊再进来状态就丢了，进度条和停止按钮会凭空消失。
 *
 * 进程重启后这份内存状态为空，由群聊窗口按消息历史（带 refSessionId 的「[执行]」）重新播种。
 */
@Singleton
class TeamExecutionRegistry @Inject constructor() {

    data class Target(val teamName: String, val memberName: String, val avatar: String)

    /** 一次正在跑的执行。 */
    data class ActiveExecution(
        val subSessionId: String,
        val teamName: String,
        val memberName: String,
        val avatar: String
    )

    private val bySubSession = ConcurrentHashMap<String, Target>()

    /**
     * 运行时合成的执行者定义（团队主执行者）。不落 agents 目录——它只服务这一次执行，
     * 子会话取系统提示词与工具集时优先从这里取，取不到再回落到 agents 目录。
     */
    private val syntheticDefinitions =
        ConcurrentHashMap<String, com.aicode.feature.agent.domain.subagent.AgentDefinition>()

    private val _active = MutableStateFlow<Map<String, ActiveExecution>>(emptyMap())

    /** subSessionId → 运行中的执行。 */
    val active: StateFlow<Map<String, ActiveExecution>> = _active.asStateFlow()

    fun registerDefinition(name: String, definition: com.aicode.feature.agent.domain.subagent.AgentDefinition) {
        if (name.isBlank()) return
        syntheticDefinitions[name] = definition
    }

    fun findDefinition(name: String): com.aicode.feature.agent.domain.subagent.AgentDefinition? =
        syntheticDefinitions[name]

    fun register(subSessionId: String, teamName: String, memberName: String, avatar: String) {
        if (subSessionId.isBlank() || teamName.isBlank() || memberName.isBlank()) return
        bySubSession[subSessionId] = Target(teamName, memberName, avatar)
        _active.value = _active.value + (
            subSessionId to ActiveExecution(subSessionId, teamName, memberName, avatar)
            )
    }

    /** 执行结束（完成/失败/停止）：保留归属记录以便返工时继续用，但移出「正在执行」。 */
    fun markFinished(subSessionId: String) {
        _active.value = _active.value - subSessionId
    }

    /** 冷启动时按历史把仍在跑的补回状态（进程重启后内存为空）。 */
    fun restoreActive(subSessionId: String, teamName: String, memberName: String, avatar: String) {
        if (_active.value.containsKey(subSessionId)) return
        register(subSessionId, teamName, memberName, avatar)
    }

    fun find(subSessionId: String): Target? = bySubSession[subSessionId]

    fun activeForTeam(teamName: String): List<ActiveExecution> =
        _active.value.values.filter { it.teamName == teamName }

    fun clear(subSessionId: String) {
        bySubSession.remove(subSessionId)
        _active.value = _active.value - subSessionId
    }
}
