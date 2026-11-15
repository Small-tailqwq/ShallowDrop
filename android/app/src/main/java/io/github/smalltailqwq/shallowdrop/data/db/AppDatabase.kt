package io.github.smalltailqwq.shallowdrop.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
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

        /** 只支持当前格式，不提供旧数据迁移；版本升级前需补充 Migration。 */
        fun create(context: Context, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name).build()
    }
}
