package com.aicode.feature.agent.domain.root

import com.aicode.feature.agent.domain.container.BoundedOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 宿主 root（su）可用状态。与 Shizuku 相互独立，互不影响。 */
enum class RootState {
    /** 设备没有可用的 su。 */
    UNAVAILABLE,

    /** su 可用，可执行命令。 */
    READY
}

/** 一次 root 命令执行结果。[exitCode] 为负值表示超时或启动异常。 */
data class RootCommandResult(val output: String, val exitCode: Int)

/**
 * 通过 `su` 在宿主 Android 上执行命令的后端。
 *
 * 这是 Shizuku 之外的第二条宿主命令通道：Shizuku 以 adb shell（uid 2000）身份执行，
 * 本通道以 root（uid 0）身份执行，仅在设备已 root 且授权了本应用时可用。
 * 两者权限评估独立，默认都需要逐次确认。
 */
@Singleton
class RootShell @Inject constructor() {

    @Volatile
    private var cachedState: RootState? = null

    /** 探测 su 是否可用；结果缓存，设置页刷新时用 [refresh]。 */
    suspend fun state(): RootState = cachedState ?: probe().also { cachedState = it }

    suspend fun refresh(): RootState = probe().also { cachedState = it }

    /**
     * 以 root 身份执行 [command]，超时后强杀。
     * 调用方应先确认 [state] 为 [RootState.READY]。
     */
    suspend fun runCommand(command: String, timeoutMs: Long): RootCommandResult =
        withContext(Dispatchers.IO) {
            exec(arrayOf("su", "-c", command), timeoutMs)
        }

    private suspend fun probe(): RootState = withContext(Dispatchers.IO) {
        val result = runCatching { exec(arrayOf("su", "-c", "id"), PROBE_TIMEOUT_MS) }.getOrNull()
        if (result != null && result.exitCode == 0 && result.output.contains("uid=0")) {
            RootState.READY
        } else {
            RootState.UNAVAILABLE
        }
    }

    private fun exec(argv: Array<String>, timeoutMs: Long): RootCommandResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder(*argv).redirectErrorStream(true).start()
            // 读取与等待并行：管道写满会阻塞子进程，先 waitFor 再读会死锁。
            val output = BoundedOutput()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            output.append(line)
                            output.append("\n")
                        }
                    }
                }
            }
            reader.start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(READER_JOIN_TIMEOUT_MS)
            RootCommandResult(output.build(), if (finished) process.exitValue() else EXIT_TIMEOUT)
        } catch (e: Exception) {
            RootCommandResult(e.message ?: "执行失败", EXIT_FAILURE)
        } finally {
            process?.destroy()
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        const val READER_JOIN_TIMEOUT_MS = 2_000L
        const val EXIT_TIMEOUT = -1000
        const val EXIT_FAILURE = -1
    }
}
