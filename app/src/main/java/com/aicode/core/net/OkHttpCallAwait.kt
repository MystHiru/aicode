package com.aicode.core.net

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Converts an OkHttp call into a cancellable suspension and closes responses cancelled before delivery. */
@OptIn(InternalCoroutinesApi::class)
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: java.io.IOException) {
            val token = continuation.tryResumeWithException(e) ?: return
            continuation.completeResume(token)
        }

        override fun onResponse(call: Call, response: Response) {
            val token = continuation.tryResume(response)
            if (token == null) {
                response.close()
            } else {
                continuation.completeResume(token)
            }
        }
    })
}