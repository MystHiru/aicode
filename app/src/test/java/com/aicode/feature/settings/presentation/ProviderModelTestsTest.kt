package com.aicode.feature.settings.presentation

import com.aicode.feature.settings.data.remote.ModelTestResult
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderModelTestsTest {
    @Test
    fun stoppingAndRestartingBatchPreservesTheNewRunAndOtherProviders() = runTest {
        val provider = provider("provider-a", "shared")
        val another = provider("provider-b", "shared")
        val currentResult = CompletableDeferred<ModelTestResult>()
        val anotherResult = CompletableDeferred<ModelTestResult>()
        var attempts = 0
        val tests = ProviderModelTests(this) { config, _ ->
            if (config.id == another.id) anotherResult.await()
            else if (++attempts == 1) awaitCancellation()
            else currentResult.await()
        }
        try {
            tests.startSingle(another, "shared")
            tests.startBatch(provider)
            runCurrent()
            assertEquals(1, attempts)
            tests.stopBatch(provider.id)
            tests.startBatch(provider)
            runCurrent()
            assertTrue(tests.batch.value.running)
            assertEquals(setOf(provider.id to "shared", another.id to "shared"), tests.testing.value)

            currentResult.complete(result("new batch"))
            runCurrent()
            assertEquals("new batch", tests.results.value[provider.id to "shared"]?.message)
            assertEquals(setOf(another.id to "shared"), tests.testing.value)
            assertFalse(tests.batch.value.running)
        } finally {
            tests.clear(provider.id)
            tests.clear(another.id)
        }
    }

    @Test
    fun leavingProviderEditorCancelsOnlyThatProvidersTests() = runTest {
        val first = provider("first", "shared")
        val second = provider("second", "shared")
        val cancelled = mutableListOf<String>()
        val tests = ProviderModelTests(this) { config, _ ->
            try {
                awaitCancellation()
            } finally {
                cancelled += config.id
            }
        }
        try {
            tests.startSingle(first, "shared")
            tests.startSingle(first, "shared")
            tests.startSingle(second, "shared")
            runCurrent()
            tests.clear(first.id)
            runCurrent()
            assertEquals(listOf(first.id), cancelled)
            assertEquals(setOf(second.id to "shared"), tests.testing.value)
            assertTrue(tests.results.value.isEmpty())
        } finally {
            tests.clear(first.id)
            tests.clear(second.id)
        }
    }

    private fun provider(id: String, vararg models: String) = AIProviderConfig(
        id = id, name = id, type = ProviderType.OPENAI, apiKey = "", baseUrl = "",
        defaultModel = models.first(), models = models.toList()
    )

    private fun result(message: String) = ModelTestResult(success = true, latencyMs = 1, message = message)
}