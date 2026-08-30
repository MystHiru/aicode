package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject as KxJsonObject
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAdapterTest {

    /** 捕获上送的请求体，并返回可配置的响应 JSON。 */
    private class FakeApi(var responseJson: String) : GeminiApi {
        var lastRequest: Map<*, *>? = null

        override suspend fun generateContent(
            url: String,
            apiKey: String,
            extraHeaders: Map<String, String>,
            request: Any
        ): JsonObject {
            lastRequest = request as Map<*, *>
            return JsonParser.parseString(responseJson).asJsonObject
        }

        override suspend fun streamGenerateContent(
            url: String,
            apiKey: String,
            extraHeaders: Map<String, String>,
            request: Any
        ): ResponseBody = throw UnsupportedOperationException("本测试只覆盖非流式路径")
    }

    private fun textResponse(
        finishReason: String = "STOP",
        candidateTokens: Int = 5,
        thoughtTokens: Int = 0
    ) = """
        {
          "candidates": [{
            "finishReason": "$finishReason",
            "content": {"role": "model", "parts": [{"text": "ok"}]}
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": $candidateTokens,
            "thoughtsTokenCount": $thoughtTokens
          }
        }
    """.trimIndent()

    private fun adapter(api: FakeApi, maxOutput: Int? = null): GeminiAdapter =
        GeminiAdapter(api).apply {
            apiKey = "k"
            maxOutputTokens = maxOutput
        }

    private fun user(text: String) = AgentMessage.UserMessage(content = text)

    @Suppress("UNCHECKED_CAST")
    private fun contents(request: Map<*, *>?): List<Map<*, *>> =
        request?.get("contents") as? List<Map<*, *>> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun generationConfig(request: Map<*, *>?): Map<*, *> =
        request?.get("generationConfig") as? Map<*, *> ?: emptyMap<Any, Any>()

    @Test
    fun max_output_tokens_and_thinking_config_coexist_in_generation_config() = runBlocking {
        val api = FakeApi(textResponse())
        // 两者曾各自覆盖式写 generationConfig，只会剩下后写的那个。
        adapter(api, maxOutput = 64000).complete("sys", listOf(user("hi")), reasoningEffort = "high")

        val config = generationConfig(api.lastRequest)
        assertEquals(64000, config["maxOutputTokens"])
        assertEquals(mapOf("thinkingBudget" to 8192), config["thinkingConfig"])
    }

    @Test
    fun max_output_tokens_absent_when_metadata_missing() = runBlocking {
        val api = FakeApi(textResponse())
        adapter(api, maxOutput = null).complete("sys", listOf(user("hi")))

        assertFalse(generationConfig(api.lastRequest).containsKey("maxOutputTokens"))
    }

    @Test
    fun output_tokens_include_thought_tokens() = runBlocking {
        val api = FakeApi(textResponse(candidateTokens = 20, thoughtTokens = 30))
        val result = adapter(api).complete("sys", listOf(user("hi")))

        // 思考按输出价计费，只取 candidatesTokenCount 会少算。
        assertEquals(50, result.outputTokens)
        assertEquals(10, result.inputTokens)
    }

    @Test
    fun max_tokens_finish_reason_marks_response_truncated() = runBlocking {
        val api = FakeApi(textResponse(finishReason = "MAX_TOKENS"))
        val result = adapter(api).complete("sys", listOf(user("hi")))

        assertTrue(result.isTruncated)
        assertFalse(result.isAborted)
    }

    @Test
    fun safety_finish_reason_marks_response_aborted() = runBlocking {
        val api = FakeApi(textResponse(finishReason = "SAFETY"))
        val result = adapter(api).complete("sys", listOf(user("hi")))

        assertTrue(result.isAborted)
    }

    @Test
    fun function_call_id_is_used_as_tool_call_id() = runBlocking {
        val api = FakeApi(
            """
            {
              "candidates": [{
                "finishReason": "STOP",
                "content": {"role": "model", "parts": [
                  {"functionCall": {"id": "call-1", "name": "readFile", "args": {"path": "a.txt"}}},
                  {"functionCall": {"id": "call-2", "name": "readFile", "args": {"path": "b.txt"}}}
                ]}
              }]
            }
            """.trimIndent()
        )
        val result = adapter(api).complete("sys", listOf(user("hi")))

        // 都用函数名当 id 时，同名工具的并行调用会互相覆盖结果。
        assertEquals(listOf("call-1", "call-2"), result.toolCalls.map { it.id })
        assertEquals(listOf("readFile", "readFile"), result.toolCalls.map { it.name })
    }

    @Test
    fun function_call_id_falls_back_to_name() = runBlocking {
        val api = FakeApi(
            """
            {
              "candidates": [{
                "finishReason": "STOP",
                "content": {"role": "model", "parts": [
                  {"functionCall": {"name": "readFile", "args": {}}}
                ]}
              }]
            }
            """.trimIndent()
        )
        val result = adapter(api).complete("sys", listOf(user("hi")))

        assertEquals(listOf("readFile"), result.toolCalls.map { it.id })
    }

    @Test
    fun parts_with_signature_are_snapshotted_and_plain_text_is_not() = runBlocking {
        val withSignature = FakeApi(
            """
            {
              "candidates": [{
                "finishReason": "STOP",
                "content": {"role": "model", "parts": [
                  {"text": "答案", "thoughtSignature": "sig-a"}
                ]}
              }]
            }
            """.trimIndent()
        )
        val signed = adapter(withSignature).complete("sys", listOf(user("hi")))
        assertTrue(signed.thinkingBlocksJson!!.contains("sig-a"))

        val plain = FakeApi(textResponse())
        val unsigned = adapter(plain).complete("sys", listOf(user("hi")))
        // 纯文本轮不需要原样回传，存快照只会让历史正文双写。
        assertNull(unsigned.thinkingBlocksJson)
    }

    @Test
    fun assistant_history_replays_parts_snapshot_verbatim() = runBlocking {
        val api = FakeApi(textResponse())
        val snapshot = """[{"text":"上轮答案","thoughtSignature":"sig-a"},""" +
            """{"functionCall":{"id":"call-1","name":"readFile","args":{"path":"a.txt"}}}]"""
        adapter(api).complete(
            "sys",
            listOf(
                user("hi"),
                AgentMessage.AssistantMessage(
                    content = "上轮答案",
                    toolCalls = listOf(ToolCall(id = "call-1", name = "readFile", arguments = KxJsonObject(emptyMap()))),
                    thinkingBlocksJson = snapshot
                ),
                AgentMessage.ToolResultMessage(id = "call-1", toolName = "readFile", result = "body")
            )
        )

        val model = contents(api.lastRequest).first { it["role"] == "model" }
        val parts = model["parts"] as com.google.gson.JsonArray
        assertEquals("sig-a", parts[0].asJsonObject.get("thoughtSignature").asString)
        assertEquals("call-1", parts[1].asJsonObject.getAsJsonObject("functionCall").get("id").asString)
    }

    @Test
    fun assistant_history_rebuilds_parts_without_snapshot() = runBlocking {
        val api = FakeApi(textResponse())
        adapter(api).complete(
            "sys",
            listOf(
                user("hi"),
                AgentMessage.AssistantMessage(content = "上轮答案")
            )
        )

        val model = contents(api.lastRequest).first { it["role"] == "model" }
        @Suppress("UNCHECKED_CAST")
        val parts = model["parts"] as List<Map<*, *>>
        assertEquals("上轮答案", parts.single()["text"])
    }

    @Test
    fun function_response_carries_name_and_call_id() = runBlocking {
        val api = FakeApi(textResponse())
        adapter(api).complete(
            "sys",
            listOf(
                user("hi"),
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(ToolCall(id = "call-1", name = "readFile", arguments = KxJsonObject(emptyMap())))
                ),
                AgentMessage.ToolResultMessage(id = "call-1", toolName = "readFile", result = "body")
            )
        )

        val toolTurn = contents(api.lastRequest).last()
        @Suppress("UNCHECKED_CAST")
        val parts = toolTurn["parts"] as List<Map<*, *>>
        val functionResponse = parts.single()["functionResponse"] as Map<*, *>
        assertEquals("readFile", functionResponse["name"])
        assertEquals("call-1", functionResponse["id"])
    }

    @Test
    fun legacy_function_response_omits_id_when_it_equals_name() = runBlocking {
        val api = FakeApi(textResponse())
        adapter(api).complete(
            "sys",
            listOf(
                user("hi"),
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(ToolCall(id = "readFile", name = "readFile", arguments = KxJsonObject(emptyMap())))
                ),
                // 旧数据里 tool 结果的 id 就是函数名，此时不该再发 id。
                AgentMessage.ToolResultMessage(id = "readFile", toolName = "readFile", result = "body")
            )
        )

        val toolTurn = contents(api.lastRequest).last()
        @Suppress("UNCHECKED_CAST")
        val parts = toolTurn["parts"] as List<Map<*, *>>
        val functionResponse = parts.single()["functionResponse"] as Map<*, *>
        assertFalse(functionResponse.containsKey("id"))
    }
}
