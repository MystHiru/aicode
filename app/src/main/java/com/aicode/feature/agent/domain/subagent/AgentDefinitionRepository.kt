package com.aicode.feature.agent.domain.subagent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理定义仓库，聚合全局与项目级两级来源；同名定义项目级优先（与技能、MCP 两级配置一致）。
 */
@Singleton
class AgentDefinitionRepository @Inject constructor(
    private val localSource: LocalDirectoryAgentSource,
    private val projectSource: ProjectDirectoryAgentSource
) {
    /** 全部定义（含来源作用域），按名称排序。 */
    fun listAll(): List<AgentDefinitionEntry> =
        mergeAll(localSource.listDefinitions(), projectSource.listDefinitions())

    /** 按名称查找定义（忽略大小写）；不存在返回 null。 */
    fun find(name: String): AgentDefinition? =
        listAll().firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition

    /** 删除指定作用域的定义文件，不可恢复。返回是否成功。 */
    fun delete(name: String, scope: AgentDefinitionScope): Boolean {
        val entry = listAll().firstOrNull {
            it.definition.name.equals(name, ignoreCase = true) && it.scope == scope
        } ?: return false
        val file = entry.definition.file ?: return false
        return file.isFile && file.delete()
    }

    companion object {
        /** 合并两级来源：同名项目级覆盖全局，按名称排序。 */
        internal fun mergeAll(
            global: List<AgentDefinition>,
            project: List<AgentDefinition>
        ): List<AgentDefinitionEntry> {
            val byName = LinkedHashMap<String, AgentDefinitionEntry>()
            global.forEach { byName[it.name.lowercase()] = AgentDefinitionEntry(it, AgentDefinitionScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = AgentDefinitionEntry(it, AgentDefinitionScope.PROJECT) }
            return byName.values.sortedBy { it.definition.name.lowercase() }
        }
    }
}
