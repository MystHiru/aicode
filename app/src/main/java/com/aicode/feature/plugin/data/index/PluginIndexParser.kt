package com.aicode.feature.plugin.data.index

import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginManifestParser
import com.aicode.feature.plugin.domain.SemVer
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** 索引静态校验；不依赖 Android，便于单元测试。 */
internal object PluginIndexParser {

    private val AUTHOR = Regex("^[a-z][a-z0-9-]*$")
    private val LANGUAGE_TAG = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$")

    /**
     * 解析索引。Vanta 商店历史上存在单个不符合 AiCode ID 规范的条目，
     * 合作来源启用 [skipInvalidPlugins] 后只跳过该条目，不影响其余插件展示。
     */
    fun parse(
        bytes: ByteArray,
        skipInvalidPlugins: Boolean = false,
        onSkippedPlugin: (Int) -> Unit = {}
    ): PluginIndexDocument {
        if (bytes.size > MAX_INDEX_BYTES) throw PluginIndexException("index.json 超过 2 MiB")
        val root = try {
            PluginIndexJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw PluginIndexException("index.json 解析失败", e)
        }
        val schemaVersion = (root["schema_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw PluginIndexException("index.json 缺少 schema_version")
        if (schemaVersion != 1) throw PluginIndexException("不支持的 schema_version=$schemaVersion")
        val rawPlugins = when (val entries = root["plugins"]) {
            null -> JsonArray(emptyList())
            is JsonArray -> entries
            else -> throw PluginIndexException("plugins 必须是数组")
        }
        if (rawPlugins.size > MAX_PLUGINS) throw PluginIndexException("插件数量超过 $MAX_PLUGINS")

        val ids = HashSet<String>()
        val plugins = rawPlugins.mapIndexedNotNull { index, raw ->
            try {
                val plugin = PluginIndexJson.decodeFromJsonElement<PluginIndexPlugin>(raw)
                validate(plugin)
                if (!ids.add(plugin.id)) throw PluginIndexException("插件 ID 重复: ${plugin.id}")
                plugin
            } catch (e: IllegalArgumentException) {
                if (!skipInvalidPlugins) throw PluginIndexException("index.json 插件条目解析失败", e)
                onSkippedPlugin(index)
                null
            } catch (e: PluginIndexException) {
                if (!skipInvalidPlugins) throw e
                onSkippedPlugin(index)
                null
            }
        }
        if (rawPlugins.isNotEmpty() && plugins.isEmpty()) {
            throw PluginIndexException("index.json 不含有效插件条目")
        }
        return PluginIndexDocument(schemaVersion = schemaVersion, plugins = plugins)
    }

    private fun validate(plugin: PluginIndexPlugin) {
        if (!PluginManifestParser.isValidPluginId(plugin.id)) {
            throw PluginIndexException("非法插件 ID: ${plugin.id}")
        }
        validateLocalized(plugin.id, "name", plugin.name, MAX_NAME, allowNewlines = false)
        validateLocalized(plugin.id, "description", plugin.description, MAX_DESCRIPTION, allowNewlines = true)
        if (!AUTHOR.matches(plugin.author)) throw PluginIndexException("${plugin.id} 的 author 非法")
        if (PluginOfficialRepositories.ownerOf(plugin.repository) == null) {
            throw PluginIndexException("${plugin.id} 的 repository 不是 GitHub 地址")
        }
        plugin.version?.let { version ->
            if (version.startsWith("v") || SemVer.parse(version) == null) {
                throw PluginIndexException("${plugin.id} 的 version 非法")
            }
        }
        plugin.minAppVersion?.let {
            if (SemVer.parse(it) == null) throw PluginIndexException("${plugin.id} 的 min_app_version 非法")
        }
        if (plugin.tags.any { it !in PluginTags.ALL }) {
            throw PluginIndexException("${plugin.id} 的 tags 含未知值")
        }
        if (plugin.tags.size > 4 || plugin.tags.distinct().size != plugin.tags.size) {
            throw PluginIndexException("${plugin.id} 的 tags 数量或重复项非法")
        }
        plugin.logo?.let { requireHttps(it, "${plugin.id} logo") }
        plugin.homepage?.let { requireHttps(it, "${plugin.id} homepage") }
        if (plugin.packageUrl != null && !isSecureStoreUrl(plugin.packageUrl)) {
            throw PluginIndexException("${plugin.id} 的 package_url 必须是无凭据的 https 地址")
        }
        plugin.packageSize?.let {
            if (it !in 1..MAX_PLUGIN_ARCHIVE_BYTES) {
                throw PluginIndexException("${plugin.id} 的 package_size 非法")
            }
        }
        plugin.packageSha256?.let {
            if (!SHA256.matches(it)) throw PluginIndexException("${plugin.id} 的 package_sha256 非法")
        }
        if (plugin.packageUrl != null && plugin.version == null) {
            throw PluginIndexException("${plugin.id} 使用 package_url 时必须提供 version")
        }
        if (plugin.packageUrl == null && (plugin.packageSize != null || plugin.packageSha256 != null)) {
            throw PluginIndexException("${plugin.id} 缺少 package_url")
        }
    }

    private fun validateLocalized(
        pluginId: String,
        field: String,
        text: LocalizedText,
        maxLength: Int,
        allowNewlines: Boolean
    ) {
        if (text.values.size > MAX_LOCALES) {
            throw PluginIndexException("$pluginId 的 $field 语言数量超过 $MAX_LOCALES")
        }
        text.values.forEach { (tag, value) ->
            if (tag != LocalizedText.DEFAULT_KEY && !LANGUAGE_TAG.matches(tag)) {
                throw PluginIndexException("$pluginId 的 $field 含非法语言标签: $tag")
            }
            if (value.isEmpty() || value.length > maxLength || hasControlChars(value, allowNewlines)) {
                throw PluginIndexException("$pluginId 的 $field 文本非法: $tag")
            }
        }
    }

    private fun hasControlChars(text: String, allowNewlines: Boolean) =
        text.any { it.isISOControl() && !(allowNewlines && it == '\n') }

    private fun requireHttps(url: String, field: String) {
        if (!url.startsWith("https://")) throw PluginIndexException("$field 必须是 https 地址")
    }

    private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private const val MAX_PLUGINS = 5000
    private const val MAX_NAME = 120
    private const val MAX_DESCRIPTION = 2000
    private const val MAX_LOCALES = 16
}

internal const val MAX_PLUGIN_ARCHIVE_BYTES = 16L * 1024 * 1024

internal fun isSecureStoreUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null
}
