package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局子代理定义来源：`aicodeDir/agents`（容器内 `/root/.aicode/agents`），跨项目、跨升级保留。
 */
@Singleton
class LocalDirectoryAgentSource @Inject constructor(
    private val containerInstaller: ContainerInstaller
) : AgentDefinitionSource {

    val agentsRoot: File by lazy {
        File(containerInstaller.aicodeDir, "agents").also { it.mkdirs() }
    }

    override fun listDefinitions(): List<AgentDefinition> =
        AgentDefinitionDirectoryScanner.scan(agentsRoot)
}
