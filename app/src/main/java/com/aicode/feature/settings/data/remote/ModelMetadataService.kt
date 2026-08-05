package com.aicode.feature.settings.data.remote

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelMetadataService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: Cache? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    /** models.dev 仅作元数据增强：独立短超时 client，不可达时快速失败，不占用共享的 120s 流式超时。 */
    private val metadataClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(type: ProviderType, modelId: String): ModelMetadata = withContext(Dispatchers.IO) {
        val catalog = loadCatalog()
        findMetadata(catalog, type, modelId) ?: default(type, modelId)
    }

    suspend fun resolveAll(type: ProviderType, modelIds: List<String>): Map<String, ModelMetadata> =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            modelIds.associateWith { modelId ->
                findMetadata(catalog, type, modelId) ?: default(type, modelId)
            }
        }

    /**
     * App 启动时统一调用的异步刷新：磁盘缓存未过期（<24h）则跳过；拉取成功写入内存与磁盘缓存，
     * 失败静默（resolve 回退内置 assets 数据）。进程内只尝试一次，绝不阻塞模型请求。
     */
    suspend fun refreshFromNetworkIfStale() {
        if (refreshAttemptedThisProcess) return
        refreshAttemptedThisProcess = true
        withContext(Dispatchers.IO) {
            val diskCache = loadCatalogFromDisk()
            if (diskCache != null && isFresh(diskCache)) return@withContext
            fetchCatalogFromNetwork()
        }
    }

    private fun isFresh(cache: Cache): Boolean =
        System.currentTimeMillis() - cache.loadedAtMs < CACHE_MAX_AGE_MS

    /** 纯只读链路：内存 → 磁盘缓存(24h 内) → 内置 assets → 空目录（由调用方回退默认值），绝不发网络请求。 */
    private fun loadCatalog(): Map<String, Map<String, ModelMetadata>> {
        cached?.let {
            return it.catalog
        }

        loadCatalogFromDisk()?.takeIf { isFresh(it) }?.let {
            cached = it
            return it.catalog
        }

        loadCatalogFromAssets()?.let {
            cached = it
            return it.catalog
        }

        return emptyMap()
    }

    private fun fetchCatalogFromNetwork() {
        runCatching {
            val request = Request.Builder()
                .url(MODELS_DEV_URL)
                .header("User-Agent", "aicode")
                .get()
                .build()

            metadataClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(200)}")
                writeCatalogCache(body)
                parseCatalog(json.parseToJsonElement(body))
            }
        }.onSuccess { catalog ->
            cached = Cache(System.currentTimeMillis(), catalog)
        }.onFailure { e ->
            FileLogger.w(TAG, "拉取 models.dev 模型元数据失败", e)
        }
    }

    private fun loadCatalogFromDisk(): Cache? {
        val file = cacheFile()
        if (!file.isFile) return null
        return runCatching {
            val body = file.readText(Charsets.UTF_8)
            val loadedAtMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            Cache(loadedAtMs, parseCatalog(json.parseToJsonElement(body)))
        }.getOrNull()
    }

    private fun loadCatalogFromAssets(): Cache? = runCatching {
        val body = context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        Cache(0L, parseCatalog(json.parseToJsonElement(body)))
    }.getOrNull()

    private fun writeCatalogCache(body: String) {
        runCatching {
            cacheFile().writeText(body, Charsets.UTF_8)
        }
    }

    private fun cacheFile(): File = File(context.cacheDir, CACHE_FILE_NAME)

    /** 目录中匹配不到模型时的兜底：统一视为文本模型，128k 输入 / 64k 输出。 */
    private fun default(type: ProviderType, modelId: String): ModelMetadata = ModelMetadata(
        id = modelId,
        providerId = type.name.lowercase(),
        displayName = modelId,
        contextTokens = DEFAULT_CONTEXT_TOKENS,
        inputTokens = DEFAULT_CONTEXT_TOKENS,
        outputTokens = DEFAULT_OUTPUT_TOKENS,
        supportsTools = true,
        supportsVision = false,
        supportsReasoning = false,
        source = ModelMetadata.Source.INFERRED
    )

    private fun findMetadata(
        catalog: Map<String, Map<String, ModelMetadata>>,
        type: ProviderType,
        modelId: String
    ): ModelMetadata? {
        val normalized = modelId.removePrefix("models/")
        val preferredProviders = when (type) {
            ProviderType.OPENAI -> listOf(
                "openai", "openrouter", "deepseek", "groq", "xai", "mistral",
                "togetherai", "alibaba", "moonshot", "github-copilot"
            )
            ProviderType.ANTHROPIC -> listOf("anthropic", "google-vertex-anthropic")
            ProviderType.GEMINI -> listOf("google", "google-vertex")
        }

        for (provider in preferredProviders) {
            catalog[provider]?.get(normalized)?.let { return it }
        }
        return catalog.values.firstNotNullOfOrNull { models -> models[normalized] }
    }

    private fun parseCatalog(root: JsonElement): Map<String, Map<String, ModelMetadata>> {
        return root.jsonObject.mapValues { (providerId, providerEl) ->
            val models = providerEl.jsonObject["models"]?.jsonObject.orEmpty()
            models.mapValues { (_, modelEl) ->
                val model = modelEl.jsonObject
                val limit = model["limit"]?.jsonObject
                val modalities = model["modalities"]?.jsonObject
                val inputModalities = modalities?.get("input")?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content }
                    .orEmpty()
                ModelMetadata(
                    id = model["id"]?.jsonPrimitive?.content ?: "",
                    providerId = providerId,
                    displayName = model["name"]?.jsonPrimitive?.content ?: model["id"]?.jsonPrimitive?.content.orEmpty(),
                    contextTokens = limit?.get("context")?.jsonPrimitive?.intOrNull ?: 0,
                    inputTokens = limit?.get("input")?.jsonPrimitive?.intOrNull,
                    outputTokens = limit?.get("output")?.jsonPrimitive?.intOrNull,
                    supportsTools = model["tool_call"]?.jsonPrimitive?.booleanOrNull == true,
                    supportsVision = "image" in inputModalities || "video" in inputModalities || "pdf" in inputModalities,
                    supportsReasoning = model["reasoning"]?.jsonPrimitive?.booleanOrNull == true,
                    source = ModelMetadata.Source.MODELS_DEV
                )
            }
        }
    }

    private data class Cache(
        val loadedAtMs: Long,
        val catalog: Map<String, Map<String, ModelMetadata>>
    )

    private companion object {
        const val TAG = "ModelMetadataService"
        const val MODELS_DEV_URL = "https://models.dev/api.json"
        const val CACHE_FILE_NAME = "models-dev-api.json"
        const val ASSET_FILE_NAME = "api.official.json"
        const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val DEFAULT_CONTEXT_TOKENS = 128_000
        const val DEFAULT_OUTPUT_TOKENS = 64_000
    }
}
