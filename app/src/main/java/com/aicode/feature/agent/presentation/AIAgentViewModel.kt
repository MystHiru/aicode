package com.aicode.feature.agent.presentation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.util.toUserMessage
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.plugin.PluginHookGateway
import com.aicode.feature.agent.domain.plugin.PluginSessionCommand
import com.aicode.feature.agent.domain.plugin.PluginSessionCommandBus
import com.aicode.feature.agent.domain.plugin.SessionActivityRegistry
import com.aicode.feature.agent.domain.plugin.applyChatMessage
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TAIL_LINES
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.takeTailLines
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.command.SlashCommandHandler
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.component.RewindOption
import com.aicode.feature.agent.presentation.component.formatTokenCount
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AIAgentViewModel @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val planApprovalManager: PlanApprovalManager,
    private val terminalSessionManager: TerminalSessionManager,
    private val slashCommandRegistry: SlashCommandRegistry,
    private val checkpointManager: CheckpointManager,
    private val checkpointDao: CheckpointDao,
    private val backupManager: BackupManager,
    private val mcpManager: McpManager,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val pluginManager: PluginHookGateway,
    private val subAgentEventBus: SubAgentEventBus,
    private val sessionActivityRegistry: SessionActivityRegistry,
    private val pluginSessionCommandBus: PluginSessionCommandBus,
    val fileAccess: FileAccessProvider,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SlashCommandContext {

    private val sessionJobs = mutableMapOf<String, Job>()

    /**
     * AI 忙碌期间到达的后台任务完成事件缓冲区（按会话累积）。
     * 会话空闲时到达的事件不缓存、立即发送；忙碌期间到达的缓存下来，
     * 等该会话本轮结束（finally）时合并成一条通知发送，只触发一轮 AI 回复。
     */
    private val pendingMergedNotifications = mutableMapOf<String, MutableList<TabFinishedEvent>>()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _agentStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentUIState>> = _agentStates.asStateFlow()

    val agentState: StateFlow<AgentUIState> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(AgentUIState.Idle)
            else _agentStates.map { it[id] ?: AgentUIState.Idle }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentUIState.Idle)

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        _agentStates.value = _agentStates.value + (sessionId to state)
    }

    private val _messageLimit = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val defaultLimit = 30

    private val _inputDraft = MutableStateFlow("")
    val inputDraft: StateFlow<String> = _inputDraft.asStateFlow()
    fun updateInputDraft(text: String) { _inputDraft.value = text }
    fun clearInputDraft() { _inputDraft.value = "" }

    fun loadMoreMessages() {
        val sid = _currentSessionId.value ?: return
        val currentLimit = _messageLimit.value[sid] ?: defaultLimit
        _messageLimit.value = _messageLimit.value + (sid to (currentLimit + 30))
    }

    /** 容器初始化实时进度（解压/部署/装包），AI 页底部气泡展示。 */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    private val _currentWorkspace = MutableStateFlow<String>("")
    fun setWorkspace(path: String) {
        if (path.isBlank() || _currentWorkspace.value == path) return
        _currentWorkspace.value = path
    }

    val sessions: StateFlow<List<ChatSession>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyList())
            else chatSessionDao.getRootSessionsByWorkspace(path)
                .map { list -> list.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 当前会话（锚定父会话）的子代理会话列表（侧边栏 Tab2）。当前会话变化时自动切换；
     * 若当前会话本身是子代理，则锚定到其父会话，保证进入子会话后仍能看到同一批子代理列表。 */
    val subSessions: StateFlow<List<ChatSession>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else flow {
                val entity = chatSessionDao.getById(id)
                val anchorId = entity?.parentId ?: id
                emitAll(
                    chatSessionDao.getSubSessionsByParent(anchorId)
                        .map { list -> list.map { it.toDomain() } }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 当前会话完整信息（根会话与子会话通用；null 表示尚未解析出会话）。 */
    val currentSessionState: StateFlow<ChatSession?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else chatSessionDao.getByIdFlow(id).map { it?.toDomain() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentSessionMode: StateFlow<AgentMode> = currentSessionState.map { it?.mode ?: AgentMode.BUILD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentMode.BUILD)

    /** 当前会话的思考强度（默认 MEDIUM）。 */
    val currentSessionReasoningEffort: StateFlow<ReasoningEffort> =
        currentSessionState.map { it?.reasoningEffort ?: ReasoningEffort.MEDIUM }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ReasoningEffort.MEDIUM)

    /** 当前会话绑定的 providerId/model（null 表示未绑定，回退全局 active provider）。 */
    val currentSessionProviderModel: StateFlow<Pair<String?, String?>> =
        currentSessionState.map { s -> (s?.providerId ?: "") to (s?.model ?: "") }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null as String? to null as String?)

    /**
     * 当前会话的消息状态：会话切换时自动切换到对应历史，并携带所属会话 id 与 loaded 标志，
     * 使 UI 能区分「切换/冷启动加载中」与「空会话」——避免先闪 Welcome 或上一个会话的消息再突然刷新。
     * 过滤掉「纯工具调用」的空助手行（content 为空、仅用于回放配对，不应显示为气泡）。
     */
    val messagesState: StateFlow<ChatMessagesState> = combine(
        _currentSessionId,
        _messageLimit
    ) { id, limitMap -> id to (limitMap[id] ?: defaultLimit) }
        .flatMapLatest { (id, limit) ->
            if (id == null) flowOf(ChatMessagesState(null, emptyList(), loaded = false))
            else agentMessageDao.getMessagesBySessionPaged(id, limit).map { list ->
                ChatMessagesState(
                    sessionId = id,
                    messages = list.asSequence()
                        .filterNot {
                            it.role == MessageRole.ASSISTANT.name &&
                                !it.content.hasVisibleContent() &&
                                it.reasoning.isNullOrEmpty()
                        }
                        .map { entity -> entity.toUIMessage() }
                        .toList(),
                    loaded = true,
                    hasMore = list.size >= limit,
                    isLoadingMore = false
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatMessagesState(null, emptyList(), loaded = false))


    private val _runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())
    val runningTool: StateFlow<List<RunningToolOutput>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _runningTools.map { it[id]?.values?.toList() ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 添加/更新一个运行中工具（按 msgId 定位，支持多个工具并行）。 */
    private fun setRunningTool(sessionId: String, msgId: String, tool: RunningToolOutput) {
        val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
        _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (msgId to tool)))
    }

    /** 移除一个运行中工具；会话无剩余运行工具时清除该会话条目。 */
    private fun removeRunningTool(sessionId: String, msgId: String) {
        val sessionTools = _runningTools.value[sessionId] ?: return
        val updated = sessionTools - msgId
        _runningTools.value = if (updated.isEmpty()) {
            _runningTools.value - sessionId
        } else {
            _runningTools.value + (sessionId to updated)
        }
    }

    private val _compactingSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    private val _llmCallEvents = MutableSharedFlow<LlmCallEvent>(extraBufferCapacity = 16)
    /** 每次单次 LLM 请求返回事件（携带单次 Token 统计）。 */
    val llmCallEvents: SharedFlow<LlmCallEvent> = _llmCallEvents.asSharedFlow()
    val isCompacting: StateFlow<Boolean> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else _compactingSessions.map { it[id] == true }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun setCompacting(sessionId: String, compacting: Boolean) {
        _compactingSessions.value = if (compacting) {
            _compactingSessions.value + (sessionId to true)
        } else {
            _compactingSessions.value - sessionId
        }
    }

    private val _streamingTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingText: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingTexts.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingText(sessionId: String, text: String?) {
        _streamingTexts.value = if (text == null) _streamingTexts.value - sessionId else _streamingTexts.value + (sessionId to text)
    }

    private val _streamingReasonings = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingReasoning: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingReasonings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingReasoning(sessionId: String, text: String?) {
        _streamingReasonings.value = if (text == null) _streamingReasonings.value - sessionId else _streamingReasonings.value + (sessionId to text)
    }

    /** 按 sessionId 维护的重试状态；流式恢复或结束后置 null。 */
    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    val retryState: StateFlow<RetryState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _retryStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.value = if (state == null) _retryStates.value - sessionId else _retryStates.value + (sessionId to state)
    }

    val pendingToolPermission = toolPermissionManager.pendingRequest

    val pendingUserQuestion = askUserQuestionManager.pendingQuestion

    private val _queuedRequests = MutableStateFlow<Map<String, List<QueuedRequest>>>(emptyMap())
    // 正在执行斜杠命令的会话集合：命令执行期间同样视为 busy（
    // 不注册 sessionJobs，否则 /compress 等命令内部的自检会误判为运行中），
    // 用于 enqueueAgentRequest 判断新消息应入队而非并行执行。
    private val _runningCommandSessions = MutableStateFlow<Set<String>>(emptySet())
    val queuedRequests: StateFlow<List<QueuedRequest>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _queuedRequests.map { it[id] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingPlanApproval: StateFlow<PlanApprovalRequest?> = planApprovalManager.pendingApproval

    // 工具调用传入参数（argsPreview）按落库消息 id 暂存：ToolCallStarted 落库后，
    // ToolCallFinished / 用户停止会用同 id REPLACE 整行，需在此把参数带到后续落库。
    private val toolArgsByMsgId = mutableMapOf<String, String>()

    /** 是否有正在运行、可被打断的 agent 任务。 */
    val isRunning: Boolean get() {
        val sid = _currentSessionId.value ?: return false
        return sessionJobs[sid]?.isActive == true
    }

    fun hasRunningSessionsInCurrentWorkspace(): Boolean {
        return sessions.value.any { sessionJobs[it.id]?.isActive == true }
    }

    /** App 退到后台时 Agent 完成，弹一条可点击的系统通知（标题=任务完成，正文=用户消息）。 */
    private fun showAgentCompletedNotification(userRequest: String) {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AGENT_COMPLETE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.agent_complete_notification_title))
            .setContentText(
                userRequest.ifBlank { context.getString(R.string.agent_complete_notification_body) }
            )
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(AGENT_COMPLETE_NOTIFICATION_ID, notification)
        }.onFailure { FileLogger.e(TAG, "发送 agent 完成通知失败", it) }
    }

    private companion object {
        const val TAG = "AIAgentViewModel"
        const val AGENT_COMPLETE_CHANNEL = "agent_complete"
        const val AGENT_COMPLETE_NOTIFICATION_ID = 100
    }

    init {
        viewModelScope.launch {
            // 冷启动收尾：上次进程被杀时若有工具正在执行，其占位行会永久显示「执行中」。
            // 这些工具不可能还在跑，统一回填为「已中断」。放在设置会话之前完成，使首帧不再闪转圈。
            sessionUseCase.initColdStartCleanup()

            _currentWorkspace.collectLatest { path ->
                if (path.isBlank()) return@collectLatest
                val recent = sessionUseCase.getFirstSessionOfWorkspace(path)
                // 每次启动/切工作区先回收多余的空会话（保留最近会话），
                // 防止「最近会话非空则新建空白会话」在用户切回旧会话场景下堆积空会话。
                if (recent != null) sessionUseCase.recycleEmptySessions(path, keepId = recent.id)
                // 复用最近一个空会话（未发送过消息），避免反复启动/切工作区堆积空会话；
                // 最近会话已有内容则新建空白会话（与 newSession 防堆积策略一致）。
                _currentSessionId.value = if (recent != null && sessionUseCase.isSessionEmpty(recent.id)) {
                    recent.id
                } else {
                    createAndUpsertSession(path)
                }
            }
        }

        // 订阅后台命令完成事件：notify=true 的命令结束后自动注入消息并触发 AI 新一轮。
        // 会话忙碌期间到达的事件会被缓存，待本轮结束后合并成一条发送（见 [flushMergedNotifications]）。
        viewModelScope.launch {
            terminalSessionManager.tabFinishedEvents.collect { event ->
                handleBackgroundCommandFinished(event)
            }
        }

        // 订阅子代理生命周期事件（类比 terminal 的 notify=true 异步回调）：
        // - SPAWNED：task 工具已创建子会话并替用户发消息，这里在子会话上自动启动 AI 工作流；
        // - STOPPED：task(action="stop") 请求停止子代理，取消对应会话的 AI 任务；
        // - COMPLETED/FAILED：由子会话工作流结束时发出（见 handleSubAgentFinished），注入父会话通知。
        viewModelScope.launch {
            subAgentEventBus.events.collect { event ->
                when (event.type) {
                    SubAgentEventType.SPAWNED -> spawnSubAgentWorkflow(event)
                    SubAgentEventType.STOPPED -> stopAgentSession(event.subSessionId)
                    SubAgentEventType.COMPLETED, SubAgentEventType.FAILED -> {
                        // 仅内置 task 工具创建的子代理（SPAWNED 时注册过回调资格）完成才注入父会话通知，
                        // 插件等其它方式创建的子代理不回调（其生命周期由插件自行管理）。
                        if (subAgentEventBus.consumeParentNotification(event.subSessionId)) {
                            enqueueSubAgentNotification(event)
                        }
                    }
                }
            }
        }

        // 订阅插件 session 命令（client.session.prompt/delete）：驱动目标会话或删除会话。
        viewModelScope.launch {
            pluginSessionCommandBus.commands.collect { command ->
                when (command) {
                    is PluginSessionCommand.Prompt -> handlePluginPrompt(command)
                    is PluginSessionCommand.Delete -> deleteSession(command.sessionId)
                }
            }
        }
    }

    /**
     * 插件 client.session.prompt 执行：noReply 仅注入上下文；否则驱动目标会话工作流。
     * 宿主侧已用 registry 校验过会话未在运行，这里再查 sessionJobs 双保险，
     * 避免竞态窗口内两个 prompt 并发跑同一会话。
     */
    private suspend fun handlePluginPrompt(command: PluginSessionCommand.Prompt) {
        if (sessionJobs[command.sessionId]?.isActive == true) {
            FileLogger.w(TAG, "插件 prompt 被拒绝（会话运行中）: ${command.sessionId}")
            return
        }
        if (command.noReply) {
            messagePersistenceUseCase.persist(command.sessionId, MessageRole.USER, command.text)
            sessionUseCase.touch(command.sessionId, messagePersistenceUseCase.nextTimestamp())
            return
        }
        command.model?.let { (providerId, model) ->
            sessionUseCase.updateProviderModel(command.sessionId, providerId, model)
        }
        val isSub = sessionUseCase.getSessionById(command.sessionId)?.parentId != null
        executeAgentRequestStream(
            request = command.text,
            targetSessionId = command.sessionId,
            skipTitleUpdate = isSub,
            toolFilter = command.tools
        )
    }

    /** 向插件派发会话运行状态事件（对齐 opencode session.status）。 */
    private fun notifyPluginSessionStatus(sessionId: String, status: String) {
        pluginManager.notifyEvent("session.status", buildJsonObject {
            put("sessionID", sessionId)
            putJsonObject("status") { put("type", status) }
        })
    }

    /**
     * 子代理已创建（task 工具已创建子会话）：在子会话上启动 AI 工作流。
     * 消息由 executeAgentRequestStream 统一落库（与用户手动发消息一致），
     * 标题保留 task 传入的 description（skipTitleUpdate）。
     */
    private suspend fun spawnSubAgentWorkflow(event: SubAgentEvent) {
        val parentSession = sessionUseCase.getSessionById(event.parentSessionId)
        if (parentSession == null) {
            FileLogger.w(TAG, "子代理父会话不存在: ${event.parentSessionId}")
            return
        }
        // 子会话可能已被用户删除，跳过
        if (sessionUseCase.getSessionById(event.subSessionId) == null) {
            FileLogger.w(TAG, "子代理会话已被删除，跳过启动: ${event.subSessionId}")
            return
        }
        // 子代理运行中不允许重复启动（同一会话已有活跃 job）
        if (sessionJobs[event.subSessionId]?.isActive == true) return

        executeAgentRequestStream(
            request = event.detail,
            projectRoot = parentSession.workspacePath,
            targetSessionId = event.subSessionId,
            skipTitleUpdate = true
        )
    }

    /**
     * 子代理完成/失败通知：向父会话注入一条后台任务完成通知（user 消息），
     * 父代理据此得知子代理结束，可 task(action="read") 取回结果。与 terminal 后台通知同机制。
     * 通知 XML 中保留子代理 id 供 AI 直接 read；summary 用子代理标题（description）展示，用户侧更可读。
     */
    private fun enqueueSubAgentNotification(event: SubAgentEvent) {
        val status = if (event.type == SubAgentEventType.FAILED) "failed" else "completed"
        viewModelScope.launch {
            val title = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
            val notification = buildString {
                appendLine(BACKGROUND_NOTIFICATION_PREFIX)
                appendLine("这是一条子代理完成事件，不是来自用户的消息。")
                appendLine("不要将其视为用户的确认、同意或对任何待处理问题的回答。")
                appendLine()
                appendLine("<subagent-notification>")
                appendLine("  <subagent-id>${event.subSessionId}</subagent-id>")
                appendLine("  <subagent-title>$title</subagent-title>")
                appendLine("  <status>$status</status>")
                appendLine("  <summary>子代理「$title」已${if (event.type == SubAgentEventType.FAILED) "执行失败" else "执行完成"}</summary>")
                appendLine("</subagent-notification>")
                appendLine()
                append("可用 task(action=\"read\", id=\"${event.subSessionId}\") 读取子代理的最后输出。")
            }
            enqueueAgentRequest(
                request = notification,
                projectRoot = _currentWorkspace.value,
                targetSessionId = event.parentSessionId
            )
        }
    }

    /**
     * 后台命令（notify=true）结束后的回调：触发 Agent 新一轮，以一条后台任务完成通知（user 消息）
     * 作为本轮输入。
     *
     * 用 user 消息而非 assistant(tool_call) + tool_result 消息对：后者会与原 terminal 工具调用的
     * tool 结果在落库顺序上错位（后台回调异步触发，可能抢先于原 terminal 结果落库），导致 messages
     * 违反 OpenAI「assistant(tool_calls) → tool 结果紧跟」的配对约束，上游返回 400。user 消息无需与
     * 任何 tool_call 配对，天然不破坏顺序。通知文本带围栏说明，防止 AI 误判为用户的新指令或批准；
     * AI 据此用 terminal(read) 取回完整输出。
     *
     * 不自行 persist 通知、用 isAutoTrigger=false 走 enqueueAgentRequest 正常流程：由
     * executeAgentRequestStream 统一 persist 这条 user 消息，workflow 的 InitRequest 追加的同一条
     * UserMessage 即是它，避免重复落库或出现空占位消息。
     */
    private fun handleBackgroundCommandFinished(event: TabFinishedEvent) {
        val sessionId = event.sourceSessionId ?: return
        val jobActive = sessionJobs[sessionId]?.isActive == true
        val currentSid = _currentSessionId.value
        FileLogger.d(TAG, "handleBgFinished: eventSid=$sessionId currentSid=$currentSid jobActive=$jobActive state=${_agentStates.value[sessionId]}")
        if (jobActive) {
            pendingMergedNotifications.getOrPut(sessionId) { mutableListOf() }.add(event)
            return
        }
        // 会话空闲：立即发送，保持及时响应
        val notification = buildBackgroundNotification(listOf(event))
        viewModelScope.launch {
            enqueueAgentRequest(
                request = notification,
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    /** 本轮结束后把忙碌期间缓存的后台任务完成通知合并成一条发送。 */
    private fun flushMergedNotifications(sessionId: String) {
        val events = pendingMergedNotifications.remove(sessionId) ?: return
        if (events.isEmpty()) return
        FileLogger.d(TAG, "flushMergedNotifications: sid=$sessionId events=${events.size} state=${_agentStates.value[sessionId]}")
        val notification = buildBackgroundNotification(events)
        viewModelScope.launch {
            enqueueAgentRequest(
                request = notification,
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    /** 构建后台任务完成通知文本；多条时合并为一条，含多个 <task-notification> 块。 */
    private fun buildBackgroundNotification(events: List<TabFinishedEvent>): String {
        if (events.size == 1) return buildBackgroundNotification(events.first())
        return buildString {
            appendLine(BACKGROUND_NOTIFICATION_PREFIX)
            appendLine("共有 ${events.size} 个后台任务已完成，这是合并后的通知。")
            appendLine("这些是后台任务完成事件，不是来自用户的消息。")
            appendLine("不要将它们视为用户的确认、同意或对任何待处理问题的回答。")
            appendLine()
            events.forEach { event ->
                val status = if (event.exitCode == 0) "completed" else "failed"
                appendLine("<task-notification>")
                appendLine("  <task-id>${event.tabId}</task-id>")
                appendLine("  <title>${event.title}</title>")
                appendLine("  <command>${event.command ?: ""}</command>")
                appendLine("  <exit-code>${event.exitCode}</exit-code>")
                appendLine("  <status>$status</status>")
                appendLine("  <summary>后台任务「${event.title}」已结束（退出码 ${event.exitCode}）</summary>")
                appendTailOutput(event)
                appendLine("</task-notification>")
                appendLine()
            }
            append("通知已携带各终端最后 $TAIL_LINES 行输出；如需完整日志可用 terminal(action=\"read\", tab_id=\"...\") 读取对应任务。")
        }
    }

    /** 构建单条后台任务完成通知文本（与历史格式一致）。 */
    private fun buildBackgroundNotification(event: TabFinishedEvent): String {
        val status = if (event.exitCode == 0) "completed" else "failed"
        return buildString {
            appendLine(BACKGROUND_NOTIFICATION_PREFIX)
            appendLine("这是一条后台任务完成事件，不是来自用户的消息。")
            appendLine("不要将其视为用户的确认、同意或对任何待处理问题的回答。")
            appendLine()
            appendLine("<task-notification>")
            appendLine("  <task-id>${event.tabId}</task-id>")
            appendLine("  <title>${event.title}</title>")
            appendLine("  <command>${event.command ?: ""}</command>")
            appendLine("  <exit-code>${event.exitCode}</exit-code>")
            appendLine("  <status>$status</status>")
            appendLine("  <summary>后台任务「${event.title}」已结束（退出码 ${event.exitCode}）</summary>")
            appendTailOutput(event)
            appendLine("</task-notification>")
            appendLine()
            append("通知已携带该终端最后 $TAIL_LINES 行输出；如需完整日志可用 terminal(action=\"read\", tab_id=\"${event.tabId}\") 读取。")
        }
    }

    /** 追加 <tail-output> 块；空白输出跳过。转义尖括号防止 <status>/<summary> 等字样污染提示条的正则提取。 */
    private fun StringBuilder.appendTailOutput(event: TabFinishedEvent) {
        event.tailOutput?.takeIf { it.isNotBlank() }?.let { tail ->
            appendLine("  <tail-output>${escapeXml(tail)}</tail-output>")
        }
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun enqueueAgentRequest(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        isAutoTrigger: Boolean = false,
        targetSessionId: String? = null
    ) {
        val sid = targetSessionId ?: _currentSessionId.value
        val isCurrentRunning = sid != null &&
            (sessionJobs[sid]?.isActive == true || sid in _runningCommandSessions.value)
        if (isCurrentRunning) {
            val req = QueuedRequest(
                id = UUID.randomUUID().toString(),
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                isAutoTrigger = isAutoTrigger
            )
            val currentList = _queuedRequests.value[sid] ?: emptyList()
            _queuedRequests.value = _queuedRequests.value + (sid to (currentList + req))
        } else {
            executeAgentRequestStream(
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                targetSessionId = sid,
                isAutoTrigger = isAutoTrigger
            )
        }
    }

    /** 从当前会话队列移除指定条目（队列面板删除按钮）。 */
    fun removeQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
    }

    private fun processNextInQueue(sessionId: String) {
        // 已有活跃 job（可能是本次收尾前由通知合并/flush 等入口启动的）时不消费，
        // 避免队列被多个收尾入口重复消费、同一会话并发跑两个 job。
        if (sessionJobs[sessionId]?.isActive == true) return
        val queue = _queuedRequests.value[sessionId] ?: return
        val next = queue.firstOrNull() ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.drop(1))
        executeAgentRequestStream(
            request = next.request,
            modelRequest = next.modelRequest,
            currentFile = next.currentFile,
            selectedCode = next.selectedCode,
            projectRoot = next.projectRoot,
            inputImages = next.inputImages,
            inputAttachments = next.inputAttachments,
            targetSessionId = sessionId,
            isAutoTrigger = next.isAutoTrigger
        )
    }

    /**
     * 执行斜杠命令：先把命令文本作为用户消息落库（进入对话上下文），再执行 handler。
     * 执行期间标记为命令占用（防新消息并行执行），结束后接续队列中排队的下一条。
     */
    private fun runSlashCommand(command: SlashCommandHandler, input: String, sessionId: String) {
        viewModelScope.launch {
            _runningCommandSessions.value = _runningCommandSessions.value + sessionId
            try {
                // command.execute.before hook：插件可改写参数或置空阻止执行。
                // output 对齐 opencode：{ parts: Part[] }（text part 数组），取回第一个 text part 的文本。
                val result = pluginManager.dispatchHook(
                    "command.execute.before",
                    buildJsonObject {
                        put("command", command.trigger)
                        put("sessionID", sessionId)
                        put("arguments", input)
                    },
                    buildJsonObject {
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", input)
                            })
                        }
                    }
                )
                val effectiveArgs = (result.output["parts"] as? JsonArray)
                    ?.mapNotNull { el ->
                        val obj = el as? JsonObject ?: return@mapNotNull null
                        if ((obj["type"] as? JsonPrimitive)?.content == "text") {
                            (obj["text"] as? JsonPrimitive)?.contentOrNull
                        } else null
                    }
                    ?.filter { it.isNotBlank() }
                    ?.joinToString("\n")
                    ?.ifBlank { null }
                    ?: input
                if (effectiveArgs.isBlank()) return@launch
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, effectiveArgs)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                command.execute(this@AIAgentViewModel)
                pluginManager.notifyEvent("command.executed", buildJsonObject {
                    put("command", command.trigger)
                    put("sessionID", sessionId)
                    put("arguments", effectiveArgs)
                })
            } finally {
                _runningCommandSessions.value = _runningCommandSessions.value - sessionId
                processNextInQueue(sessionId)
            }
        }
    }

    fun executeAgentRequestStream(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        targetSessionId: String? = null,
        isAutoTrigger: Boolean = false,
        /** 子代理等场景：已预设会话标题，跳过首条消息的标题推导/生成，保留预设标题。 */
        skipTitleUpdate: Boolean = false,
        /** 工具排除（对齐 opencode prompt body.tools）：值为 false 的工具本轮不可用，null=不过滤。 */
        toolFilter: Map<String, Boolean>? = null
    ): Job = viewModelScope.launch {
        val sessionId = targetSessionId ?: ensureSession()
        if (sessionId.isBlank()) {
            FileLogger.w(TAG, "工作区未就绪，跳过请求")
            return@launch
        }
        // 命令分流：完全相等匹配到斜杠命令时，不走 agent workflow，直接执行命令操作
        // （命令文本已作为用户消息落库，进入对话上下文）。不注册 sessionJobs，
        // 因此 isRunning 保持 false，/compress 等命令内部的自检可以正常工作。
        slashCommandRegistry.findExact(request)?.let { command ->
            runSlashCommand(command, request, sessionId)
            return@launch
        }
        // 兼容历史会话：若会话尚未持久化绑定 providerId/model，发消息时将其固化，避免后续默认模型变动影响已有会话
        val currentSession = sessionUseCase.getSessionById(sessionId)
        if (currentSession != null && (currentSession.providerId.isNullOrBlank() || currentSession.model.isNullOrBlank())) {
            val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
            val defaultModel = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
            if (defaultProviderId != null && defaultModel != null) {
                sessionUseCase.updateProviderModel(sessionId, defaultProviderId, defaultModel)
            }
        }

        coroutineContext[Job]?.let { sessionJobs[sessionId] = it }
        FileLogger.d(TAG, "stream start: sid=$sessionId prevState=${_agentStates.value[sessionId]} isAutoTrigger=$isAutoTrigger")
        setAgentState(sessionId, AgentUIState.Streaming)

        try {
            var failed = false
            // 必须在插入本次用户消息之前读取历史：workflow 会自己 add(userRequest)，避免重复。
            val history = messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
            val isFirst = history.isEmpty()

            if (!isAutoTrigger) {
                val userMsgId = UUID.randomUUID().toString()
                // chat.message hook：插件可改写用户输入（落库前）。output 对齐 opencode {message, parts}。
                val effectiveRequest = pluginManager.applyChatMessage(
                    sessionId, userMsgId, request,
                    agent = currentSession?.subagentType,
                    providerId = currentSession?.providerId,
                    model = currentSession?.model
                )
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, effectiveRequest, id = userMsgId, attachments = inputAttachments)
                checkpointManager.createCheckpoint(sessionId, userMsgId, effectiveRequest)
                if (isFirst && !skipTitleUpdate) {
                    sessionUseCase.updateTitle(sessionId, sessionUseCase.deriveTitle(effectiveRequest))
                    // 后台异步用 LLM 生成更贴切的标题替换临时标题；失败/取不到时保留临时标题
                    viewModelScope.launch {
                        agentWorkflow.generateTitle(sessionId, request)?.let { sessionUseCase.updateTitle(sessionId, it) }
                    }
                }
            }
            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())

            val sessionEntity = sessionUseCase.getSessionById(sessionId)
            val sessionDomain = sessionEntity?.toDomain()
            val mode = sessionDomain?.mode ?: AgentMode.BUILD

            val agentContext = AgentContext(
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                language = currentFile?.let { detectLanguage(it) },
                history = history,
                inputImages = inputImages,
                sessionId = sessionId,
                mode = mode,
                reasoningEffort = sessionDomain?.reasoningEffort?.apiValue
            )

            val isSub = sessionUseCase.getSessionById(sessionId)?.parentId != null
            val allTools = toolRegistry.getAvailableTools()
            val baseTools = if (isSub) allTools.filterNot { it.name == "task" } else allTools
            val tools = if (toolFilter == null) baseTools else baseTools.filter { tool ->
                toolFilter[tool.name] != false
            }

            agentWorkflow.executeEvents(
                userRequest = modelRequest,
                context = agentContext,
                tools = tools
            ).collect { event ->
                when (event) {
                    is AgentEvent.AssistantDelta -> {
                        setRetryState(sessionId, null)
                        setStreamingText(sessionId, event.accumulated)
                    }
                    is AgentEvent.ReasoningDelta -> {
                        setRetryState(sessionId, null)
                        setStreamingReasoning(sessionId, event.accumulated)
                    }
                    is AgentEvent.Retrying -> {
                        setRetryState(sessionId, RetryState(event.attempt, event.maxRetries, event.error))
                        notifyPluginEvent(sessionId, event)
                    }
                    is AgentEvent.CompactionStarted -> {
                        setRetryState(sessionId, null)
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setCompacting(sessionId, true)
                        notifyPluginEvent(sessionId, event)
                    }
                    AgentEvent.CompactionFinished -> {
                        setCompacting(sessionId, false)
                        notifyPluginEvent(sessionId, event)
                    }
                    is AgentEvent.CompactionFailed -> {
                        setCompacting(sessionId, false)
                        // 落库为无配对的 TOOL 消息：UI 渲染失败卡片，buildHistory 回放自动丢弃，不进模型上下文。
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.reason,
                            toolName = COMPACTION_FAILURE_TOOL_NAME,
                            isError = true
                        )
                        notifyPluginEvent(sessionId, event)
                    }
                    is AgentEvent.AssistantText -> {
                        val normalized = if (event.content.hasVisibleContent()) event.content else ""
                        val reasoning = event.reasoning.takeIf { it.hasVisibleContent() }
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.ASSISTANT,
                            normalized,
                            toolCalls = event.toolCalls,
                            reasoning = reasoning,
                            signature = event.signature.ifEmpty { null },
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens
                        )
                        if (event.inputTokens > 0 || event.outputTokens > 0) {
                            _llmCallEvents.tryEmit(LlmCallEvent(sessionId, event.inputTokens, event.outputTokens, event.cachedInputTokens))
                            // 同步写库：工具循环下一轮 CallLlm 前会重读 lastInputTokens 判断压缩，
                            // 异步写库可能读到压缩前的旧大值导致重复触发压缩。
                            runCatching {
                                chatSessionDao.addTokenUsage(sessionId, event.inputTokens, event.outputTokens)
                                if (event.inputTokens > 0) {
                                    chatSessionDao.updateLastInputTokens(sessionId, event.inputTokens)
                                }
                            }
                        }
                        setStreamingReasoning(sessionId, null)
                        setStreamingText(sessionId, null)
                    }
                    is AgentEvent.ToolCallStarted -> {
                        val msgId = "tool_${event.id}"
                        setStreamingText(sessionId, null)
                        toolArgsByMsgId[msgId] = event.argsPreview
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            "${SessionUseCase.PENDING_TOOL_MARKER} ${context.getString(R.string.agent_tool_executing, event.toolName)}",
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview,
                            isError = false
                        )
                        setRunningTool(sessionId, msgId, RunningToolOutput(msgId, "", event.toolName, event.argsPreview))
                        notifyPluginEvent(sessionId, event)
                    }
                    is AgentEvent.ToolCallProgress -> {
                        val msgId = "tool_${event.id}"
                        setRunningTool(sessionId, msgId, RunningToolOutput(
                            msgId,
                            event.accumulated,
                            event.toolName,
                            toolArgsByMsgId[msgId] ?: ""
                        ))
                    }
                    is AgentEvent.ToolCallFinished -> {
                        val msgId = "tool_${event.id}"
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.result,
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview ?: toolArgsByMsgId[msgId],
                            isError = event.isError,
                            attachments = event.attachments
                        )
                        toolArgsByMsgId.remove(msgId)
                        removeRunningTool(sessionId, msgId)
                        notifyPluginEvent(sessionId, event)
                    }
                    is AgentEvent.Failed -> {
                        failed = true
                        setCompacting(sessionId, false)
                        setAgentState(sessionId, AgentUIState.Error(event.error))
                        // 子代理会话失败时通知父会话
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.FAILED,
                                        detail = event.error
                                    )
                                )
                            }
                        }
                        notifyPluginEvent(sessionId, event)
                    }
                    AgentEvent.Completed -> {
                        setRetryState(sessionId, null)
                        setCompacting(sessionId, false)
                        // 子代理会话完成时通知父会话（异步回调）
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.COMPLETED
                                    )
                                )
                            }
                        }
                        // 仅当 App 不在前台时发 agent 完成通知（避免打扰正在看对话的用户）。
                        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                        if (!inForeground && agentSoundSettings.isEnabled()) {
                            showAgentCompletedNotification(modelRequest)
                        }
                        notifyPluginEvent(sessionId, event)
                    }
                    is AgentEvent.ModeChanged -> {
                        // 模式切换事件：PlanApprovalManager 已在 workflow 层面挂起等待用户批准
                        // 这里只更新 streamingText 显示
                        notifyPluginEvent(sessionId, event)
                    }
                }
            }

            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
            // 仅当本 job 仍持有忙状态时才置完成态：并发场景下队列/通知可能已启动新的 job
            // 并把状态改为 Streaming，不能被先结束的 job 误覆盖成 Result（按钮会提前变回发送）。
            val finishedState = _agentStates.value[sessionId]
            if (!failed && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
                setAgentState(sessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
            }
            setStreamingText(sessionId, null)

        } catch (e: CancellationException) {
            val cancelledState = _agentStates.value[sessionId]
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream cancelled: sid=$sessionId isOwnJob=$isOwnJob state=$cancelledState")
            if (isOwnJob &&
                (cancelledState is AgentUIState.Loading || cancelledState is AgentUIState.Streaming)
            ) {
                setAgentState(sessionId, AgentUIState.Idle)
            }
            throw e
        } catch (e: Exception) {
             FileLogger.e(TAG, "executeAgentRequestStream 失败: request=$request", e)
             setAgentState(sessionId, AgentUIState.Error(e.toUserMessage()))
        } finally {
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream finally: sid=$sessionId isOwnJob=$isOwnJob state=${_agentStates.value[sessionId]}")
            if (isOwnJob) {
                sessionJobs.remove(sessionId)
                sessionActivityRegistry.remove(sessionId)
                notifyPluginSessionStatus(sessionId, "idle")
            }
            _runningTools.value = _runningTools.value - sessionId
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)

            // 忙碌期间缓存的后台任务完成通知：本轮结束且 job 已移除后，合并成一条发送
            flushMergedNotifications(sessionId)

            // 正常完成时先回到 Idle，再处理队列；队列若有下一轮会重新设 Streaming
            val currentState = _agentStates.value[sessionId]
            if (currentState !is AgentUIState.Error && currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                setAgentState(sessionId, AgentUIState.Idle)
            }
            if (currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                processNextInQueue(sessionId)
            }
        }
    }.also { job ->
        // 同步注册 job：launch 内的 sessionJobs 赋值是异步的，finally 中 flushMergedNotifications
        // 与 processNextInQueue 会在赋值前都看到 isActive=false 而双消费启动两个 job，
        // 先结束的 job 把状态置 Idle/Result 覆盖仍在跑的 job 的 Streaming。
        if (targetSessionId != null && slashCommandRegistry.findExact(request) == null) {
            sessionJobs[targetSessionId] = job
            sessionActivityRegistry.add(targetSessionId)
            notifyPluginSessionStatus(targetSessionId, "busy")
        }
    }

    fun resolveToolPermission(id: String, choice: PermissionChoice) {
        toolPermissionManager.resolve(id, choice)
    }

    fun resolveUserQuestion(id: String, answer: UserQuestionAnswer) {
        askUserQuestionManager.resolve(id, answer)
    }

    /**
     * 主动打断正在运行的 agent：取消协程（会一并取消挂起的网络请求与容器命令进程），
     * 并把「执行中」的工具占位行收尾为「已停止」，避免悬挂的 spinner 与孤儿记录。
     */
    /** 停止当前工作区所有正在运行的 AI 会话并关闭所有终端标签（切换工作区前调用）。 */
    fun stopAllAndCloseTerminal() {
        stopAllAgents()
        terminalSessionManager.tabs.value.map { it.id }.forEach { terminalSessionManager.closeTab(it) }
    }

    /** 停止当前工作区所有正在运行的 AI 会话（切换工作区前调用）。 */
    fun stopAllAgents() {
        val jobs = sessionJobs.values.filter { it.isActive }
        jobs.forEach { it.cancel() }
        sessionJobs.clear()
        sessionActivityRegistry.clear()
        pendingMergedNotifications.clear()
        _queuedRequests.value = emptyMap()
        _runningCommandSessions.value = emptySet()
        _agentStates.value = _agentStates.value.mapValues { AgentUIState.Idle }
        _streamingTexts.value = emptyMap()
        _streamingReasonings.value = emptyMap()
        _runningTools.value = emptyMap()
        _retryStates.value = emptyMap()
    }

    fun stopAgent() {
        val sessionId = _currentSessionId.value ?: return
        stopAgentSession(sessionId)
    }

    /**
     * 停止指定会话的 AI 任务（子代理停止/用户手动停止共用）。
     * 取消 job 并把未完成的流式内容落库为「已停止」；队列下一条照常执行。
     */
    fun stopAgentSession(sessionId: String) {
        val job = sessionJobs[sessionId] ?: return
        if (!job.isActive) return
        val runningTools = _runningTools.value[sessionId]?.values?.toList() ?: emptyList()
        val streamingText = _streamingTexts.value[sessionId]
        val streamingReasoning = _streamingReasonings.value[sessionId]
        val pendingPermission = toolPermissionManager.pendingRequest.value
        val stoppedText = context.getString(R.string.agent_stopped_by_user)
        val pendingNotifs = pendingMergedNotifications[sessionId]?.size ?: 0
        FileLogger.d(TAG, "stopAgent: sid=$sessionId runningTools=${runningTools.size} pendingPerm=${pendingPermission?.id} pendingNotifs=$pendingNotifs state=${_agentStates.value[sessionId]}")
        // cancel() 在 Dispatchers.Main.immediate 上可能立即恢复挂起协程
        // （如 awaitApproval 的 CompletableDeferred.await），旧 job 的 finally →
        // flushMergedNotifications 在 cancel() 调用栈内同步执行并可能启动新 job。
        // 不预先清除缓存通知——它们应由 finally 正常 flush 给新 job 处理。
        job.cancel()
        // cancel 可能已同步执行完 finally（flush 启动了新 job 并注册到 sessionJobs），
        // 此时不能再覆盖新 job 的状态；仅当无新 job 接管时才做清理。
        if (sessionJobs[sessionId]?.isActive != true) {
            pendingMergedNotifications.remove(sessionId)
            setAgentState(sessionId, AgentUIState.Idle)
        }
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        viewModelScope.launch {
            if (runningTools.isNotEmpty()) {
                // 并行执行被中止：所有未完成的工具都落库为「已停止」
                runningTools.forEach { running ->
                    val partial = running.text.trimEnd()
                    val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                    messagePersistenceUseCase.persist(
                        sessionId = sessionId,
                        role = MessageRole.TOOL,
                        content = content,
                        id = running.messageId,
                        toolCallId = running.messageId.removePrefix("tool_"),
                        toolName = running.toolName.ifBlank { null },
                        toolArgs = running.toolArgs.ifBlank { toolArgsByMsgId[running.messageId] },
                        isError = true
                    )
                    toolArgsByMsgId.remove(running.messageId)
                }
            } else if (!streamingText.isNullOrEmpty() || !streamingReasoning.isNullOrEmpty()) {
                val partial = (streamingText ?: "").trimEnd()
                val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                val reasoning = streamingReasoning?.takeIf { it.hasVisibleContent() }
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = content,
                    reasoning = reasoning
                )
            }
            // 授权弹窗挂起中的工具调用：awaitApproval 挂起期间 _runningTools 为空
            // （ToolCallStarted 在授权通过后才发出），但 AssistantText 已落库了带
            // tool_call 声明的 assistant 消息。不补结果会导致该 tool_call 成为
            // 孤立记录，被 buildHistory 的 validIds 交集过滤掉，AI 不知道自己曾调用过。
            if (pendingPermission != null) {
                val msgId = "tool_${pendingPermission.id}"
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.TOOL,
                    content = stoppedText,
                    id = msgId,
                    toolCallId = pendingPermission.id,
                    toolName = pendingPermission.toolName,
                    isError = true
                )
            }
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            // 点「停止」= 跳过当前轮，立即执行队列下一条
            processNextInQueue(sessionId)
        }
    }

    // region 会话管理

    /** 新建会话；若当前会话还是空的则直接复用，避免堆积空会话。 */
    fun newSession() = viewModelScope.launch {
        if (_currentWorkspace.value.isBlank()) return@launch
        val curId = _currentSessionId.value
        if (curId != null && sessionUseCase.isSessionEmpty(curId)) {
            setAgentState(curId, AgentUIState.Idle)
            return@launch
        }
        // 新会话时异步重连未连接的 MCP server，让 manageMcp 新增的配置真正生效；
        // 不阻塞会话创建——MCP 环境未就绪/超时时不能卡住「新建会话」。
        mcpManager.reconnectUnconnectedAsync()
        val sid = createAndUpsertSession(_currentWorkspace.value)
        _currentSessionId.value = sid
    }

    fun setCurrentSessionId(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun setSessionMode(mode: AgentMode) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateMode(sid, mode.name)
        }
    }

    fun setSessionReasoningEffort(effort: ReasoningEffort) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateReasoningEffort(sid, effort.name)
            // 同步记忆到模型级默认档位，供后续新建会话沿用
            val s = sessionUseCase.getSessionById(sid)?.toDomain()
            val pid = s?.providerId
            val model = s?.model
            if (!pid.isNullOrBlank() && !model.isNullOrBlank()) {
                modelReasoningEffortRepository.set(pid, model, effort.name)
            }
        }
    }

    fun setSessionProviderModel(providerId: String, model: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateProviderModel(sid, providerId, model)
            // 空会话中的选择视为「新会话默认模型」，供下次新建会话沿用
            if (sessionUseCase.isSessionEmpty(sid)) {
                defaultModelSettingsRepository.setDefaultModel(providerId, model)
            }
        }
    }

    /** 暴露给 UI：输入框下拉菜单展示的命令列表。 */
    val slashCommands: List<SlashCommandHandler> get() = slashCommandRegistry.all

    /** /status —— 以 Markdown 表格作为 AI 气泡输出当前会话状态。 */
    override fun showSessionStatus() {
        val sid = _currentSessionId.value ?: return
        val session = sessions.value.find { it.id == sid } ?: return
        val msgCount = messagesState.value.messages.size
        val model = session.model ?: sessionProviderModelDisplay(sid)
        val table = buildString {
            appendLine("| 项目 | 值 |")
            appendLine("|---|---|")
            appendLine("| 会话 | ${escapeMd(session.title)} |")
            appendLine("| 模型 | ${escapeMd(model)} |")
            appendLine("| 模式 | ${session.mode.name} |")
            appendLine("| 工作区 | ${escapeMd(session.workspacePath)} |")
            appendLine("| 消息数 | $msgCount |")
            appendLine("| 输入 tokens | ${formatTokenCount(session.totalInputTokens)} |")
            appendLine("| 输出 tokens | ${formatTokenCount(session.totalOutputTokens)} |")
        }
        viewModelScope.launch {
            sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table.trimEnd(), isCompacted = true)
        }
    }

    /** /compress —— 手动触发当前会话的上下文压缩。 */
    override fun compactCurrentSession() {
        val sid = _currentSessionId.value ?: return
        if (isRunning) return
        sessionJobs[sid]?.let { if (it.isActive) return }
        val job = viewModelScope.launch {
            setCompacting(sid, true)
            var failed = false
            try {
                val changed = agentWorkflow.compactSession(sid) { event ->
                    when (event) {
                        is AgentEvent.CompactionStarted -> setCompacting(sid, true)
                        AgentEvent.CompactionFinished -> setCompacting(sid, false)
                        is AgentEvent.CompactionFailed -> {
                            failed = true
                            setCompacting(sid, false)
                            // 与自动压缩一致：落库无配对的 TOOL 消息渲染失败卡片，buildHistory 回放丢弃。
                            messagePersistenceUseCase.persist(
                                sessionId = sid,
                                role = MessageRole.TOOL,
                                content = event.reason,
                                toolName = COMPACTION_FAILURE_TOOL_NAME,
                                isError = true
                            )
                        }
                        else -> {}
                    }
                }
                // 压缩成功时 marker 分隔线 + 摘要卡片已提供反馈，不再落库重复的提示气泡；
                // 仅「无需压缩」（head 为空/无可压缩内容）时给出一条提示。
                if (!failed && !changed) {
                    messagePersistenceUseCase.persist(
                        sessionId = sid,
                        role = MessageRole.ASSISTANT,
                        content = context.getString(R.string.agent_context_no_compaction)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "手动压缩失败: session=$sid", e)
                messagePersistenceUseCase.persist(
                    sessionId = sid,
                    role = MessageRole.ASSISTANT,
                    content = context.getString(R.string.agent_compaction_failed, e.message)
                )
            } finally {
                setCompacting(sid, false)
                // 压缩是异步流程，结束后接续队列中排队的下一条
                processNextInQueue(sid)
            }
        }
        sessionJobs[sid] = job
    }

    private fun sessionProviderModelDisplay(sid: String): String {
        val pair = currentSessionProviderModel.value ?: return context.getString(R.string.agent_model_not_selected)
        val (_, model) = pair
        return model?.takeIf { it.isNotBlank() } ?: context.getString(R.string.agent_model_not_selected)
    }

    private fun escapeMd(text: String): String = text.replace("|", "\\|").replace("\n", " ")


    /** 用户批准计划，唤醒 workflow 继续在 BUILD 模式执行。 */
    fun approvePlanAndBuild() {
        planApprovalManager.resolve(PlanApprovalChoice.APPROVE)
    }

    /** 用户选择继续反馈，唤醒 workflow 回滚到 PLAN 模式。 */
    fun refinePlan() {
        planApprovalManager.resolve(PlanApprovalChoice.REFINE)
    }

    fun selectSession(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        checkpointManager.clearSessionCheckpoints(id)
        val deletedIds = sessionUseCase.deleteSession(id)

        deletedIds.forEach { sid ->
            sessionJobs[sid]?.cancel()
            sessionJobs.remove(sid)
            sessionActivityRegistry.remove(sid)
            _agentStates.value = _agentStates.value - sid
            _streamingTexts.value = _streamingTexts.value - sid
            _streamingReasonings.value = _streamingReasonings.value - sid
            _runningTools.value = _runningTools.value - sid
            _retryStates.value = _retryStates.value - sid
            _queuedRequests.value = _queuedRequests.value - sid
            pendingMergedNotifications.remove(sid)
        }

        if (_currentSessionId.value in deletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }
    }

    /** 重命名会话标题。仅更新 title，不改 updatedAt，列表顺序保持不变。 */
    // Checkpoint Rewind 选中的 Target Message
    private val _targetRewindMessageId = MutableStateFlow<String?>(null)
    val targetRewindMessageId: StateFlow<String?> = _targetRewindMessageId.asStateFlow()

    fun openRewindMenu(messageId: String) {
        _targetRewindMessageId.value = messageId
    }

    fun dismissRewindMenu() {
        _targetRewindMessageId.value = null
    }

    fun executeRewindOption(
        messageId: String,
        option: RewindOption,
        onFillPrompt: (String, List<AgentAttachment>) -> Unit
    ) = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        dismissRewindMenu()

        // 1. 停止当前正在运行的 Agent 任务及后续排队
        _queuedRequests.value = _queuedRequests.value + (sessionId to emptyList())
        pendingMergedNotifications.remove(sessionId)
        val runningJob = sessionJobs[sessionId]
        if (runningJob != null && runningJob.isActive) {
            runningJob.cancelAndJoin()
        }
        sessionJobs.remove(sessionId)

        // 2. 重置会话运行、流式与检查点状态
        setAgentState(sessionId, AgentUIState.Idle)
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        checkpointManager.setActiveCheckpointId(null)

        val checkpoint = checkpointDao.getCheckpointByMessageId(messageId)
        val targetMsgEntity = agentMessageDao.getMessageById(messageId) ?: return@launch
        val attachments = targetMsgEntity.toUIMessage().attachments

        when (option) {
            RewindOption.RESTORE_CODE_AND_CONVERSATION -> {
                if (checkpoint != null) {
                    checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                }
                agentMessageDao.deleteMessagesFromTimestamp(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CONVERSATION -> {
                agentMessageDao.deleteMessagesFromTimestamp(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CODE -> {
                if (checkpoint != null) {
                    checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                }
            }
        }
    }

    /** 重命名会话标题。仅更新 title，不改 updatedAt，列表顺序保持不变。 */
    fun renameSession(id: String, newTitle: String) = viewModelScope.launch {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@launch
        sessionUseCase.updateTitle(id, trimmed)
    }

    /** 置顶/取消置顶会话。置顶后排在列表最前（置顶分组），不改 updatedAt。 */
    fun togglePinSession(id: String) = viewModelScope.launch {
        val pinned = sessions.value.find { it.id == id }?.isPinned ?: return@launch
        sessionUseCase.updatePinned(id, !pinned)
    }

    /** 导出单个会话为无密码备份格式（tar.gz），流式写入 [output]（调用方打开，本方法负责关闭）。成功回调 true，失败回调 false。 */
    fun exportSession(sessionId: String, output: OutputStream, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            backupManager.exportSession(sessionId, output)
            onResult(true)
        } catch (e: Exception) {
            FileLogger.e("AIAgentViewModel", "exportSession failed", e)
            onResult(false)
        } finally {
            runCatching { output.close() }
        }
    }

    private suspend fun ensureSession(): String {
        _currentSessionId.value?.let { return it }
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return ""
        val id = sessionUseCase.getFirstSessionOfWorkspace(ws)?.id ?: createAndUpsertSession(ws)
        _currentSessionId.value = id
        return id
    }

    /** 新建会话并落库，返回 id。 */
    private suspend fun createAndUpsertSession(workspacePath: String): String {
        val s = createSession(workspacePath)
        sessionUseCase.upsertSession(s)
        return s.id
    }

    /**
     * 创建新会话并按「新会话默认模型」绑定 provider/model；未设置默认时回退全局 active provider。
     * 所有新建会话的入口（冷启动、新建、删除兜底、ensureSession）都走这里。
     */
    private suspend fun createSession(workspacePath: String): ChatSessionEntity {
        val providerId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
        val model = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
        val effort = if (providerId != null && model != null) {
            modelReasoningEffortRepository.get(providerId, model) ?: ReasoningEffort.MEDIUM.name
        } else {
            ReasoningEffort.MEDIUM.name
        }
        return sessionUseCase.newSessionEntity(
            workspacePath = workspacePath,
            providerId = providerId,
            model = model,
            reasoningEffort = effort
        ).also {
            pluginManager.notifyEvent(
                "session.created",
                mapOf(
                    "sessionID" to JsonPrimitive(it.id),
                    "title" to JsonPrimitive(it.title)
                )
            )
        }
    }

    // endregion

    fun updateMessageContent(messageId: String, newContent: String) = viewModelScope.launch {
        try {
            messagePersistenceUseCase.updateContent(messageId, newContent)
        } catch (e: Exception) {
            FileLogger.e(TAG, "更新消息失败", e)
        }
    }

    fun deleteMessage(messageId: String) = viewModelScope.launch {
        try {
            val msg = agentMessageDao.getMessageById(messageId)
            if (msg != null && msg.role == MessageRole.USER.name) {
                agentMessageDao.deleteMessagesAfterTimestamp(msg.sessionId, msg.timestamp)
            }
            agentMessageDao.deleteMessageById(messageId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "删除消息失败", e)
        }
    }

    /** 把工作流事件转发给插件（fire-and-forget，不阻塞工作流）。 */
    private fun notifyPluginEvent(sessionId: String, event: AgentEvent) {
        val type = when (event) {
            is AgentEvent.Completed -> "session.idle"
            is AgentEvent.Failed -> "session.error"
            is AgentEvent.ModeChanged -> "mode.changed"
            is AgentEvent.ToolCallStarted -> "tool.started"
            is AgentEvent.ToolCallFinished -> "tool.finished"
            is AgentEvent.CompactionStarted -> "compaction.started"
            AgentEvent.CompactionFinished -> "compaction.finished"
            is AgentEvent.CompactionFailed -> "compaction.failed"
            is AgentEvent.Retrying -> "llm.retrying"
            else -> null
        } ?: return
        pluginManager.notifyEvent(
            type,
            mapOf(
                "sessionID" to JsonPrimitive(sessionId),
                "type" to JsonPrimitive(type)
            )
        )
    }

    private fun detectLanguage(filePath: String): String {
        return when (filePath.substringAfterLast(".").lowercase()) {
            "kt", "kotlin" -> "kotlin"
            "java" -> "java"
            "dart" -> "dart"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "jsx" -> "javascript"
            "go" -> "go"
            "rs" -> "rust"
            else -> "text"
        }
    }
}
