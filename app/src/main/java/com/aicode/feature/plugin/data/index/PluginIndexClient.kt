package com.aicode.feature.plugin.data.index

import android.content.Context
import android.graphics.BitmapFactory
import com.aicode.core.net.readResponseCancellable
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.data.PluginStoreSettingsRepository
import com.aicode.feature.plugin.domain.SemVer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

data class PluginIndexSnapshot(
    val index: PluginIndexDocument,
    val fetchedAt: Long
)

/** 商店列表图标：文件、是否仍在 TTL 内、最后修改时间（供 UI 判断要不要刷新）。 */
data class PluginLogoCacheEntry(
    val file: File,
    val isFresh: Boolean,
    val lastModified: Long
)

internal const val PLUGIN_LOGO_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

internal fun isPluginLogoCacheFresh(lastModified: Long, now: Long): Boolean =
    now - lastModified < PLUGIN_LOGO_CACHE_TTL_MS

data class PluginReleaseAsset(
    val version: String,
    val name: String,
    val url: String,
    val size: Long?,
    val sha256: String? = null,
    val direct: Boolean = false
)

class PluginIndexException(message: String, cause: Throwable? = null) : IOException(message, cause)

class NoMatchingReleaseException(message: String) : IOException(message)

internal class PluginPackageIntegrityException(message: String) : IOException(message)

