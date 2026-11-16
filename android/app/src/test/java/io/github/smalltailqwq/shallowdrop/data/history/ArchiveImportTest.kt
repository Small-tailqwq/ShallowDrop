package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.core.contract.HistoryException
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.data.db.FileAttachmentStore
import io.github.smalltailqwq.shallowdrop.data.db.RoomSessionStore
import io.github.smalltailqwq.shallowdrop.data.db.TestEnv
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArchiveImportTest {
    private val env = TestEnv()
    private val store = RoomSessionStore(env.db, env.paths)
    private val attachments = FileAttachmentStore(env.db, env.paths, env.context.contentResolver)
    private val backup = ArchiveHistoryBackup(env.db, env.paths, { null }, { null }, DefaultReplyNotice { true })

    @After
    fun tearDown() = env.close()

    private suspend fun import(bytes: ByteArray) = backup.importFrom({ ByteArrayInputStream(bytes) })

    private suspend fun expectFailure(code: String, bytes: ByteArray) {
        try {
            import(bytes)
            fail("expected $code")
        } catch (error: HistoryException) {
            assertEquals(code, error.code)
        }
    }

    private suspend fun export(): ByteArray {
        val out = ByteArrayOutputStream()
        backup.exportTo { out }
        return out.toByteArray()
    }

    private suspend fun allSessions(): List<Session> =
        store.observeSummaries().first().map { store.load(it.id)!! }.sortedBy { it.id }

    // ---------- 样例归档 ----------

    private val sessionA = Fx.session(
        "sa",
        "图片会话",
        4,
        listOf(
            Fx.user(
                1,
                null,
                "看图",
                Fx.files(
                    Fx.fileEntry("img1", "猫.png", Fx.PNG.size, true, "chat_sessions/sa_files/img1_猫.png"),
                    Fx.fileEntry("img2", "缺.jpg", 10, true, "chat_sessions/sa_files/img2_缺.jpg"),
                    Fx.fileEntry("doc1", "a.pdf", 100, false, null),
                ),
            ),
            Fx.assistant(2, 1, Fx.think("想", 1.25), Fx.response("是猫")),
            Fx.user(3, 2, "再看", Fx.files(Fx.fileEntry("img1", "猫.png", Fx.PNG.size, true, "chat_sessions/sa_files/img1_猫.png"))),
            Fx.assistant(4, 3, Fx.template("服务器繁忙"), status = "FAILED"),
        ),
    )

    private val sessionB = Fx.session(
        "sb",
        null,
        2,
        listOf(
            Fx.user(1, null, "搜", Fx.files(Fx.fileEntry("img3", "j.jpg", Fx.JPEG.size, true, "chat_sessions/sb_files/img3_j.jpg"))),
            Fx.assistant(
                2,
                1,
                Fx.toolSearch(listOf("k"), listOf(Fx.searchResult("https://a.example", "A", Fx.at(9)))),
                Fx.readLink(Fx.readLinkResult("https://r.example", "R", "sig")),
                Fx.response("结果"),
            ),
            Fx.assistant(3, 1, Fx.response("另一个分支")),
        ),
    )

    private fun standard(stored: Boolean = false) = Fx.zip(
        ArchivePaths.MANIFEST to Fx.manifest(),
        ArchivePaths.USER to """{"id":"x"}""",
        ArchivePaths.session("sa") to sessionA,
        ArchivePaths.session("sb") to sessionB,
        ArchivePaths.INDEX to Fx.index(sessionA, sessionB),
        "chat_sessions/sa_files/img1_猫.png" to Fx.PNG,
        "chat_sessions/sa_files/unreferenced.png" to Fx.PNG,
        "chat_sessions/sb_files/img3_j.jpg" to Fx.JPEG,
        stored = stored,
    )

    @Test
    fun importsStandardArchive() = runTest {
        val result = import(standard())
        assertEquals(2, result.sessions)
        assertEquals(7, result.messages)
        assertEquals(2, result.files)

        val a = store.load("sa")!!
        assertEquals("图片会话", a.title)
        val first = a.messages[0] as Message.User
        assertEquals(listOf("img1", "img2", "doc1"), first.files.map { it.id })
        assertEquals("image/png", first.files[0].mimeType)
        // 缺失原件的图片保留文件名和大小
        assertEquals("", first.files[1].mimeType)
        assertTrue(first.files[1].isImage)
        assertNull(attachments.file("img2"))
        assertArrayEquals(Fx.PNG, attachments.file("img1")!!.readBytes())
        assertEquals("image/png", (a.messages[2] as Message.User).files.single().mimeType)
        assertEquals("image/png", attachments.metadata("img1")!!.mimeType)
        val failed = a.messages[3] as Message.Assistant
        assertEquals(ReplyStatus.Error, failed.status)
        assertEquals(listOf(ReplyBlock.Text("服务器繁忙", isTemplate = true)), failed.blocks)

        val b = store.load("sb")!!
        assertEquals("", b.title)
        assertEquals("2", b.messages[0].selectedChildId)
        assertArrayEquals(Fx.JPEG, attachments.file("img3")!!.readBytes())
        assertEquals("image/jpeg", (b.messages[0] as Message.User).files.single().mimeType)
        val reply = b.messages[1] as Message.Assistant
        assertEquals("sig", reply.blocks.filterIsInstance<ReplyBlock.WebFetchToolResult>().single().signature)
        assertEquals(listOf("sb", "sa"), store.observeSummaries().first().map { it.id }.sortedDescending())
    }

    @Test
    fun storedArchiveAndInterleavedOrder() = runTest {
        import(standard(stored = true))
        val expected = allSessions()

        // 会话 JSON → 该会话图片 交错排列，index 在末尾，无 user.json
        val interleaved = Fx.zip(
            ArchivePaths.MANIFEST to Fx.manifest(),
            ArchivePaths.session("sa") to sessionA,
            "chat_sessions/sa_files/img1_猫.png" to Fx.PNG,
            ArchivePaths.session("sb") to sessionB,
            "chat_sessions/sb_files/img3_j.jpg" to Fx.JPEG,
            ArchivePaths.INDEX to Fx.index(sessionA, sessionB),
        )
        import(interleaved)
        assertEquals(expected, allSessions())
        assertNotNull(attachments.file("img3"))
    }

    @Test
    fun rejectsBadManifestAndVersion() = runTest {
        expectFailure(HistoryErrorCodes.UNRECOGNIZED, "not a zip".toByteArray())
        expectFailure(
            HistoryErrorCodes.UNRECOGNIZED,
            Fx.zip(ArchivePaths.INDEX to Fx.index(), ArchivePaths.MANIFEST to Fx.manifest()),
        )
        expectFailure(HistoryErrorCodes.NEWER_VERSION, Fx.zip(ArchivePaths.MANIFEST to Fx.manifest(4)))
        expectFailure(HistoryErrorCodes.INVALID_VERSION, Fx.zip(ArchivePaths.MANIFEST to Fx.manifest(2)))
        expectFailure(HistoryErrorCodes.INVALID_JSON, Fx.zip(ArchivePaths.MANIFEST to "{oops"))
        // 只有 manifest、缺少索引
        expectFailure(HistoryErrorCodes.INCOMPLETE, Fx.zip(ArchivePaths.MANIFEST to Fx.manifest()))
    }

    @Test
    fun rejectsStructuralProblems() = runTest {
        val cases = mapOf(
            "索引与会话不对应" to Fx.zip(
                ArchivePaths.MANIFEST to Fx.manifest(),
                ArchivePaths.session("sa") to sessionA,
                ArchivePaths.INDEX to Fx.index(sessionA, sessionB),
            ),
            "会话 ID 与条目名不一致" to Fx.zip(
                ArchivePaths.MANIFEST to Fx.manifest(),
                ArchivePaths.session("other") to sessionA,
                ArchivePaths.INDEX to Fx.index(sessionA),
            ),
            "未知条目" to Fx.zip(
                ArchivePaths.MANIFEST to Fx.manifest(),
                ArchivePaths.session("sa") to sessionA,
                ArchivePaths.INDEX to Fx.index(sessionA),
                "extra.txt" to "x",
            ),
            "user.json 位于会话之后" to Fx.zip(
                ArchivePaths.MANIFEST to Fx.manifest(),
                ArchivePaths.session("sa") to sessionA,
                ArchivePaths.USER to "{}",
                ArchivePaths.INDEX to Fx.index(sessionA),
            ),
            "路径穿越" to Fx.zip(
                ArchivePaths.MANIFEST to Fx.manifest(),
                "chat_sessions/../evil.json" to sessionA,
            ),
            "重复条目" to Fx.zip(
                ArchivePaths.MANIFEST to Fx.manifest(),
                ArchivePaths.session("sa") to sessionA,
                "chat_sessions/sa.json/" to "",
            ),
            "截断" to standard().let { it.copyOf(it.size - 30) },
        )
        for ((name, bytes) in cases) {
            try {
                import(bytes)
                fail(name)
            } catch (error: HistoryException) {
                assertEquals(name, HistoryErrorCodes.INCOMPLETE, error.code)
            }
        }
    }

    @Test
    fun corruptBranchFailsAndKeepsExistingData() = runTest {
        import(standard())
        val before = allSessions()
        val beforeFiles = env.paths.dir.list()!!.sorted()

        val broken = Fx.session("sc", null, 2, listOf(Fx.user(1, null, "q"), Fx.user(2, 1, "q")))
        expectFailure(
            HistoryErrorCodes.INVALID_BRANCHES,
            Fx.zip(
                ArchivePaths.MANIFEST to Fx.manifest(),
                ArchivePaths.session("sb") to sessionB,
                "chat_sessions/sb_files/img3_j.jpg" to Fx.JPEG,
                ArchivePaths.session("sc") to broken,
                ArchivePaths.INDEX to Fx.index(sessionB, broken),
            ),
        )
        assertEquals(before, allSessions())
        assertEquals(beforeFiles, env.paths.dir.list()!!.sorted())
        assertFalse(env.paths.stagingDir.exists())
        assertEquals(0, env.db.stagingDao().messageCount())
    }

    @Test
    fun leftoverStagingIsCleared() = runTest {
        // 模拟上次中断遗留的暂存
        env.paths.stagingDir.mkdirs()
        File(env.paths.stagingDir, "stale").writeText("x")
        env.db.stagingDao().insertSession(
            io.github.smalltailqwq.shallowdrop.data.db.StagingSessionEntity("stale", "t", 0, 0, null),
        )
        import(standard())
        assertNull(store.load("stale"))
        assertFalse(File(env.paths.dir, "stale").exists())
    }

    @Test
    fun interruptedSwapIsRecovered() = runTest {
        // 数据库已提交、原件仍在暂存目录
        env.paths.stagingDir.mkdirs()
        File(env.paths.stagingDir, "img9").writeBytes(Fx.PNG)
        env.db.stagingDao().putState(
            io.github.smalltailqwq.shallowdrop.data.db.ImportStateEntity(
                io.github.smalltailqwq.shallowdrop.data.db.StagingDao.STATE_FILES_SWAP_PENDING,
                "1",
            ),
        )
        backup.recoverInterruptedImport()
        assertNotNull(attachments.file("img9"))
        assertFalse(env.paths.stagingDir.exists())
    }

    @Test
    fun committedStagingSurvivesCleanup() = runTest {
        // 提交后、换入前清理暂存（提交后出错或取消的路径）不得删除已生效导入的原件
        env.paths.stagingDir.mkdirs()
        File(env.paths.stagingDir, "img9").writeBytes(Fx.PNG)
        env.db.stagingDao().putState(
            io.github.smalltailqwq.shallowdrop.data.db.ImportStateEntity(
                io.github.smalltailqwq.shallowdrop.data.db.StagingDao.STATE_FILES_SWAP_PENDING,
                "1",
            ),
        )
        io.github.smalltailqwq.shallowdrop.data.history.ArchiveImporter(env.db, env.paths).clearStaging()
        assertTrue(File(env.paths.stagingDir, "img9").exists())
        backup.recoverInterruptedImport()
        assertNotNull(attachments.file("img9"))
    }

    @Test
    fun exportRoundTrip() = runTest {
        import(standard())
        val imported = allSessions()
        val zip = export()

        // 条目顺序：manifest → 会话 JSON → index → 图片；STORE
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                assertEquals(java.util.zip.ZipEntry.STORED, entry.method)
                names += entry.name
            }
        }
        assertEquals(ArchivePaths.MANIFEST, names[0])
        assertEquals(listOf("chat_sessions/sa.json", "chat_sessions/sb.json"), names.subList(1, 3).sorted())
        assertEquals(ArchivePaths.INDEX, names[3])
        assertEquals(
            listOf("chat_sessions/sa_files/img1_猫.png", "chat_sessions/sb_files/img3_j.jpg"),
            names.drop(4).sorted(),
        )

        // 再次导入与首次导入一致
        store.deleteAll()
        import(zip)
        assertEquals(imported, allSessions())
        assertArrayEquals(Fx.PNG, attachments.file("img1")!!.readBytes())

        // 缺失原件的图片 export_path 为 null
        val sessionJson = readEntry(zip, "chat_sessions/sa.json")
        val files = Json.parseToJsonElement(sessionJson).jsonObject["messages"]!!.let { it as kotlinx.serialization.json.JsonArray }
            .first().jsonObject["fragments"]!!.let { it as kotlinx.serialization.json.JsonArray }
            .first().jsonObject["files"] as kotlinx.serialization.json.JsonArray
        assertEquals("null", (files[1] as JsonObject)["export_path"].toString())
    }

    @Test
    fun exportsLocalSessions() = runTest {
        store.save(
            Session(
                id = "local",
                title = "本地",
                createdAt = 1000,
                updatedAt = 2000,
                rootMessageId = "u",
                messages = listOf(
                    Message.User(id = "u", createdAt = 1000, selectedChildId = "a", content = "hi"),
                    Message.Assistant(
                        id = "a",
                        createdAt = 1500,
                        parentId = "u",
                        thinking = true,
                        prefix = true,
                        status = ReplyStatus.Done,
                        blocks = listOf(ReplyBlock.Text("hello")),
                    ),
                ),
            ),
        )
        val zip = export()
        store.deleteAll()
        import(zip)
        val session = store.load("local")!!
        assertEquals(listOf("1", "2"), session.messages.map { it.id })
        assertEquals("1", session.rootMessageId)
        val reply = session.messages[1] as Message.Assistant
        assertFalse(reply.prefix)
        assertTrue(reply.thinking)
        assertEquals(listOf(ReplyBlock.Text("hello")), reply.blocks)
        assertEquals(2000, session.updatedAt)
    }

    private fun readEntry(zip: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name == name) return input.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("missing $name")
    }
}
