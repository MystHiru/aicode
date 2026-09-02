package com.aicode.feature.agent.domain.tool.file

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLConnection
import javax.inject.Inject

/**
 * 把工作区文件以「文件卡片」形式发送到聊天区展示，点击卡片会用系统对应 app 打开。
 *
 * 原子语义：所有文件必须全部存在且合法，任一失败则整体失败（不渲染任何卡片），
 * 错误信息逐条列出失败项，AI 可修正 paths 后重新调用。
 * 文件数据（内容/base64）不会进入模型上下文，模型只收到文件元数据文本。
 */
class SendFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentTool() {
    override val name = "sendFile"
    override val description = "把工作区文件以「文件卡片」形式发送到聊天区展示给用户，点击卡片查看：图片在应用内全屏预览，其它类型用系统对应 app 打开。支持一次发送多个文件（聊天区可左右滑动）。所有文件必须全部存在，任一文件不存在/不是文件/过大则整体失败，需修正后重新调用。仅用于把已有文件展示给用户，不读取文件内容。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val parameters = mapOf(
        "paths" to ToolParameter(
            name = "paths",
            type = ParameterType.ARRAY,
            description = "要发送的文件路径列表，支持 ~/workspace/... 项目文件或容器绝对路径；一次最多 $MAX_FILES 个，单个文件不超过 ${MAX_FILE_SIZE_MB}MB。",
            required = true,
            itemsSchema = mapOf("type" to "string")
        ),
        "names" to ToolParameter(
            name = "names",
            type = ParameterType.ARRAY,
            description = "可选的自定义显示名列表，与 paths 一一对应；缺省用文件名。",
            required = false,
            itemsSchema = mapOf("type" to "string")
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val paths = (args["paths"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (paths.isEmpty()) {
                return ToolResult.Error("paths 参数缺失或为空", "MISSING_PATHS")
            }
            if (paths.size > MAX_FILES) {
                return ToolResult.Error("一次最多发送 $MAX_FILES 个文件，当前 ${paths.size} 个", "TOO_MANY_FILES")
            }
            val names = (args["names"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map { it.trim() }
                .orEmpty()
            if (names.isNotEmpty() && names.size != paths.size) {
                return ToolResult.Error("names 数量（${names.size}）与 paths 数量（${paths.size}）不一致", "ARGS_MISMATCH")
            }

            // 原子校验：全部通过才成功，任一失败整体失败并列出所有失败项。
            val failures = mutableListOf<String>()
            paths.forEachIndexed { index, path ->
                when {
                    !fileAccess.exists(path) -> failures.add("文件不存在: $path")
                    !fileAccess.isFile(path) -> failures.add("路径不是文件: $path")
                    fileAccess.fileSize(path) > MAX_FILE_SIZE_BYTES -> {
                        val sizeMb = fileAccess.fileSize(path) / (1024 * 1024)
                        failures.add("文件超过 ${MAX_FILE_SIZE_MB}MB 限制: $path（${sizeMb}MB）")
                    }
                }
            }
            if (failures.isNotEmpty()) {
                FileLogger.w(TAG, "sendFile 原子校验失败: ${failures.joinToString("；")}")
                return ToolResult.Error(failures.joinToString("；"), "INVALID_FILE")
            }

            val files = paths.mapIndexed { index, path ->
                val rawName = path.substringAfterLast('/').ifBlank { path }
                // 显示名允许自定义；MIME 类型必须按原始文件名推断，自定义名缺后缀/加前缀都不影响类型识别。
                val displayName = names.getOrNull(index)?.takeIf { it.isNotBlank() } ?: rawName
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                        "local_path" to JsonPrimitive(fileAccess.copyToLocal(path).absolutePath),
                        "name" to JsonPrimitive(displayName),
                        "mime_type" to JsonPrimitive(guessMimeType(rawName)),
                        "size_bytes" to JsonPrimitive(fileAccess.fileSize(path)),
                        "is_image" to JsonPrimitive(guessMimeType(rawName).startsWith("image/"))
                    )
                )
            }

            FileLogger.i(TAG, "sendFile 成功: ${paths.size} 个文件 ${paths.joinToString(", ") { fileAccess.toDisplayPath(it) }}")
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "content" to JsonPrimitive(
                            buildString {
                                append("已发送 ${files.size} 个文件：")
                                files.forEach { file ->
                                    append('\n')
                                    append("- ")
                                    append(file["name"]?.jsonPrimitive?.contentOrNull)
                                    append("：")
                                    append(file["path"]?.jsonPrimitive?.contentOrNull)
                                }
                            }
                        ),
                        "files" to JsonArray(files),
                        "file_count" to JsonPrimitive(files.size)
                    )
                )
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "sendFile 异常", e)
            ToolResult.Error(e.message ?: "发送文件失败", "SEND_FILE_ERROR")
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extMime = when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt", "md", "kt", "java", "js", "ts", "json", "xml", "html", "css", "py", "sh", "gradle", "kts" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> null
        }
        return extMime ?: URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
    }

    private companion object {
        const val TAG = "SendFileTool"
        const val MAX_FILES = 10
        const val MAX_FILE_SIZE_MB = 100
        const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024L * 1024L
    }
}
