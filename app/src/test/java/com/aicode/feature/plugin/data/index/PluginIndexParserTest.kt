package com.aicode.feature.plugin.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginIndexParserTest {

    private fun index(vararg plugins: String): ByteArray =
        """{"schema_version":1,"plugins":[${plugins.joinToString(",")}]}""".toByteArray()

    private fun plugin(
        version: String? = null,
        tags: List<String>? = null,
        name: String = "\"Sample Plugin\"",
        description: String = "\"Sample\"",
        packageUrl: String? = null,
        packageSize: Long? = null,
        packageSha256: String? = null
    ): String = buildString {
        append("{")
        append("\"id\":\"author-name.sample-plugin\",")
        append("\"name\":$name,")
        append("\"description\":$description,")
        append("\"author\":\"author-name\",")
        append("\"repository\":\"https://github.com/author-name/sample-plugin\"")
        version?.let { append(",\"version\":\"$it\"") }
        tags?.let { append(",\"tags\":[${it.joinToString(",") { tag -> "\"$tag\"" }}]") }
        packageUrl?.let { append(",\"package_url\":\"$it\"") }
        packageSize?.let { append(",\"package_size\":$it") }
        packageSha256?.let { append(",\"package_sha256\":\"$it\"") }
        append("}")
    }

    @Test
    fun parsesMinimalPluginWithoutOptionalFields() {
        val result = PluginIndexParser.parse(index(plugin()))
        assertEquals(1, result.plugins.size)
        assertEquals("Sample Plugin", result.plugins[0].name.resolve("en"))
        assertEquals("Sample", result.plugins[0].description.resolve("zh-CN"))
        assertNull(result.plugins[0].version)
        assertTrue(result.plugins[0].tags.isEmpty())
    }

    @Test
    fun parsesLocalizedTextAndResolvesByLanguageTag() {
        val result = PluginIndexParser.parse(
            index(
                plugin(
                    name = """{"default":"Sample Plugin","zh-CN":"示例插件"}""",
                    description = """{"default":"Sample","zh_CN":"示例"}"""
                )
            )
        )
        val plugin = result.plugins[0]
        for (tag in listOf("zh", "zh-CN", "zh-Hans", "zh-Hans-CN", "zh-Hant-TW")) {
            assertEquals("示例插件", plugin.name.resolve(tag))
            assertEquals("示例", plugin.description.resolve(tag))
        }
        assertEquals("Sample Plugin", plugin.name.resolve("en"))
    }

    @Test
    fun rejectsInvalidLanguageTag() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(name = """{"zh-CN":"示例","bad tag":"x"}""")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsDuplicateNormalizedLanguageTags() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(name = """{"zh-CN":"甲","zh_cn":"乙"}""")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsBlankLocalizedText() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(name = """{"default":"   "}""")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsNonStringLocalizedValue() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(name = """{"zh-CN":1}""")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsOverlongLocalizedName() {
        val longName = "a".repeat(121)
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(name = "\"$longName\"")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsNewlineInNameButAllowsItInDescription() {
        val nameError = runCatching {
            PluginIndexParser.parse(index(plugin(name = "\"Line\\nBreak\"")))
        }.exceptionOrNull()
        assertTrue(nameError is PluginIndexException)

        val result = PluginIndexParser.parse(index(plugin(description = "\"Line\\nBreak\"")))
        assertEquals("Line\nBreak", result.plugins[0].description.resolve("en"))
    }

    @Test
    fun rejectsVersionStartingWithV() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(version = "v1.0.0")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsUnknownTag() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(tags = listOf("unknown"))))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsDuplicatePluginIds() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(), plugin()))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun parsesDirectPackageMetadata() {
        val result = PluginIndexParser.parse(
            index(
                plugin(
                    version = "1.2.3",
                    packageUrl = "https://example.com/sample.zip",
                    packageSize = 128,
                    packageSha256 = "a".repeat(64)
                )
            )
        )
        val plugin = result.plugins.single()
        assertEquals("https://example.com/sample.zip", plugin.packageUrl)
        assertEquals(128L, plugin.packageSize)
        assertEquals("a".repeat(64), plugin.packageSha256)
    }

    @Test
    fun rejectsDirectPackageWithoutVersion() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(packageUrl = "https://example.com/sample.zip")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsNonHttpsDirectPackage() {
        assertThrows(PluginIndexException::class.java) {
            PluginIndexParser.parse(index(plugin(version = "1.0.0", packageUrl = "http://example.com/sample.zip")))
        }
    }

    @Test
    fun rejectsInvalidIntegrityFieldsIndependently() {
        val url = "https://example.com/sample.zip"
        listOf(
            plugin(version = "1.0.0", packageUrl = url, packageSize = 0),
            plugin(version = "1.0.0", packageUrl = url, packageSize = MAX_PLUGIN_ARCHIVE_BYTES + 1),
            plugin(version = "1.0.0", packageUrl = url, packageSha256 = "not-a-sha256"),
            plugin(version = "1.0.0", packageSize = 128),
            plugin(version = "1.0.0", packageSha256 = "a".repeat(64))
        ).forEach { invalid ->
            assertThrows(PluginIndexException::class.java) { PluginIndexParser.parse(index(invalid)) }
        }
    }

    @Test
    fun skipsInvalidPluginWhenRequested() {
        val skipped = mutableListOf<Int>()
        val result = PluginIndexParser.parse(
            index(
                plugin().replace("author-name.sample-plugin", "一束挽风.wb-gateway"),
                "null",
                plugin(),
                plugin()
            ),
            skipInvalidPlugins = true
        ) { skipped += it }
        assertEquals(listOf(0, 1, 3), skipped)
        assertEquals("author-name.sample-plugin", result.plugins.single().id)
    }

    @Test
    fun lenientIndexStillRejectsInvalidDocumentOrAnIndexWithNoUsableEntries() {
        listOf("{", """{"schema_version":"1","plugins":[]}""", """{"schema_version":1,"plugins":{}}""")
            .map { it.toByteArray() }
            .plus(listOf(index("null")))
            .forEach { invalid ->
                assertThrows(PluginIndexException::class.java) {
                    PluginIndexParser.parse(invalid, skipInvalidPlugins = true)
                }
            }
        assertTrue(PluginIndexParser.parse("""{"schema_version":1}""".toByteArray()).plugins.isEmpty())
    }

    @Test
    fun officialPluginIsDeterminedByRepositoryOwner() {
        assertTrue(PluginOfficialRepositories.isOfficial("https://github.com/jieapi/aicode"))
        assertTrue(PluginOfficialRepositories.isOfficial("https://github.com/jieapi/aicode-plugin"))
        assertFalse(PluginOfficialRepositories.isOfficial("https://github.com/other/aicode-plugin"))
    }
}
