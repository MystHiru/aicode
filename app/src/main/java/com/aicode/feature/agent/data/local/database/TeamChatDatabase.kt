package com.aicode.feature.agent.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicode.feature.agent.data.local.dao.TeamChatDao
import com.aicode.feature.agent.data.local.entity.TeamChatMessageEntity

/**
 * 团队群聊独立数据库：只有一张消息表，和主聊天库解耦。
 *
 * 独立成库的理由：群聊记录不参与会话/消息/统计的既有查询，单独一个库不需要给主库加迁移，
 * 主库 schema 的版本节奏也不被它拖累。
 */
@Database(
    entities = [TeamChatMessageEntity::class],
    version = 4,
    exportSchema = false
)
abstract class TeamChatDatabase : RoomDatabase() {
    abstract fun teamChatDao(): TeamChatDao

    companion object {
        const val DATABASE_NAME = "team_chat_db"

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 历史记录没有工作区归属，不把它们分配给当前工作区。
                db.execSQL("ALTER TABLE team_chat_messages ADD COLUMN teamKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE team_chat_messages ADD COLUMN workspaceKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("DROP INDEX IF EXISTS index_team_chat_messages_teamName")
                db.execSQL("CREATE INDEX index_team_chat_messages_teamKey_workspaceKey ON team_chat_messages (teamKey, workspaceKey)")
            }
        }

        /** v2 → v3：执行类消息记住对应的子会话，供群里点击跳转。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE team_chat_messages ADD COLUMN refSessionId TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v1 → v2：消息支持附带图片，列里只存路径。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE team_chat_messages ADD COLUMN imagePaths TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
