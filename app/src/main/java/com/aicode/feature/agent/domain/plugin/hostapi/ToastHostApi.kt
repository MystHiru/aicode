package com.aicode.feature.agent.domain.plugin.hostapi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.JsonRpcError
import com.aicode.feature.agent.domain.mcp.JsonRpcResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件 client.tui.showToast API：映射为 Android Toast 提示（前台有 UI 时可见）。
 *
 * 独立成类因为需要 Context，且 Toast 必须在主线程调用。
 */
@Singleton
class ToastHostApi @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private companion object {
        const val TAG = "ToastHostApi"
    }

    fun handleToast(params: JsonObject, plugin: String?): JsonRpcResponse {
        val body = (params["body"] as? JsonObject)
        val message = (body?.get("message") as? JsonPrimitive)?.contentOrNull ?: ""
        if (message.isNotBlank()) {
            FileLogger.d(TAG, "插件 toast: ${message.take(80)}" + (plugin?.let { " plugin=$it" } ?: ""))
            val prefix = runCatching { context.getString(R.string.plugin_toast_prefix) }.getOrDefault("[plugin] ")
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    Toast.makeText(context.applicationContext, "$prefix$message", Toast.LENGTH_SHORT).show()
                }.onFailure { FileLogger.w(TAG, "showToast 失败: ${it.message}") }
            }
        }
        return ok()
    }

    private fun ok(result: JsonElement = buildJsonObject { }): JsonRpcResponse = JsonRpcResponse(result = result)
}
