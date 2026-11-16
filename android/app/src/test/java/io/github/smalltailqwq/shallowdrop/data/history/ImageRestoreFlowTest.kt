package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.core.contract.ImageRestoreState
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageParam
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.UserContentParam
import io.github.smalltailqwq.shallowdrop.data.db.FileAttachmentStore
import io.github.smalltailqwq.shallowdrop.data.db.RoomSessionStore
import io.github.smalltailqwq.shallowdrop.data.db.TestEnv
import io.github.smalltailqwq.shallowdrop.domain.chat.ChatEngine
import io.github.smalltailqwq.shallowdrop.domain.chat.FakeChatApi
import io.github.smalltailqwq.shallowdrop.domain.chat.FakeSettings
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 导入的会话已提交、图片原件换入失败之后的完整路径：
 * 导入结果与状态如实反映「图片待恢复」；此时发送不会悄悄漏掉图片；重试恢复后可继续发送。
 * 换入失败用测试接缝注入（真实场景为磁盘写入失败），其余均为真实的数据库、原件目录与对话引擎。
 */
@RunWith(RobolectricTestRunner::class)
class ImageRestoreFlowTest {
    private val env = TestEnv()
    private val store = RoomSessionStore(env.db, env.paths)
    private val attachments = FileAttachmentStore(env.db, env.paths, env.context.contentResolver)
    private var diskBroken = false
    private val backup = ArchiveHistoryBackup(
        env.db,
        env.paths,
        { null },
        { null },
        DefaultReplyNotice { true },
        swapFault = { if (diskBroken) throw IOException("disk full") },
    )
    private val api = FakeChatApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = ChatEngine(
        api = api,
        store = store,
        attachments = attachments,
        settings = FakeSettings(),
        scope = scope,
        restoreAttachments = { backup.retryImageRestore() },
    )
    private val options = SendOptions(thinking = false, search = false, model = ChatModel.Flash)

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    private val session = Fx.session(
        "new",
        "新会话",
        2,
        listOf(
            Fx.user(
                1,
                null,
                "看图",
                Fx.files(
                    Fx.fileEntry("imgA", "a.png", Fx.PNG.size, true, "chat_sessions/new_files/imgA_a.png"),
                    Fx.fileEntry("imgB", "b.jpg", Fx.JPEG.size, true, "chat_sessions/new_files/imgB_b.jpg"),
                ),
            ),
            Fx.assistant(2, 1, Fx.response("好")),
        ),
    )

    private val archive = Fx.zip(
        ArchivePaths.MANIFEST to Fx.manifest(),
        ArchivePaths.session("new") to session,
        ArchivePaths.INDEX to Fx.index(session),
        "chat_sessions/new_files/imgA_a.png" to Fx.PNG,
        "chat_sessions/new_files/imgB_b.jpg" to Fx.JPEG,
    )

    private suspend fun import() = backup.importFrom({ ByteArrayInputStream(archive) })

    private suspend fun awaitIdle() = engine.generating.first { it.isEmpty() }

    private suspend fun lastReply() = store.load("new")!!.messages.last() as Message.Assistant

    private fun imageBlocks() = api.requests.last().messages
        .filterIsInstance<MessageParam.User>()
        .flatMap { it.content }
        .filterIsInstance<UserContentParam.Image>()

    @Test
    fun stateStartsUnknownAndBecomesReady() = runTest {
        assertEquals(ImageRestoreState.Checking, backup.imageRestore.value)
        assertTrue(backup.retryImageRestore())
        assertEquals(ImageRestoreState.Ready, backup.imageRestore.value)
    }

    @Test
    fun successfulImportIsNotPending() = runTest {
        val result = import()
        assertFalse(result.imagesPending)
        assertEquals(ImageRestoreState.Ready, backup.imageRestore.value)
    }

