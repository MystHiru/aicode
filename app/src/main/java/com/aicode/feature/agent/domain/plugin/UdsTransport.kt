package com.aicode.feature.agent.domain.plugin

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.JsonRpcError
import com.aicode.feature.agent.domain.mcp.JsonRpcNotification
import com.aicode.feature.agent.domain.mcp.JsonRpcRequest
import com.aicode.feature.agent.domain.mcp.JsonRpcResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 插件运行时传输层：通过 Unix Domain Socket 与容器内 Node 伴生进程（runner.mjs）通信。
 *
 * 协议为 NDJSON 上的 JSON-RPC 2.0（报文模型复用 [JsonRpcRequest]/[JsonRpcResponse]）：
 * 每行一条消息；带 id 的请求等待响应（按 id 路由到 pending），不带 id 的通知不回包。
 * 连接由 [connect] 建立（阻塞式 LocalSocket），读写循环跑在 IO 线程。
 *
 * 与 MCP 的 [com.aicode.feature.agent.domain.mcp.StdioTransport] 结构对称，
 * 区别仅在于进程间通道：MCP 用 stdin/stdout，插件用 UDS。
 */
class UdsTransport(
    private val socketFile: String,
    private val json: Json = DEFAULT_JSON
) {
    private companion object {
        const val TAG = "PluginUdsTransport"

        /** 单次请求等待响应的上限：插件 hook 可能执行异步逻辑（如网络调用），给足时间。 */
        const val REQUEST_TIMEOUT_MS = 30_000L

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicLong(0)
    private val writeLock = Any()

    /** 已发出、等待响应的请求：id → 待完成的响应。 */
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()

    /**
     * runner → Kotlin 方向请求处理器：收到带 id+method 的消息时调用，返回响应（含 result 或 error）。
     * 由 PluginClient 在 connect 前注册，用于实现插件 client.* API（session/config/files 等宿主能力）。
     */
    @Volatile
    var onRequest: (suspend (JsonObject, String?) -> JsonRpcResponse)? = null

    @Volatile private var socket: LocalSocket? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var closed = false

    /** 建立 UDS 连接并启动读写循环。失败抛异常，由 PluginManager 兜底。 */
    fun connect() {
        if (closed) throw IllegalStateException("transport 已关闭")
        FileLogger.i(TAG, "连接插件运行时 UDS: $socketFile")
        val s = LocalSocket()
        s.connect(LocalSocketAddress(socketFile, LocalSocketAddress.Namespace.FILESYSTEM))
        socket = s
        reader = s.inputStream.bufferedReader()
        writer = s.outputStream.bufferedWriter()
        scope.launch { readLoop() }
    }

    suspend fun request(method: String, params: JsonObject? = null): JsonRpcResponse {
        ensureConnected()
        val id = idCounter.incrementAndGet()
        val payload = JsonRpcRequest(id = id, method = method, params = params)
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
        FileLogger.d(TAG, "→ [$method] id=$id")

        try {
            withContext(Dispatchers.IO) { writeLine(json.encodeToString(JsonRpcRequest.serializer(), payload)) }
        } catch (e: Exception) {
            pending.remove(id)
            throw PluginException(message = "写入 UDS 失败: ${e.message}", cause = e)
        }

        val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
        if (response == null) {
            pending.remove(id)
            throw PluginException(message = "请求 $method 超时（${REQUEST_TIMEOUT_MS}ms）")
        }
        response.error?.let {
            throw PluginException(rpcCode = it.code, message = "$method 返回错误 [${it.code}] ${it.message}")
        }
        return response
    }

    /** 发送通知（无 id，服务端不应答）。失败仅记日志，不抛出。 */
    fun notify(method: String, params: JsonObject? = null) {
        runCatching {
            val payload = JsonRpcNotification(method = method, params = params)
            writeLine(json.encodeToString(JsonRpcNotification.serializer(), payload))
        }.onFailure {
            FileLogger.w(TAG, "发送通知 $method 失败: ${it.message}")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        FileLogger.i(TAG, "关闭插件 UDS 传输")
        scope.cancel()
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        writer = null
        reader = null
        socket = null
        failAllPending("transport 已关闭")
    }

    private fun ensureConnected() {
        if (closed) throw PluginException(message = "transport 已关闭")
        if (socket == null) throw PluginException(message = "transport 未连接")
    }

    private fun writeLine(line: String) {
        val w = writer ?: throw PluginException(message = "transport 未连接")
        synchronized(writeLock) {
            w.write(line)
            w.write("\n")
            w.flush()
        }
    }

    /** 逐行读 socket，把每条响应按 id 路由到对应 pending；非响应（带 method 或不可解析）忽略。 */
    private fun readLoop() {
        val r = reader ?: return
        try {
            while (true) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue

                val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                if (obj == null) {
                    FileLogger.d(TAG, "跳过非 JSON 行: ${line.take(200)}")
                    continue
                }
                // 带 method 且无 id 的是服务端主动通知，当前协议无此场景，忽略。
                if (obj.containsKey("method") && !obj.containsKey("id")) {
                    FileLogger.d(TAG, "忽略服务端主动消息: ${obj["method"]}")
                    continue
                }
                // 带 method 且有 id 的是 runner 主动发来的请求（插件 client.* API 向宿主要数据），
                // 交给 onRequest 处理并把响应写回；独立协程执行，避免阻塞读循环。
                if (obj.containsKey("method")) {
                    handleIncomingRequest(obj)
                    continue
                }

                val resp = runCatching { json.decodeFromJsonElement(JsonRpcResponse.serializer(), obj) }.getOrNull()
                if (resp?.id == null) {
                    FileLogger.d(TAG, "跳过无 id 响应")
                    continue
                }
                val deferred = pending.remove(resp.id)
                if (deferred == null) {
                    FileLogger.w(TAG, "收到无匹配请求的响应 id=${resp.id}")
                } else {
                    deferred.complete(resp)
                }
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "UDS 读循环异常结束: ${e.message}")
        } finally {
            FileLogger.i(TAG, "UDS 读循环结束（连接可能已断开）")
            failAllPending("UDS 连接已关闭")
        }
    }

    /** 处理 runner 发来的请求：调用 onRequest 拿到响应后写回；无处理器或处理异常时回错误响应。 */
    private fun handleIncomingRequest(request: JsonObject) {
        val id = (request["id"] as? JsonPrimitive)?.longOrNull
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull ?: ""
        // runner 在消息顶层携带 plugin 字段（发起请求的插件名），旧版 runner 缺失时为 null
        val plugin = (request["plugin"] as? JsonPrimitive)?.contentOrNull
        val handler = onRequest
        if (handler == null) {
            FileLogger.w(TAG, "收到 runner 请求 $method 但无处理器")
            replyError(id, -32601, "Method not found: $method")
            return
        }
        scope.launch {
            try {
                FileLogger.d(TAG, "← [$method] id=$id" + if (plugin != null) " plugin=$plugin" else "")
                val resp = handler(request, plugin)
                // handler 返回的响应不含请求 id（业务层不感知 id），补齐后 runner 才能配对 pending 请求
                val reply = JsonRpcResponse(id = id, result = resp.result, error = resp.error)
                FileLogger.d(TAG, "← [$method] id=$id 响应: ${reply.result?.toString()?.take(2000)}")
                writeLine(json.encodeToString(JsonRpcResponse.serializer(), reply))
            } catch (e: Exception) {
                FileLogger.w(TAG, "处理 runner 请求 $method 异常: ${e.message}")
                replyError(id, -32603, "AICODE internal error: ${e.message}")
            }
        }
    }

    private fun replyError(id: Long?, code: Int, message: String) {
        if (id == null) return
        runCatching {
            writeLine(
                json.encodeToString(
                    JsonRpcResponse.serializer(),
                    JsonRpcResponse(id = id, error = JsonRpcError(code = code, message = message))
                )
            )
        }.onFailure { FileLogger.w(TAG, "回写错误响应失败: ${it.message}") }
    }

    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(PluginException(message = reason))
        }
    }
}

/** 插件运行时调用失败时抛出，携带 JSON-RPC 错误码便于上层区分。 */
class PluginException(
    val rpcCode: Int? = null,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** JSON-RPC 错误码常量（对齐 JSON-RPC 2.0 规范）。 */
object PluginJsonRpcError {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INTERNAL_ERROR = -32603
}