package com.aicode.feature.settings.data.repository

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import javax.inject.Inject
import javax.inject.Singleton

/** 一次 Key 切换的结果，供 UI 提示「已切换到第 N 个 Key」。 */
data class KeySwitchResult(
    val newKey: String,
    /** 1 起的新 Key 序号。 */
    val newIndex: Int,
    val total: Int
)

/**
 * 多 Key 轮换器：决定某个 provider 在某条会话上该用哪个 API Key，并按失败次数做切换。
 *
 * 状态全部是运行时内存态、不落库：进程重启后回到第一个 Key、冷却记录清空——多 Key 的意义是
 * 遇到限流/配额问题时能自动绕开，没有必须跨进程保持的语义。
 *
 * **会话粘性**是这里的核心约束：服务端 prompt 缓存按 API Key 隔离，同一会话逐请求换 Key 会让
 * 每轮都落到没有缓存的 Key 上，缓存命中率归零、成本和首字延迟一起恶化。因此两种策略都在会话
 * 内粘住同一个 Key，只有该 Key 连续失败达阈值时才切换并重绑。
 */
@Singleton
class ProviderKeyRotator @Inject constructor() {

    /** 由最近一次 [activeKey] 记录的 provider Key 配置，供失败上报时读取阈值与冷却时长。 */
    private data class KeySetup(
        val keys: List<String>,
        val strategy: KeyRotationStrategy,
        val failoverThreshold: Int,
        val cooldownMillis: Long
    )

    private val lock = Any()

    private val setups = mutableMapOf<String, KeySetup>()

    /** providerId + Key → 该 Key 的连续失败次数（成功即清零）。 */
    private val failureCounts = mutableMapOf<String, Int>()

    /** providerId + Key → 冷却截止时间戳（毫秒）。 */
    private val cooldownUntil = mutableMapOf<String, Long>()

    /** providerId → 轮询策略下一个新会话的起始下标。 */
    private val roundRobinCursor = mutableMapOf<String, Int>()

    /** providerId → 最近一次选中的 Key，供余额查询等旁路请求取「当前活动 Key」。 */
    private val lastSelected = mutableMapOf<String, String>()

