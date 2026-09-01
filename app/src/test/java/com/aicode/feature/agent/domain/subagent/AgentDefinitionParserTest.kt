package com.aicode.feature.agent.domain.subagent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentDefinitionParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun write(name: String, content: String): File =
        File(tempFolder.root, name).apply { writeText(content) }

    @Test
    fun parse_fullFrontmatter() {
        val file = write(
            "researcher.md",
            """
            ---
            name: researcher
            description: 只读调研
            provider: DeepSeek
            model: deepseek-reasoner
            reasoningEffort: high
            tools: [readFile, search]
            disallowedTools: [Bash]
            inject: [base, projectRules]
            ---
            你是调研专家。
            """.trimIndent()
        )

        val def = AgentDefinitionParser.parse(file)!!

        assertEquals("researcher", def.name)
        assertEquals("只读调研", def.description)
        assertEquals("DeepSeek", def.providerId)
        assertEquals("deepseek-reasoner", def.model)
        assertEquals("high", def.reasoningEffort)
        assertEquals(listOf("readFile", "search"), def.allowedTools)
        assertEquals(listOf("Bash"), def.disallowedTools)
        assertEquals(setOf(InjectPart.BASE, InjectPart.PROJECT_RULES), def.inject)
        assertEquals("你是调研专家。", def.prompt)
    }

    @Test
    fun parse_nameFallsBackToFileName() {
        val def = AgentDefinitionParser.parse(write("coder.md", "---\ndescription: 写代码\n---\n正文"))!!

        assertEquals("coder", def.name)
    }

    @Test
    fun parse_defaultsWhenInjectOmitted() {
        val def = AgentDefinitionParser.parse(write("a.md", "---\nname: a\n---\n正文"))!!

        assertEquals(AgentDefinition.DEFAULT_INJECT, def.inject)
        assertTrue(def.allowedTools.isEmpty())
        assertTrue(def.disallowedTools.isEmpty())
        assertNull(def.providerId)
        assertNull(def.model)
        assertNull(def.reasoningEffort)
    }

    @Test
    fun parse_injectNoneMeansNoInjection() {
        val def = AgentDefinitionParser.parse(write("a.md", "---\nname: a\ninject: [none]\n---\n正文"))!!

        assertTrue(def.inject.isEmpty())
    }

    /** 全部取值非法时回退默认，避免写错一个词就丢掉全部上下文。 */
    @Test
    fun parse_invalidInjectFallsBackToDefault() {
        val def = AgentDefinitionParser.parse(write("a.md", "---\nname: a\ninject: [bogus]\n---\n正文"))!!

        assertEquals(AgentDefinition.DEFAULT_INJECT, def.inject)
    }

    @Test
    fun parse_toolsAcceptsCommaSeparatedString() {
        val def = AgentDefinitionParser.parse(
            write("a.md", "---\nname: a\ntools: readFile, search\n---\n正文")
        )!!

        assertEquals(listOf("readFile", "search"), def.allowedTools)
    }

    @Test
    fun parse_invalidReasoningEffortIgnored() {
        val def = AgentDefinitionParser.parse(
            write("a.md", "---\nname: a\nreasoningEffort: turbo\n---\n正文")
        )!!

        assertNull(def.reasoningEffort)
    }

    @Test
    fun parse_blankPromptReturnsNull() {
        assertNull(AgentDefinitionParser.parse(write("a.md", "---\nname: a\n---\n   \n")))
    }

    @Test
    fun parse_noFrontmatterTreatsWholeFileAsPrompt() {
        val def = AgentDefinitionParser.parse(write("plain.md", "只有正文"))!!

        assertEquals("plain", def.name)
        assertEquals("只有正文", def.prompt)
    }

    @Test
    fun scan_onlyTopLevelMarkdownSortedByName() {
        write("b.md", "---\nname: b\n---\n正文")
        write("a.md", "---\nname: a\n---\n正文")
        write("notes.txt", "不是定义")
        File(tempFolder.root, "nested").mkdirs()
        File(File(tempFolder.root, "nested"), "c.md").writeText("---\nname: c\n---\n正文")

        val defs = AgentDefinitionDirectoryScanner.scan(tempFolder.root)

        assertEquals(listOf("a", "b"), defs.map { it.name })
    }

    @Test
    fun scan_missingRootReturnsEmpty() {
        assertTrue(AgentDefinitionDirectoryScanner.scan(File(tempFolder.root, "not-exists")).isEmpty())
    }
}
