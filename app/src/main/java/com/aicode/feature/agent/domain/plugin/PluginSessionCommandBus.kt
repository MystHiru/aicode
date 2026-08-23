package com.aicode.feature.agent.domain.plugin

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 插件 client.session 写操作命令：PluginHostApiHandler 校验后写入，AIAgentViewModel 收集后执行。 */
sealed class PluginSessionCommand {
    /** 向会话发送消息（对齐 opencode session.prompt）。 */
    data class Prompt(
        val sessionId: String,
        /** 拼接后的消息文本（parts 中仅 text 部分生效）。 */
        val text: String,
        /** true 时仅注入上下文（落库 user 消息），不触发 AI 回复。 */
        val noReply: Boolean = false,
        /** 可选模型覆盖：providerId to modelId，prompt 前更新会话绑定。 */
        val model: Pair<String, String>? = null,
        /** 工具排除（对齐 opencode prompt body.tools）：值为 false 的工具在本轮会话中不可用，null=不过滤。 */
        val tools: Map<String, Boolean>? = null
    ) : PluginSessionCommand()

    /** 删除会话（含子代理会话级联删除）。 */
    data class Delete(val sessionId: String) : PluginSessionCommand()
}

/**
 * 插件 session 命令总线（仿 SubAgentEventBus）：宿主 API 处理器与 ViewModel 之间的解耦通道。
 * PluginHostApiHandler 在同步校验（会话存在、prompt 目标未在运行）后写入，
 * AIAgentViewModel 收集后调用 executeAgentRequestStream / deleteSession 执行。
 */
@Singleton
class PluginSessionCommandBus @Inject constructor() {
    private val _commands = MutableSharedFlow<PluginSessionCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<PluginSessionCommand> = _commands.asSharedFlow()

    fun emit(command: PluginSessionCommand) {
        _commands.tryEmit(command)
    }
}