package com.aicode.core.net

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertTrue
import org.junit.Test

/** 协程取消必须传导到 OkHttp Call，否则「停止测速」只是让界面不再等，请求仍在跑。 */
class OkHttpCallAwaitTest {

    @Test
    fun cancellingTheCoroutineCancelsTheUnderlyingCall() = runBlocking {
        val call = FakeCall()
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            call.awaitResponse()
        }
        yield()
        assertTrue("请求应已发起且仍在等响应", job.isActive && call.isExecuted() && !call.isCanceled())

        job.cancel()
        job.join()

        assertTrue("协程取消应触发 Call.cancel()", call.awaitCancelInvoked.get())
        assertTrue("协程应已结束", job.isCancelled)
    }

    private class FakeCall : Call {
        val awaitCancelInvoked = AtomicBoolean(false)
        private var callback: Callback? = null
        private var canceled = false

        override fun request(): Request = Request.Builder().url("https://example.com").build()

        override fun execute(): Response = error("not used")

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }

        override fun cancel() {
            canceled = true
            awaitCancelInvoked.set(true)
        }

        override fun isCanceled(): Boolean = canceled

        override fun isExecuted(): Boolean = callback != null

        override fun timeout(): Timeout = Timeout()

        override fun clone(): Call = FakeCall()
    }
}