    /**
     * providerId + sessionId → 该会话粘住的 Key。会话数量无上界，用访问序 LRU 兜住内存，
     * 被淘汰的老会话下次请求重新分配 Key 即可，没有正确性问题。
     */
    private val sessionKeys = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_TRACKED_SESSIONS
    }

    /**
     * 取 [config] 在 [sessionId] 上当前该用的 Key；没有任何可用 Key 时返回 null。
     * 同时把该 provider 的 Key 配置快照下来，供后续 [reportFailure] / [reportSuccess] 使用。
     */
    fun activeKey(config: AIProviderConfig, sessionId: String?): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            setups[config.id] = KeySetup(
                keys = keys,
                strategy = config.keyRotationStrategy,
                failoverThreshold = config.keyFailoverThreshold.coerceAtLeast(1),
                cooldownMillis = config.keyCooldownMinutes.coerceAtLeast(0) * 60_000L
            )
            if (keys.size == 1) return keys.first()

            val bindKey = sessionId?.let { sessionBinding(config.id, it) }
            val bound = bindKey?.let { sessionKeys[it] }
            if (bound != null && bound in keys && !isCoolingDown(config.id, bound)) return bound

            val chosen = pick(config.id, keys, config.keyRotationStrategy, avoid = null)
            if (bindKey != null) sessionKeys[bindKey] = chosen
            lastSelected[config.id] = chosen
            return chosen
        }
    }

    /**
     * 只读地取该 provider 当前活动的 Key：不建会话绑定、不推进轮询游标。
     * 供余额查询这类旁路请求使用——它们要反映「聊天正在用哪个 Key」，而不该干扰轮换节奏。
     */
    fun currentKey(config: AIProviderConfig): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            val last = lastSelected[config.id]
            if (last != null && last in keys && !isCoolingDown(config.id, last)) return last
            return keys.firstOrNull { !isCoolingDown(config.id, it) } ?: keys.first()
        }
    }

    /**
     * 上报一次可归因于 [key] 的失败。连续失败达到阈值时把该 Key 打进冷却、切到下一个并重绑会话，
     * 返回切换结果；未达阈值或无可切换目标时返回 null。
     */
    fun reportFailure(providerId: String, sessionId: String?, key: String): KeySwitchResult? {
        synchronized(lock) {
            val setup = setups[providerId] ?: return null
            if (setup.keys.size <= 1 || key !in setup.keys) return null

            val counterKey = stateKey(providerId, key)
            val count = (failureCounts[counterKey] ?: 0) + 1
            if (count < setup.failoverThreshold) {
                failureCounts[counterKey] = count
                FileLogger.w(TAG, "Key 失败计数 provider=$providerId key=${key.masked()} $count/${setup.failoverThreshold}")
                return null
            }

            failureCounts[counterKey] = 0
            if (setup.cooldownMillis > 0) {
                cooldownUntil[counterKey] = System.currentTimeMillis() + setup.cooldownMillis
            }
            val next = pick(providerId, setup.keys, setup.strategy, avoid = key)
            sessionId?.let { sessionKeys[sessionBinding(providerId, it)] = next }
            lastSelected[providerId] = next
            if (next == key) {
                FileLogger.w(TAG, "Key 已达失败阈值但无其它可用 Key provider=$providerId key=${key.masked()}")
                return null
            }
            val index = setup.keys.indexOf(next) + 1
            FileLogger.i(TAG, "Key 切换 provider=$providerId ${key.masked()} → ${next.masked()} (第 $index/${setup.keys.size} 个)")
            return KeySwitchResult(newKey = next, newIndex = index, total = setup.keys.size)
        }
    }

    /** 上报一次成功：清零该 Key 的连续失败计数。 */
    fun reportSuccess(providerId: String, key: String) {
        synchronized(lock) {
            failureCounts.remove(stateKey(providerId, key))
        }
    }

    /**
     * 按策略挑一个 Key：优先未冷却者；[avoid] 用于「刚失败的那个不要再选」。
     * 全都在冷却时退回最早到期的那个——宁可再试一次，也不要因为全员冷却直接把会话打死。
     */
    private fun pick(
        providerId: String,
        keys: List<String>,
        strategy: KeyRotationStrategy,
        avoid: String?
    ): String {
        val ordered = when (strategy) {
            KeyRotationStrategy.SEQUENTIAL -> keys
            KeyRotationStrategy.ROUND_ROBIN -> {
                val start = (roundRobinCursor[providerId] ?: 0) % keys.size
                roundRobinCursor[providerId] = (start + 1) % keys.size
                keys.subList(start, keys.size) + keys.subList(0, start)
            }
        }
        val candidates = ordered.filter { it != avoid }.ifEmpty { ordered }
        return candidates.firstOrNull { !isCoolingDown(providerId, it) }
            ?: candidates.minByOrNull { cooldownUntil[stateKey(providerId, it)] ?: 0L }
            ?: keys.first()
    }

    private fun isCoolingDown(providerId: String, key: String): Boolean {
        val until = cooldownUntil[stateKey(providerId, key)] ?: return false
        if (System.currentTimeMillis() >= until) {
            cooldownUntil.remove(stateKey(providerId, key))
            return false
        }
        return true
    }

    private fun stateKey(providerId: String, key: String) = "$providerId\u0000$key"

    private fun sessionBinding(providerId: String, sessionId: String) = "$providerId\u0000$sessionId"

    /** 日志脱敏：只留末 4 位，避免把完整 Key 写进日志文件。 */
    private fun String.masked(): String = if (length <= 4) "****" else "****${takeLast(4)}"

    private companion object {
        const val TAG = "ProviderKeyRotator"
        const val MAX_TRACKED_SESSIONS = 200
    }
}
