package com.aicode.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 68 → 69：补齐被跳过的 ADD COLUMN。
 *
 * 本分支曾把本地 `66_add_root_grants.sql` 整体后移到 68，把上游 66（provider websocket）
 * 与 67（session fast mode）填进空出的编号。旧分支的 66 号数据库会跳过新的 66，
 * 因而需要补上 `ai_providers.useResponseWebSocket`；部分 68 号数据库也可能缺少
 * `chat_sessions.fastMode`。Room 在整条迁移链完成后才校验列集合。
 *
 * SQLite 没有 `ADD COLUMN IF NOT EXISTS`，这里按列是否存在条件补列；这两列都是
 * `NOT NULL DEFAULT 0`，对已有行安全。重复执行不会报错。
 */
val MIGRATION_68_69 = object : Migration(68, 69) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasColumn("ai_providers", "useResponseWebSocket")) {
            db.execSQL("ALTER TABLE ai_providers ADD COLUMN useResponseWebSocket INTEGER NOT NULL DEFAULT 0")
        }
        if (!db.hasColumn("chat_sessions", "fastMode")) {
            db.execSQL("ALTER TABLE chat_sessions ADD COLUMN fastMode INTEGER NOT NULL DEFAULT 0")
        }

        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS migration_history (" +
                    "version INTEGER PRIMARY KEY, " +
                    "script_name TEXT, " +
                    "executed_at INTEGER)"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO migration_history (version, script_name, executed_at) VALUES (?, ?, ?)",
                arrayOf<Any>(69, "69_repair_skipped_columns", System.currentTimeMillis())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return@use false
        generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
            .any { it == column }
    }