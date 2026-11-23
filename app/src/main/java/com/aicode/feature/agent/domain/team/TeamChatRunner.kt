package com.aicode.feature.agent.domain.team

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.GeminiAdapter
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.agent.domain.provider.toReasoningCapability
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class TeamChatRunner @Inject constructor(
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettings: DefaultModelSettingsRepository,
    private val anthropicApi: AnthropicApi,
    private val openAIApi: OpenAIApi,
    private val geminiApi: GeminiApi,
    private val keyRotator: ProviderKeyRotator,
    private val modelMetadataService: ModelMetadataService,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val llmCallRecordDao: LlmCallRecordDao
) {
    internal data class Turn(val speaker: String, val avatar: String, val content: String)

    internal class Speaker(
        val name: String,
        val avatar: String,
        val prompt: String,
        val agent: String?,
        val config: AIProviderConfig
    )

    internal class Discussion(val speakers: List<Speaker>, val skipped: List<String>) {
        val recipients: List<String> = speakers.map { "${it.config.name} / ${it.config.effectiveModel}" }.distinct()
    }

    internal data class Outcome(val plan: String?, val failedMembers: List<String>)

    internal suspend fun prepare(team: TeamDefinition): Discussion {
        val providers = aiProviderRepository.getAllProviders().first()
            .filter { it.isEnabled && it.hasUsableApiKey }
        val defaultId = defaultModelSettings.getDefaultProviderId()
        val defaultModel = defaultModelSettings.getDefaultModel()
        val fallback = providers.firstOrNull { it.id == defaultId } ?: providers.firstOrNull()
        val skipped = mutableListOf<String>()
        val speakers = team.members.mapNotNull { member ->
            val speaker = resolveSpeaker(member, providers, fallback, defaultId, defaultModel)
            if (speaker == null) skipped += member.name
            speaker
        }
        return Discussion(speakers, skipped)
    }

    private fun resolveSpeaker(
        member: TeamMember,
        providers: List<AIProviderConfig>,
        fallback: AIProviderConfig?,
        defaultId: String,
        defaultModel: String
    ): Speaker? {
        val definition = if (member.usesAgent) agentDefinitionRepository.find(member.agent) ?: return null else null
        val requestedProvider = if (definition != null) definition.providerId else member.providerId
        val config = if (requestedProvider.isNullOrBlank()) fallback else {
            providers.firstOrNull { it.id == requestedProvider }
                ?: providers.singleOrNull { it.name.equals(requestedProvider, ignoreCase = true) }
        } ?: return null
        val memberModel = if (definition != null) definition.model else member.model
        val model = memberModel?.takeIf { it.isNotBlank() }
            ?: defaultModel.takeIf { config.id == defaultId && requestedProvider.isNullOrBlank() && it.isNotBlank() }
            ?: config.effectiveModel.ifBlank { config.models.firstOrNull().orEmpty() }
        if (model.isBlank()) return null
        return Speaker(
            name = member.name,
            avatar = member.avatar.ifBlank { "logo:$model" },
            prompt = definition?.prompt ?: member.prompt,
            agent = definition?.name,
            config = config.copy(selectedModel = model)
        )
    }

    internal suspend fun run(
        team: TeamDefinition,
        discussion: Discussion,
        topic: String,
        history: List<Turn>,
        beforeRequest: () -> Unit,
        onDelta: (Speaker, String) -> Unit,
        onTurn: suspend (Turn) -> Unit
    ): Outcome {
        val turns = history.toMutableList()
        val failed = linkedSetOf<String>()
        val participants = linkedSetOf<Speaker>()

        repeat(2) {
            for (speaker in discussion.speakers) {
                currentCoroutineContext().ensureActive()
                if (speaker.name in failed) continue
                val reply = try {
                    beforeRequest()
                    val provider = activeProvider(speaker)
                    beforeRequest()
                    speak(team, speaker, provider, topic, turns, onDelta)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Team discussion request failed: ${e.javaClass.simpleName}")
                    failed += speaker.name
                    null
                }
                if (reply.isNullOrBlank()) continue
                val turn = Turn(speaker.name, speaker.avatar, reply)
                turns += turn
                participants += speaker
                onTurn(turn)
            }
        }

        val moderator = participants.firstOrNull() ?: return Outcome(null, failed.toList())
        val plan = try {
            beforeRequest()
            val provider = activeProvider(moderator)
            beforeRequest()
            val started = System.currentTimeMillis()
            val response = provider.complete(
                systemPrompt = "Summarize the team's discussion into a practical proposal for the user to review. " +
                    "Use the user's language, include verification steps and open questions, and do not claim work was performed.",
                messages = listOf(AgentMessage.UserMessage(content = contextFor(topic, turns))),
                tools = emptyList()
            )
            recordCall(provider, response, started)
            response.content.trim().take(MAX_REPLY_CHARS).takeIf { !response.isAborted && it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "Team proposal request failed: ${e.javaClass.simpleName}")
            null
        }
        return Outcome(plan, failed.toList())
    }

    private suspend fun speak(
        team: TeamDefinition,
        speaker: Speaker,
        provider: AIProvider,
        topic: String,
        turns: List<Turn>,
        onDelta: (Speaker, String) -> Unit
    ): String {
        val prompt = buildString {
            appendLine("You are ${speaker.name}, a member of ${team.name}. Discuss the user's topic; do not execute tasks or call tools.")
            appendLine("Reply in the user's language. Contribute a distinct, concise view and note trade-offs or open questions.")
            appendLine(team.description)
            appendLine(speaker.prompt.take(MAX_ROLE_CHARS))
            appendLine(team.strategy.take(MAX_ROLE_CHARS))
        }
        val started = System.currentTimeMillis()
        val buffer = StringBuilder()
        var response: AIResponse? = null
        provider.completeStream(
            systemPrompt = prompt,
            messages = listOf(AgentMessage.UserMessage(content = contextFor(topic, turns))),
            tools = emptyList(),
            reasoningEffort = null
        ).collect { chunk ->
            when (chunk) {
                is AIStreamChunk.TextDelta -> {
                    val delta = chunk.text.take(MAX_REPLY_CHARS - buffer.length)
                    if (delta.isNotEmpty()) {
                        buffer.append(delta)
                        onDelta(speaker, buffer.toString())
                    }
                }
                is AIStreamChunk.Final -> response = chunk.response
                else -> Unit
            }
        }
        val final = checkNotNull(response)
        recordCall(provider, final, started)
        check(!final.isAborted)
        return buffer.toString().ifBlank { final.content.take(MAX_REPLY_CHARS) }.trim()
    }

    private fun contextFor(topic: String, turns: List<Turn>) = buildString {
        appendLine("User topic:")
        appendLine(topic)
        appendLine("Discussion so far:")
        for (turn in turns.takeLast(MAX_CONTEXT_MESSAGES)) {
            appendLine("${turn.speaker}: ${turn.content.take(MAX_CONTEXT_MESSAGE_CHARS)}")
        }
    }

    private suspend fun activeProvider(speaker: Speaker): AIProvider = withContext(Dispatchers.IO) {
        // A pending discussion is approved for specific models and providers; a changed configuration needs a new preview.
        check(speaker.agent == null || agentDefinitionRepository.find(speaker.agent) != null)
        val config = speaker.config
        val current = aiProviderRepository.getProviderById(config.id)
        check(current != null && current.isEnabled && current.copy(selectedModel = config.selectedModel) == config)
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also { it.chatCacheKeyEnabled = config.openaiChatCacheKey }
        }
        provider.apiKey = checkNotNull(keyRotator.activeKey(config, null))
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        provider.maxOutputTokens = metadata.outputTokens
        provider.reasoningCapability = metadata.toReasoningCapability()
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        provider
    }

    private suspend fun recordCall(provider: AIProvider, response: AIResponse, started: Long) {
        try {
            llmCallRecordDao.insert(
                LlmCallRecordEntity(
                    sessionId = null,
                    providerId = provider.providerId.ifBlank { null },
                    model = provider.model,
                    kind = "team_chat",
                    inputTokens = response.inputTokens,
                    outputTokens = response.outputTokens,
                    cachedInputTokens = response.cachedInputTokens,
                    cacheCreationTokens = response.cacheCreationTokens,
                    ttfbMillis = null,
                    durationMillis = (System.currentTimeMillis() - started).toInt(),
                    status = if (response.isAborted) "failed" else "success",
                    errorMessage = null,
                    stopReason = response.stopReason,
                    createdAt = started
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "Could not record team discussion usage: ${e.javaClass.simpleName}")
        }
    }

    internal companion object {
        private const val TAG = "TeamChatRunner"
        const val MAX_CONTEXT_MESSAGES = 20
        private const val MAX_CONTEXT_MESSAGE_CHARS = 1200
        private const val MAX_REPLY_CHARS = 8000
        private const val MAX_ROLE_CHARS = 8000
    }
}
