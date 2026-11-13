package com.aicode.feature.agent.domain.tool.root

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.root.RootShell
import com.aicode.feature.agent.domain.root.RootState
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * 通过 `su` 以 root 身份在宿主 Android 上执行命令。
 *
 * 与 [com.aicode.feature.agent.domain.tool.shizuku.ShizukuTool] 并存：Shizuku 走 adb shell
 * （uid 2000），本工具走 root（uid 0）。设备没有 su 时返回明确错误，不影响 Shizuku。
 * 默认每次调用需用户确认，与 Shizuku 的权限策略一致。
 */
class RootTool @Inject constructor(
    private val rootShell: RootShell
) : AgentTool() {
    private companion object {
        const val TAG = "RootTool"

        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Root"

    override val description =
        "通过 su 以 root 身份在宿主 Android 系统上执行命令。设备需已 root 并授权本应用；" +
            "无 su 时不可用，可改用 Shizuku。默认每次调用需用户确认。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "要通过 su 执行的 Shell 命令",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "命令最长执行时间（秒），超时将被强制终止。默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。",
            required = false
        )
    )

    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: "未知命令"
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认以 root 执行命令",
            summary = command,
            details = "将通过 su 以 root 身份在 Android 系统上执行。\n超时：${timeoutSeconds} 秒",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：command")

        if (rootShell.state() != RootState.READY) {
            return ToolResult.Error(
                "Root 不可用：设备没有可用的 su，或尚未授权本应用。可改用 Shizuku 工具。",
                code = "ROOT_NOT_AVAILABLE"
            )
        }

        return try {
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "Root exec (timeout=${timeoutMs}ms): $command")
            val result = rootShell.runCommand(command, timeoutMs)
            FileLogger.v(TAG, "Root exec 完成，输出 ${result.output.length} 字符，退出码 ${result.exitCode}")
            ToolResult.Success(JsonPrimitive(result.output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Root exec 失败: $command", e)
            ToolResult.Error("执行 root 命令失败：${e.message}")
        }
    }
}
