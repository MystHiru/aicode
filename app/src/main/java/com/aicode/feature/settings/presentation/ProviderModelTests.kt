package com.aicode.feature.settings.presentation

import com.aicode.feature.settings.data.remote.ModelTestResult
import com.aicode.feature.settings.domain.model.AIProviderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class ProviderModelTests(
    private val scope: CoroutineScope,
    private val test: suspend (AIProviderConfig, String) -> ModelTestResult
) {
    private val _results = MutableStateFlow<Map<Pair<String, String>, ModelTestResult>>(emptyMap())
    val results = _results.asStateFlow()
    private val _testing = MutableStateFlow<Set<Pair<String, String>>>(emptySet())
    val testing = _testing.asStateFlow()
    private val _batch = MutableStateFlow(BatchTestState())
    val batch = _batch.asStateFlow()

    private val permits = Semaphore(4)
    private val singleJobs = mutableMapOf<Pair<String, String>, Job>()
    private var batchJob: Job? = null
    private var batchKeys = emptySet<Pair<String, String>>()

    fun startSingle(provider: AIProviderConfig, model: String) {
        val key = provider.id to model
        if (key in singleJobs || key in batchKeys) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = permits.withPermit { test(provider, model) }
                ensureActive()
                _results.update { it + (key to result) }
            } finally {
                if (singleJobs[key] === coroutineContext.job) {
                    singleJobs.remove(key)
                    publishTesting()
                }
            }
        }
        singleJobs[key] = job
        publishTesting()
        job.start()
    }

    fun startBatch(provider: AIProviderConfig) {
        if (batchJob != null) return
        val keys = provider.models.distinct().map { provider.id to it }.filterNot { it in singleJobs }
        if (keys.isEmpty()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                coroutineScope {
                    val workers = minOf(keys.size, 4)
                    repeat(workers) { worker ->
                        launch {
                            for (index in worker until keys.size step workers) {
                                val key = keys[index]
                                val result = permits.withPermit { test(provider, key.second) }
                                ensureActive()
                                _results.update { it + (key to result) }
                                batchKeys = batchKeys - key
                                publishTesting()
                                _batch.update { state ->
                                    state.copy(done = state.done + 1, success = state.success + if (result.success) 1 else 0)
                                }
                            }
                        }
                    }
                }
            } finally {
                if (batchJob === coroutineContext.job) finishBatch()
            }
        }
        batchJob = job
        batchKeys = keys.toSet()
        _batch.value = BatchTestState(provider.id, keys.size, 0, 0, true)
        publishTesting()
        job.start()
    }

    fun stopBatch(providerId: String) {
        val job = batchJob ?: return
        if (_batch.value.providerId != providerId) return
        finishBatch()
        job.cancel()
    }

    fun clear(providerId: String) {
        stopBatch(providerId)
        val jobs = singleJobs.keys.filter { it.first == providerId }.mapNotNull { singleJobs.remove(it) }
        jobs.forEach { it.cancel() }
        _results.update { it.filterKeys { key -> key.first != providerId } }
        publishTesting()
    }

    private fun finishBatch() {
        batchJob = null
        batchKeys = emptySet()
        _batch.update { it.copy(running = false) }
        publishTesting()
    }

    private fun publishTesting() {
        _testing.value = singleJobs.keys + batchKeys
    }
}