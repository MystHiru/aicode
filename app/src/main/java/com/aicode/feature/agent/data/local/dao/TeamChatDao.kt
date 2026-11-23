package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.TeamChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TeamChatDao {
    @Query("SELECT * FROM team_chat_messages WHERE teamKey = :team AND workspaceKey = :workspace ORDER BY id ASC")
    fun observeMessages(team: String, workspace: String): Flow<List<TeamChatMessageEntity>>

    @Query("SELECT * FROM team_chat_messages WHERE teamKey = :team AND workspaceKey = :workspace ORDER BY id DESC LIMIT :limit")
    suspend fun recentMessages(team: String, workspace: String, limit: Int): List<TeamChatMessageEntity>

    @Insert
    suspend fun insert(message: TeamChatMessageEntity): Long

    @Query("DELETE FROM team_chat_messages WHERE teamKey = :team AND workspaceKey = :workspace")
    suspend fun clear(team: String, workspace: String)

    @Query("DELETE FROM team_chat_messages WHERE teamKey = :team AND workspaceKey != ''")
    suspend fun clearTeam(team: String)

    @Query(
        "DELETE FROM team_chat_messages WHERE teamKey = :team AND workspaceKey = :workspace AND id NOT IN " +
            "(SELECT id FROM team_chat_messages WHERE teamKey = :team AND workspaceKey = :workspace ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun trim(team: String, workspace: String, keep: Int)
}
