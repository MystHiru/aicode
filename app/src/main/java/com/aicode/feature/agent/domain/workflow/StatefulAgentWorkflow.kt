package com.aicode.feature.agent.domain.workflow

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.PermissionScope
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.agent.domain.tool.parseToolResult
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.GeminiAdapter
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.agent.domain.plugin.PluginHookGateway
import com.aicode.feature.agent.domain.plugin.hostapi.MessageMapper
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import android.os.SystemClock
import javax.inject.Inject

/** AgentMessage 的 JSON 序列化实例（消息转换 hook 用）。 */
private val AgentMessageJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 普通 Kotlin 值 → JsonElement（工具 Schema Map → JSON，供 tool.definition hook 传给插件）。 */
private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(
        value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) }
    )
    is Iterable<*> -> JsonArray(value.map { anyToJsonElement(it) })
    else -> JsonPrimitive(value.toString())
}

/** JsonElement → 普通 Kotlin 值（插件改写的 JSON Schema 转回 Map）。 */
private fun jsonToAny(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.booleanOrNull != null -> element.booleanOrNull
        element.longOrNull != null -> element.longOrNull
        element.doubleOrNull != null -> element.doubleOrNull
        else -> element.content
    }
    is JsonArray -> element.map { jsonToAny(it) }
    is JsonObject -> element.mapValues { (_, v) -> jsonToAny(v) }
}

/**
 * 阶段三重构 (完全版)：基于不可变状态 (Immutable State) 与 MVI 架构的 Agent 工作流引擎。
 * 通过定义明确的 AgentSessionState, AgentAction 与 AgentSideEffect，
 * 采用 Reducer 来进行状态扭转，将纯函数的业务逻辑与带有副作用的外部环境操作完全解耦。
 */
