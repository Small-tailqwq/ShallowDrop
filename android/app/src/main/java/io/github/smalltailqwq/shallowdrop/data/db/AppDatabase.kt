package io.github.smalltailqwq.shallowdrop.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.sync.Mutex

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        MessageFileEntity::class,
        FileEntity::class,
        StagingSessionEntity::class,
        StagingMessageEntity::class,
        StagingMessageFileEntity::class,
        StagingFileEntity::class,
        ImportStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun stagingDao(): StagingDao

    /**
     * 导入、导出与全部删除串行执行，避免读取归档时数据被清空或两次替换交错（对应 Web backup.ts 的队列）。
     * ArchiveHistoryBackup 与 RoomSessionStore.deleteAll 共用。
     */
    val historyLock = Mutex()

    companion object {
        const val NAME = "shallowdrop.db"

        fun create(context: Context, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("messages", "staging_messages")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN parentId TEXT")
            db.execSQL("ALTER TABLE $table ADD COLUMN selectedChildId TEXT")
            // API 26 的 SQLite 不保证提供 JSON 函数，逐条解码也避免同时载入全部回复正文。
            db.compileStatement("UPDATE $table SET parentId = ?, selectedChildId = ? WHERE sessionId = ? AND id = ?").use { update ->
                db.query("SELECT sessionId, id, json FROM $table").use { rows ->
                    while (rows.moveToNext()) {
                        val message = DbJson.decode(rows.getString(2))
                        update.clearBindings()
                        message.parentId?.let { update.bindString(1, it) }
                        message.selectedChildId?.let { update.bindString(2, it) }
                        update.bindString(3, rows.getString(0))
                        update.bindString(4, rows.getString(1))
                        update.executeUpdateDelete()
                    }
                }
            }
        }
        db.execSQL("DROP INDEX index_messages_sessionId")
        // 覆盖树查询，读取分支时不经过存放大正文的溢出页。
        db.execSQL("CREATE INDEX index_messages_branches ON messages(sessionId, seq, id, parentId, selectedChildId)")
    }
}