    @Test
    fun swapFailureIsReportedAndRetryRecovers() = runTest {
        diskBroken = true
        val result = import()
        // 会话已生效，但结果与状态都标明图片待恢复
        assertTrue(result.imagesPending)
        assertEquals(1, result.sessions)
        assertEquals(ImageRestoreState.Pending, backup.imageRestore.value)
        assertEquals(listOf("new"), store.observeSummaries().first().map { it.id })
        assertNull(attachments.file("imgA"))

        // 故障未排除时重试仍失败，状态不变，不抛异常
        assertFalse(backup.retryImageRestore())
        assertEquals(ImageRestoreState.Pending, backup.imageRestore.value)
        assertNull(attachments.file("imgA"))

        diskBroken = false
        assertTrue(backup.retryImageRestore())
        assertEquals(ImageRestoreState.Ready, backup.imageRestore.value)
        assertArrayEquals(Fx.PNG, attachments.file("imgA")!!.readBytes())
        assertArrayEquals(Fx.JPEG, attachments.file("imgB")!!.readBytes())
    }

    @Test
    fun sendingWhileImagesPendingDoesNotDropThem() = runTest {
        diskBroken = true
        import()

        // 在导入的会话里继续提问：上下文里的两张图片原件还取不到
        engine.send("new", "继续", emptyList(), options)
        awaitIdle()
        assertTrue("图片待恢复时不得发出请求", api.requests.isEmpty())
        assertTrue(api.uploads.isEmpty())
        val blocked = lastReply()
        assertEquals(ReplyStatus.Error, blocked.status)
        assertEquals(ReplyErrorKind.Upload, blocked.error?.kind)
        assertEquals("attachmentsRestoring", blocked.error?.code)
        assertEquals(true, blocked.error?.retryable)
        assertEquals(ImageRestoreState.Pending, backup.imageRestore.value)

        // 故障排除后重新生成：发送前自动补完恢复，图片补传并进入请求
        diskBroken = false
        engine.regenerate("new", blocked.id, options)
        awaitIdle()
        assertEquals(ImageRestoreState.Ready, backup.imageRestore.value)
        assertEquals(1, api.requests.size)
        assertEquals(2, api.uploads.size)
        assertEquals(2, imageBlocks().size)
        assertEquals(ReplyStatus.Done, lastReply().status)
    }

    @Test
    fun sendRightAfterInterruptedImportWaitsForRestore() = runTest {
        // 进程在提交后中断、启动恢复尚未执行时就发送：发送自身会先补完恢复
        import()
        assertTrue(env.paths.dir.renameTo(env.paths.stagingDir))
        env.db.stagingDao().putState(
            io.github.smalltailqwq.shallowdrop.data.db.ImportStateEntity(
                io.github.smalltailqwq.shallowdrop.data.db.StagingDao.STATE_FILES_SWAP_PENDING,
                io.github.smalltailqwq.shallowdrop.data.db.StagingDao.SWAP_PENDING,
            ),
        )
        assertNull(attachments.file("imgA"))

        engine.send("new", "继续", emptyList(), options)
        awaitIdle()
        assertEquals(1, api.requests.size)
        assertEquals(2, imageBlocks().size)
        assertEquals(ImageRestoreState.Ready, backup.imageRestore.value)
    }

    @Test
    fun genuinelyMissingOriginalsStillSendOnceRestoreIsReady() = runTest {
        // 归档里本来就没有原件的图片：恢复就绪后按原规则只展示、不进入请求，发送不被拦住
        val bare = Fx.zip(
            ArchivePaths.MANIFEST to Fx.manifest(),
            ArchivePaths.session("new") to session,
            ArchivePaths.INDEX to Fx.index(session),
        )
        backup.importFrom({ ByteArrayInputStream(bare) })
        engine.send("new", "继续", emptyList(), options)
        awaitIdle()
        assertEquals(1, api.requests.size)
        assertTrue(imageBlocks().isEmpty())
        assertEquals(ReplyStatus.Done, lastReply().status)
    }
}
