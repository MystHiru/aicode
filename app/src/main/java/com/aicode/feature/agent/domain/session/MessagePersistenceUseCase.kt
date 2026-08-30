package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagePersistenceUseCase @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val agentDatabase: AgentDatabase
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** agent_messages 表变更版本号：任何写路径（含 rewind 删除、压缩标记、冷启动清理）触发递增，
     *  作为 [buildHistory] 缓存的失效信号。InvalidationTracker 监听表级变更，覆盖所有 DAO 写入。 */
    private val dbVersion = java.util.concurrent.atomic.AtomicLong(0)
    private val historyCache = HashMap<String, HistoryEntry>()

    private class HistoryEntry(
        val version: Long,
        val pendingToolMarker: String,
        val messages: List<AgentMessage>
    )

    init {
        agentDatabase.invalidationTracker.addObserver(
            object : androidx.room.InvalidationTracker.Observer(arrayOf("agent_messages")) {
                override fun onInvalidated(tables: Set<String>) {
                    dbVersion.incrementAndGet()
                }
            }
        )
    }

    /** 内嵌图片 base64 的 LRU 缓存：key = path:size:lastModified，避免工具循环中
     *  每轮 LLM 调用都重读文件 + base64 编码。带条目与总字节双重上限。 */
    private val imageBase64Cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_IMAGE_CACHE_ENTRIES
    }
    private var imageCacheBytes = 0L

    private fun cachedImageBase64(key: String): String? = synchronized(imageBase64Cache) { imageBase64Cache[key] }

    private fun cacheImageBase64(key: String, value: String) {
        synchronized(imageBase64Cache) {
            val old = imageBase64Cache.put(key, value)
            imageCacheBytes += value.length - (old?.length ?: 0)
            while (imageCacheBytes > MAX_IMAGE_CACHE_BYTES && imageBase64Cache.isNotEmpty()) {
                val it = imageBase64Cache.entries.iterator()
                val eldest = it.next()
                it.remove()
                imageCacheBytes -= eldest.value.length
            }
        }
    }

    // 单调递增时间戳：保证同毫秒内多次落库的顺序稳定（assistant 永远在其 tool 结果之前）。
    @Volatile
    private var lastTimestamp = 0L

    @Synchronized
    fun nextTimestamp(): Long {
        val now = System.currentTimeMillis()
        val ts = if (now > lastTimestamp) now else lastTimestamp + 1
        lastTimestamp = ts
        return ts
    }

    suspend fun persist(
        sessionId: String,
        role: MessageRole,
        content: String,
        id: String = UUID.randomUUID().toString(),
        toolCalls: List<ToolCall> = emptyList(),
        toolCallId: String? = null,
        toolName: String? = null,
        toolArgs: String? = null,
        isError: Boolean = false,
        reasoning: String? = null,
        signature: String? = null,
        thinkingBlocksJson: String? = null,
        attachments: List<AgentAttachment> = emptyList(),
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        isCompacted: Boolean = false
    ) {
        agentMessageDao.insert(
            AgentMessageEntity(
                id = id,
                sessionId = sessionId,
                role = role.name,
                content = sanitizeContent(content),
                timestamp = nextTimestamp(),
                toolCallsJson = if (toolCalls.isNotEmpty()) json.encodeToString(toolCalls) else null,
                toolCallId = toolCallId,
                toolName = toolName,
                toolArgs = toolArgs,
                isError = isError,
                reasoning = reasoning?.let { sanitizeContent(it) },
                signature = signature,
                thinkingBlocksJson = thinkingBlocksJson,
                attachmentsJson = if (attachments.isNotEmpty()) json.encodeToString(attachments) else null,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                isCompacted = isCompacted
            )
        )
    }

    suspend fun updateContent(messageId: String, newContent: String) {
        agentMessageDao.updateMessageContent(messageId, newContent)
    }

    companion object {
        /**
         * 单条消息字段持久化上限（字符数）。远小于 SQLite CursorWindow 单行约 2MB 的硬限制，
         * 防止生图/多模态模型返回的超大 base64 图片撑爆数据行，导致读取消息时抛
         * [android.database.sqlite.SQLiteBlobTooBigException] 使应用启动即崩。
         */
        const val MAX_CONTENT_CHARS = 200_000
        const val IMAGE_OMITTED_MARKER = "[图片已省略：内嵌图片数据过大]"
        const val CONTENT_TRUNCATED_MARKER = "…[内容过长，已截断]"
        /** 图片 base64 缓存条目上限。 */
        private const val MAX_IMAGE_CACHE_ENTRIES = 12
        /** 图片 base64 缓存总字节上限（base64 为原始大小的 ~4/3，48MB 约可存 36MB 原始图片）。 */
        private const val MAX_IMAGE_CACHE_BYTES = 48L * 1024 * 1024

        /** 内嵌 base64 图片 data URL（`data:image/...;base64,...`）。 */
        private val INLINE_BASE64_IMAGE = Regex("""data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\r\n]+""")

        /**
         * 落库前的内容净化，为所有 provider/模型提供统一兜底防线：
         * 1. 剥离内嵌的 base64 图片 data URL（替换为占位说明），此类内容本不该进数据库文本；
         * 2. 剥离后仍超长的内容截断到 [MAX_CONTENT_CHARS]，避免任何超大行触发 CursorWindow 崩溃。
         */
        internal fun sanitizeContent(raw: String): String {
            if (raw.length <= MAX_CONTENT_CHARS && !raw.contains("data:image/", ignoreCase = true)) {
                return raw
            }
            var text = INLINE_BASE64_IMAGE.replace(raw, IMAGE_OMITTED_MARKER)
            if (text.length > MAX_CONTENT_CHARS) {
                text = text.take(MAX_CONTENT_CHARS) + CONTENT_TRUNCATED_MARKER
            }
            return text
        }
    }

    /**
     * 从持久化的消息重建合法的上下文历史。
     * 关键：只保留「assistant 的 tool_call」与「tool 结果」能配对成功的部分，
     * 丢弃任何一方缺失的悬挂项，避免回放出现孤儿 tool_use / tool_result 违反 API 约束。
     * 已被上下文压缩标记的消息（isCompacted=true）不参与回放。
     */
    suspend fun buildHistory(sessionId: String, pendingToolMarker: String): List<AgentMessage> {
        // 版本化缓存：agent_messages 表无变更且 marker 相同时直接复用上次重建结果，
        // 避免工具循环中每轮 LLM 调用都全量读库 + 多次遍历 + JSON 解码。
        // InvalidationTracker 覆盖所有写路径（含 rewind 删除、压缩标记、冷启动清理），不会漏失效。
        val version = dbVersion.get()
        synchronized(historyCache) {
            historyCache[sessionId]?.let { cached ->
                if (cached.version == version && cached.pendingToolMarker == pendingToolMarker) {
                    return cached.messages
                }
            }
        }
        val messages = buildHistoryUncached(sessionId, pendingToolMarker)
        synchronized(historyCache) {
            historyCache[sessionId] = HistoryEntry(version, pendingToolMarker, messages)
        }
        return messages
    }

    private suspend fun buildHistoryUncached(sessionId: String, pendingToolMarker: String): List<AgentMessage> {
        val entities = agentMessageDao.getMessagesBySessionOnce(sessionId)
            .filter { !it.isCompacted }

        // 第一遍：求 assistant 声明的 toolCallId 与 tool 结果 toolCallId 的交集。
        val declaredIds = mutableSetOf<String>()
        val resultIds = mutableSetOf<String>()
        for (e in entities) {
            when (MessageRole.valueOf(e.role)) {
                MessageRole.ASSISTANT -> e.toolCallsJson?.let {
                    runCatching { json.decodeFromString<List<ToolCall>>(it) }
                        .getOrNull()?.forEach { tc -> declaredIds.add(tc.id) }
                }
                MessageRole.TOOL -> {
                    // 只有真正完成的结果才计入配对；执行中占位行（完成事件未回来的孤儿）不算。
                    if (!e.content.startsWith(pendingToolMarker) &&
                        !e.content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
                    ) {
                        e.toolCallId?.let { resultIds.add(it) }
                    }
                }
                else -> {}
            }
        }
        val validIds = declaredIds intersect resultIds

        // 第二遍：构建消息，过滤掉无法配对的工具调用 / 工具结果。
        val result = mutableListOf<AgentMessage>()
        for (e in entities) {
            when (MessageRole.valueOf(e.role)) {
                MessageRole.USER -> {
                    val rawContent = if (e.isCompactionMarker) CONTEXT_COMPACTION_MARKER else e.content
                    val attachments = if (!e.isCompactionMarker) {
                        e.attachmentsJson?.let {
                            runCatching { json.decodeFromString<List<AgentAttachment>>(it) }.getOrNull()
                        } ?: emptyList()
                    } else emptyList()

                    val finalContent = if (attachments.isNotEmpty()) {
                        val attachmentText = buildString {
                            append("附件：")
                            attachments.forEach { att ->
                                append('\n')
                                append("- ")
                                append(att.fileName)
                                append("：")
                                append(att.containerPath)
                            }
                        }
                        if (rawContent.isBlank()) attachmentText else "${rawContent.trimEnd()}\n\n$attachmentText"
                    } else {
                        rawContent
                    }

                    val images = attachments.mapNotNull { it.toAgentImage() }

                    result.add(
                        AgentMessage.UserMessage(
                            id = e.id,
                            content = finalContent,
                            images = images
                        )
                    )
                }
                MessageRole.ASSISTANT -> {
                    val toolCalls = e.toolCallsJson?.let {
                        runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
                    }?.filter { it.id in validIds } ?: emptyList()
                    if (e.content.isNotBlank() || toolCalls.isNotEmpty()) {
                        val previous = result.lastOrNull()
                        if (
                            e.isContextSummary &&
                            !(previous is AgentMessage.UserMessage && previous.content == CONTEXT_COMPACTION_MARKER)
                        ) {
                            result.add(AgentMessage.UserMessage(content = CONTEXT_COMPACTION_MARKER))
                        }
                        result.add(
                            AgentMessage.AssistantMessage(
                                id = e.id,
                                content = e.content.removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX).trimStart(),
                                toolCalls = toolCalls,
                                reasoning = e.reasoning ?: "",
                                signature = e.signature ?: "",
                                thinkingBlocksJson = e.thinkingBlocksJson ?: ""
                            )
                        )
                    }
                }
                MessageRole.TOOL -> {
                    val tcId = e.toolCallId
                    if (tcId != null && tcId in validIds) {
                        result.add(
                            AgentMessage.ToolResultMessage(
                                id = tcId,
                                toolName = e.toolName ?: "unknown",
                                result = e.content
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun AgentAttachment.toAgentImage(): com.aicode.feature.agent.domain.model.AgentImage? {
        if (!isImage || localPath.isBlank()) return null
        val file = java.io.File(localPath)
        if (!file.exists() || !file.isFile || file.length() <= 0) return null
        // 按路径+大小+修改时间缓存 base64：文件未变时直接复用，省去每次 LLM 调用的重读+编码。
        val key = "$localPath:${file.length()}:${file.lastModified()}"
        cachedImageBase64(key)?.let { cached ->
            return com.aicode.feature.agent.domain.model.AgentImage(
                mimeType = mimeType.ifBlank { "image/jpeg" },
                base64Data = cached,
                path = containerPath
            )
        }
        return try {
            val bytes = file.readBytes()
            val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
            cacheImageBase64(key, base64)
            com.aicode.feature.agent.domain.model.AgentImage(
                mimeType = mimeType.ifBlank { "image/jpeg" },
                base64Data = base64,
                path = containerPath
            )
        } catch (e: Exception) {
            null
        }
    }
}
