package io.github.smalltailqwq.shallowdrop.data.memory

import android.util.Log
import io.github.smalltailqwq.shallowdrop.core.contract.MemoryStore
import io.github.smalltailqwq.shallowdrop.core.model.MemoryState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 记忆存为单个 JSON 文件（条目只有几十到几百条，整份读写）。
 * 先写临时文件再改名，写到一半中断不会损坏原文件。
 */
class FileMemoryStore(private val file: File) : MemoryStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override suspend fun load(): MemoryState = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext MemoryState()
        try {
            json.decodeFromString(MemoryState.serializer(), file.readText())
        } catch (e: SerializationException) {
            // 留下损坏的文件供排查，下次保存前不覆盖它
            Log.w(TAG, "记忆文件无法解析", e)
            file.renameTo(File(file.parentFile, file.name + ".bad"))
            MemoryState()
        } catch (e: IOException) {
            Log.w(TAG, "读取记忆失败", e)
            MemoryState()
        }
    }

    override suspend fun save(state: MemoryState) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(MemoryState.serializer(), state))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("无法保存记忆")
        }
    }

    private companion object {
        const val TAG = "FileMemoryStore"
    }
}
