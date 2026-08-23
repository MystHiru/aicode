package com.aicode.feature.settings.data

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * provider 级 API 基础地址解析。
 *
 * models.dev 数据中部分 provider（xai / mistral 等）无 `api` 字段——它们有官方 SDK，
 * 默认端点内置在 SDK 源码里；只有无 SDK 的第三方服务才在 `api` 字段写 baseURL。
 * 本 Store 用内置映射（app/src/main/assets/sdk-provider-baseurl.json，由
 * scripts/update-models-dev-assets.py 生成，数据源为各官方 AI SDK 默认 baseURL）
 * 兜底这些 provider，并接受运行时网络拉取（ModelMetadataService.parseCatalog）的
 * `api` 字段值覆盖（最新优先）。供插件 auth 虚拟 provider 解析真实端点使用。
 */
@Singleton
class ProviderBaseUrlStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val builtin = loadBuiltin(context)

    @Volatile
    private var networkOverrides: Map<String, String> = emptyMap()

    /** 记录网络拉取到的 provider.api.baseURL（覆盖内置映射，最新优先）。 */
    fun update(providerId: String, baseUrl: String) {
        if (providerId.isBlank() || baseUrl.isBlank()) return
        synchronized(this) {
            networkOverrides = networkOverrides + (providerId to baseUrl)
        }
    }

    /** 解析 provider 的 baseUrl：网络值优先，其次内置 SDK 映射；均无返回 null（调用方回退默认值）。 */
    fun resolve(providerId: String): String? = networkOverrides[providerId] ?: builtin[providerId]

    private fun loadBuiltin(context: Context): Map<String, String> = runCatching {
        val body = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        json.keys().asSequence().mapNotNull { key ->
            json.optString(key).takeIf { it.isNotBlank() }?.let { key to it }
        }.toMap()
    }.getOrElse {
        FileLogger.w(TAG, "读取内置 SDK baseURL 映射失败: ${it.message}")
        emptyMap()
    }

    companion object {
        const val TAG = "ProviderBaseUrlStore"
        const val ASSET_FILE = "sdk-provider-baseurl.json"
    }
}