@Singleton
class PluginIndexClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    client: OkHttpClient,
    private val storeSettings: PluginStoreSettingsRepository
) {
    private val http = client.newBuilder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .cache(null)
        .build()
    private val https = http.newBuilder().followSslRedirects(false).build()

    private val cacheRoot = File(context.filesDir, "plugin-index-cache")
    private val logoCacheRoot = File(context.cacheDir, "plugin-store-icons")
    private val logoRefreshLocks = ConcurrentHashMap<String, Mutex>()

    private val hostAppVersion: SemVer? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.let(SemVer::parse)
    }.getOrNull()

    suspend fun readCachedIndex(source: PluginIndexSource): PluginIndexSnapshot? = withContext(Dispatchers.IO) {
        val file = cacheFile(source)
        readCache(source, file)?.let { PluginIndexSnapshot(it, file.lastModified()) }
    }

    suspend fun readPluginLogoCache(url: String): PluginLogoCacheEntry? = withContext(Dispatchers.IO) {
        logoCacheFile(url)?.let(::logoCacheEntry)
    }

    suspend fun refreshPluginLogo(url: String): PluginLogoCacheEntry? = withContext(Dispatchers.IO) {
        val file = logoCacheFile(url) ?: return@withContext null
        val lock = logoRefreshLocks.computeIfAbsent(url) { Mutex() }
        lock.withLock {
            logoCacheEntry(file)?.takeIf { it.isFresh } ?: run {
                try {
                    downloadLogo(url, file)
                } catch (_: IOException) {
                    return@withLock logoCacheEntry(file)
                }
                logoCacheEntry(file)
            }
        }
    }

    suspend fun fetchIndex(source: PluginIndexSource, forceRefresh: Boolean = false): PluginIndexSnapshot =
        withContext(Dispatchers.IO) {
            // 内置索引提供校验元数据，不能让第三方镜像替换它。
            val url = if (source.builtIn) source.indexUrl else accelerated(source.indexUrl)
            val bytes = getBytes(url, MAX_INDEX_BYTES, noCache = forceRefresh, httpsOnly = source.builtIn)
            val index = parseIndex(source, bytes)
            ensureActive()
            try {
                writeCache(cacheFile(source), bytes)
            } catch (e: IOException) {
                FileLogger.w("PluginIndexClient", "保存插件索引缓存失败: ${source.id}", e)
            }
            PluginIndexSnapshot(index, fetchedAt = System.currentTimeMillis())
        }

    fun isCompatible(plugin: PluginIndexPlugin): Boolean {
        val minimum = plugin.minAppVersion?.let { SemVer.parse(it) ?: return false } ?: return true
        val current = hostAppVersion ?: return true
        // 开发 / RC 构建按同一 major.minor.patch 的正式版看待；
        // 最低版本本身是预发布时，仍按完整版本比较。
        val comparable = if (minimum.preRelease.isEmpty()) {
            SemVer(current.major, current.minor, current.patch)
        } else {
            current
        }
        return comparable >= minimum
    }

    /**
     * 直链绑定索引里的具体版本；否则按 GitHub Release 资产名查找。
     */
    suspend fun resolvePackage(plugin: PluginIndexPlugin): PluginReleaseAsset = withContext(Dispatchers.IO) {
        plugin.packageUrl?.let { url ->
            val version = plugin.version ?: throw NoMatchingReleaseException("直链插件缺少版本: ${plugin.id}")
            return@withContext PluginReleaseAsset(
                version = version,
                name = assetName(plugin.id, version),
                url = url,
                size = plugin.packageSize,
                sha256 = plugin.packageSha256,
                direct = true
            )
        }
        val (owner, repo) = parseGitHubRepo(plugin.repository)
            ?: throw NoMatchingReleaseException("不是 GitHub 仓库: ${plugin.repository}")
        val expectedName = plugin.version?.let { assetName(plugin.id, it) }
        val releases = if (plugin.version == null) {
            listOf(apiGet<GitHubRelease>("$GITHUB_API/repos/$owner/$repo/releases/latest"))
        } else {
            apiGet<List<GitHubRelease>>("$GITHUB_API/repos/$owner/$repo/releases?per_page=100")
        }
        val candidates = releases
            .filterNot { it.draft }
            .flatMap { release -> release.assets.map { asset -> release to asset } }
        val matched = when {
            expectedName != null -> candidates.firstOrNull { it.second.name == expectedName }
            else -> candidates
                .mapNotNull { pair ->
                    val version = assetVersion(plugin.id, pair.second.name) ?: return@mapNotNull null
                    pair to version
                }
                .maxByOrNull { SemVer.parse(it.second) ?: SemVer(0, 0, 0) }
                ?.let { it.first }
        } ?: throw NoMatchingReleaseException("未找到插件资产: ${expectedName ?: "${plugin.id}_*.zip"}")
        val asset = matched.second
        val version = expectedName?.let { plugin.version!! } ?: assetVersion(plugin.id, asset.name)!!
        PluginReleaseAsset(
            version = version,
            name = asset.name,
            url = asset.browserDownloadUrl,
            size = asset.size
        )
    }

    suspend fun download(asset: PluginReleaseAsset, allowMirror: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val expectedSize = asset.size
        if (expectedSize != null && expectedSize !in 1..MAX_PLUGIN_ARCHIVE_BYTES) {
            throw PluginPackageIntegrityException("资产大小非法: $expectedSize")
        }
        val url = if (allowMirror && !asset.direct) accelerated(asset.url) else asset.url
        val bytes = getBytes(
            url,
            MAX_PLUGIN_ARCHIVE_BYTES,
            noCache = true,
            expectedSize = expectedSize,
            httpsOnly = url == asset.url
        )
        verifyPackageHash(bytes, asset.sha256)
        bytes
    }

    /** 配置了镜像加速时给 GitHub 地址加上前缀；其他主机的索引仓库保持原地址。 */
    private suspend fun accelerated(url: String): String {
        val mirror = storeSettings.downloadMirror.first().trim()
        if (mirror.isEmpty()) return url
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return url
        if (host !in MIRRORED_HOSTS) return url
        return mirror.trimEnd('/') + "/" + url
    }

    private fun assetName(id: String, version: String): String = "${id}_$version.zip"

    private fun assetVersion(id: String, name: String): String? {
        val prefix = "${id}_"
        if (!name.startsWith(prefix) || !name.endsWith(".zip")) return null
        val version = name.removePrefix(prefix).removeSuffix(".zip")
        return version.takeIf { !it.startsWith("v") && SemVer.parse(it) != null }
    }

    private fun parseGitHubRepo(repository: String): Pair<String, String>? {
        val uri = runCatching { URI(repository) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "github.com") return null
        val segments = uri.path.trim('/').split('/')
        if (segments.size != 2 || segments.any { it.isBlank() }) return null
        return segments[0] to segments[1].removeSuffix(".git")
    }

    private suspend inline fun <reified T> apiGet(url: String): T {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "aicode-plugin-index")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val bytes = https.newCall(request).readResponseCancellable { response ->
            if (response.code == 404) throw NoMatchingReleaseException("GitHub Release 不存在")
            if (!response.isSuccessful) throw IOException("GitHub Release 请求失败: HTTP ${response.code}")
            val body = response.body ?: throw IOException("GitHub Release 响应为空")
            readStoreBody(body, MAX_RELEASE_RESPONSE_BYTES, null)
        }
        return try {
            PluginIndexJson.decodeFromString<T>(bytes.decodeToString())
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IOException("GitHub Release 响应解析失败", e)
        }
    }

    private suspend fun getBytes(
        url: String,
        maxBytes: Long,
        noCache: Boolean,
        expectedSize: Long? = null,
        httpsOnly: Boolean = false
    ): ByteArray {
        if (httpsOnly && !isSecureStoreUrl(url)) throw IOException("插件商店请求必须使用 HTTPS")
        val builder = Request.Builder().url(url).header("User-Agent", "aicode-plugin-index")
        if (noCache) builder.cacheControl(CacheControl.Builder().noCache().build())
        val client = if (httpsOnly) https else http
        return client.newCall(builder.build()).readResponseCancellable { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.request.url.host}")
            val body = response.body ?: throw IOException("空响应")
            readStoreBody(body, maxBytes, expectedSize)
        }
    }

    private fun logoCacheFile(url: String): File? {
        if (url.length > MAX_LOGO_URL_LENGTH) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        val key = sha256Hex(url.toByteArray(Charsets.UTF_8))
        return File(logoCacheRoot, "$key.img")
    }

    private fun logoCacheEntry(file: File): PluginLogoCacheEntry? {
        if (!file.isFile || file.length() == 0L) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            file.delete()
            return null
        }
        val lastModified = file.lastModified()
        return PluginLogoCacheEntry(
            file = file,
            isFresh = isPluginLogoCacheFresh(lastModified, System.currentTimeMillis()),
            lastModified = lastModified
        )
    }

    private suspend fun downloadLogo(url: String, file: File) {
        val bytes = withContext(Dispatchers.IO) {
            getBytes(accelerated(url), MAX_LOGO_BYTES, noCache = true)
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("插件图标格式无效")

        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temp.renameTo(file)) throw IOException("无法写入插件图标缓存")
        } finally {
            temp.delete()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun cacheFile(source: PluginIndexSource): File = File(File(cacheRoot, source.id), "index.json")

    private fun writeCache(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile("index-", ".tmp", file.parentFile)
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) throw IOException("无法替换索引缓存")
        } finally {
            temp.delete()
        }
    }

    private fun readCache(source: PluginIndexSource, file: File): PluginIndexDocument? {
        if (!file.isFile || file.length() > MAX_INDEX_BYTES) return null
        return runCatching { parseIndex(source, file.readBytes()) }.getOrNull()
    }

    private fun parseIndex(source: PluginIndexSource, bytes: ByteArray): PluginIndexDocument {
        var skipped = 0
        val index = PluginIndexParser.parse(bytes, skipInvalidPlugins = source.id == PluginIndexDefaults.VANTA_ID) {
            skipped++
        }
        if (skipped > 0) FileLogger.w("PluginIndexClient", "索引 ${source.id} 跳过 $skipped 个无效条目")
        return index
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList()
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long = 0
    )

    private companion object {
        const val GITHUB_API = "https://api.github.com"
        val MIRRORED_HOSTS = setOf(
            "raw.githubusercontent.com",
            "github.com",
            "objects.githubusercontent.com",
            "codeload.github.com"
        )
        const val MAX_INDEX_BYTES = 2L * 1024 * 1024
        const val MAX_RELEASE_RESPONSE_BYTES = 16L * 1024 * 1024
        const val MAX_LOGO_BYTES = 5L * 1024 * 1024
        const val MAX_LOGO_URL_LENGTH = 1024
    }
}

