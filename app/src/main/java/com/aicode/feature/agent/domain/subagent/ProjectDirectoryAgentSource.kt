package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目级子代理定义来源：`<projectRoot>/.aicode/agents/`，随工作区走，可 git 追踪。
 */
@Singleton
class ProjectDirectoryAgentSource @Inject constructor(
    private val workspaceRepository: WorkspaceRepository
) : AgentDefinitionSource {

    val agentsRoot: File
        get() = File(File(workspaceRepository.currentPath(), ".aicode"), "agents")

    override fun listDefinitions(): List<AgentDefinition> =
        AgentDefinitionDirectoryScanner.scan(agentsRoot)
}
