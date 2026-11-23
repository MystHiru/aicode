package com.aicode.feature.agent.domain.team

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.util.UUID
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * 解析团队定义文件（`teams/<name>.md`）：YAML frontmatter 为编排配置，正文为群规。
 *
 * `members` 两种写法都接受，方便手写：
 * - 字符串列表：`["Explore", "Reviewer"]`——引用同名子代理定义；
 * - 对象列表：`[{name: 小测, agent: Explore}, {name: 架构师, provider: OpenAI, model: gpt-5, prompt: ...}]`
 */
object TeamDefinitionParser {
    private const val TAG = "TeamDefinitionParser"

    fun parse(provider: FileAccessProvider, filePath: String): TeamDefinition? {
        val text = runCatching { provider.readBytes(filePath, MAX_DEFINITION_BYTES).toString(Charsets.UTF_8) }.getOrElse {
            FileLogger.w(TAG, "读取团队定义失败: $filePath", it)
            return null
        }

        val (frontmatter, body) = splitAndParseFrontmatter(text) ?: return null

        val fileName = filePath.substringAfterLast('/')
        val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.')
        val description = (frontmatter["description"]?.toString() ?: "")
            .take(TeamDefinition.MAX_DESC_CHARS)
        val members = parseMembers(frontmatter["members"])
        if (members.isEmpty()) {
            FileLogger.w(TAG, "团队定义没有可用成员，已跳过: $filePath")
            return null
        }

        return TeamDefinition(
            name = name,
            description = description,
            ownerName = frontmatter["ownerName"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: TeamDefinition.DEFAULT_OWNER_NAME,
            ownerAvatar = frontmatter["ownerAvatar"]?.toString()?.trim().orEmpty(),
            members = members,
            strategy = body.trim(),
            filePath = filePath,
            key = frontmatter["id"]?.toString()?.let { raw ->
                runCatching { UUID.fromString(raw).toString() }.getOrNull()
            } ?: UUID.nameUUIDFromBytes(filePath.toByteArray(Charsets.UTF_8)).toString()
        )
    }

    /** 成员既可能是字符串（引用子代理），也可能是对象（自定义名字/头像/模型）。 */
    internal fun parseMembers(raw: Any?): List<TeamMember> {
        val list = when (raw) {
            is List<*> -> raw
            is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        return list.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> {
                    val name = item["name"]?.toString()?.trim().orEmpty()
                    val agent = item["agent"]?.toString()?.trim().orEmpty()
                    val model = item["model"]?.toString()?.trim().orEmpty()
                    if (name.isBlank() && agent.isBlank() && model.isBlank()) return@mapNotNull null
                    TeamMember(
                        name = name.ifBlank { agent.ifBlank { model } },
                        avatar = item["avatar"]?.toString()?.trim().orEmpty(),
                        agent = agent,
                        providerId = item["provider"]?.toString()?.trim().orEmpty(),
                        model = model,
                        prompt = item["prompt"]?.toString()?.trim().orEmpty(),
                        isLead = item["lead"]?.toString()?.trim()?.equals("true", ignoreCase = true) == true
                    )
                }
                else -> {
                    val token = item?.toString()?.trim().orEmpty()
                    if (token.isEmpty()) null else TeamMember(name = token, agent = token)
                }
            }
        }
            .distinctBy { it.name.lowercase() }
            .take(TeamDefinition.MAX_MEMBERS)
    }

    /** 把设置页表单写回团队定义文件文本（frontmatter + 正文）。 */
    fun serialize(
        key: String,
        name: String,
        description: String,
        ownerName: String,
        ownerAvatar: String,
        members: List<TeamMember>,
        strategy: String
    ): String = buildString {
        appendLine("---")
        appendLine("id: ${quote(key)}")
        appendLine("name: ${quote(name)}")
        appendLine("description: ${quote(description)}")
        appendLine("ownerName: ${quote(ownerName)}")
        if (ownerAvatar.isNotBlank()) appendLine("ownerAvatar: ${quote(ownerAvatar)}")
        appendLine("members:")
        members.forEach { member ->
            appendLine("  - name: ${quote(member.name)}")
            if (member.avatar.isNotBlank()) appendLine("    avatar: ${quote(member.avatar)}")
            if (member.agent.isNotBlank()) appendLine("    agent: ${quote(member.agent)}")
            if (member.providerId.isNotBlank()) appendLine("    provider: ${quote(member.providerId)}")
            if (member.model.isNotBlank()) appendLine("    model: ${quote(member.model)}")
            if (member.isLead) appendLine("    lead: true")
            if (member.prompt.isNotBlank()) {
                // 人设可能多行：用 YAML 块标量，避免引号里塞换行
                appendLine("    prompt: |")
                member.prompt.trim().lines().forEach { appendLine("      $it") }
            }
        }
        appendLine("---")
        appendLine(strategy.trim())
    }

    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String>? {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val loaded = try {
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 8
            }
            Yaml(SafeConstructor(options)).load<Any>(block)
        } catch (e: Throwable) {
            // 必须捕 Throwable：snakeyaml 类初始化失败会抛 NoClassDefFoundError（Error 而非
            // Exception），一个坏掉的团队定义不该把调用方线程带走。
            FileLogger.w(TAG, "解析团队定义 YAML 失败", e)
            null
        }
        val map = loaded as? Map<*, *>
        if (loaded != null && map == null) {
            FileLogger.w(TAG, "团队定义 frontmatter 顶层不是键值表，已跳过")
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return (map ?: emptyMap<String, Any>()) as Map<String, Any> to rest
    }

    internal const val MAX_DEFINITION_BYTES = 128L * 1024

    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .trim()
        return "\"$escaped\""
    }
}
