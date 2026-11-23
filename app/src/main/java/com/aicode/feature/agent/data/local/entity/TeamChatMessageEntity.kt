package com.aicode.feature.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 团队群聊里的一条消息。
 *
 * 用独立的库（见 [com.aicode.feature.agent.data.local.database.TeamChatDatabase]）而不是并进主库：
 * 群聊只是「团队」功能的附属记录，单独成库可以避免给主库加迁移、也不影响聊天会话表的结构。
 *
 * @param teamName 发言时的团队名称
 * @param speaker 发言人显示名（群主或成员名）
 * @param avatar 发言人头像快照（emoji 或图片路径），发送时定格，之后改头像不影响历史
 * @param role "owner"（用户本人）/ "member"（团队成员）
 * @param imagePaths 随消息附带的图片路径，JSON 数组字符串（空串表示无图）；只存路径不存 base64，
 *   发请求时再读文件编码，避免数据库被图片撑大
 */
@Entity(
    tableName = "team_chat_messages",
    indices = [Index(value = ["teamKey", "workspaceKey"])]
)
data class TeamChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val teamName: String,
    val speaker: String,
    val avatar: String = "",
    val role: String = "member",
    val content: String,
    /** 执行类消息关联的子会话 id：点这条消息能直接跳进执行现场。其余消息为空。 */
    val refSessionId: String = "",
    val imagePaths: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val teamKey: String = "",
    @ColumnInfo(defaultValue = "''") val workspaceKey: String = ""
)
