package com.aicode.feature.agent.domain.skill

import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目级技能来源：`<projectRoot>/.aicode/skills/`，随工作区走，可 git 追踪。
 * 远程工作区模式下 [WorkspaceRepository.currentPath] 返回远程路径，同样生效。
 */
@Singleton
class ProjectDirectorySkillSource @Inject constructor(
    private val workspaceRepository: WorkspaceRepository
) : SkillSource {

    val skillsRoot: File
        get() = File(File(workspaceRepository.currentPath(), ".aicode"), "skills")

    override fun listSkills(): List<Skill> = SkillDirectoryScanner.scan(skillsRoot)

    override fun loadInstructions(name: String): String? =
        listSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions
}
