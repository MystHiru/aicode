package com.aicode.feature.agent.domain.team

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.TeamChatDao
import com.aicode.feature.agent.data.local.entity.TeamChatMessageEntity
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把团队任务交给主执行者去跑。
 *
 * 从 `task(action="team_execute")` 抽出来的服务：群聊窗口收敛后也要能自己开工，
 * 而不是逼用户切回主对话再喊一遍「开工」。
 *
 * 执行者拿到的是**完整 agent 定义**——工具全量、注入 mainRules/skills/memory/projectRules，
 * 与普通会话一致，不是精简版子代理。
 */
@Singleton
class TeamExecutor @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val eventBus: SubAgentEventBus,
    private val teamChatDao: TeamChatDao,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val aiProviderRepository: AIProviderRepository,
    private val teamExecutionRegistry: TeamExecutionRegistry
) {
    companion object {
        private const val TAG = "TeamExecutor"
        private const val TITLE_MAX = 30
    }

    data class Outcome(val subSessionId: String, val leadName: String)

    /**
     * 派发一次执行。
     *
     * @param parentSessionId 父会话 id；群聊由哪个会话承载执行，就传哪个会话。
     */
    suspend fun execute(
        team: TeamDefinition,
        task: String,
        parentSessionId: String
    ): kotlin.Result<Outcome> = runCatching {
        if (!eventBus.tryReserveSlots(1)) {
            error("子代理已达上限（最多 ${SubAgentEventBus.MAX_RUNNING} 个同时运行），等当前任务完成再派")
        }
        // 父会话必须是根会话：用户在群聊里时 currentSessionId 可能正停在某个执行子会话上，
        // 直接拿来当父会变成「执行套执行」，通知与结果回写都会错位。沿 parentId 上溯到最顶层。
        var parent = sessionUseCase.getSessionById(parentSessionId)
            ?: error("父会话不存在，无法派发执行")
        var guard = 0
        while (parent.parentId != null && guard++ < 16) {
            parent = sessionUseCase.getSessionById(parent.parentId!!) ?: break
        }

        val lead = pickLead(team) ?: error("团队「${team.name}」没有成员")

        val definition = buildLeadDefinition(team, lead)
        teamExecutionRegistry.registerDefinition(definition.name, definition)

        val providers = runCatching { aiProviderRepository.getAllProviders().first() }.getOrDefault(emptyList())
        val matchedProviderId = definition.providerId?.let { raw ->
            providers.firstOrNull { it.id == raw }?.id
                ?: providers.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.id
        }

        val subSession = sessionUseCase.newSubSessionEntity(
            title = "执行·${lead.name}".take(TITLE_MAX),
            parentId = parent.id,
            parent = parent,
            subagentType = definition.name,
            providerId = matchedProviderId ?: parent.providerId,
            model = definition.model ?: parent.model,
            reasoningEffort = null,
            mode = definition.mode
        )
        sessionUseCase.upsertSession(subSession)
        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSession.id,
                parentSessionId = parent.id,
                type = SubAgentEventType.SPAWNED,
                detail = task
            )
        )
        val avatar = lead.avatar.ifBlank {
            lead.model.takeIf { it.isNotBlank() }?.let { "logo:" + it }.orEmpty()
        }
        teamExecutionRegistry.register(
            subSessionId = subSession.id,
            teamName = team.name,
            memberName = lead.name,
            avatar = avatar
        )
        runCatching {
            teamChatDao.insert(
                TeamChatMessageEntity(
                    teamName = team.name,
                    speaker = lead.name,
                    avatar = avatar,
                    role = "member",
                    refSessionId = subSession.id,
                    content = "[执行] ${lead.name} 接手：${task.take(120)}"
                )
            )
        }
        FileLogger.i(TAG, "团队执行已派发: team=${team.name} lead=${lead.name} session=${subSession.id}")
        Outcome(subSessionId = subSession.id, leadName = lead.name)
    }

    /**
     * 选主执行者。
     *
     * 优先标了「主执行」且**真的能动手**的成员；没标就选第一个有写/执行能力的。
     * 以前是「标了主执行的 → 第一个引用子代理的」，不看能力——团队里第一个是只读的 Explore 时，
     * 它会被选成执行者，跑完只给你一份分析报告，文件一个字节都不动，还很难看出是选错人。
     */
    private fun pickLead(team: TeamDefinition): TeamMember? {
        fun canExecute(member: TeamMember): Boolean {
            // 直接用模型的成员挂全量工具，天然能干活
            if (!member.usesAgent) return true
            val definition = agentDefinitionRepository.findIncludingDisabled(member.agent) ?: return false
            return definition.canModifyOrExecute()
        }
        return team.members.firstOrNull { it.isLead && canExecute(it) }
            ?: team.members.firstOrNull { canExecute(it) }
            ?: team.members.firstOrNull { it.isLead }
            ?: team.members.firstOrNull()
    }

    /**
     * 合成执行者的完整定义：提示词 = 团队上下文 + 角色 + 执行要求；
     * 注入项对齐主代理，工具不限，行为同普通会话。
     */
    private fun buildLeadDefinition(team: TeamDefinition, member: TeamMember): AgentDefinition {
        val base = if (member.usesAgent) agentDefinitionRepository.findIncludingDisabled(member.agent) else null
        val rolePrompt = base?.prompt?.takeIf { it.isNotBlank() } ?: member.prompt
        val prompt = buildString {
            appendLine("你是「${team.name}」团队的执行者。团队已经讨论收敛，现在由你一个人把结果做出来。")
            if (team.description.isNotBlank()) appendLine("团队职责：${team.description}")
            appendLine()
            appendLine("你的角色")
            appendLine(rolePrompt.ifBlank { "（无特别设定，按任务本身判断该怎么做）" })
            if (team.strategy.isNotBlank()) {
                appendLine()
                appendLine("团队协作约定")
                appendLine(team.strategy)
            }
            appendLine()
            appendLine("执行方式")
            appendLine("- 你手里有完整工具：自己判断读哪些文件、改哪些地方、跑什么验证，直到交付可用结果。不要停在「建议怎么做」。")
            appendLine("- 能自己查清的自己查，不要为细节反复请示。")
            appendLine("- 真正阻塞时才用 messageParent 把问题发回群里：缺关键信息、方案互相冲突、同一条命令连续失败两次以上。")
            appendLine("- 交付时说明：做了什么、改了哪些文件、跑了什么验证、还遗留什么。")
        }
        return AgentDefinition(
            name = member.name,
            description = "团队执行者（${team.name}）",
            providerId = member.providerId.ifBlank { base?.providerId },
            model = member.model.ifBlank { base?.model },
            reasoningEffort = base?.reasoningEffort,
            mode = base?.mode,
            allowedTools = emptyList(),
            disallowedTools = emptyList(),
            inject = setOf(
                InjectPart.MAIN_RULES,
                InjectPart.SKILLS,
                InjectPart.MEMORY,
                InjectPart.PROJECT_RULES
            ),
            prompt = prompt,
            filePath = null
        )
    }
}
