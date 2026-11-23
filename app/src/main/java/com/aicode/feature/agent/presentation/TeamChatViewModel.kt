package com.aicode.feature.agent.presentation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.TeamChatDao
import com.aicode.feature.agent.data.local.entity.TeamChatMessageEntity
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.team.TeamChatRunner
import com.aicode.feature.agent.domain.team.TeamDefinition
import com.aicode.feature.agent.domain.team.TeamRepository
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.WorkspaceRef
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class TeamChatViewModel @Inject constructor(
    private val teamRepository: TeamRepository,
    private val dao: TeamChatDao,
    private val runner: TeamChatRunner,
    private val workspaceRepository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder
) : ViewModel() {
    internal data class StreamingTurn(val speaker: String, val avatar: String, val text: String)

    internal data class Preview(val recipients: List<String>, val skipped: List<String>)

    internal data class State(
        val team: TeamDefinition? = null,
        val messages: List<TeamChatMessageEntity> = emptyList(),
        val loading: Boolean = false,
        val busy: Boolean = false,
        val streaming: StreamingTurn? = null,
        val preview: Preview? = null,
        @param:StringRes val error: Int? = null,
        val failedMembers: List<String> = emptyList()
    ) {
        val proposal: String?
            get() = messages.lastOrNull { it.role == ROLE_PLAN }
                ?.takeIf { plan -> plan.id > (messages.lastOrNull { it.role == ROLE_OWNER }?.id ?: 0) }
                ?.content
    }

    private class Room(val teamKey: String, val workspace: WorkspaceRef)
    private class Pending(val team: TeamDefinition, val text: String, val discussion: TeamChatRunner.Discussion)

    private val _state = MutableStateFlow(State())
    internal val state = _state.asStateFlow()
    private var room: Room? = null
    private var pending: Pending? = null
    private var historyJob: Job? = null
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            combine(workspaceRepository.current, executionModeHolder.activeBackend) { _, _ -> Unit }.collect {
                val active = room ?: return@collect
                if (!isActive(active)) close()
            }
        }
    }

    internal fun open(teamKey: String, workspace: WorkspaceRef) {
        close()
        val selection = Room(teamKey, workspace)
        room = selection
        if (!isActive(selection)) {
            close()
            return
        }
        _state.value = State(loading = true)
        historyJob = viewModelScope.launch {
            try {
                val team = withContext(Dispatchers.IO) { teamRepository.find(teamKey) }
                requireActive(selection)
                if (team == null) {
                    _state.value = State(error = R.string.team_chat_unavailable)
                    return@launch
                }
                dao.observeMessages(teamKey, workspace.stableKey).collect { rows ->
                    requireActive(selection)
                    _state.update { it.copy(team = team, messages = rows, loading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "Could not load discussion history: ${e.javaClass.simpleName}")
                if (isActive(selection)) _state.value = State(error = R.string.team_chat_storage_error)
            }
        }
    }

    internal fun close() {
        room = null
        pending = null
        historyJob?.cancel()
        historyJob = null
        operation?.cancel()
        operation = null
        _state.value = State()
    }

    internal fun prepare(input: String) {
        val selection = room?.takeIf(::isActive) ?: return
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_INPUT_CHARS || _state.value.busy || pending != null || _state.value.team == null) return
        startOperation(selection) {
            val prepared = withContext(Dispatchers.IO) {
                val team = teamRepository.find(selection.teamKey) ?: return@withContext null
                Pending(team, text, runner.prepare(team))
            }
            requireActive(selection)
            when {
                prepared == null -> _state.update { it.copy(error = R.string.team_chat_unavailable) }
                prepared.discussion.speakers.isEmpty() -> _state.update {
                    it.copy(error = R.string.team_chat_no_models, failedMembers = prepared.discussion.skipped)
                }
                else -> {
                    pending = prepared
                    _state.update {
                        it.copy(preview = Preview(prepared.discussion.recipients, prepared.discussion.skipped))
                    }
                }
            }
        }
    }

    internal fun confirm(): Boolean {
        val selection = room?.takeIf(::isActive) ?: return false
        val approved = pending ?: return false
        if (_state.value.busy) return false
        dismissPreview()
        startOperation(selection) {
            val workspaceKey = selection.workspace.stableKey
            val history = dao.recentMessages(selection.teamKey, workspaceKey, TeamChatRunner.MAX_CONTEXT_MESSAGES)
                .asReversed().map { TeamChatRunner.Turn(it.speaker, it.avatar, it.content) }
            requireActive(selection)
            suspend fun store(speaker: String, avatar: String, role: String, content: String) {
                requireActive(selection)
                dao.insert(TeamChatMessageEntity(
                    teamName = approved.team.name,
                    speaker = speaker,
                    avatar = avatar,
                    role = role,
                    content = content,
                    teamKey = selection.teamKey,
                    workspaceKey = workspaceKey
                ))
            }
            store(approved.team.ownerName, approved.team.ownerAvatar, ROLE_OWNER, approved.text)
            val result = runner.run(
                team = approved.team,
                discussion = approved.discussion,
                topic = approved.text,
                history = history,
                beforeRequest = { requireActive(selection) },
                onDelta = { speaker, text ->
                    requireActive(selection)
                    _state.update { it.copy(streaming = StreamingTurn(speaker.name, speaker.avatar, text)) }
                },
                onTurn = { reply ->
                    store(reply.speaker, reply.avatar, ROLE_MEMBER, reply.content)
                    _state.update { it.copy(streaming = null) }
                }
            )
            if (result.plan != null) store("", "", ROLE_PLAN, result.plan)
            dao.trim(selection.teamKey, workspaceKey, MAX_MESSAGES)
            requireActive(selection)
            _state.update {
                it.copy(
                    error = if (result.plan == null) R.string.team_chat_no_proposal else null,
                    failedMembers = result.failedMembers
                )
            }
        }
        return true
    }

    internal fun dismissPreview() {
        pending = null
        _state.update { it.copy(preview = null) }
    }

    internal fun stop() {
        operation?.cancel()
    }

    internal fun canHandOff(proposal: String): Boolean =
        room?.let(::isActive) == true && !_state.value.busy && _state.value.proposal == proposal

    internal fun clearHistory() {
        val selection = room?.takeIf(::isActive) ?: return
        if (_state.value.busy || _state.value.loading || pending != null) return
        startOperation(selection) {
            dao.clear(selection.teamKey, selection.workspace.stableKey)
        }
    }

    private fun startOperation(selection: Room, action: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null, failedMembers = emptyList()) }
        operation = viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "Team discussion operation failed: ${e.javaClass.simpleName}")
                if (isActive(selection)) _state.update { it.copy(error = R.string.team_chat_failed) }
            } finally {
                if (room === selection) _state.update { it.copy(busy = false, streaming = null) }
            }
        }
    }

    private fun isActive(selection: Room): Boolean {
        val current = workspaceRepository.current.value ?: return false
        return room === selection && current.available && current.ref == selection.workspace &&
            current.backendId == ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID &&
            executionModeHolder.currentBackend().instanceId == selection.workspace.backendInstanceId
    }

    private fun requireActive(selection: Room) {
        if (!isActive(selection)) throw CancellationException("The discussion's workspace is no longer active")
    }

    internal companion object {
        private const val TAG = "TeamChatViewModel"
        private const val MAX_MESSAGES = 500
        const val MAX_INPUT_CHARS = 8000
        const val ROLE_OWNER = "owner"
        const val ROLE_MEMBER = "member"
        const val ROLE_PLAN = "plan"
    }
}
