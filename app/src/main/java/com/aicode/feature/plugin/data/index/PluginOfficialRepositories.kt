package com.aicode.feature.plugin.data.index

import java.net.URI

/** 官方插件判定只看插件源码仓库是否归属 AiCode 官方 GitHub 账号/组织。 */
object PluginOfficialRepositories {
    val GITHUB_OWNERS = setOf("jieapi")
    private val repositoryPath = Regex("^/([a-zA-Z0-9-]+)/([a-zA-Z0-9_.-]+)/?$")

    fun ownerOf(repository: String): String? {
        val uri = runCatching { URI(repository) }.getOrNull() ?: return null
        if (uri.host != "github.com" || uri.port != -1 || uri.query != null || !isSecureStoreUrl(repository)) return null
        val match = repositoryPath.matchEntire(uri.rawPath ?: return null) ?: return null
        if (match.groupValues[2] in setOf(".", "..", ".git")) return null
        return match.groupValues[1].lowercase()
    }

    fun isOfficial(repository: String): Boolean = ownerOf(repository) in GITHUB_OWNERS
}
