package com.aicode.feature.agent.domain.team

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.GeminiAdapter
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.agent.domain.provider.toReasoningCapability
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 团队自动组队：只给一个群名（可带一句方向），让模型自己排出成员、各自负责什么、怎么协作。
 *
 * 组队是一次性请求：模型拿到「可用子代理 + 已配置模型清单」，输出一份 JSON，
 * 由这里转成 [TeamDefinition.Form] 回填到编辑页——用户确认或直接保存都行。
 *
 * 角色尽量落在现成的子代理定义上（工具集是现成的）；需要不同模型视角时，
 * 才从已配置模型里拉人。
 */
@Singleton
class TeamComposer @Inject constructor(
    private val aiProviderRepository: AIProviderRepository,
    private val anthropicApi: AnthropicApi,
    private val openAIApi: OpenAIApi,
    private val geminiApi: GeminiApi,
    private val keyRotator: ProviderKeyRotator,
    private val modelMetadataService: ModelMetadataService,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val agentDefinitionRepository: AgentDefinitionRepository
) {
    companion object {
        private const val TAG = "TeamComposer"
        private const val MAX_COMPOSE_MEMBERS = 5
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 生成一份团队编排。
     *
     * @param teamName 用户填的群名，作为分组主题
     * @param hint 可选的补充说明（比如「主要是写前端」「要能审代码」）
     */
    suspend fun compose(
        teamName: String,
        hint: String,
        fallbackProviderId: String?,
        fallbackModel: String?
    ): kotlin.Result<TeamDefinition.Form> = runCatching {
        val providers = aiProviderRepository.getAllProviders().first()
            .filter { it.isEnabled && it.hasUsableApiKey }
        if (providers.isEmpty()) {
            error("还没有可用的 AI 供应商（需要已启用且填了 Key），先在设置里配一个")
        }

        val modelCatalog = providers.joinToString("\n") { provider ->
            val models = provider.models.take(12).joinToString("、")
            "- ${provider.name}（id=${provider.id}）：${models.ifBlank { "（模型列表为空，请先拉取模型）" }}"
        }
        val agentCatalog = agentDefinitionRepository.listEnabled().joinToString("\n") { entry ->
            "- ${entry.definition.name}"
        }.ifBlank { "（没有可用的子代理定义）" }

        val prompt = buildString {
            appendLine("你要为一个叫「$teamName」的协作小组排班，决定由谁组成、各自负责什么、怎么配合。")
            if (hint.isNotBlank()) appendLine("用户的补充要求：$hint")
            appendLine()
            appendLine("可作为讨论成员的子代理：")
            appendLine(agentCatalog)
            appendLine()
            appendLine("可用的模型（providerId + 模型名）：")
            appendLine(modelCatalog)
            appendLine()
            appendLine(
                """
                规则：
                1. 从清单中选择成员，人数不超过 $MAX_COMPOSE_MEMBERS 人。
                2. 每个成员应提供不同的讨论视角，例如组织思路、提出取舍、检查风险或审阅结论。
                3. 写明如何交换意见和整理方案。讨论中不会调用工具或派发任务。
                4. 只输出 JSON，不要任何解释、不要代码围栏。

                输出格式：
                {
                  "description": "这个组负责什么，一两句",
                  "members": [
                    {"name": "显示名", "agent": "子代理名", "prompt": "这个成员在本组里的职责"},
                    {"name": "显示名", "provider": "providerId", "model": "模型名", "prompt": "这个成员在本组里的职责"}
                  ],
                  "strategy": "协作方式，说明分工、先后、返工规则"
                }
                """.trimIndent()
            )
        }

        val config = pickConfig(providers, fallbackProviderId, fallbackModel)
        val provider = createProvider(config)
        val response = provider.complete(
            systemPrompt = "你是资深的工程团队编排者，只输出合法 JSON。",
            messages = listOf(AgentMessage.UserMessage(content = prompt)),
            tools = emptyList()
        )
        val raw = response.content.trim()
        parseForm(raw, teamName) ?: error("模型没有返回可用的组队结果，请重试")
    }.onFailure { error ->
        if (error is CancellationException) throw error
        FileLogger.w(TAG, "Team suggestion failed: ${error.javaClass.simpleName}")
    }

    /** 从模型输出中取出 JSON 并转成表单。 */
    private fun parseForm(raw: String, teamName: String): TeamDefinition.Form? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = raw.substring(start, end + 1)
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null

        val members = (root["members"] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val agentName = (obj["agent"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val modelName = (obj["model"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            TeamMember(
                name = name,
                // 直接用模型的成员默认显示上游厂商 logo
                avatar = if (agentName.isNotBlank() || modelName.isBlank()) "" else "logo:" + modelName,
                agent = agentName,
                providerId = (obj["provider"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
                model = modelName,
                prompt = (obj["prompt"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            )
        }.orEmpty().take(MAX_COMPOSE_MEMBERS)
        if (members.isEmpty()) return null

        return TeamDefinition.Form(
            name = teamName,
            description = (root["description"] as? JsonPrimitive)?.contentOrNull?.trim()
                .orEmpty().take(TeamDefinition.MAX_DESC_CHARS),
            ownerName = TeamDefinition.DEFAULT_OWNER_NAME,
            ownerAvatar = "",
            members = members,
            strategy = (root["strategy"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        )
    }

    private fun pickConfig(
        providers: List<AIProviderConfig>,
        fallbackProviderId: String?,
        fallbackModel: String?
    ): AIProviderConfig {
        val picked = providers.firstOrNull { it.id == fallbackProviderId }
        val config = picked ?: providers.first()
        val model = when {
            picked != null && !fallbackModel.isNullOrBlank() -> fallbackModel
            config.effectiveModel.isNotBlank() -> config.effectiveModel
            else -> config.models.firstOrNull().orEmpty()
        }
        return config.copy(selectedModel = model)
    }

    private suspend fun createProvider(config: AIProviderConfig): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi)
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi)
        }
        provider.apiKey = keyRotator.activeKey(config, null)
            ?: throw IllegalStateException("「${config.name}」的 Key 都在冷却中")
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
        return provider
    }
}
