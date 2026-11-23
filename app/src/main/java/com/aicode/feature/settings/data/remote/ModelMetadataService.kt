package com.aicode.feature.settings.data.remote

import android.content.Context
import com.aicode.core.net.RepoDataFetcher
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.local.CustomModelMetadataStore
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.mergeModelMetadata
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelMetadataService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val customModelMetadataStore: CustomModelMetadataStore
) {
    private val activeCatalogParses = java.util.concurrent.atomic.AtomicInteger(0)

    /** 串行化目录加载：并发的 resolve 会同时穿透 [cached] 未命中的空窗，各自把 5MB 目录读盘并解析一遍。 */
    private val catalogLock = Mutex()

    /** 统一拉取器：模型元数据从独立数据分支拉取，避免每日机器产物污染 main。 */
    private val repoFetcher = RepoDataFetcher(context, branch = MODELS_BRANCH)

    @Volatile
    private var cached: Catalog? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    suspend fun resolve(providerId: String, type: ProviderType, modelId: String): ModelMetadata =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            val auto = findMetadata(catalog, type, modelId) ?: default(type, modelId)
            mergeCustom(providerId, modelId, auto)
        }

    suspend fun resolveAll(providerId: String, type: ProviderType, modelIds: List<String>): Map<String, ModelMetadata> =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            modelIds.associateWith { modelId ->
                val auto = findMetadata(catalog, type, modelId) ?: default(type, modelId)
                mergeCustom(providerId, modelId, auto)
            }
        }

    /** 自定义元数据优先于自动解析（拉取/内置）结果；providerId 为空（未关联配置）时跳过合并。 */
    private suspend fun mergeCustom(providerId: String, modelId: String, auto: ModelMetadata): ModelMetadata {
        if (providerId.isBlank()) return auto
        val custom = customModelMetadataStore.get(providerId, modelId)
        return mergeModelMetadata(modelId, auto, custom)
    }

    /** 启动时统一调用：经统一拉取器从本仓库刷新模型目录（12h 缓存，失败静默）。 */
    suspend fun refreshFromNetworkIfStale() {
        if (refreshAttemptedThisProcess) return
        refreshAttemptedThisProcess = true
        withContext(Dispatchers.IO) {
            val fetchStartedNs = System.nanoTime()
            FileLogger.memoryCheckpoint(TAG, "catalog.fetch.start", details = "operation=$fetchStartedNs")
            val result = runCatching { repoFetcher.fetch(MODELS_REPO_PATH) }.getOrNull()
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.fetch.finished",
                elapsedMs = (System.nanoTime() - fetchStartedNs) / 1_000_000,
                details = "operation=$fetchStartedNs hasContent=${result is RepoDataFetcher.FetchResult.Success || result is RepoDataFetcher.FetchResult.FallbackDiskCache}"
            )
            val body = when (result) {
                is RepoDataFetcher.FetchResult.Success -> result.content
                is RepoDataFetcher.FetchResult.FallbackDiskCache -> result.content
                else -> null
            } ?: return@withContext
            if (body.isBlank()) return@withContext
            // 解析放在锁外：持锁解析 5MB 目录会让同时进来的 resolve（发送消息前的必经路径）一直排队。
            runCatching { body.reader().use { parseCatalogWithDiagnostics(it, "refresh") } }
                .onSuccess { fresh -> catalogLock.withLock { cached = fresh } }
                .onFailure { FileLogger.w(TAG, "解析模型元数据失败", it) }
        }
    }

    /** 纯只读链路：内存 → 本仓库磁盘缓存 → 内置 assets → 空目录（由调用方回退默认值），绝不发网络请求。 */
    private suspend fun loadCatalog(): Catalog = catalogLock.withLock {
        cached?.let { return it }

        val readStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "catalog.disk.start", details = "operation=$readStartedNs")
        val diskCatalog = runCatching {
            repoFetcher.openLocalCache(MODELS_REPO_PATH)?.use { parseCatalogWithDiagnostics(it, "disk") }
        }.getOrNull()
        FileLogger.memoryCheckpoint(
            TAG,
            "catalog.disk.finished",
            elapsedMs = (System.nanoTime() - readStartedNs) / 1_000_000,
            details = "operation=$readStartedNs validCatalog=${diskCatalog != null}"
        )
        diskCatalog?.let {
            cached = it
            return it
        }

        loadCatalogFromAssets()?.let {
            cached = it
            return it
        }

        return Catalog(emptyMap(), emptyMap())
    }

    private fun loadCatalogFromAssets(): Catalog? = runCatching {
        val readStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "catalog.assets.start", details = "operation=$readStartedNs")
        val catalog = context.assets.open(ASSET_FILE_NAME).bufferedReader(Charsets.UTF_8).use {
            parseCatalogWithDiagnostics(it, "assets")
        }
        FileLogger.memoryCheckpoint(
            TAG,
            "catalog.assets.ready",
            elapsedMs = (System.nanoTime() - readStartedNs) / 1_000_000,
            details = "operation=$readStartedNs providers=${catalog.byProvider.size}"
        )
        catalog
    }.getOrNull()

    private fun parseCatalogWithDiagnostics(reader: Reader, source: String): Catalog {
        val startedNs = System.nanoTime()
        val concurrent = activeCatalogParses.incrementAndGet()
        try {
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.parse.start",
                details = "operation=$startedNs source=$source activeParses=$concurrent"
            )
            val catalog = parseCatalog(reader)
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.parse.ready",
                elapsedMs = (System.nanoTime() - startedNs) / 1_000_000,
                details = "operation=$startedNs source=$source providers=${catalog.byProvider.size} models=${catalog.byProvider.values.sumOf { it.size }} activeParses=${activeCatalogParses.get()}"
            )
            return catalog
        } finally {
            activeCatalogParses.decrementAndGet()
        }
    }

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

    companion object {
        /**
         * 解析后的模型目录：[byProvider] 保留目录里的原始 id 大小写；[lowerByProvider] 结构相同但键统一小写
         * （复用同一批 [ModelMetadata] 实例），用于大小写不敏感匹配。
         */
        internal data class Catalog(
            val byProvider: Map<String, Map<String, ModelMetadata>>,
            val lowerByProvider: Map<String, Map<String, ModelMetadata>>
        )

        internal fun parseCatalog(reader: Reader): Catalog {
            val json = JsonReader(reader)
            val byProvider = LinkedHashMap<String, Map<String, ModelMetadata>>()
            json.beginObject()
            while (json.hasNext()) {
                val providerId = json.nextName()
                var models = emptyMap<String, ModelMetadata>()
                json.beginObject()
                while (json.hasNext()) {
                    if (json.nextName() == "models") {
                        models = readModels(json, providerId)
                    } else {
                        json.skipValue()
                    }
                }
                json.endObject()
                byProvider[providerId] = models
            }
            json.endObject()
            check(json.peek() == JsonToken.END_DOCUMENT)
            return Catalog(
                byProvider = byProvider,
                lowerByProvider = byProvider.mapValues { (_, models) ->
                    models.mapKeys { (id, _) -> id.lowercase() }
                }
            )
        }

        private val jsonElementAdapter = Gson().getAdapter(JsonElement::class.java)
        private val modelFields = setOf(
            "id", "name", "limit", "modalities", "cost", "reasoning_options", "tool_call", "reasoning", "temperature"
        )

        private fun readModels(json: JsonReader, providerId: String): Map<String, ModelMetadata> = buildMap {
            json.beginObject()
            while (json.hasNext()) {
                val key = json.nextName()
                val fields = JsonObject()
                json.beginObject()
                while (json.hasNext()) {
                    val field = json.nextName()
                    if (field in modelFields) fields.add(field, jsonElementAdapter.read(json)) else json.skipValue()
                }
                json.endObject()
                put(key, toMetadata(providerId, fields))
            }
            json.endObject()
        }

        private fun toMetadata(providerId: String, model: JsonObject): ModelMetadata {
            val id = model["id"].literalText().orEmpty()
            val limit = model["limit"]?.asJsonObject
            val modalities = model["modalities"]?.asJsonObject
            val inputModalities = modalities?.get("input")?.asJsonArray
            val outputModalities = modalities?.get("output")?.asJsonArray
            val cost = model["cost"]?.asJsonObject
            val options = model["reasoning_options"]?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }.orEmpty()
            val levels = options.firstOrNull { it["type"].literalText() == "effort" }
                ?.get("values")?.asJsonArray
                ?.mapNotNull { if (it.isJsonNull) null else it.literalText() }
            val budget = options.firstOrNull { it["type"].literalText() == "budget_tokens" }
            return ModelMetadata(
                id = id,
                providerId = providerId,
                displayName = model["name"].literalText() ?: id,
                contextTokens = limit?.get("context").literalText()?.toIntOrNull() ?: 0,
                inputTokens = limit?.get("input").literalText()?.toIntOrNull(),
                outputTokens = limit?.get("output").literalText()?.toIntOrNull(),
                supportsTools = model["tool_call"].literalText().equals("true", ignoreCase = true),
                supportsVision = inputModalities?.any {
                    val type = it.literalText()
                    type == "image" || type == "video" || type == "pdf"
                } == true,
                supportsImageOutput = outputModalities?.any { it.literalText() == "image" } == true || id.endsWith("-image"),
                supportsReasoning = model["reasoning"].literalText().equals("true", ignoreCase = true),
                supportsCustomTemperature = model["temperature"].literalText().equals("true", ignoreCase = true),
                reasoningLevels = levels?.takeIf { it.isNotEmpty() },
                reasoningCanDisable = options.any { it["type"].literalText() == "toggle" },
                reasoningBudgetMin = budget?.get("min").literalText()?.toIntOrNull(),
                reasoningBudgetMax = budget?.get("max").literalText()?.toIntOrNull(),
                inputCostUsdPerM = cost?.get("input").literalText()?.toDoubleOrNull(),
                outputCostUsdPerM = cost?.get("output").literalText()?.toDoubleOrNull(),
                cacheReadCostUsdPerM = cost?.get("cache_read").literalText()?.toDoubleOrNull(),
                cacheWriteCostUsdPerM = cost?.get("cache_write").literalText()?.toDoubleOrNull(),
                source = ModelMetadata.Source.MODELS_DEV
            )
        }

        private fun JsonElement?.literalText(): String? = when {
            this == null -> null
            isJsonNull -> "null"
            else -> asJsonPrimitive.asString
        }

        /**
         * 模型 id 匹配：每轮完整名称（精确 → 忽略大小写）优先于已知后缀候选，再逐级剥离 vendor 前缀重试；
         * 所有候选落空后才去掉最后一个连字符后缀一次，每次查找都先偏好 provider，再退到全目录。
         */
        internal fun findMetadata(
            catalog: Catalog,
            type: ProviderType,
            modelId: String
        ): ModelMetadata? {
            val preferred = preferredProviders(type)
            val normalized = if (modelId.startsWith(MODELS_PREFIX, ignoreCase = true)) {
                modelId.substring(MODELS_PREFIX.length)
            } else {
                modelId
            }
            val names = listOf(normalized) + vendorStrippedCandidates(normalized)
            for (name in names) {
                lookup(catalog.byProvider, preferred, listOf(name))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, listOf(name.lowercase()))?.let { return it }
                lookup(catalog.byProvider, preferred, strippedCandidates(name).drop(1))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, strippedCandidates(name.lowercase()).drop(1))?.let { return it }
            }
            for (name in names) {
                val lastDash = name.lastIndexOf('-')
                if (lastDash <= name.lastIndexOf('/') + 1) continue
                val candidate = name.substring(0, lastDash)
                lookup(catalog.byProvider, preferred, listOf(candidate))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, listOf(candidate.lowercase()))?.let { return it }
            }
            return null
        }

        private fun preferredProviders(type: ProviderType): List<String> = when (type) {
            ProviderType.OPENAI -> listOf(
                "openai", "openrouter", "deepseek", "groq", "xai", "mistral",
                "togetherai", "alibaba", "moonshot", "github-copilot"
            )
            ProviderType.ANTHROPIC -> listOf("anthropic", "google-vertex-anthropic")
            ProviderType.GEMINI -> listOf("google", "google-vertex")
        }

        /** 在 [catalog]（键大小写已由调用方对齐）里按候选顺序查找：先偏好 provider，再全目录同名键。 */
        private fun lookup(
            catalog: Map<String, Map<String, ModelMetadata>>,
            preferred: List<String>,
            candidates: List<String>
        ): ModelMetadata? {
            for (candidate in candidates) {
                for (provider in preferred) {
                    catalog[provider]?.get(candidate)?.let { return it }
                }
                catalog.values.firstNotNullOfOrNull { models -> models[candidate] }?.let { return it }
            }
            return null
        }

        /**
         * 逐级剥离 vendor 前缀后的候选（`z-ai/glm-5.3-flash` → `glm-5.3-flash`，
         * `@cf/zai-org/glm-5.3-flash` → `zai-org/glm-5.3-flash` → `glm-5.3-flash`）。
         */
        private fun vendorStrippedCandidates(modelId: String): List<String> {
            val candidates = mutableListOf<String>()
            var current = modelId
            while (true) {
                val slash = current.indexOf('/')
                if (slash < 0 || slash == current.lastIndex) break
                current = current.substring(slash + 1)
                candidates.add(current)
            }
            return candidates
        }

        /** 生成匹配候选：原始 id 在前，随后迭代剥离常见后缀（-thinking/-preview/-high/-low 及括号形式），可连续剥离多层。 */
        private fun strippedCandidates(modelId: String): List<String> {
            val candidates = mutableListOf(modelId)
            var current = modelId
            var changed: Boolean
            do {
                changed = false
                for (suffix in MODEL_SUFFIXES) {
                    if (current.length > suffix.length && current.endsWith(suffix)) {
                        current = current.dropLast(suffix.length)
                        candidates.add(current)
                        changed = true
                        break
                    }
                }
            } while (changed)
            return candidates
        }

        const val TAG = "ModelMetadataService"
        const val ASSET_FILE_NAME = "api.official.json"

        /** 承载 models.dev 全量快照的独立数据分支（CI 每日同步，main 只保留人工提交）。 */
        const val MODELS_BRANCH = "data"

        /** 统一拉取器中的仓库相对路径：本仓库维护的 models.dev 全量快照（CI 每日同步至 [MODELS_BRANCH]）。 */
        const val MODELS_REPO_PATH = "data/models.json"
        const val DEFAULT_CONTEXT_TOKENS = 128_000
        const val DEFAULT_OUTPUT_TOKENS = 64_000

        /** Gemini 风格的模型 id 前缀（`models/gemini-2.5-pro`），匹配前剥离，忽略大小写。 */
        private const val MODELS_PREFIX = "models/"

        /** 兜底模糊匹配：依次尝试剥离的模型 id 后缀。 */
        private val MODEL_SUFFIXES = listOf(
            "-thinking", "-preview", "-high", "-low",
            "(thinking)", "(xhigh)", "(high)", "(low)"
        )
    }
}