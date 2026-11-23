package com.aicode.feature.plugin.data.index

import java.io.IOException
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginStoreDownloadTest {
    @Test
    fun acceptsExactSizeAndMatchingDigest() {
        val bytes = "abc".toByteArray()
        val output = bytes.toResponseBody().use { readStoreBody(it, maxBytes = 4, expectedSize = 3) }
        assertArrayEquals(bytes, output)
        verifyPackageHash(output, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD")
    }

    @Test
    fun rejectsHashMismatch() {
        assertThrows(PluginPackageIntegrityException::class.java) {
            verifyPackageHash("abc".toByteArray(), "0".repeat(64))
        }
    }

    @Test
    fun rejectsIncorrectDeclaredSize() {
        assertThrows(PluginPackageIntegrityException::class.java) {
            "abc".toResponseBody().use { readStoreBody(it, maxBytes = 4, expectedSize = 2) }
        }
    }

    @Test
    fun enforcesLimitsEvenWithoutContentLength() {
        val bytes = "abc".toByteArray()
        assertArrayEquals(bytes, unknownLengthBody().use { readStoreBody(it, maxBytes = 3, expectedSize = null) })
        listOf(2L to null, 4L to 2L, 4L to 4L).forEach { (limit, size) ->
            assertThrows(IOException::class.java) {
                unknownLengthBody().use { readStoreBody(it, maxBytes = limit, expectedSize = size) }
            }
        }
    }

    @Test
    fun requiresHttpsWithoutUrlCredentialsOrFragment() {
        assertTrue(isSecureStoreUrl("https://example.com/package.zip"))
        assertFalse(isSecureStoreUrl("http://example.com/package.zip"))
        assertFalse(isSecureStoreUrl("https://user@example.com/package.zip"))
        assertFalse(isSecureStoreUrl("https://example.com/package.zip#fragment"))
    }

    private fun unknownLengthBody() = object : ResponseBody() {
        private val buffer = Buffer().writeUtf8("abc")
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source() = buffer
    }
}
