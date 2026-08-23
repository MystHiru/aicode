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

        /** 单次请求默认等待响应的上限：插件 hook 可能执行异步逻辑（如网络调用），给足时间；tool.call 等长任务可用 [request] 的 timeoutMs 覆盖。 */
        const val REQUEST_TIMEOUT_MS = 30_000L

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 独立 scope：处理 runner → Kotlin 反向请求（client.* API）。
     * 与 [scope] 分离，避免 [close] 时取消正在处理的 in-flight 请求导致响应写不出去、runner 端永久挂起。
     * [close] 流程：先等 [requestScope] 所有子任务完成，再 cancel [scope]。
     */
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    /**
     * 向 runner 发送请求并等待响应。[timeoutMs] 为 null 时无限等待（对齐 opencode：插件工具执行不受框架超时限制），
     * 连接断开/进程退出由 failAllPending 兜底，协程取消照常传播。
     */
    suspend fun request(method: String, params: JsonObject? = null, timeoutMs: Long? = REQUEST_TIMEOUT_MS): JsonRpcResponse {
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

        val response = if (timeoutMs != null) {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } else {
            deferred.await()
        }
        if (response == null) {
            pending.remove(id)
            throw PluginException(message = "请求 $method 超时（${timeoutMs}ms）")
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
        // 先关 socket（读循环会自然退出），再等 in-flight 请求处理完成，最后 cancel scope。
        // 顺序保证：读循环不再产生新请求；in-flight 请求能写完响应；scope.cancel 兜底未完成的。
        // readLoop 阻塞在 reader.readLine() 持有 BufferedReader 内部锁。LocalSocket close 不会给
        // 阻塞中的 read 传递 EOS（与 TCP socket 不同，AOSP 只关 fd），必须先 shutdownInput()
        // （底层 Os.shutdown(fd, SHUT_RD)）让 readLine 立即收到 EOF 返回、释放锁，
        // 否则 reader.close() 会一直等锁永久卡死。
        runCatching { socket?.shutdownInput() }
        runCatching { writer?.close() }
        FileLogger.d(TAG, "close: writer 已关闭")
        runCatching { socket?.close() }
        FileLogger.d(TAG, "close: socket 已关闭")
        runCatching { reader?.close() }
        FileLogger.d(TAG, "close: reader 已关闭")
        writer = null
        reader = null
        socket = null
        // 等 in-flight 请求处理完成（最多 5s，超时强制 cancel）
        val requestJob = requestScope.coroutineContext[kotlinx.coroutines.Job]
        if (requestJob != null) {
            val activeChildren = requestJob.children.count { it.isActive }
            val t0 = System.currentTimeMillis()
            FileLogger.d(TAG, "等待 in-flight 请求完成（活跃子协程 $activeChildren 个）")
            runCatching {
                kotlinx.coroutines.runBlocking {
                    withTimeoutOrNull(5_000L) { requestJob.children.forEach { it.join() } }
                }
            }
            FileLogger.d(TAG, "in-flight 等待结束，耗时 ${System.currentTimeMillis() - t0}ms")
        }
        requestScope.cancel()
        scope.cancel()
        failAllPending("transport 已关闭")
        FileLogger.d(TAG, "UDS 传输关闭完成")
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
        requestScope.launch {
            try {
                FileLogger.d(TAG, "← [$method] id=$id" + if (plugin != null) " plugin=$plugin" else "")
                val resp = handler(request, plugin)
                // handler 返回的响应不含请求 id（业务层不感知 id），补齐后 runner 才能配对 pending 请求
                val reply = JsonRpcResponse(id = id, result = resp.result, error = resp.error)
                FileLogger.d(TAG, "← [$method] id=$id 响应: ${reply.result?.toString()?.take(2000)}")
                writeLine(json.encodeToString(JsonRpcResponse.serializer(), reply))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // close() 等待超时后会 cancel requestScope，这里吞掉 CancellationException，
                // 避免污染读循环（外层 readLoop 不应因单个请求处理失败而退出）。
                FileLogger.w(TAG, "处理 runner 请求 $method 被取消（transport 关闭中）")
                throw e
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