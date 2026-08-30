package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.ResponsesItem
import com.aicode.feature.agent.data.remote.openai.ResponsesPart
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.google.gson.Gson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses API 请求体构造（[buildResponsesTools] / [buildResponsesInput]）。
 * 覆盖点即两类曾导致 400 的写法：工具定义嵌套 `function`、工具往返仍用 Chat Completions 的
 * assistant.tool_calls + role:"tool" 消息。
 */
class ResponsesPayloadTest {

    private class FakeTool(
        override val name: String,
        override val description: String = "desc",
        override val parameters: Map<String, ToolParameter> = emptyMap()
    ) : AgentTool() {
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Success(JsonPrimitive("ok"))
    }

    private fun call(id: String, name: String, path: String) =
        ToolCall(id = id, name = name, arguments = mapOf("path" to JsonPrimitive(path)))

    @Suppress("UNCHECKED_CAST")
    private fun parts(value: Any?): List<Map<String, Any>> = value as List<Map<String, Any>>

    @Test
    fun tool_definition_is_flat_without_function_wrapper() {
        val defs = buildResponsesTools(listOf(FakeTool("readFile")))
        assertEquals(1, defs?.size)
        val json = Gson().toJsonTree(defs!!.first()).asJsonObject
        assertEquals("function", json.get("type").asString)
        assertEquals("readFile", json.get("name").asString)
        assertTrue(json.has("description"))
        assertTrue(json.has("parameters"))
        // strict 在官方 schema 里不带 optional 标记，必须出现；工具 schema 不保证严格子集，固定 false
        assertFalse(json.get("strict").asBoolean)
        // 嵌套写法会被服务端以「tools[0]: missing field `name`」拒绝
        assertFalse(json.has("function"))
    }

    @Test
    fun no_tools_means_no_tools_field() {
        assertNull(buildResponsesTools(emptyList()))
    }

    @Test
    fun blank_system_prompt_adds_no_item() {
        val items = buildResponsesInput("", "system", emptyList())
        assertTrue(items.isEmpty())
    }

    @Test
    fun tool_round_trip_becomes_function_call_and_output_items() {
        val items = buildResponsesInput(
            systemPrompt = "you are helpful",
            systemRole = "system",
            messages = listOf(
                AgentMessage.UserMessage(id = "u1", content = "读一下 a.txt"),
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "好的",
                    toolCalls = listOf(call("call_1", "readFile", "a.txt"))
                ),
                AgentMessage.ToolResultMessage(id = "call_1", toolName = "readFile", result = "file body")
            )
        )

        assertEquals(5, items.size)
        assertEquals("system", items[0]["role"])
        assertEquals("you are helpful", items[0]["content"])
        assertEquals("user", items[1]["role"])
        assertEquals("读一下 a.txt", items[1]["content"])

        assertEquals("assistant", items[2]["role"])
        val assistantParts = parts(items[2]["content"])
        assertEquals(ResponsesPart.OUTPUT_TEXT, assistantParts[0]["type"])
        assertEquals("好的", assistantParts[0]["text"])

        assertEquals(ResponsesItem.FUNCTION_CALL, items[3]["type"])
        assertEquals("call_1", items[3]["call_id"])
        assertEquals("readFile", items[3]["name"])
        assertEquals("""{"path":"a.txt"}""", items[3]["arguments"])

        assertEquals(ResponsesItem.FUNCTION_CALL_OUTPUT, items[4]["type"])
        assertEquals("call_1", items[4]["call_id"])
        assertEquals("file body", items[4]["output"])
    }

    @Test
    fun assistant_without_text_emits_only_function_call() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(id = "a1", content = "", toolCalls = listOf(call("c1", "listFiles", "."))),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "listFiles", result = "a.txt")
            )
        )
        assertEquals(2, items.size)
        assertEquals(ResponsesItem.FUNCTION_CALL, items[0]["type"])
        assertEquals(ResponsesItem.FUNCTION_CALL_OUTPUT, items[1]["type"])
    }

    @Test
    fun orphan_tool_result_is_dropped() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.ToolResultMessage(id = "ghost", toolName = "readFile", result = "body"),
                AgentMessage.UserMessage(id = "u1", content = "hi")
            )
        )
        assertEquals(1, items.size)
        assertEquals("user", items[0]["role"])
    }

    @Test
    fun call_without_result_is_dropped_together_with_the_call() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "要执行两个工具",
                    toolCalls = listOf(call("c1", "readFile", "a.txt"), call("c2", "readFile", "b.txt"))
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "body a")
            )
        )
        // assistant 文本 + c1 的调用与结果；被用户拒绝执行的 c2 不出现，避免上游报缺少工具响应
        assertEquals(3, items.size)
        assertEquals("c1", items[1]["call_id"])
        assertEquals("c1", items[2]["call_id"])
        assertFalse(items.any { it["call_id"] == "c2" })
    }

    @Test
    fun out_of_order_results_are_paired_by_call_id() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "",
                    toolCalls = listOf(call("c1", "readFile", "a.txt"), call("c2", "readFile", "b.txt"))
                ),
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "body b"),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "body a")
            )
        )
        assertEquals(4, items.size)
        assertEquals(listOf("c1", "c1", "c2", "c2"), items.map { it["call_id"] })
        assertEquals("body a", items[1]["output"])
        assertEquals("body b", items[3]["output"])
    }

    @Test
    fun user_images_become_input_image_parts() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.UserMessage(
                    id = "u1",
                    content = "这是什么",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "AAAA"))
                )
            )
        )
        val content = parts(items[0]["content"])
        assertEquals(ResponsesPart.INPUT_TEXT, content[0]["type"])
        assertEquals(ResponsesPart.INPUT_IMAGE, content[1]["type"])
        assertEquals("data:image/png;base64,AAAA", content[1]["image_url"])
    }

    @Test
    fun tool_result_images_become_input_image_parts_in_output() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(id = "a1", content = "", toolCalls = listOf(call("c1", "viewImage", "a.png"))),
                AgentMessage.ToolResultMessage(
                    id = "c1",
                    toolName = "viewImage",
                    result = "截图如下",
                    images = listOf(AgentImage(mimeType = "image/jpeg", base64Data = "BBBB"))
                )
            )
        )
        val output = parts(items[1]["output"])
        assertEquals(ResponsesPart.INPUT_TEXT, output[0]["type"])
        assertEquals(ResponsesPart.INPUT_IMAGE, output[1]["type"])
        assertEquals("data:image/jpeg;base64,BBBB", output[1]["image_url"])
    }

    @Test
    fun developer_role_is_used_for_reasoning_models() {
        val items = buildResponsesInput("prompt", "developer", emptyList())
        assertEquals("developer", items[0]["role"])
    }
}
