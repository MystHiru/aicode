package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.ReasoningEffort
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 解析单个子代理定义文件（`agents/<name>.md`）：YAML frontmatter 为配置，正文为 agent 提示词。
 * 与 [com.aicode.feature.agent.domain.skill.SkillParser] 同一套 frontmatter 约定。
 */
object AgentDefinitionParser {
    private const val TAG = "AgentDefinitionParser"
    private const val MAX_DESC_CHARS = 500

    /** 正文为空视为非法定义（agent 必须有提示词），返回 null。 */
    fun parse(file: File): AgentDefinition? {
        val text = try {
            if (!file.isFile || !file.canRead()) return null
            file.readText()
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取子代理定义失败: ${file.absolutePath}", e)
            return null
        }

        val (frontmatter, body) = splitAndParseFrontmatter(text)
        val prompt = body.trim()
        if (prompt.isEmpty()) return null

        val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        return AgentDefinition(
            name = name,
            description = description,
            providerId = frontmatter["provider"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            model = frontmatter["model"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            reasoningEffort = frontmatter["reasoningEffort"]?.toString()?.trim()?.lowercase()
                ?.takeIf { it in VALID_EFFORTS },
            allowedTools = stringList(frontmatter["tools"]),
            disallowedTools = stringList(frontmatter["disallowedTools"]),
            inject = parseInject(frontmatter["inject"]),
            prompt = prompt,
            file = file
        )
    }

    /** 同时接受 YAML 列表与逗号分隔字符串两种写法。 */
    internal fun stringList(raw: Any?): List<String> = when (raw) {
        null -> emptyList()
        is List<*> -> raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    /** 缺省或全部取值非法时回退默认注入项，避免定义写错就丢掉全部上下文。 */
    internal fun parseInject(raw: Any?): Set<InjectPart> {
        val tokens = stringList(raw)
        if (tokens.isEmpty()) return AgentDefinition.DEFAULT_INJECT
        if (tokens.size == 1 && tokens.first().equals("none", ignoreCase = true)) return emptySet()
        val parsed = tokens.mapNotNull { InjectPart.fromToken(it) }.toSet()
        return parsed.ifEmpty { AgentDefinition.DEFAULT_INJECT }
    }

    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val map = try {
            Yaml().load<Map<String, Any>>(block) ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析子代理定义 YAML 失败", e)
            emptyMap()
        }
        return map to rest
    }

    private val VALID_EFFORTS = ReasoningEffort.entries.map { it.apiValue }.toSet()
}
