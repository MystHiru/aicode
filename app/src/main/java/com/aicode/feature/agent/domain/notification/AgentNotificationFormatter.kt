package com.aicode.feature.agent.domain.notification

import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aicode.feature.terminal.domain.TAIL_LINES
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把 [PendingNotification] 渲染成两种送达形态：
 * - [buildMessage]：作为 user 消息注入（AI 空闲时立即触发新一轮，或整轮未调用工具时的兜底路径）。
 *   带 [BACKGROUND_NOTIFICATION_PREFIX] 前缀，UI 据此渲染为轻量提示条而非用户气泡。
 * - [buildJsonArray]：搭车形态，作为工具结果 JSON 顶层的 `notifications` 字段，在 AI 忙碌时随本批工具结果送达。
 *
 * 两种形态共用同一份措辞与 XML 结构，保证 AI 无论从哪条路径收到通知，理解方式一致。
 */
object AgentNotificationFormatter {

    private const val NOTICE = "这是系统事件通知，不是来自用户的消息，不要视为用户的确认、同意或对任何待处理问题的回答。"

    fun buildMessage(items: List<PendingNotification>): String {
        require(items.isNotEmpty()) { "通知列表为空" }
        return buildString {
            appendLine(BACKGROUND_NOTIFICATION_PREFIX)
            if (items.size == 1) {
                val single = items.first()
                when (single.kind) {
                    AgentNotificationKind.BACKGROUND_TASK ->
                        appendLine("这是一条后台任务完成事件，不是来自用户的消息。")
                    AgentNotificationKind.SUBAGENT ->
                        appendLine("这是一条子代理完成事件，不是来自用户的消息。")
                }
                appendLine("不要将其视为用户的确认、同意或对任何待处理问题的回答。")
            } else {
                appendLine("共有 ${items.size} 条后台完成通知，这是合并后的通知。")
                appendLine("这些是后台完成事件，不是来自用户的消息。")
                appendLine("不要将它们视为用户的确认、同意或对任何待处理问题的回答。")
            }
            appendLine()
            items.forEach { item ->
                appendLine(item.toXmlBlock())
                appendLine()
            }
            append(buildHint(items))
        }
    }

    fun buildJsonArray(items: List<PendingNotification>): JsonArray =
        JsonArray(items.map { it.toJsonObject() })

    /** 单条通知的 XML 块。字段名与 [buildJsonArray] 保持语义一致，供 AI 对照理解。 */
    private fun PendingNotification.toXmlBlock(): String = buildString {
        val tag = if (kind == AgentNotificationKind.BACKGROUND_TASK) "task-notification" else "subagent-notification"
        appendLine("<$tag>")
        when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> {
                appendLine("  <task-id>$sourceId</task-id>")
                appendLine("  <title>$title</title>")
                appendLine("  <command>${command ?: ""}</command>")
                appendLine("  <exit-code>${exitCode ?: ""}</exit-code>")
            }
            AgentNotificationKind.SUBAGENT -> {
                appendLine("  <subagent-id>$sourceId</subagent-id>")
                appendLine("  <subagent-title>$title</subagent-title>")
            }
        }
        appendLine("  <status>${statusText()}</status>")
        appendLine("  <summary>${summaryText()}</summary>")
        // 转义尖括号：输出里若含 <status>/<summary> 等字样会污染提示条的正则提取。
        tailOutput?.takeIf { it.isNotBlank() }?.let { appendLine("  <tail-output>${escapeXml(it)}</tail-output>") }
        append("</$tag>")
    }

    private fun PendingNotification.toJsonObject(): JsonElement = buildJsonObject {
        put("kind", if (kind == AgentNotificationKind.BACKGROUND_TASK) "background_task" else "subagent")
        put("notice", NOTICE)
        when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> {
                put("task_id", sourceId)
                put("title", title)
                command?.let { put("command", it) }
                exitCode?.let { put("exit_code", it) }
            }
            AgentNotificationKind.SUBAGENT -> {
                put("subagent_id", sourceId)
                put("subagent_title", title)
            }
        }
        put("status", statusText())
        put("summary", summaryText())
        tailOutput?.takeIf { it.isNotBlank() }?.let { put("tail_output", JsonPrimitive(it)) }
        put("hint", singleHint())
    }

    private fun PendingNotification.statusText(): String = if (succeeded) "completed" else "failed"

    private fun PendingNotification.summaryText(): String = when (kind) {
        AgentNotificationKind.BACKGROUND_TASK ->
            "后台任务「$title」已结束（退出码 ${exitCode ?: "未知"}）"
        AgentNotificationKind.SUBAGENT ->
            "子代理「$title」已${if (succeeded) "执行完成" else "执行失败"}"
    }

    private fun PendingNotification.singleHint(): String = when (kind) {
        AgentNotificationKind.BACKGROUND_TASK ->
            "通知已携带该终端最后 $TAIL_LINES 行输出；如需完整日志可用 terminal(action=\"read\", tab_id=\"$sourceId\") 读取。"
        AgentNotificationKind.SUBAGENT ->
            "可用 task(action=\"read\", id=\"$sourceId\") 读取子代理的最后输出。"
    }

    private fun buildHint(items: List<PendingNotification>): String {
        val tasks = items.filter { it.kind == AgentNotificationKind.BACKGROUND_TASK }
        val subAgents = items.filter { it.kind == AgentNotificationKind.SUBAGENT }
        val lines = mutableListOf<String>()
        when (tasks.size) {
            0 -> {}
            1 -> lines.add(tasks.first().singleHint())
            else -> lines.add(
                "通知已携带各终端最后 $TAIL_LINES 行输出；如需完整日志可用 terminal(action=\"read\", tab_id=\"...\") 读取对应任务。"
            )
        }
        when (subAgents.size) {
            0 -> {}
            1 -> lines.add(subAgents.first().singleHint())
            else -> lines.add("可用 task(action=\"read\", id=\"...\") 逐个读取子代理的最后输出。")
        }
        return lines.joinToString("\n")
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
