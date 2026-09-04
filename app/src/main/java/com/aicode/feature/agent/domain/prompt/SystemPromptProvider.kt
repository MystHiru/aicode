package com.aicode.feature.agent.domain.prompt

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.InjectPart
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按模块组装系统提示词：稳定基线放最前（享受 KV Cache），仅日期为低频变化。
 * 每个 Source 维护内容缓存，避免重复读取与格式化。
 */
@Singleton
class SystemPromptProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val skillRepository: SkillRepository,
    private val memoryRepository: MemoryRepository,
    private val containerInstaller: ContainerInstaller,
    private val agentDefinitionRepository: AgentDefinitionRepository
) {
    // 抽象独立的 Source
    interface PromptSource {
        fun build(ctx: AgentContext): String?
    }

    private inner class StaticRuleSource : PromptSource {
        private val fragments = listOf(
            "00-identity.md",
            "10-communication.md",
            "15-project-rules.md",
            "20-coding-discipline.md",
            "30-comments.md",
            "40-approach.md",
            "50-safety.md",
            "60-tools-and-paths.md",
            "70-skills-and-mcp.md"
        )
        @Volatile private var cached: String? = null

        override fun build(ctx: AgentContext): String {
            return cached ?: fragments.joinToString("\n\n") { name ->
                resolvePrompt(name)
                    .replace(LEADING_COMMENT, "")
                    .trim()
            }.also { cached = it }
        }
    }

    private inner class ActiveSkillsSource : PromptSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只扫一次磁盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空内容用 "" 占位以区分"未缓存"。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val skills = try { skillRepository.listSkills() } catch (e: Exception) { return null }
            if (skills.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val list = skills.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "（无描述）" }}" }
            val content = "可用技能 (skills)（格式为 名称: 何时使用；相关时用 loadSkill 传入名称取完整正文，详见上文「技能」说明）：\n当清单里有与当前任务对口的技能时，在合适的时机主动 `loadSkill` 加载并按其正文行事，让技能辅助你更规范、更高效地完成工作，而不是仅凭默认流程硬做。\n$list"
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** 子代理专用精简基线：只保留工具用法、路径约定与安全边界，不含模式切换、结尾总结等主代理专属规则。 */
    private inner class SubAgentBaseSource : PromptSource {
        @Volatile private var cached: String? = null

        override fun build(ctx: AgentContext): String =
            cached ?: resolvePrompt(SUBAGENT_BASE_FILE)
                .replace(LEADING_COMMENT, "")
                .trim()
                .also { cached = it }
    }

    /**
     * 可用子代理清单（仅注入主代理）：让 AI 知道有哪些自定义 agent 可派发。
     * 会话级缓存，避免每轮扫盘导致 system prompt 抖动打断 KV 缓存。
     */
    private inner class SubAgentListSource : PromptSource {
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val entries = try {
                agentDefinitionRepository.listEnabled()
            } catch (e: Exception) {
                FileLogger.w(TAG, "扫描子代理定义失败: ${e.message}", e)
                return null
            }
            if (entries.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val list = entries.joinToString("\n") { entry ->
                "- ${entry.definition.name}: ${entry.definition.description.ifBlank { "（无描述）" }}"
            }
            val content = "可用子代理 (subagents)（格式为 名称: 何时派发；用 `task(action=\"create\", agent=\"名称\", ...)` 派发）：\n" +
                "这些子代理有各自专属的提示词、模型与工具集，任务与某个 agent 对口时优先按名派发，而不是用默认通用子代理。\n$list"
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    private inner class ProjectRuleSource : PromptSource {
        @Volatile private var cached: String? = null
        private var lastModified: Long = 0
        private var lastProjectRoot: String = ""

        override fun build(ctx: AgentContext): String? {
            if (ctx.projectRoot.isBlank()) return null
            val agentsFile = File(ctx.projectRoot, AGENTS_FILE)
            val claudeFile = File(ctx.projectRoot, CLAUDE_FILE)
            val file = when {
                agentsFile.isFile && agentsFile.canRead() -> agentsFile to AGENTS_FILE
                claudeFile.isFile && claudeFile.canRead() -> claudeFile to CLAUDE_FILE
                else -> return null
            }
            
            val currentMod = file.first.lastModified()
            // 如果文件未修改且路径一致，直接返回快照基线，避免重复读取与格式化
            if (ctx.projectRoot == lastProjectRoot && currentMod == lastModified && cached != null) {
                return cached
            }
            
            val text = try { file.first.readText() } catch (e: Exception) { return null }
            if (text.isBlank()) return null
            
            val body = if (text.length > MAX_AGENTS_CHARS) {
                text.take(MAX_AGENTS_CHARS) + "\n…（${file.second} 过长，已截断）"
            } else {
                text
            }
            cached = "项目规则 (来自 ~/workspace/${file.second}，务必遵守):\n${body.trim()}"
            lastModified = currentMod
            lastProjectRoot = ctx.projectRoot
            return cached
        }
    }

    private inner class WorkspaceSource : PromptSource {
        override fun build(ctx: AgentContext): String {
            val hasWorkspace = ctx.projectRoot.isNotBlank()
            return "当前上下文:\n- 项目根目录: ${if (hasWorkspace) "~/workspace" else "（未选择工作区）"}"
        }
    }

    private inner class CurrentTimeSource : PromptSource {
        override fun build(ctx: AgentContext): String {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val currentTime = java.time.ZonedDateTime.now().format(formatter)
            return "[System] 当前本地时间: $currentTime"
        }
    }

    private inner class MemoryListSource : PromptSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只读一次盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空内容用 "" 占位以区分"未缓存"。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val memories = try { memoryRepository.listMemories(ctx.projectRoot) } catch (e: Exception) { return null }
            if (memories.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val globalMemories = memories.filter { it.scope == MemoryScope.GLOBAL }
            val projectMemories = memories.filter { it.scope == MemoryScope.PROJECT }

            val content = buildString {
                if (globalMemories.isNotEmpty()) {
                    append("全局记忆 (跨项目个人偏好，需要详情时用 memory(action=read, name=xxx, scope=global))：\n")
                    globalMemories.forEach { append("- ${it.name}: ${it.description.ifBlank { "无" }}\n") }
                }
                if (projectMemories.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("项目记忆 (当前项目专属，需要详情时用 memory(action=read, name=xxx, scope=project))：\n")
                    projectMemories.forEach { append("- ${it.name}: ${it.description.ifBlank { "无" }}\n") }
                }
            }.trimEnd()

            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** 会话级缓存 key：同一会话同一工作区共享一份快照，避免每轮重扫磁盘导致 system prompt 变化。 */
    private data class SourceCacheKey(val sessionId: String?, val projectRoot: String)

    private val staticRuleSource = StaticRuleSource()
    private val subAgentBaseSource = SubAgentBaseSource()
    private val subAgentListSource = SubAgentListSource()
    private val memoryListSource = MemoryListSource()
    private val activeSkillsSource = ActiveSkillsSource()
    private val projectRuleSource = ProjectRuleSource()
    private val workspaceSource = WorkspaceSource()
    private val currentTimeSource = CurrentTimeSource()

    fun build(agentContext: AgentContext): String {
        agentContext.agentDefinition?.let { return buildForSubAgent(it, agentContext) }

        // 1. 获取各个 Source 的基线快照。
        val staticContent = staticRuleSource.build(agentContext)
        val skillsContent = activeSkillsSource.build(agentContext)
        val subAgentsContent = subAgentListSource.build(agentContext)
        val memoriesContent = memoryListSource.build(agentContext)
        val projectRules = projectRuleSource.build(agentContext)
        
        // 2. Workspace 上下文固定输出（内容已精简，无需快照占位）
        val effectiveWorkspaceContent = workspaceSource.build(agentContext)

        // 3. 组装最终提示词：把稳定不变的重头基线放最前面（享受 KV Cache），变化部分放末尾
        return buildString {
            append(staticContent)

            skillsContent?.let {
                append("\n\n")
                append(it)
            }

            subAgentsContent?.let {
                append("\n\n")
                append(it)
            }

            memoriesContent?.let {
                append("\n\n")
                append(it)
            }

            projectRules?.let {
                append("\n\n")
                append(it)
            }

            append("\n\n")
            append(effectiveWorkspaceContent)
            append("\n\n")
            append(currentTimeSource.build(agentContext))
        }
    }

    /**
     * 按子代理定义组装提示词：只注入 [AgentDefinition.inject] 列出的片段，再接 agent 自己的提示词。
     * 不注入可用子代理清单（子代理不能嵌套派发）。
     */
    private fun buildForSubAgent(
        definition: AgentDefinition,
        agentContext: AgentContext
    ): String = buildString {
        if (InjectPart.MAIN_RULES in definition.inject) {
            append(staticRuleSource.build(agentContext))
            append("\n\n")
        }
        if (InjectPart.BASE in definition.inject) {
            append(subAgentBaseSource.build(agentContext))
            append("\n\n")
        }

        append("当前角色 (subagent: ${definition.name})：你是一个由主代理派发的子代理，拥有独立上下文，看不到主对话历史。")
        append("专注完成本会话交给你的任务，并在最后一条回复里给出完整结论——主代理只能读到你的最后一条回复，中间过程与工具结果它看不到。\n\n")
        append(definition.prompt)

        if (InjectPart.SKILLS in definition.inject) {
            activeSkillsSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }
        if (InjectPart.MEMORY in definition.inject) {
            memoryListSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }
        if (InjectPart.PROJECT_RULES in definition.inject) {
            projectRuleSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }

        append("\n\n")
        append(workspaceSource.build(agentContext))
        append("\n\n")
        append(currentTimeSource.build(agentContext))
    }

    /**
     * 按优先级解析单个提示词片段：prompts.custom/（用户覆盖） > prompts/（本地默认副本） > assets（内置兑底）。
     * 本地副本由 [ContainerInstaller.extractPrompts] 在启动时全量释放，
     * App 升级后随之更新；用户只需在 prompts.custom/ 放同名文件即可覆盖，无需改内置。
     */
    fun resolvePrompt(name: String): String {
        val customDir = File(containerInstaller.aicodeDir, "prompts.custom")
        val customFile = File(customDir, name)
        if (customFile.isFile) {
            try {
                return customFile.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                FileLogger.w(TAG, "读取自定义提示词失败 $name: ${e.message}", e)
            }
        }
        val defaultFile = File(File(containerInstaller.aicodeDir, "prompts"), name)
        if (defaultFile.isFile) {
            try {
                return defaultFile.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                FileLogger.w(TAG, "读取本地提示词失败 $name: ${e.message}", e)
            }
        }
        return context.assets.open("prompts/$name").bufferedReader().use { it.readText() }
    }

    private companion object {
        const val TAG = "SystemPromptProvider"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val SUBAGENT_BASE_FILE = "90-subagent-base.md"
        const val MAX_AGENTS_CHARS = 32_000
        /** 会话级缓存 key 数量上限：超过后整体清空，仅防长期累积；正常会话数远小于此。 */
        const val SOURCE_CACHE_LIMIT = 32
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