class StatefulAgentWorkflow @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val promptProvider: SystemPromptProvider,
    private val permissionManager: ToolPermissionManager,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val contextCompactor: ContextCompactor,
    private val planApprovalManager: PlanApprovalManager,
    private val toolOutputStore: ToolOutputStore,
    private val modelMetadataService: ModelMetadataService,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val pluginManager: PluginHookGateway
) : AgentWorkflow {

    private companion object {
        const val TAG = "StatefulAgentWorkflow"
        const val LIVE_TAIL_CHARS = 4_000
        const val PROGRESS_INTERVAL_MS = 250L
        const val USER_REJECTED_CODE = "USER_REJECTED"
        const val TITLE_GENERATOR_FILE = "agent/title-generator.md"
        const val TITLE_MAX_CHARS = 50
        /** 模式提醒提示词：复用 prompts 目录文件（用户可自定义覆盖），切换时随消息注入而非进 system。 */
        const val MODE_REMINDER_PLAN_FILE = "80-plan-mode.md"
        const val MODE_REMINDER_AUTO_FILE = "81-auto-mode.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }

    /** 不可变状态树 */
    data class AgentSessionState(
        val messages: List<AgentMessage> = emptyList(),
        val iterations: Int = 0,
        val isFinished: Boolean = false,
        val error: String? = null,
        /** 本批模型返回的 toolCalls（原始顺序，用于最后按序组装 tool 响应） */
        val batchToolCalls: List<ToolCall> = emptyList(),
        /** 待请求权限的 toolCall（逐个弹窗收集） */
        val pendingPermissionCalls: List<ToolCall> = emptyList(),
        /** 已批准、待并行执行的 toolCall */
        val approvedToolCalls: List<ToolCall> = emptyList(),
        /** 被策略/系统拒绝（非用户拒绝）的 tool 结果，key = toolCall.id */
        val rejectedToolResults: Map<String, ToolBatchResult> = emptyMap()
    )

    /** 改变状态的动作 (Action) */
    sealed interface AgentAction {
        data class InitRequest(val initialMessages: List<AgentMessage>) : AgentAction
        data class LlmResponse(val response: AIResponse) : AgentAction
        data class LlmError(val error: String) : AgentAction
        data class PermissionEvaluated(
            val toolCall: ToolCall,
            val approved: Boolean,
            val argsPreview: String,
            val denyReason: String = "用户拒绝执行该工具",
            val errorCode: String = "USER_REJECTED"
        ) : AgentAction
        data class ToolBatchFinished(
            val results: List<ToolBatchResult>
        ) : AgentAction
    }

    private data class PermissionCheckResult(
        val approved: Boolean,
        val denyReason: String = "用户拒绝执行该工具",
        val errorCode: String = "USER_REJECTED"
    )

    private data class ToolRunResult(
        val raw: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<com.aicode.feature.agent.presentation.AgentAttachment> = emptyList(),
        val images: List<com.aicode.feature.agent.domain.model.AgentImage> = emptyList()
    )

    /** 批量工具执行结果：携带 toolCall 元信息，供最后按原始顺序组装 ToolResultMessage。 */
    data class ToolBatchResult(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<com.aicode.feature.agent.presentation.AgentAttachment> = emptyList(),
        val images: List<com.aicode.feature.agent.domain.model.AgentImage> = emptyList()
    )

    /** 需要在外部环境中执行的副作用 (SideEffect) */
    sealed interface AgentSideEffect {
        object CallLlm : AgentSideEffect
        data class RequestPermission(val toolCall: ToolCall) : AgentSideEffect
        /** 批量并行执行已批准的工具；传入空列表表示本批无工具可执行，直接进入收尾。 */
        data class ExecuteToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
        /** 整批取消（用户拒绝批次中某个调用）：补发已启动工具的完成事件，清理 UI「执行中」状态。 */
        data class CancelToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
    }

    private suspend fun getEffectiveProvider(sessionId: String?): AIProvider {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 提供商，请到设置中添加并选择一个")
        if (config.apiKey.isBlank() && !pluginManager.hasPluginAuth(config.id)) {
            throw IllegalStateException("「${config.name}」未填写 API Key")
        }
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        return createStandaloneProvider(config, sessionId)
    }

    /**
     * 按 id 解析 provider 配置：优先 Room 记录，查不到时回退插件 auth 虚拟 provider（id=插件 auth.provider）。
     */
    private suspend fun resolveProviderConfigById(providerId: String): AIProviderConfig? =
        aiProviderRepository.getProviderById(providerId)
            ?: pluginManager.pluginProviders().firstOrNull { it.id == providerId }

    /**
     * 解析当前生效的 provider 配置：优先用 session 绑定的 providerId/model，回退全局 active provider。
     * session 绑定的 provider 不存在或已禁用时回退全局，保证老会话与异常数据不中断。
     */
    private suspend fun resolveProviderConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = resolveProviderConfigById(boundProviderId)
                if (config != null && config.isEnabled && (config.apiKey.isNotBlank() || pluginManager.hasPluginAuth(config.id))) {
                    return if (!boundModel.isNullOrBlank()) config.copy(selectedModel = boundModel) else config
                }
            }
        }
        // 回退：新会话默认模型（主页空会话中选择后记忆）；未设置则返回 null，由调用方报错引导。
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = resolveProviderConfigById(defaultProviderId)
            if (config != null && config.isEnabled && (config.apiKey.isNotBlank() || pluginManager.hasPluginAuth(config.id))) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 提供商，请到设置中添加并选择一个")
        if (config.apiKey.isBlank() && !pluginManager.hasPluginAuth(config.id)) {
            throw IllegalStateException("「${config.name}」未填写 API Key")
        }
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        val provider = createStandaloneProvider(config, sessionId)
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__manual_compress__")
        if (history.size <= 2) return false
        val compactionProvider = resolveCompactionFallbackProvider(sessionId) ?: provider
        val compacted = contextCompactor.compactIfNeeded(history, compactionProvider, sessionId, force = true, onEvent = onEvent)
        return compacted.size != history.size
    }

    /**
     * 根据 [config] 创建一个全新的、独立的 [AIProvider] 实例。
     * 用于上下文压缩等独立请求场景，完全不占用或修改主对话所用的 Provider 单例。
     * 同时把提供商级 LLM 缓存开关（Anthropic 断点 / OpenAI cache key）应用到实例。
     */
    private fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
                it.pluginManager = pluginManager
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi).also {
                it.pluginManager = pluginManager
            }
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
                it.pluginManager = pluginManager
            }
        }
        provider.apiKey = config.apiKey
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.userAgent = config.userAgent
        return provider
    }

    /** 核心 Reducer，接收旧状态与 Action，返回新状态以及触发的副作用列表 (纯函数) */
    private fun reduce(
        state: AgentSessionState,
        action: AgentAction
    ): Pair<AgentSessionState, List<AgentSideEffect>> {
        var newState = state
        val effects = mutableListOf<AgentSideEffect>()

        when (action) {
            is AgentAction.InitRequest -> {
                newState = state.copy(messages = action.initialMessages)
                effects.add(AgentSideEffect.CallLlm)
            }
            is AgentAction.LlmResponse -> {
                val assistantMsg = AgentMessage.AssistantMessage(
                    content = action.response.content,
                    toolCalls = action.response.toolCalls,
                    reasoning = action.response.reasoning ?: "",
                    signature = action.response.signature ?: ""
                )
                newState = state.copy(
                    messages = state.messages + assistantMsg,
                    iterations = state.iterations + 1
                )
                
                if (action.response.toolCalls.isEmpty()) {
                    if (action.response.isTruncated) {
                        newState = newState.copy(
                            messages = newState.messages + AgentMessage.UserMessage(content = "你的回复因长度限制被截断了，请从截断处继续。")
                        )
                        effects.add(AgentSideEffect.CallLlm)
                    } else {
                        newState = newState.copy(isFinished = true)
                    }
                } else {
                    // 本批多个 tool_call：全部进入待权限队列，逐个弹窗收集批准；
                    // 全部批准后才进入并行执行阶段（见 PermissionEvaluated / ToolBatchFinished）。
                    val toolCalls = action.response.toolCalls.toList()
                    newState = newState.copy(
                        batchToolCalls = toolCalls,
                        pendingPermissionCalls = toolCalls,
                        approvedToolCalls = emptyList(),
                        rejectedToolResults = emptyMap()
                    )
                    effects.add(AgentSideEffect.RequestPermission(toolCalls.first()))
                }
            }
            is AgentAction.LlmError -> {
                newState = state.copy(isFinished = true, error = action.error)
            }
            is AgentAction.PermissionEvaluated -> {
                if (action.approved) {
                    // 批准：当前 toolCall 移入已批准集合；若还有待请求权限的则继续弹窗，否则开始并行执行。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    val approved = newState.approvedToolCalls + action.toolCall
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        approvedToolCalls = approved
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(approved))
                    }
                } else {
                    val rawResult = ToolResult.Error(action.denyReason, action.errorCode).toTransportString()
                    if (action.errorCode == USER_REJECTED_CODE) {
                        // 模型一次可能返回多个 tool_calls。用户拒绝批次中任意一个 → 整批取消：
                        // 按 batchToolCalls 原始顺序为所有调用补上 tool 响应（不重复不遗漏），
                        // 否则 assistant(toolCalls=N) 后只有部分 tool 消息，OpenAI 会报 400
                        // "insufficient tool messages following tool_calls"。
                        val cancelled = newState.batchToolCalls.map { call ->
                            AgentMessage.ToolResultMessage(
                                id = call.id,
                                toolName = call.name,
                                result = ToolResult.Error(
                                    "用户拒绝了本轮工具调用，该调用未执行。",
                                    USER_REJECTED_CODE
                                ).toTransportString()
                            )
                        }
                        newState = state.copy(
                            messages = state.messages + cancelled,
                            batchToolCalls = emptyList(),
                            pendingPermissionCalls = emptyList(),
                            approvedToolCalls = emptyList(),
                            isFinished = true
                        )
                        // 已批准未执行（已收到 ToolCallStarted）的工具需补发完成事件，
                        // 否则 UI 与落库消息会一直停留在「执行中」。
                        if (state.approvedToolCalls.isNotEmpty()) {
                            effects.add(AgentSideEffect.CancelToolBatch(state.approvedToolCalls))
                        }
                        return newState to effects
                    }
                    // 策略/系统拒绝（如 PLAN 模式禁止执行）：记录拒绝结果，继续收集后续权限。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        rejectedToolResults = newState.rejectedToolResults + (
                            action.toolCall.id to ToolBatchResult(
                                id = action.toolCall.id,
                                toolName = action.toolCall.name,
                                result = rawResult,
                                isError = true
                            )
                        )
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(newState.approvedToolCalls))
                    }
                }
            }
            is AgentAction.ToolBatchFinished -> {
                // 本批工具全部执行完，按 batchToolCalls 原始顺序组装 tool 响应：
                // 优先取策略拒绝结果，其次取并行执行结果，保证与 assistant(toolCalls) 顺序一致。
                val resultsById = action.results.associateBy { it.id }
                val appendedMessages = mutableListOf<AgentMessage>()
                newState.batchToolCalls.forEach { call ->
                    val batchResult = newState.rejectedToolResults[call.id] ?: resultsById[call.id] ?: return@forEach
                    appendedMessages.add(
                        AgentMessage.ToolResultMessage(
                            id = batchResult.id,
                            toolName = batchResult.toolName,
                            result = batchResult.result,
                            images = batchResult.images
                        )
                    )
                }
                newState = state.copy(
                    messages = state.messages + appendedMessages,
                    batchToolCalls = emptyList(),
                    pendingPermissionCalls = emptyList(),
                    approvedToolCalls = emptyList(),
                    rejectedToolResults = emptyMap()
                )
                effects.add(AgentSideEffect.CallLlm)
            }
        }
        
        return Pair(newState, effects)
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        var currentContext = context
        var state = AgentSessionState()
        var currentTools = tools
        val actionQueue = ArrayDeque<AgentAction>()
        // 模式提醒随最新用户消息注入（不进 system，避免切换时 system 前缀变化打断缓存）。
        val modeReminder = buildModeReminder(currentContext.mode)
        actionQueue.addLast(
            AgentAction.InitRequest(
                currentContext.history + AgentMessage.UserMessage(
                    content = if (modeReminder == null) userRequest else "$userRequest\n\n$modeReminder",
                    images = currentContext.inputImages
                )
            )
        )

        val systemPrompt = promptProvider.build(currentContext)
        val aiProvider = getEffectiveProvider(currentContext.sessionId)
        // 系统提示词转换 hook：插件可向 system 注入额外上下文（⚠️ 修改会打断隐式前缀缓存）。
        val effectiveSystemPrompt = applySystemTransform(systemPrompt, currentContext.sessionId, aiProvider.model)
        // 压缩失败后本轮（本次用户请求内）不再重复尝试压缩，避免每次 LLM 调用都白试一次。
        var compactionAttemptFailed = false

        while (!state.isFinished && actionQueue.isNotEmpty()) {
            val action = actionQueue.removeFirst()
            val (newState, effects) = reduce(state, action)
            state = newState

            for (effect in effects) {
                when (effect) {
                    is AgentSideEffect.CallLlm -> {
                        val providerInUse = aiProvider
                        // 压缩轮：若配置了压缩专用模型，使用独立压缩模型压缩
                        val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: providerInUse
                        var compactedMessages = state.messages
                        if (!compactionAttemptFailed) {
                            val sessionLastInputTokens = currentContext.sessionId?.let { sessionUseCase.getSessionById(it)?.lastInputTokens } ?: 0
                            compactedMessages = contextCompactor.compactIfNeeded(state.messages, compactionProvider, context.sessionId, lastInputTokens = sessionLastInputTokens, windowProvider = aiProvider) { event ->
                                if (event is AgentEvent.CompactionFailed) compactionAttemptFailed = true
                                send(event)
                            }
                            if (compactedMessages !== state.messages) {
                                state = state.copy(messages = compactedMessages)
                            }
                        }

                        val acc = StringBuilder()
                        val reasoningAcc = StringBuilder()
                        var finalResponse: AIResponse? = null

                        // 调用统计埋点：记录请求发出/首字/结束时刻与 usage，失败与取消同样留痕。
                        val callStartElapsed = SystemClock.elapsedRealtime()
                        val callStartWall = System.currentTimeMillis()
                        val callKind = "chat"
                        var ttfbElapsed: Long? = null
                        var callError: String? = null
                        var callCompleted = false

                        try {
                            // 发送前按实际模型的视觉能力处理图片（同 execute 路径）。
                            val supportsVision = activeModelSupportsVision(currentContext.sessionId)
                            val messagesToSend = sanitizeImagesForModel(compactedMessages, supportsVision)
                            // 历史消息转换 hook：插件可裁剪、脱敏消息。
                            val effectiveMessages = applyMessagesTransform(messagesToSend, currentContext.sessionId)
                            // 工具定义转换 hook：插件可改写工具描述与参数 Schema（发给模型前）。
                            val definedTools = applyToolDefinition(currentTools)
                            providerInUse.completeStream(effectiveSystemPrompt, effectiveMessages, definedTools, currentContext.reasoningEffort).collect { chunk ->
                                when (chunk) {
                                    is AIStreamChunk.TextDelta -> {
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        acc.append(chunk.text)
                                        send(AgentEvent.AssistantDelta(acc.toString()))
                                    }
                                    is AIStreamChunk.ReasoningDelta -> {
                                        // 思考内容也算首字（推理模型先吐思考再吐正文）
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        reasoningAcc.append(chunk.text)
                                        send(AgentEvent.ReasoningDelta(reasoningAcc.toString()))
                                    }
                                    is AIStreamChunk.Retrying -> {
                                        acc.setLength(0)
                                        reasoningAcc.setLength(0)
                                        send(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries, chunk.error))
                                    }
                                    is AIStreamChunk.Final -> {
                                        // 纯工具调用轮没有文本/思考增量，Final 是首个内容事件，兜底记为 TTFB
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        finalResponse = chunk.response
                                    }
                                }
                            }
                            val aiResponse = finalResponse ?: AIResponse(content = acc.toString())
                            callCompleted = true
                            // 将本轮 reasoning 附加到 AIResponse，以便 reduce 时存入 AssistantMessage 并在下一轮回传
                            val responseWithReasoning = if (reasoningAcc.isNotEmpty()) {
                                aiResponse.copy(reasoning = reasoningAcc.toString())
                            } else aiResponse

                            if (aiResponse.content.isNotBlank() || aiResponse.toolCalls.isNotEmpty()) {
                                send(AgentEvent.AssistantText(aiResponse.content, aiResponse.toolCalls, reasoningAcc.toString(), aiResponse.signature ?: "", aiResponse.inputTokens, aiResponse.outputTokens, aiResponse.cachedInputTokens))
                            }
                            actionQueue.addLast(AgentAction.LlmResponse(responseWithReasoning))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val partial = acc.toString()
                            val reasoning = reasoningAcc.toString()
                            // 流式被中断时也要落库已收到的思考：否则下方 finally 会清空流式思考气泡，
                            // 而落库的接力消息又没产生，表现为「思考显示后凭空消失且无报错」。
                            // 有正文或有思考其一即落库；两者皆空则不写空消息。
                            if (partial.isNotEmpty() || reasoning.isNotBlank()) {
                                send(AgentEvent.AssistantText(partial, emptyList(), reasoning))
                            }
                            actionQueue.addLast(AgentAction.LlmError("LLM 调用失败: ${e.message}"))
                            callError = e.message ?: e.javaClass.simpleName
                        } finally {
                            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                            val usage = finalResponse
                            runCatching {
                                llmCallRecordDao.insert(
                                    LlmCallRecordEntity(
                                        sessionId = currentContext.sessionId,
                                        providerId = providerInUse.providerId.ifBlank { null },
                                        model = providerInUse.model,
                                        reasoningEffort = currentContext.reasoningEffort,
                                        kind = callKind,
                                        inputTokens = usage?.inputTokens ?: 0,
                                        outputTokens = usage?.outputTokens ?: 0,
                                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                                        ttfbMillis = ttfbElapsed?.toInt(),
                                        durationMillis = durationMillis,
                                        status = when {
                                            callCompleted -> "success"
                                            callError != null -> "error"
                                            else -> "cancelled"
                                        },
                                        errorMessage = callError,
                                        stopReason = usage?.stopReason,
                                        createdAt = callStartWall
                                    )
                                )
                            }
                        }
                    }
                    is AgentSideEffect.RequestPermission -> {
                        val tool = toolRegistry.getTool(effect.toolCall.name)
                        val argsPreview = JsonObject(effect.toolCall.arguments).toString().take(500)
                        val checkResult = requestPermissionIfNeeded(tool, effect.toolCall.id, effect.toolCall.arguments, argsPreview, currentContext.mode, currentContext.sessionId)

                        if (!checkResult.approved) {
                            val rawResult = ToolResult.Error(checkResult.denyReason, checkResult.errorCode).toTransportString()
                            send(AgentEvent.ToolCallFinished(effect.toolCall.id, effect.toolCall.name, rawResult, true, argsPreview))
                        } else {
                            send(AgentEvent.ToolCallStarted(effect.toolCall.id, effect.toolCall.name, argsPreview))
                        }
                        actionQueue.addLast(AgentAction.PermissionEvaluated(effect.toolCall, checkResult.approved, argsPreview, checkResult.denyReason, checkResult.errorCode))
                    }
                    is AgentSideEffect.CancelToolBatch -> {
                        // 整批取消：已批准未执行的工具补发完成事件（内容为未执行），
                        // 让 ViewModel 清理 runningTool 并 REPLACE 掉「执行中」占位消息。
                        effect.toolCalls.forEach { toolCall ->
                            send(
                                AgentEvent.ToolCallFinished(
                                    id = toolCall.id,
                                    toolName = toolCall.name,
                                    result = ToolResult.Error(
                                        "用户拒绝了本轮工具调用，该调用未执行。",
                                        USER_REJECTED_CODE
                                    ).toTransportString(),
                                    isError = true
                                )
                            )
                        }
                    }
                    is AgentSideEffect.ExecuteToolBatch -> {
                        // 并行执行本批已批准的工具。先统一记录 checkpoint（editFile/writeFile 修改前快照），
                        // 再并行执行；mode 切换检查在结果收集后于主协程串行处理（planApproval 单例）。
                        val toolCalls = effect.toolCalls
                        toolCalls.forEach { toolCall ->
                            if (toolCall.name == "editFile" || toolCall.name == "writeFile") {
                                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                                    currentContext.sessionId?.let { sid ->
                                        checkpointManager.beforeFileModified(sid, path)
                                    }
                                }
                            }
                        }

                        val runResults = if (toolCalls.isEmpty()) {
                            emptyList()
                        } else {
                            coroutineScope {
                                toolCalls.map { toolCall ->
                                    async {
                                        val tool = toolRegistry.getTool(toolCall.name)
                                        if (tool is StreamingAgentTool) {
                                            runToolStream(tool, toolCall, currentContext) { send(it) }
                                        } else {
                                            runToolSync(tool, toolCall, currentContext)
                                        }
                                    }
                                }.awaitAll()
                            }
                        }

                        // 串行处理 mode 切换并组装批量结果。
                        val batchResults = mutableListOf<ToolBatchResult>()
                        toolCalls.forEachIndexed { index, toolCall ->
                            val runResult = runResults.getOrNull(index)
                                ?: ToolRunResult(ToolResult.Error("工具未执行", "TOOL_NOT_EXECUTED").toTransportString(), true)
                            var rawResult = runResult.raw
                            var isError = runResult.isError
                            val (newCtx, updated) = checkAndUpdateMode(toolCall, isError, currentContext)
                            if (updated) {
                                val reason = (toolCall.arguments["reason"] as? JsonPrimitive)?.content?.trim()
                                    ?: toolCall.arguments["reason"]?.toString()?.replace("\"", "")?.trim()
                                    ?: ""
                                send(AgentEvent.ModeChanged(newCtx.mode, reason))

                                // PLAN→BUILD 时挂起 workflow，等待用户在计划审查面板批准后才继续
                                if (newCtx.mode == AgentMode.BUILD) {
                                    val choice = planApprovalManager.awaitApproval(reason, currentContext.sessionId)
                                    if (choice == PlanApprovalChoice.APPROVE) {
                                        currentContext = newCtx
                                        // system 与 mode 已解耦（SystemPromptProvider 不再注入模式提示词），
                                        // 切换不重建 systemPrompt，避免 system 前缀变化打断缓存；模式状态通过工具结果与下轮消息提醒告知。
                                        rawResult += buildModeSwitchNotice(AgentMode.BUILD)
                                    } else {
                                        // 用户选择继续反馈，回滚到 PLAN 模式，修正工具结果让 AI 知道切换被取消
                                        currentContext = currentContext.copy(mode = AgentMode.PLAN)
                                        rawResult = ToolResult.Error("用户拒绝了模式切换请求，请继续在 PLAN 模式下完善方案，待用户认可后再次申请切换。", "MODE_SWITCH_REJECTED").toTransportString()
                                        isError = true
                                    }
                                } else {
                                    currentContext = newCtx
                                    rawResult += buildModeSwitchNotice(newCtx.mode)
                                }
                            }
                            batchResults.add(ToolBatchResult(toolCall.id, toolCall.name, rawResult, isError, runResult.attachments, runResult.images))
                        }

                        // 逐个推送完成事件（保持与 batchToolCalls 一致顺序），并进入收尾。
                        batchResults.forEach { br ->
                            send(AgentEvent.ToolCallFinished(br.id, br.toolName, br.result, br.isError, attachments = br.attachments))
                        }
                        actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
                    }
                }
            }
        }
        
        state.error?.let { send(AgentEvent.Failed(it)) }
        send(AgentEvent.Completed)
    }

    /** 系统提示词转换 hook：插件可向 system 注入额外上下文（⚠️ 修改会打断隐式前缀缓存）。 */
    private suspend fun applySystemTransform(system: String, sessionId: String?, model: String): String {
        val result = pluginManager.dispatchHook(
            "experimental.chat.system.transform",
            buildJsonObject { put("sessionID", sessionId ?: ""); put("model", model) },
            buildJsonObject { putJsonArray("system") { add(system) } }
        )
        val parts = (result.output["system"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return if (parts.isEmpty()) system else parts.joinToString("\n\n")
    }

    /** 历史消息转换 hook：插件可裁剪、脱敏消息。
     *  对齐 opencode：output 为 `{info: Message, parts: Part[]}[]`，复用 MessageMapper 反向转换回 AiCode AgentMessage。 */
    private suspend fun applyMessagesTransform(messages: List<AgentMessage>, sessionId: String?): List<AgentMessage> {
        // 把 AiCode AgentMessage 列表转换为 opencode `{info, parts}[]` 形状（对齐 MessageMapper 语义）：
        // assistant 携带 text/reasoning/tool 声明 part，tool 结果作为独立 role=tool 消息（tool part）。
        val openCodeMessages = buildJsonArray {
            messages.forEach { msg ->
                when (msg) {
                    is AgentMessage.UserMessage -> add(buildJsonObject {
                        put("info", buildJsonObject {
                            put("id", msg.id)
                            put("sessionID", sessionId ?: "")
                            put("role", "user")
                        })
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", msg.content)
                            })
                        }
                    })
                    is AgentMessage.AssistantMessage -> add(buildJsonObject {
                        put("info", buildJsonObject {
                            put("id", msg.id)
                            put("sessionID", sessionId ?: "")
                            put("role", "assistant")
                            if (msg.signature.isNotBlank()) put("signature", msg.signature)
                        })
                        putJsonArray("parts") {
                            if (msg.content.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                })
                            }
                            if (msg.reasoning.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    put("text", msg.reasoning)
                                })
                            }
                            msg.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("type", "tool")
                                    put("tool", tc.name)
                                    put("callID", tc.id)
                                    putJsonObject("state") {
                                        put("status", "running")
                                        put("input", JsonObject(tc.arguments))
                                    }
                                })
                            }
                        }
                    })
                    is AgentMessage.ToolResultMessage -> add(buildJsonObject {
                        put("info", buildJsonObject {
                            put("id", msg.id)
                            put("sessionID", sessionId ?: "")
                            put("role", "tool")
                        })
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("type", "tool")
                                put("tool", msg.toolName)
                                put("callID", msg.id)
                                putJsonObject("state") {
                                    put("status", "completed")
                                    put("output", msg.result)
                                }
                            })
                        }
                    })
                }
            }
        }
        val result = pluginManager.dispatchHook(
            "experimental.chat.messages.transform",
            buildJsonObject { put("sessionID", sessionId ?: "") },
            buildJsonObject { put("messages", openCodeMessages) }
        )
        val arr = (result.output["messages"] as? JsonArray) ?: return messages
        // 插件未修改消息（无插件/未实现该 hook 时输出与输入一致）：直接返回原始消息，
        // 避免 JSON 往返剥掉 tool_calls / reasoning / tool 结果，导致模型看不到工具结果而死循环重试。
        if (arr === openCodeMessages || arr.toString() == openCodeMessages.toString()) return messages
        // 反向转换：从 opencode `{info, parts}[]` 完整还原（text/reasoning/toolCalls/tool 结果）。
        val transformed = MessageMapper.fromOpenCodeMessages(arr)
        return if (transformed.isEmpty()) messages else transformed
    }

    /** 工具定义转换 hook：插件可改写工具的描述与参数 Schema（发给模型前）。 */
    private suspend fun applyToolDefinition(tools: List<AgentTool>): List<AgentTool> {
        return tools.map { tool ->
            val result = pluginManager.dispatchHook(
                "tool.definition",
                buildJsonObject { put("toolID", tool.name) },
                buildJsonObject {
                    put("description", tool.description)
                    put("parameters", anyToJsonElement(tool.toJsonSchema()))
                }
            )
            val newDesc = (result.output["description"] as? JsonPrimitive)?.contentOrNull
            val newParams = (result.output["parameters"] as? JsonObject)?.takeIf { it.isNotEmpty() }
            if (newDesc == null && newParams == null) return@map tool
            object : AgentTool() {
                override val name: String = tool.name
                override val description: String = newDesc ?: tool.description
                override val parameters: Map<String, com.aicode.feature.agent.domain.tool.ToolParameter> = tool.parameters
                override val permissionPolicy: ToolPermissionPolicy = tool.permissionPolicy
                override val capabilities: Set<ToolCapability> = tool.capabilities
                override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> =
                    tool.effectiveCapabilities(args)
                override suspend fun execute(args: Map<String, JsonElement>): ToolResult = tool.execute(args)
                override suspend fun executeWithContext(
                    args: Map<String, JsonElement>,
                    context: com.aicode.feature.agent.domain.model.AgentContext
                ): ToolResult = tool.executeWithContext(args, context)
                override fun buildPermissionRequest(
                    callId: String,
                    args: Map<String, JsonElement>,
                    argsPreview: String
                ): com.aicode.feature.agent.domain.tool.PendingToolPermission =
                    tool.buildPermissionRequest(callId, args, argsPreview)
                override fun toJsonSchema(): Map<String, Any> = newParams?.let { p ->
                    @Suppress("UNCHECKED_CAST")
                    (jsonToAny(p) as? Map<String, Any>) ?: tool.toJsonSchema()
                } ?: tool.toJsonSchema()
            }
        }
    }

    private suspend fun runToolSync(tool: AgentTool?, toolCall: ToolCall, context: AgentContext): ToolRunResult {
        val name = toolCall.name
        if (tool == null) {
            return ToolRunResult(ToolResult.Error("工具 $name 不存在", "TOOL_NOT_FOUND").toTransportString(), true)
        }
        // 工具执行前拦截 hook：插件可改写参数或抛错阻止（errors 非空则按执行失败处理）。
        val beforeResult = pluginManager.dispatchHook(
            "tool.execute.before",
            buildJsonObject { put("tool", name); put("sessionID", context.sessionId ?: ""); put("callID", toolCall.id) },
            buildJsonObject { put("args", JsonObject(toolCall.arguments)) }
        )
        if (beforeResult.errors.isNotEmpty()) {
            val msg = beforeResult.errors.joinToString("; ") { "[${it.plugin}] ${it.error}" }
            return ToolRunResult(ToolResult.Error("工具执行被插件拦截: $msg", "PLUGIN_BLOCKED").toTransportString(), true)
        }
        val effectiveArgs = (beforeResult.output["args"] as? JsonObject)?.toMap() ?: toolCall.arguments
        return try {
            val result = tool.executeWithContext(effectiveArgs, context)
            // 工具执行后处理 hook：插件可改写输出。
            val afterResult = pluginManager.dispatchHook(
                "tool.execute.after",
                buildJsonObject {
                    put("tool", name)
                    put("sessionID", context.sessionId ?: "")
                    put("callID", toolCall.id)
                    put("args", JsonObject(effectiveArgs))
                },
                buildJsonObject {
                    put("title", name)
                    put("output", result.toTransportString())
                    put("metadata", buildJsonObject { })
                }
            )
            val processedResult = (afterResult.output["output"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.let { parseToolResult(it) }
                ?: result
            val attachments = if (name == "sendFile") extractAttachments(processedResult) else emptyList()
            val images = if (processedResult is ToolResult.Success) processedResult.images else emptyList()
            val transportResult = if (attachments.isNotEmpty()) stripAttachments(processedResult) else processedResult
            val processed = toolOutputStore.process(name, toolCall.id, transportResult)
            ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, attachments, images)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveProviderConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    /**
     * 发送前按模型视觉能力处理消息中的图片：
     * - 支持 vision：原样返回。
     * - 不支持：剥离所有图片（仅影响本次发送，不动持久化数据），历史/输入中的图片不会原样发给
     *   非多模态模型导致请求失败；切回多模态模型后图片上下文仍可正常使用。
     */
    private fun sanitizeImagesForModel(
        messages: List<AgentMessage>,
        supportsVision: Boolean
    ): List<AgentMessage> {
        if (supportsVision) return messages
        return messages.map { msg ->
            when (msg) {
                is AgentMessage.UserMessage ->
                    if (msg.images.isEmpty()) msg
                    else msg.copy(images = emptyList(), content = msg.content.ifBlank { "（图片已省略：当前模型不支持图片输入）" })
                is AgentMessage.ToolResultMessage ->
                    if (msg.images.isEmpty()) msg else msg.copy(images = emptyList())
                is AgentMessage.AssistantMessage -> msg
            }
        }
    }

    /**
     * 压缩轮专用 provider 解析。若用户配置了压缩专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveCompactionFallbackProvider(sessionId: String? = null): AIProvider? {
        val providerId = compactionModelSettingsRepository.getCompactionProviderId().trim()
        val model = compactionModelSettingsRepository.getCompactionModel().trim()
        if (providerId.isNotEmpty() && model.isNotEmpty()) {
            val config = resolveProviderConfigById(providerId) ?: return null
            if (!config.isEnabled || (config.apiKey.isBlank() && !pluginManager.hasPluginAuth(config.id))) return null
            return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
        }
        // 用户未配置压缩专用模型：查询插件 small_model 建议（无建议则沿用聊天模型）。
        return resolvePluginSmallModelProvider(sessionId)
    }

    /**
     * 标题生成专用 provider 解析。若用户配置了标题总结专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveTitleFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = titleModelSettingsRepository.getTitleProviderId().trim()
        val model = titleModelSettingsRepository.getTitleModel().trim()
        if (providerId.isNotEmpty() && model.isNotEmpty()) {
            val config = resolveProviderConfigById(providerId) ?: return null
            if (!config.isEnabled || (config.apiKey.isBlank() && !pluginManager.hasPluginAuth(config.id))) return null
            return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
        }
        // 用户未配置标题专用模型：查询插件 small_model 建议（无建议则沿用聊天模型）。
        return resolvePluginSmallModelProvider(sessionId)
    }

    /**
     * 轻量任务小模型建议（experimental.provider.small_model）：用户未配置压缩/标题专用模型时，
     * 向插件查询当前 provider 的小模型建议，用建议模型构建独立 provider；无建议则返回 null（沿用聊天模型）。
     */
    private suspend fun resolvePluginSmallModelProvider(sessionId: String? = null): AIProvider? {
        val config = resolveProviderConfig(sessionId) ?: return null
        if (!config.isEnabled || (config.apiKey.isBlank() && !pluginManager.hasPluginAuth(config.id))) return null
        val results = pluginManager.dispatchReturnHook(
            "experimental.provider.small_model",
            buildJsonObject { put("provider", config.id) }
        )
        val model = results.mapNotNull { el ->
            ((el as? JsonObject)?.get("model") as? JsonPrimitive)?.contentOrNull
        }.firstOrNull { it.isNotBlank() } ?: return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 为新建会话生成标题：默认跟随当前聊天模型，配置了标题总结专用模型则用之。
     * 提示词来自 [SystemPromptProvider] 的 `agent/title-generator.md`。
     * 生成失败或取不到标题时返回 null（调用方保留临时标题）。
     */
    override suspend fun generateTitle(sessionId: String, request: String): String? = runCatching {
        val provider = resolveTitleFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
        val prompt = promptProvider.resolvePrompt(TITLE_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val response = provider.complete(
            systemPrompt = prompt,
            messages = listOf(AgentMessage.UserMessage(content = request)),
            tools = emptyList()
        )
        response.content.trim().take(TITLE_MAX_CHARS).ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成会话标题失败", e)
    }.getOrNull()

    private suspend fun runToolStream(
        tool: StreamingAgentTool, 
        toolCall: ToolCall,
        context: AgentContext,
        onEvent: suspend (AgentEvent) -> Unit
    ): ToolRunResult {
        val live = StringBuilder()
        var lastEmitMs = 0L
        var finalResult: ToolResult? = null
        try {
            // 工具执行前拦截 hook（与 runToolSync 一致）。
            val beforeResult = pluginManager.dispatchHook(
                "tool.execute.before",
                buildJsonObject { put("tool", toolCall.name); put("sessionID", context.sessionId ?: ""); put("callID", toolCall.id) },
                buildJsonObject { put("args", JsonObject(toolCall.arguments)) }
            )
            if (beforeResult.errors.isNotEmpty()) {
                val msg = beforeResult.errors.joinToString("; ") { "[${it.plugin}] ${it.error}" }
                return ToolRunResult(ToolResult.Error("工具执行被插件拦截: $msg", "PLUGIN_BLOCKED").toTransportString(), true)
            }
            val effectiveArgs = (beforeResult.output["args"] as? JsonObject)?.toMap() ?: toolCall.arguments
            tool.executeStream(effectiveArgs, context).collect { ev ->
                when (ev) {
                    is ToolStreamEvent.Progress -> {
                        live.append(ev.chunk).append('\n')
                        if (live.length > LIVE_TAIL_CHARS) {
                            live.delete(0, live.length - LIVE_TAIL_CHARS)
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                            lastEmitMs = now
                            onEvent(AgentEvent.ToolCallProgress(toolCall.id, toolCall.name, live.toString()))
                        }
                    }
                    is ToolStreamEvent.Completed -> finalResult = ev.result
                }
            }
            val result = finalResult ?: ToolResult.Error("流式工具未返回结果", "MISSING_STREAM_RESULT")
            // 工具执行后处理 hook。
            val afterResult = pluginManager.dispatchHook(
                "tool.execute.after",
                buildJsonObject {
                    put("tool", toolCall.name)
                    put("sessionID", context.sessionId ?: "")
                    put("callID", toolCall.id)
                    put("args", JsonObject(effectiveArgs))
                },
                buildJsonObject {
                    put("title", toolCall.name)
                    put("output", result.toTransportString())
                    put("metadata", buildJsonObject { })
                }
            )
            val processedResult = (afterResult.output["output"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.let { parseToolResult(it) }
                ?: result
            val processed = toolOutputStore.process(toolCall.name, toolCall.id, processedResult)
            val images = if (processed is ToolResult.Success) processed.images else emptyList()
            return ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, images = images)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    private fun checkAndUpdateMode(toolCall: ToolCall, isError: Boolean, currentContext: AgentContext): Pair<AgentContext, Boolean> {
        if (toolCall.name == "switchMode" && !isError) {
            val targetModeStr = (toolCall.arguments["mode"] as? JsonPrimitive)?.content?.trim()?.uppercase()
                ?: toolCall.arguments["mode"]?.toString()?.replace("\"", "")?.trim()?.uppercase()
            if (targetModeStr != null) {
                runCatching { AgentMode.valueOf(targetModeStr) }.getOrNull()?.let { newMode ->
                    if (currentContext.mode != newMode) {
                        return currentContext.copy(mode = newMode) to true
                    }
                }
            }
        }
        return currentContext to false
    }

    /**
     * 当前模式提醒：随最新用户消息注入（借鉴 opencode SessionReminders 的思路）。
     * 模式提示词不进 system——一旦切换就要重建 system、打断前缀缓存；
     * 改为消息级提醒：每次用户请求拼在最新用户消息末尾，位置在消息流尾部，前缀保持稳定。
     */
    private fun buildModeReminder(mode: AgentMode): String? = when (mode) {
        AgentMode.PLAN -> promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.AUTO -> promptProvider.resolvePrompt(MODE_REMINDER_AUTO_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.BUILD -> null
    }

    /** 工具切换成功后拼进 switchMode 工具结果的模式状态通知（当轮即可见，无需等下一条用户消息）。 */
    private fun buildModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n【模式切换】计划已获用户批准，你已切换到 BUILD（构建）模式，可以开始执行计划。"
        AgentMode.AUTO -> "\n\n【模式切换】你已切换到 AUTO（自动）模式。"
    }

    /**
     * 从 sendFile 工具结果的 `files` 数组提取文件卡片元数据（含宿主本地路径，供 UI 打开文件用）。
     * 任一文件缺关键字段则整体返回空（与 sendFile 的原子语义一致）。
     */
    private fun extractAttachments(result: ToolResult): List<AgentAttachment> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyList()
        val files = data["files"] as? JsonArray ?: return emptyList()
        val attachments = files.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
            val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
            AgentAttachment(
                fileName = name,
                containerPath = path,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = obj["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                isImage = obj["is_image"]?.jsonPrimitive?.booleanOrNull ?: mimeType.startsWith("image/")
            )
        }
        return if (attachments.size == files.size) attachments else emptyList()
    }

    /** 从回传给模型的 sendFile 结果中剥离宿主本地路径（模型只应看到容器路径）。 */
    private fun stripAttachments(result: ToolResult): ToolResult {
        val success = result as? ToolResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        val strippedFiles = (data["files"] as? JsonArray)?.map { elem ->
            val obj = elem as? JsonObject ?: return@map elem
            JsonObject(obj.toMutableMap().apply { remove("local_path") })
        } ?: return result
        val strippedData = data.toMutableMap().apply {
            this["files"] = JsonArray(strippedFiles)
            this["files_attached"] = JsonPrimitive(true)
        }
        return ToolResult.Success(JsonObject(strippedData))
    }

    private suspend fun requestPermissionIfNeeded(
        tool: AgentTool?,
        callId: String,
        arguments: Map<String, kotlinx.serialization.json.JsonElement>,
        argsPreview: String,
        mode: com.aicode.feature.agent.domain.model.AgentMode,
        sessionId: String?
    ): PermissionCheckResult {
        if (tool == null) {
            return PermissionCheckResult(true)
        }

        // switchMode 从 PLAN 切到 BUILD 时，后续会有计划审查面板兜底用户决策，
        // 此处权限弹窗冗余，直接放行；BUILD→PLAN 方向无后续审查面板，仍走权限弹窗。
        if (tool.name == "switchMode" && mode == AgentMode.PLAN) {
            val targetModeStr = (arguments["mode"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
            if (targetModeStr == AgentMode.BUILD.name) {
                return PermissionCheckResult(true)
            }
        }

        // 权限拦截 hook：插件可自动化允许/拒绝（allow 跳过弹窗与策略拒绝，deny 直接拒绝）。
        val permResult = pluginManager.dispatchHook(
            "permission.ask",
            buildJsonObject {
                put("tool", tool.name)
                put("args", JsonObject(arguments))
                put("mode", mode.name)
            },
            buildJsonObject { put("status", "ask") }
        )
        when ((permResult.output["status"] as? JsonPrimitive)?.contentOrNull) {
            "allow" -> return PermissionCheckResult(true)
            "deny" -> return PermissionCheckResult(false, "插件拒绝执行该工具", "PLUGIN_DENIED")
        }

        val eval = policyEngine.evaluate(tool, tool.name, arguments, mode)
        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            val reason = eval.denyReason ?: "该工具被项目安全规则策略禁止执行"
            val code = if (mode == com.aicode.feature.agent.domain.model.AgentMode.PLAN) "PLAN_MODE_REJECTED" else "SYSTEM_DENIED"
            return PermissionCheckResult(false, reason, code)
        }

        if (tool.permissionPolicy == ToolPermissionPolicy.AUTO_APPROVE) {
            return PermissionCheckResult(true)
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> PermissionCheckResult(true)
            ToolPermissionPolicyEngine.Verdict.DENY -> PermissionCheckResult(false)
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                val request = tool.buildPermissionRequest(callId, arguments, argsPreview)
                    .copy(
                        rememberablePatterns = eval.rememberablePatterns,
                        rememberDisabledReason = eval.rememberDisabledReason
                    )
                pluginManager.notifyEvent("permission.asked", buildJsonObject {
                    put("tool", tool.name)
                    put("sessionID", sessionId ?: "")
                    put("callID", callId)
                })
                val choice = permissionManager.awaitApproval(request)
                pluginManager.notifyEvent("permission.replied", buildJsonObject {
                    put("tool", tool.name)
                    put("sessionID", sessionId ?: "")
                    put("callID", callId)
                    put("choice", choice.name.lowercase())
                })
                when (choice) {
                    PermissionChoice.REJECT -> PermissionCheckResult(false, "用户拒绝执行该工具", "USER_REJECTED")
                    PermissionChoice.ONCE -> PermissionCheckResult(true)
                    PermissionChoice.ALWAYS -> {
                        if (eval.rememberablePatterns.isNotEmpty()) {
                            policyEngine.remember(tool.name, eval.rememberablePatterns, PermissionScope.PROJECT)
                        }
                        PermissionCheckResult(true)
                    }
                }
            }
        }
    }
}
