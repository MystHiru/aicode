package com.aicode.feature.agent.domain.prompt

import android.content.Context
import android.content.res.AssetManager
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 四级来源片段的解析/保存/删除/编号交换：编号身份、优先级、落盘层与交换只动两个片段。
 *
 * 注意：本地层（`prompts/`）只在**内置存在对应编号**时才会被解析（见 [PromptFragmentCatalog.resolve]），
 * 故这里让 assets 暴露一个内置文件名 `10-builtin.md`，本地同名文件才会生效。
 */
class PromptFragmentCatalogTest {

    private lateinit var root: File
    private lateinit var aicodeDir: File
    private lateinit var workspaceDir: File
    private lateinit var catalog: PromptFragmentCatalog

    private val globalDir get() = File(aicodeDir, "prompts.custom")
    private val localDir get() = File(aicodeDir, "prompts")
    private val projectDir get() = File(File(workspaceDir, ".aicode"), "prompts.custom")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("prompt-catalog-test").toFile()
        aicodeDir = File(root, "aicode").apply { mkdirs() }
        workspaceDir = File(root, "workspace").apply { mkdirs() }
        globalDir.mkdirs()
        localDir.mkdirs()

        val context = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { context.assets } returns assets
        every { assets.list(any()) } returns arrayOf(BUILTIN_NAME)
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns aicodeDir
        val projectRoot = mockk<ProjectAicodeRoot>()
        every { projectRoot.forPath(any()) } returns File(workspaceDir, ".aicode")

        catalog = PromptFragmentCatalog(context, installer, projectRoot)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(dir: File, name: String, content: String): File {
        dir.mkdirs()
        return File(dir, name).apply { writeText(content) }
    }

    @Test
    fun list_同编号_取优先级最高的项目层() {
        write(globalDir, "10-g.md", "G")
        write(localDir, BUILTIN_NAME, "L")
        write(projectDir, "10-p.md", "P")

        val fragment = catalog.list(workspaceDir.path).first { it.number == 10 }

        assertEquals(PromptFragmentSource.PROJECT, fragment.source)
        assertEquals("P", fragment.content)
    }

    @Test
    fun list_无项目层_回退全局再回退本地() {
        write(localDir, BUILTIN_NAME, "L")
        write(globalDir, "10-g.md", "G")
        assertEquals(PromptFragmentSource.GLOBAL, catalog.list(workspaceDir.path).first { it.number == 10 }.source)

        globalDir.listFiles()!!.forEach { it.delete() }
        assertEquals(PromptFragmentSource.LOCAL, catalog.list(workspaceDir.path).first { it.number == 10 }.source)
    }

    @Test
    fun renderStatic_按编号升序拼接正文() {
        write(globalDir, "30-b.md", "BBB")
        write(globalDir, "10-a.md", "AAA")

        assertEquals("AAA\n\nBBB", catalog.renderStatic(workspaceDir.path))
    }

    @Test
    fun saveOverride_写到指定层并清掉旧编号() {
        catalog.saveOverride(12, "x", "X", workspaceDir.path, target = PromptFragmentSource.GLOBAL)
        assertTrue(File(globalDir, "12-x.md").isFile)

        catalog.saveOverride(
            20, "x", "X", workspaceDir.path,
            target = PromptFragmentSource.GLOBAL, previousNumber = 12
        )
        assertFalse("旧编号应被清掉", File(globalDir, "12-x.md").exists())
        assertTrue(File(globalDir, "20-x.md").isFile)
    }

    @Test
    fun deleteOverride_删除可写覆盖() {
        write(globalDir, "12-x.md", "X")
        assertTrue(catalog.deleteOverride(12, workspaceDir.path))
        assertFalse(File(globalDir, "12-x.md").exists())
    }

    @Test
    fun swapNumbers_只重写两个片段_其余不动_只读层不被删() {
        write(projectDir, "12-a.md", "A")
        write(projectDir, "20-c.md", "C")
        write(localDir, BUILTIN_NAME, "L")

        val a = catalog.list(workspaceDir.path).first { it.number == 12 }
        val b = catalog.list(workspaceDir.path).first { it.number == 10 }
        assertEquals(PromptFragmentSource.LOCAL, b.source)
        assertTrue(catalog.swapNumbers(a, b, workspaceDir.path))

        assertTrue("只读的本地文件不应被删除", File(localDir, BUILTIN_NAME).isFile)
        assertEquals("未参与的片段不应被改写", "C", File(projectDir, "20-c.md").readText())

        val after = catalog.list(workspaceDir.path)
        assertEquals("A", after.first { it.number == 10 }.content)
        assertEquals("L", after.first { it.number == 12 }.content)
    }

    private companion object {
        /** 让本地层可解析的内置文件名（编号 10）。 */
        const val BUILTIN_NAME = "10-builtin.md"
    }
}
