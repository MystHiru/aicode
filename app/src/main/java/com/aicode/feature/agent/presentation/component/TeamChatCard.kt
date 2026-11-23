package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.MemberAvatar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 群聊里的一条发言。 */
internal data class ParsedTeamChatTurn(
    val round: Int,
    val speaker: String,
    val avatar: String,
    val content: String
)

/** 从 `task(action="team_chat")` 的参数与结果里解析出的群聊记录。 */
internal data class ParsedTeamChat(val team: String, val turns: List<ParsedTeamChatTurn>, val warnings: List<String>)

private val teamChatJson = Json { ignoreUnknownKeys = true }

/**
 * 解析 `task(action="team_chat")`：参数给出团队名，结果给出逐条发言。
 * 解析不出任何发言（如运行中、失败）时返回 null，只显示普通工具行。
 */
internal fun parseTeamChat(argsJson: String?, content: String?): ParsedTeamChat? {
    val args = argsJson?.let { runCatching { teamChatJson.parseToJsonElement(it).jsonObject }.getOrNull() }
    val action = (args?.get("action") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "create"
    if (action != "team_chat") return null

    val result = content?.let { runCatching { teamChatJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
    if ((result["status"] as? JsonPrimitive)?.contentOrNull == "error") return null
    val data = when (val element = result["data"]) {
        is JsonObject -> element
        is JsonPrimitive -> runCatching { teamChatJson.parseToJsonElement(element.content).jsonObject }.getOrNull()
        else -> null
    } ?: return null

    val turns = (data["turns"] as? JsonArray)?.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val speaker = (obj["speaker"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ParsedTeamChatTurn(
            round = (obj["round"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1,
            speaker = speaker,
            avatar = (obj["avatar"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            content = (obj["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        )
    }.orEmpty()

    if (turns.isEmpty()) return null
    val team = (data["team"] as? JsonPrimitive)?.contentOrNull
        ?: (args?.get("team") as? JsonPrimitive)?.contentOrNull.orEmpty()
    val warnings = (data["warnings"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    return ParsedTeamChat(team = team, turns = turns, warnings = warnings)
}

/** 发言者色标：按出现顺序取色，同一成员在同一张卡里颜色固定。 */
private val speakerPalette = listOf(
    Color(0xFF3BA7FF),
    Color(0xFF8B5CF6),
    Color(0xFF10B981),
    Color(0xFFF59E0B),
    Color(0xFFEC4899),
    Color(0xFF14B8A6)
)

/**
 * 团队群聊卡片：一张卡里按轮次铺开成员发言，像群聊记录一样从上往下读。
 *
 * 与 [SubAgentDispatchCard]（并行派发）的区别：这里的发言是有先后的，后发言的人看得到前面说过什么，
 * 所以按「轮次 → 发言顺序」线性排列，而不是并排的独立任务项。
 */
@Composable
internal fun TeamChatCard(chat: ParsedTeamChat) {
    val speakers = chat.turns.map { it.speaker }.distinct()
    val rounds = chat.turns.maxOfOrNull { it.round } ?: 1

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.cardSurface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "团队群聊",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (chat.team.isNotBlank()) {
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = "· ${chat.team}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "· $rounds 轮 · ${chat.turns.size} 条发言",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            var lastRound = -1
            chat.turns.forEach { turn ->
                if (turn.round != lastRound) {
                    lastRound = turn.round
                    Text(
                        text = "第 ${turn.round} 轮",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
                val color = speakerPalette[speakers.indexOf(turn.speaker).coerceAtLeast(0) % speakerPalette.size]
                Row(modifier = Modifier.fillMaxWidth()) {
                    MemberAvatar(avatar = turn.avatar, name = turn.speaker, tint = color, size = 26.dp)
                    Spacer(Modifier.width(Spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = turn.speaker,
                            style = MaterialTheme.typography.labelLarge,
                            color = color
                        )
                        Text(
                            text = turn.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            chat.warnings.forEach { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.warning
                )
            }
        }
    }
}
