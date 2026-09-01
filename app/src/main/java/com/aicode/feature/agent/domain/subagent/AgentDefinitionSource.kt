package com.aicode.feature.agent.domain.subagent

import java.io.File

/** 子代理定义来源：一个目录下的 `*.md`，每个文件一个 agent。 */
interface AgentDefinitionSource {
    fun listDefinitions(): List<AgentDefinition>
}

/** 目录扫描：只取顶层 `*.md`，避免把技能目录等无关内容误当 agent 定义。 */
internal object AgentDefinitionDirectoryScanner {
    fun scan(root: File): List<AgentDefinition> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
            ?.mapNotNull { AgentDefinitionParser.parse(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }
}
