package io.github.smalltailqwq.shallowdrop.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File

/** 测试用：内存数据库 + 临时 filesDir。 */
class TestEnv {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val filesDir: File = File(context.filesDir, "test-${System.nanoTime()}").apply { mkdirs() }
    val paths = AttachmentPaths(filesDir)

    fun close() {
        db.close()
        filesDir.deleteRecursively()
    }
}