internal fun readStoreBody(body: ResponseBody, maxBytes: Long, expectedSize: Long?): ByteArray {
    val limit = minOf(maxBytes, expectedSize ?: maxBytes)
    val declared = body.contentLength()
    if (expectedSize != null && declared >= 0 && declared != expectedSize) {
        throw PluginPackageIntegrityException("响应大小 $declared 与声明 $expectedSize 不一致")
    }
    if (declared > maxBytes) throw IOException("响应超过大小限制")
    val stream = body.byteStream()
    val output = ByteArrayOutputStream(if (declared in 1..limit) declared.toInt() else 8192)
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) {
            if (expectedSize != null && total > expectedSize) throw PluginPackageIntegrityException("响应超过声明大小")
            throw IOException("响应超过大小限制")
        }
        output.write(buffer, 0, read)
    }
    if (expectedSize != null && total != expectedSize) {
        throw PluginPackageIntegrityException("响应大小 $total 与声明 $expectedSize 不一致")
    }
    return output.toByteArray()
}

internal fun verifyPackageHash(bytes: ByteArray, expectedSha256: String?) {
    if (expectedSha256 == null) return
    val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    if (!expectedSha256.equals(actual, ignoreCase = true)) {
        throw PluginPackageIntegrityException("插件包 SHA-256 校验失败")
    }
}
