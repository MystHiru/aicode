package com.aicode.core.net

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test

/** 协程取消必须传导到 OkHttp Call，否则「停止测速」只是让界面不再等，请求仍在跑。 */
class OkHttpCallAwaitTest {

    @Test
    fun cancellingTheCoroutineCancelsTheUnderlyingCall() = runBlocking {
        val call = FakeCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            call.readResponseCancellable { error("不应读到响应") }
        }
        assertTrue(job.isActive && call.isExecuted() && !call.isCanceled())

        job.cancelAndJoin()

        assertTrue(call.isCanceled())
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancellingWhileReadingTheBodyCancelsCallAndClosesResponse() = runBlocking {
        val call = FakeCall()
        val reading = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            call.readResponseCancellable { response -> response.body!!.string() }
        }
        val source = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.countDown()
                check(call.cancelledSignal.await(5, TimeUnit.SECONDS))
                throw IOException("Cancelled")
            }

            override fun close() {
                closed.set(true)
                super.close()
            }
        }.buffer()
        val response = Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun source(): BufferedSource = source
            }).build()
        val networkThread = Thread { call.deliver(response) }.apply { start() }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
        } finally {
            job.cancelAndJoin()
            networkThread.join(6_000)
        }
        assertTrue(call.isCanceled())
        assertTrue(closed.get())
        assertTrue(!networkThread.isAlive)
    }

    private class FakeCall : Call {
        val cancelledSignal = CountDownLatch(1)
        private var callback: Callback? = null

        fun deliver(response: Response) = checkNotNull(callback).onResponse(this, response)

        override fun request(): Request = Request.Builder().url("https://example.com").build()

        override fun execute(): Response = error("not used")

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }

        override fun cancel() {
            cancelledSignal.countDown()
        }

        override fun isCanceled(): Boolean = cancelledSignal.count == 0L

        override fun isExecuted(): Boolean = callback != null

        override fun timeout(): Timeout = Timeout()

        override fun clone(): Call = FakeCall()
    }
}