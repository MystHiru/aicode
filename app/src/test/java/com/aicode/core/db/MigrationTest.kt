package com.aicode.core.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicode.feature.agent.data.local.database.AgentDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Room 官方迁移测试（Robolectric，JVM 上跑真实的 Android SQLite）。
 *
 * 本容器（Android 宿主上的 PRoot）JVM 加载不了 Robolectric 的 conscrypt native 库
 * （UnsatisfiedLinkError，java.library.path 指向 /data/app、/system 等 Android 路径），
 * 故用 [guardEnvironment] 在非标准 Linux 环境跳过——跳过不算失败；
 * CI（ubuntu x86_64）与普通开发机上真实执行。
 *
 * 历史版本（8~49）的 schema json 当年未导出，[MigrationTestHelper] 只能覆盖
 * 「当前版本及以后」的路径；历史路径的连续/无重复由 [MigrationFilesParseTest] 与
 * scripts/check_migrations.py 双向兜底。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric 仅支持标准 Linux/CI 环境（当前为 Android PRoot 容器，会 UnsatisfiedLinkError）", !androidContainer)
        }
    }

    @Test
    fun open_current_schema_passes_identity_check() {
        helper.createDatabase(dbName, AgentDatabase.SCHEMA_VERSION).close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(MIGRATION_68_69)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        // 打开即触发 Room 的 identity/schema 校验，entity 与迁移产物不符会抛异常
        db.openHelper.writableDatabase
        db.close()
    }

    /**
     * 非正常降级会留下「user_version 落后于 migration_history」的库：旧版的
     * fallbackToDestructiveMigration 只 DROP Room 实体表再按旧 schema 重建（migration_history
     * 不在实体表之列因而保留），并把 user_version 置回旧值。升级时 Room 会把整条迁移链重放，
     * 历史表写入必须幂等，否则抛 SQLiteConstraintException:
     * UNIQUE constraint failed: migration_history.version。
     */
    @Test
    fun replaying_migrations_with_prerecorded_history_rows() {
        helper.createDatabase(dbName, 50).apply {
            execSQL(
                "CREATE TABLE IF NOT EXISTS migration_history (" +
                        "version INTEGER PRIMARY KEY, script_name TEXT, executed_at INTEGER)"
            )
            for (version in 51..AgentDatabase.SCHEMA_VERSION) {
                execSQL(
                    "INSERT INTO migration_history (version, script_name, executed_at) " +
                            "VALUES ($version, 'prerecorded', 0)"
                )
            }
        }.close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(MIGRATION_68_69)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        val columns = mutableSetOf<String>()
        db.openHelper.writableDatabase
            .query("PRAGMA table_info(`ai_providers`)")
            .use { cursor -> while (cursor.moveToNext()) columns += cursor.getString(1) }
        db.close()

        assertTrue(
            "重放迁移应补回 53/54 加入的两列，实际列：$columns",
            columns.containsAll(listOf("scriptParams", "customHeaders"))
        )
    }

    /**
     * 未来新增迁移（51+）时把 @AutoMigration/新迁移加进来后在此补升级用例：
     * createDatabase(50) → 跑迁移数组 → 断言新列/新表存在。
     */
    @Test
    fun migrate_58_to_59_adds_backup_targets() {
        helper.createDatabase(dbName, 58).close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(MIGRATION_68_69)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        val tables = mutableSetOf<String>()
        db.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table'")
            .use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
        db.close()

        assertTrue(
            "迁移 59 应新增 backup_targets 表，实际表：$tables",
            tables.contains("backup_targets")
        )
    }

    /**
     * 旧分支的 66 号库会跳过新版 66（websocket），但仍会执行 67（fastMode）。
     */
    @Test
    fun migrate_fork_66_preserves_root_grants_and_repairs_skipped_columns() {
        helper.createDatabase(dbName, 65).use { db ->
            val rootGrantMigration = MigrationLoader.loadMigrations(context)
                .filterIsInstance<FileMigration>().single { it.version == 68 }
            FileMigration(66, "66_add_root_grants.sql", rootGrantMigration.sqlStatements).migrate(db)
            db.version = 66
            db.execSQL("INSERT INTO root_grants VALUES ('existing', '/tmp/example', 'READ', 'ALWAYS', 0, 1)")
        }

        openMigratedDatabase().let { db ->
            try {
                val sqlite = db.openHelper.writableDatabase
                assertEquals(AgentDatabase.SCHEMA_VERSION, sqlite.version)
                sqlite.query("SELECT useResponseWebSocket FROM ai_providers").close()
                sqlite.query("SELECT fastMode FROM chat_sessions").close()
                sqlite.query("SELECT id FROM root_grants").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("existing", cursor.getString(0))
                }
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun migrate_69_with_stale_hash_revalidates_and_preserves_data() {
        helper.createDatabase(dbName, 69).use { db ->
            db.execSQL("UPDATE room_master_table SET identity_hash = 'stale' WHERE id = 42")
            db.execSQL("INSERT INTO root_grants VALUES ('existing', '/tmp/example', 'READ', 'ALWAYS', 0, 1)")
        }
        val db = openMigratedDatabase()
        try {
            assertEquals(AgentDatabase.SCHEMA_VERSION, db.openHelper.writableDatabase.version)
            db.openHelper.writableDatabase.query("SELECT id FROM root_grants").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("existing", cursor.getString(0))
            }
            db.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0) != "stale")
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate_69_with_incompatible_schema_fails_without_overwriting_hash() {
        helper.createDatabase(dbName, 69).use { db ->
            db.execSQL("UPDATE room_master_table SET identity_hash = 'stale' WHERE id = 42")
            db.execSQL("DROP INDEX index_root_grants_realPath_operation")
        }
        val db = openMigratedDatabase()
        try {
            db.openHelper.writableDatabase
            fail("缺少索引时 Room 应拒绝升级")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("root_grants"))
        } finally {
            db.close()
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        ).use { unchanged ->
            assertEquals(69, unchanged.version)
            unchanged.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("stale", cursor.getString(0))
            }
        }
    }

    private fun openMigratedDatabase(): AgentDatabase =
        Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(MIGRATION_68_69, *MigrationLoader.loadMigrations(context))
            .build()
}