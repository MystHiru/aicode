package com.aicode.core.net

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

// 读完响应体后才恢复协程，保证等待响应头和读取响应体时都可以取消 Call。
internal suspend fun <T> Call.readResponseCancellable(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resumeWith(runCatching { response.use(read) })
            }
        })
    }