package com.aicode.feature.plugin.presentation

import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.data.SOURCE_DEVELOPMENT
import com.aicode.feature.plugin.data.index.PluginIndexDefaults
import com.aicode.feature.plugin.data.index.PluginIndexPlugin
import com.aicode.feature.plugin.data.index.PluginIndexSource
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginInstallOrigin
import com.aicode.feature.plugin.domain.PluginManifestParser
import com.aicode.feature.plugin.domain.ManifestParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginStoreCatalogTest {
    private val official = entry(PluginIndexDefaults.official, "2.0.0")
    private val vanta = entry(PluginIndexDefaults.vanta, "3.0.0")
    private val custom = entry(PluginIndexSource("custom", "Custom", "https://example.com/index.json"), "9.0.0")

    @Test
    fun newInstallsUseSourcePriorityBeforeVersion() {
        val state = PluginStoreUiState().withCatalog(listOf(official, vanta, custom), emptyMap())
        assertEquals(official, state.items.single())
        assertTrue(state.updatesByPluginId.isEmpty())
    }

    @Test
    fun installedDuplicateUsesOriginalIndexInsteadOfHigherPriorityIndex() {
        for (source in listOf(official, vanta, custom)) {
            val state = catalog(listOf(official, vanta, custom), record(source))
            val listing = checkNotNull(state.currentListing(source))
            assertEquals(source.source.id, state.items.single().source.id)
            assertEquals(listing, state.updatesByPluginId.getValue(source.plugin.id))
            assertEquals(source.plugin.version, listing.plugin.version)
        }
    }

    @Test
    fun losingOriginalIndexCannotEnableCrossSourceUpdate() {
        val state = catalog(listOf(vanta, custom), record(official))
        assertEquals(PluginIndexDefaults.VANTA_ID, state.items.single().source.id)
        assertEquals("1.0.0", state.items.single().installedVersion)
        assertFalse(state.items.single().updateAvailable)
        assertTrue(state.updatesByPluginId.isEmpty())
        assertNull(state.currentListing(official))
    }

    @Test
    fun updateRequiresOriginalRepository() {
        val sameSource = catalog(listOf(vanta), record(vanta))
        assertTrue(sameSource.items.single().updateAvailable)
        val changedRepository = record(vanta).copy(repository = "https://github.com/other/plug")
        assertFalse(catalog(listOf(vanta), changedRepository).items.single().updateAvailable)
    }

    @Test
    fun staleDetailDoesNotMatchAnotherRepositoryAtTheSameIndex() {
        val replacement = vanta.copy(plugin = vanta.plugin.copy(repository = "https://github.com/other/plug"))
        val state = catalog(listOf(replacement), record(vanta))
        assertNull(state.currentListing(vanta))
        assertTrue(state.updatesByPluginId.isEmpty())
    }

    @Test
    fun updateRequiresCompatibleNewerPackageAndAnIndexedInstallation() {
        val installed = record(vanta)
        assertFalse(vanta.canUpdate(installed.copy(version = "3.0.0")))
        assertFalse(vanta.canUpdate(installed.copy(version = "4.0.0")))
        assertFalse(vanta.copy(compatible = false).canUpdate(installed))
        assertFalse(vanta.canUpdate(installed.copy(origin = PluginInstallOrigin.KIND_LOCAL)))
        assertFalse(vanta.canUpdate(installed.copy(sourceKind = SOURCE_DEVELOPMENT)))
        assertTrue(vanta.canUpdate(installed.copy(enabled = false)))
    }

    @Test
    fun downloadedManifestMustMatchListingAndResolvedVersion() {
        val parsed = PluginManifestParser.parse(
            """{
                "manifestVersion":1,"id":"jieapi.sample","name":"Sample","version":"3.0.0",
                "publisher":"jieapi","repository":"https://github.com/jieapi/sample",
                "description":"Sample plugin","license":"MIT","engines":{"pluginApi":"^1.0.0"},
                "contributes":{"configPage":"pages/config.html"}
            }"""
        ) as ManifestParseResult.Success
        val manifest = parsed.manifest
        assertTrue(vanta.matchesPackage(manifest, "3.0.0"))
        assertFalse(vanta.matchesPackage(manifest, "2.0.0"))
        assertFalse(vanta.matchesPackage(manifest.copy(id = "jieapi.other"), "3.0.0"))
        assertFalse(vanta.matchesPackage(manifest.copy(publisher = "other"), "3.0.0"))
        // 社区商店索引与包内 repository 常不一致（索引写商店地址、包内写作者仓库），
        // 不参与校验，只要求 id / version 对得上。
        assertTrue(vanta.matchesPackage(manifest.copy(repository = "https://github.com/other/plug"), "3.0.0"))
    }

    @Test
    fun requiresThirdPartyConfirmationEvenWhenRepositoryClaimsOfficialOwnership() {
        assertTrue(official.official)
        assertFalse(vanta.official)
        assertFalse(custom.official)
        assertFalse(official.copy(plugin = official.plugin.copy(packageUrl = "https://example.com/package.zip")).official)
    }

    @Test
    fun changedDownloadMetadataInvalidatesAnOpenPreview() {
        val refreshed = vanta.copy(plugin = vanta.plugin.copy(packageUrl = "https://example.com/new.zip"))
        val before = catalog(listOf(vanta), record(vanta))
        val after = catalog(listOf(refreshed), record(vanta))
        assertTrue(before.isListingCurrent(vanta))
        assertFalse(after.isListingCurrent(vanta))
        assertTrue(after.isListingCurrent(refreshed))
    }

    private fun catalog(items: List<PluginStoreItem>, installed: PluginRecord) =
        PluginStoreUiState().withCatalog(items, mapOf(installed.id to installed))

    private fun record(item: PluginStoreItem) = PluginRecord(
        id = item.plugin.id,
        version = "1.0.0",
        enabled = true,
        installedAt = 0,
        origin = PluginInstallOrigin.KIND_INDEX,
        archiveSha256 = "",
        manifestSha256 = "",
        sourceId = item.source.id,
        indexUrl = item.source.indexUrl,
        repository = item.plugin.repository
    )

    private fun entry(source: PluginIndexSource, version: String) = PluginStoreItem(
        plugin = PluginIndexPlugin(
            id = "jieapi.sample",
            name = LocalizedText.single("Sample"),
            description = LocalizedText.single("Sample plugin"),
            author = "jieapi",
            repository = "https://github.com/jieapi/sample",
            version = version
        ),
        source = source,
        compatible = true
    )
}
