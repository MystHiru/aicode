package com.aicode.feature.agent.domain.team

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.TeamChatDao
import com.aicode.feature.workspace.domain.LocalFileAccess
import java.nio.file.Files
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 团队定义仓库：全局目录 `~/.aicode/teams`，每个 `*.md` 一个团队。
 *
 * 团队跨项目复用（同一套角色分工在多个工作区都成立），因此只做全局一级，
 * 不做项目级覆盖——成员本身仍可来自 agents 的全局或项目级定义。
 */
@Singleton
class TeamRepository @Inject constructor(
    private val fileAccess: LocalFileAccess,
    private val teamChatDao: TeamChatDao
) {
    companion object {
        private const val TAG = "TeamRepository"
        private const val MAX_NAME_LENGTH = 40
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** 名称同时用作文件名，禁掉路径分隔符与保留字符。 */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }
    }

    private val teamsRoot = "/root/.aicode/teams"
    private val mutations = Mutex()

    /** 全部团队，按名称排序。目录不存在时返回空列表。 */
    fun listAll(): List<TeamDefinition> = runCatching {
        if (!fileAccess.isDirectory(teamsRoot)) return@runCatching emptyList()
        val base = teamsRoot.trimEnd('/')
        fileAccess.listFiles(teamsRoot)
            .filter { entry ->
                !entry.isDirectory && entry.name.endsWith(".md", ignoreCase = true) &&
                    entry.localFile?.let { Files.isRegularFile(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) } == true
            }
            .mapNotNull { entry ->
                runCatching { TeamDefinitionParser.parse(fileAccess, "$base/${entry.name}") }
                    .onFailure { FileLogger.w(TAG, "解析团队定义失败，已跳过: ${entry.name}", it) }
                    .getOrNull()
            }
            .distinctBy { it.key }
            .sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描团队定义目录失败: $teamsRoot", e)
        emptyList()
    }

    fun find(key: String): TeamDefinition? = listAll().firstOrNull { it.key == key }

    /**
     * 写入团队定义；现有团队保留标识和文件，显示名称不参与群聊归属。
     */
    suspend fun save(form: TeamDefinition.Form, teamKey: String?): TeamDefinition.SaveError? = mutations.withLock {
        val name = form.name.trim()
        if (!isValidName(name)) return@withLock TeamDefinition.SaveError.INVALID_NAME
        val cleaned = form.members
            .map { it.copy(name = it.name.trim(), agent = it.agent.trim()) }
            .filter { it.name.isNotBlank() && (it.usesAgent || it.model.isNotBlank()) }
            .distinctBy { it.name.lowercase() }
        // 超限要明确报错，不能静默截断——用户以为加上了，实际被丢了
        if (cleaned.size > TeamDefinition.MAX_MEMBERS) {
            return@withLock TeamDefinition.SaveError.MEMBER_LIMIT
        }
        val members = cleaned
        if (members.isEmpty()) return@withLock TeamDefinition.SaveError.NO_MEMBERS

        val teams = listAll()
        if (teams.any { it.key != teamKey && it.name.equals(name, ignoreCase = true) }) {
            return@withLock TeamDefinition.SaveError.NAME_CONFLICT
        }
        val existingFile = teamKey?.let { key ->
            teams.firstOrNull { it.key == key }?.filePath ?: return@withLock TeamDefinition.SaveError.IO_FAILED
        }
        val key = teamKey ?: UUID.randomUUID().toString()
        val text = TeamDefinitionParser.serialize(
            key = key,
            name = name,
            description = form.description,
            ownerName = form.ownerName.ifBlank { TeamDefinition.DEFAULT_OWNER_NAME },
            ownerAvatar = form.ownerAvatar,
            members = members,
            strategy = form.strategy
        )

        if (text.toByteArray(Charsets.UTF_8).size > TeamDefinitionParser.MAX_DEFINITION_BYTES) {
            return@withLock TeamDefinition.SaveError.IO_FAILED
        }
        try {
            val namedFile = "$teamsRoot/$name.md"
            val target = existingFile ?: namedFile.takeUnless(fileAccess::exists) ?: "$teamsRoot/$key.md"
            fileAccess.writeFile(target, text, overwrite = existingFile != null)
            null
        } catch (e: Exception) {
            FileLogger.w(TAG, "保存团队定义失败", e)
            TeamDefinition.SaveError.IO_FAILED
        }
    }

    suspend fun delete(key: String): Boolean = mutations.withLock {
        val path = find(key)?.filePath ?: return@withLock false
        try {
            fileAccess.delete(path)
        } catch (e: Exception) {
            FileLogger.w(TAG, "删除团队定义失败", e)
            return@withLock false
        }
        try {
            teamChatDao.clearTeam(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "清理团队讨论记录失败", e)
            return@withLock false
        }
        true
    }
}
