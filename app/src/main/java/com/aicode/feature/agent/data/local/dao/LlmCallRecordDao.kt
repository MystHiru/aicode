package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import kotlinx.coroutines.flow.Flow

/** 按天（本地时区）聚合的单日调用统计，供趋势图使用。day 为本地时区下的 epoch 天序号。 */
data class DayCallStats(
    val day: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val calls: Int,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?
)

/** 按渠道（provider）聚合的调用统计，供渠道排行使用。 */
data class ProviderCallStats(
    val providerId: String?,
    val providerName: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val errors: Int
)

/** 按模型聚合的调用统计，供模型排行使用。 */
data class ModelCallStats(
    val model: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    /** 写入缓存的 token 数之和（Anthropic cache_creation），按单独单价计入费用估算。 */
    val cacheCreationTokens: Long,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val errors: Int
)

/** 当前周期的整体汇总，供概览卡片使用。 */
data class CallSummary(
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?
)

/** 调用明细分页记录：单次调用 + 渠道名（LEFT JOIN ai_providers 取，渠道被删除后仍显示）。 */
data class RecentCallRecord(
    @Embedded val record: LlmCallRecordEntity,
    val providerName: String?
)

@Dao
interface LlmCallRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: LlmCallRecordEntity)

    @Query("DELETE FROM llm_call_records")
    suspend fun deleteAll()

    /**
     * 按天聚合（本地时区：把 createdAt 平移 [tzOffsetMillis] 后再按 86400000 取整切天）。
     * [tzOffsetMillis] 由调用方按当前系统时区提供（TimeZone.getDefault().getOffset(now)）。
     */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 86400000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis
        FROM llm_call_records
        WHERE createdAt >= :start
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getDayStats(start: Long, tzOffsetMillis: Long): Flow<List<DayCallStats>>

    /** 按小时聚合（本地时区），「今天」周期用小时粒度展示趋势。 */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 3600000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis
        FROM llm_call_records
        WHERE createdAt >= :start
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getHourStats(start: Long, tzOffsetMillis: Long): Flow<List<DayCallStats>>

    /** 按渠道聚合，LEFT JOIN ai_providers 取渠道名（渠道被删除后记录仍显示）。 */
    @Query(
        """
        SELECT r.providerId AS providerId,
               p.name AS providerName,
               COUNT(*) AS calls,
               SUM(r.inputTokens) AS inputTokens,
               SUM(r.outputTokens) AS outputTokens,
               SUM(r.cachedInputTokens) AS cachedInputTokens,
               SUM(CASE WHEN r.status = 'error' THEN 1 ELSE 0 END) AS errors
        FROM llm_call_records r
        LEFT JOIN ai_providers p ON p.id = r.providerId
        WHERE r.createdAt >= :start
        GROUP BY r.providerId
        ORDER BY (SUM(r.inputTokens) + SUM(r.outputTokens)) DESC
        """
    )
    fun getProviderStats(start: Long): Flow<List<ProviderCallStats>>

    /** 按模型聚合，按总消耗倒序。 */
    @Query(
        """
        SELECT model AS model,
               COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors
        FROM llm_call_records
        WHERE createdAt >= :start AND model IS NOT NULL AND model != ''
        GROUP BY model
        ORDER BY (SUM(inputTokens) + SUM(outputTokens)) DESC
        """
    )
    fun getModelStats(start: Long): Flow<List<ModelCallStats>>

    /** 周期整体汇总（无 GROUP BY，恒返回一行）。 */
    @Query(
        """
        SELECT COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis
        FROM llm_call_records
        WHERE createdAt >= :start
        """
    )
    fun getSummary(start: Long): Flow<CallSummary>

    /** 当前周期调用总数，供分页显示总页数。 */
    @Query("SELECT COUNT(*) FROM llm_call_records WHERE createdAt >= :start")
    fun getCallsCount(start: Long): Flow<Int>

    /** 调用明细分页（倒序，[offset] 起取 [limit] 条），LEFT JOIN 渠道名。 */
    @Query(
        """
        SELECT r.*, p.name AS providerName
        FROM llm_call_records r
        LEFT JOIN ai_providers p ON p.id = r.providerId
        WHERE r.createdAt >= :start
        ORDER BY r.createdAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getRecentCalls(start: Long, limit: Int, offset: Int): Flow<List<RecentCallRecord>>
}
