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
 * 结果 chat_sessions 缺 workflow 列，升级后一打开会话就崩。
 *
 * 这里在 Room 打开数据库之前检查：如果 64 的记录是本分支的 root_grants，
 * 就把 user_version 退回 63 并删掉那条记录，让 Room 重放 64 → 65 → 66。
 * 66 的 root_grants 是 `CREATE TABLE IF NOT EXISTS`，重放不会破坏已有授权。
 */
object LegacySchemaRepair {

    private const val LEGACY_VERSION = 64
    private const val LEGACY_SCRIPT_MARKER = "root_grants"
    private const val REPLAY_FROM_VERSION = 63

    /**
     * 当前 schema 的 identity hash，必须与 KSP 生成的 `AgentDatabase_Impl` 一致。
     * 改了实体/索引后跟着 SCHEMA_VERSION 一起更新，scripts/check_migrations.py 会比对。
     */
    private const val EXPECTED_IDENTITY_HASH = "b6de7a7bdc104f4630bf2bff245fb610"

    fun repairIfNeeded(context: Context, databaseName: String, schemaVersion: Int) {
        val file = runCatching { context.getDatabasePath(databaseName) }.getOrNull() ?: return
        if (!file.isFile || file.length() == 0L) return

        val repaired = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (db.version != LEGACY_VERSION) return@use false
                if (hasColumn(db, "chat_sessions", "workflow")) return@use false
                if (!legacyRecordIsOurs(db)) return@use false

                db.execSQL("DELETE FROM migration_history WHERE version = ?", arrayOf<Any>(LEGACY_VERSION))
                db.version = REPLAY_FROM_VERSION
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

        repairIdentityHash(file, schemaVersion)
    }

    /**
     * 版本已是最新但 identity hash 与当前包不一致时，直接重写 hash。
     * 用户手工改过库（或装过临时修复包）会留下旧 hash，Room 在 onOpen 会报
     * 「Room cannot verify the data integrity」；这里先确认补列迁移已经落地，
     * 再把 hash 对齐到当前编译期常量。版本没到位时不动，交给正常升级路径。
     */
    private fun repairIdentityHash(file: java.io.File, schemaVersion: Int) {
        val changed = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (db.version != schemaVersion) return@use false
                if (!hasColumn(db, "ai_providers", "useResponseWebSocket")) return@use false
                if (!hasColumn(db, "chat_sessions", "fastMode")) return@use false
                // 除了补列结果，再核对几处标志性结构；缺任何一项都不像“只是 hash 落后”，
                // 宁可继续报 Room 校验失败，也不改写真实不一致的库。
                if (!hasColumn(db, "chat_sessions", "workflow")) return@use false
                if (!hasColumn(db, "chat_sessions", "workspaceId")) return@use false
                if (!hasTable(db, "root_grants")) return@use false
                val current = readIdentityHash(db) ?: return@use false
                if (current == EXPECTED_IDENTITY_HASH) return@use false
                db.execSQL(
                    "UPDATE room_master_table SET identity_hash = ? WHERE id = 42",
                    arrayOf<Any>(EXPECTED_IDENTITY_HASH)
                )
                true
            }
        }.getOrElse {
            FileLogger.w("LegacySchemaRepair", "identity hash 对齐失败: ${it.message}")
            false
        }
        if (changed) FileLogger.i("LegacySchemaRepair", "已把 room_master_table 的 identity hash 对齐到当前版本")
    }

    private fun readIdentityHash(db: SQLiteDatabase): String? = runCatching {
        db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        runCatching {
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return@use false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return@use true
                }
                false
            }
        }.getOrDefault(true)

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean = runCatching {
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name = ?", arrayOf(table)).use { cursor ->
            cursor.moveToFirst()
        }
    }.getOrDefault(false)

    private fun legacyRecordIsOurs(db: SQLiteDatabase): Boolean = runCatching {
        db.rawQuery(
            "SELECT script_name FROM migration_history WHERE version = ?",
            arrayOf(LEGACY_VERSION.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            cursor.getString(0)?.contains(LEGACY_SCRIPT_MARKER) == true
        }
    }.getOrDefault(false)
}