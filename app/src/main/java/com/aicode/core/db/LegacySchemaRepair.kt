package com.aicode.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.aicode.core.util.FileLogger

/**
 * 旧版本数据库的一次性修补。
 *
 * 本分支曾把 `root_grants` 建在 64 号迁移上，后来上游的 64 号用于
 * `session_workflow`、65 号用于 `session_workspace_identity`。已装过旧包的库
 * user_version 停在 64、migration_history 里也记着 64，Room 会直接跳过上游的 64，
 * 结果 `chat_sessions` 缺少 `workflowId`、`workflowState`。
 *
 * 仅当数据库确实停在旧分支的 64 号、且两列都未建立时，才回到 63 让 Room
 * 重新执行迁移。后续的 `root_grants` 建表使用 `IF NOT EXISTS`，保留已有授权。
 */
object LegacySchemaRepair {

    private const val LEGACY_VERSION = 64
    private const val LEGACY_SCRIPT_NAME = "64_add_root_grants.sql"
    private const val REPLAY_FROM_VERSION = 63

    fun repairIfNeeded(context: Context, databaseName: String) {
        val file = runCatching { context.getDatabasePath(databaseName) }.getOrNull() ?: return
        if (!file.isFile || file.length() == 0L) return

        val repaired = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (db.version != LEGACY_VERSION) return@use false
                if (!legacyRecordIsOurs(db)) return@use false
                if (hasWorkflowColumn(db)) return@use false

                db.beginTransaction()
                try {
                    db.execSQL("DELETE FROM migration_history WHERE version = ?", arrayOf<Any>(LEGACY_VERSION))
                    db.version = REPLAY_FROM_VERSION
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                true
            }
        }.getOrElse {
            // 修补失败不阻断启动：Room 自己的校验会给出更明确的错误。
            FileLogger.w("LegacySchemaRepair", "旧库修补失败，交由 Room 校验: ${it.message}")
            false
        }

        if (repaired) {
            FileLogger.i("LegacySchemaRepair", "检测到旧版 64 号迁移，已回退到 63 以便重放上游迁移")
        }
    }

    private fun hasWorkflowColumn(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA table_info(chat_sessions)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                val column = cursor.getString(nameIndex)
                if (column == "workflowId" || column == "workflowState") return@use true
            }
            false
        }

    private fun legacyRecordIsOurs(db: SQLiteDatabase): Boolean = runCatching {
        db.rawQuery(
            "SELECT script_name FROM migration_history WHERE version = ?",
            arrayOf(LEGACY_VERSION.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            cursor.getString(0) == LEGACY_SCRIPT_NAME
        }
    }.getOrDefault(false)
}