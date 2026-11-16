package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.data.db.FileAttachmentStore
import io.github.smalltailqwq.shallowdrop.data.db.ImportStateEntity
import io.github.smalltailqwq.shallowdrop.data.db.RoomSessionStore
import io.github.smalltailqwq.shallowdrop.data.db.StagingDao
import io.github.smalltailqwq.shallowdrop.data.db.TestEnv
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 导入的数据库提交之后、原件目录换入完成之前的各个中断点：
 * 用真实导入得到提交后的数据库，再把磁盘摆回对应中断点的样子，验证数据库、原件与恢复结果。
 */
@RunWith(RobolectricTestRunner::class)
class ImportSwapRecoveryTest {
    private val env = TestEnv()
    private val store = RoomSessionStore(env.db, env.paths)
    private val attachments = FileAttachmentStore(env.db, env.paths, env.context.contentResolver)
    private val backup = ArchiveHistoryBackup(env.db, env.paths, { null }, { null }, DefaultReplyNotice { true })
    private val staging get() = env.db.stagingDao()

    @After
    fun tearDown() = env.close()

    private val newSession = Fx.session(
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

    private val newArchive = Fx.zip(
        ArchivePaths.MANIFEST to Fx.manifest(),
        ArchivePaths.session("new") to newSession,
        ArchivePaths.INDEX to Fx.index(newSession),
        "chat_sessions/new_files/imgA_a.png" to Fx.PNG,
        "chat_sessions/new_files/imgB_b.jpg" to Fx.JPEG,
    )

    private val oldBytes = byteArrayOf(1, 2, 3)

    private suspend fun seedOldData() {
        val file = FileAttachment(id = "old1", name = "old.png", size = 3, isImage = true, mimeType = "image/png")
        attachments.put(file, ByteArrayInputStream(oldBytes))
        store.save(
            Session(
                id = "old",
                title = "旧会话",
                createdAt = 1,
                updatedAt = 1,
                messages = listOf(Message.User(id = "u1", createdAt = 1, content = "旧", files = listOf(file))),
                rootMessageId = "u1",
            ),
        )
    }

    private suspend fun setState(value: String) =
        staging.putState(ImportStateEntity(StagingDao.STATE_FILES_SWAP_PENDING, value))

    /**
     * 摆出「数据库已提交、原件一个都没换入」的状态：等同于进程在 commit 之后立刻被杀，
     * 或换入第一步就失败。正式目录里还是旧原件，新原件全在暂存目录。
     */
    private suspend fun crashedRightAfterCommit() {
        seedOldData()
        backup.importFrom({ ByteArrayInputStream(newArchive) })
        assertTrue(env.paths.dir.renameTo(env.paths.stagingDir))
        env.paths.dir.mkdirs()
        File(env.paths.dir, "old1").writeBytes(oldBytes)
        setState(StagingDao.SWAP_PENDING)
    }

    private suspend fun assertDatabaseIsNew() {
        assertEquals(listOf("new"), store.observeSummaries().first().map { it.id })
        assertNull(store.load("old"))
        val files = (store.load("new")!!.messages[0] as Message.User).files
        assertEquals(listOf("imgA", "imgB"), files.map { it.id })
        assertEquals(listOf("image/png", "image/jpeg"), files.map { it.mimeType })
    }

    private suspend fun assertFullyRecovered() {
        assertDatabaseIsNew()
        assertArrayEquals(Fx.PNG, attachments.file("imgA")!!.readBytes())
        assertArrayEquals(Fx.JPEG, attachments.file("imgB")!!.readBytes())
        assertNull(attachments.file("old1"))
        assertFalse(env.paths.stagingDir.exists())
        assertFalse(env.paths.oldDir.exists())
        assertNull(staging.state(StagingDao.STATE_FILES_SWAP_PENDING))
    }

    @Test
    fun killedRightAfterCommit() = runTest {
        crashedRightAfterCommit()
        // 恢复前：会话已是新数据，新图片还取不到原件（界面只显示文件名），旧原件未被清理
        assertDatabaseIsNew()
        assertNull(attachments.file("imgA"))
        assertNotNull(attachments.file("old1"))

        backup.recoverInterruptedImport()
        assertFullyRecovered()
        // 再次恢复是空操作
        backup.recoverInterruptedImport()
        assertFullyRecovered()
    }

    @Test
    fun killedAfterOldDirMovedAway() = runTest {
        crashedRightAfterCommit()
        assertTrue(env.paths.dir.renameTo(env.paths.oldDir))

        backup.recoverInterruptedImport()
        assertFullyRecovered()
    }

    @Test
    fun killedOrFailedHalfwayThroughMovingFiles() = runTest {
        crashedRightAfterCommit()
        // 旧原件已移走，imgA 已换入，imgB 还在暂存；期间用户又添加了一个新附件
        env.paths.dir.deleteRecursively()
        env.paths.dir.mkdirs()
        assertTrue(File(env.paths.stagingDir, "imgA").renameTo(File(env.paths.dir, "imgA")))
        val added = FileAttachment(id = "added", name = "n.txt", size = 2, isImage = false, mimeType = "text/plain")
        attachments.put(added, ByteArrayInputStream(byteArrayOf(7, 8)))
        setState(StagingDao.SWAP_MOVING)

        backup.recoverInterruptedImport()
        assertFullyRecovered()
        // 重试不得清空正式目录：已换入的图片和之后新增的附件都保留
        assertArrayEquals(byteArrayOf(7, 8), attachments.file("added")!!.readBytes())
    }

    @Test
    fun killedAfterSwapBeforeMarkerCleared() = runTest {
        crashedRightAfterCommit()
        env.paths.dir.renameTo(env.paths.oldDir)
        assertTrue(env.paths.stagingDir.renameTo(env.paths.dir))
        setState(StagingDao.SWAP_MOVING)

        backup.recoverInterruptedImport()
        assertFullyRecovered()
    }

    @Test
    fun nextImportFinishesPendingSwapFirst() = runTest {
        crashedRightAfterCommit()
        // 没有经过启动恢复就直接再次导入：先补完上次的换入，再整体替换
        val again = Fx.session("again", "再次", 1, listOf(Fx.user(1, null, "hi")))
        backup.importFrom({
            ByteArrayInputStream(
                Fx.zip(
                    ArchivePaths.MANIFEST to Fx.manifest(),
                    ArchivePaths.session("again") to again,
                    ArchivePaths.INDEX to Fx.index(again),
                ),
            )
        })
        assertEquals(listOf("again"), store.observeSummaries().first().map { it.id })
        assertNull(attachments.file("imgA"))
        assertNull(attachments.file("old1"))
        assertFalse(env.paths.stagingDir.exists())
        assertNull(staging.state(StagingDao.STATE_FILES_SWAP_PENDING))
    }

    @Test
    fun failedImportWhilePendingKeepsCommittedImages() = runTest {
        crashedRightAfterCommit()
        // 待换入状态下再导入一个坏归档：上次已生效的导入补完，本次失败不影响它
        try {
            backup.importFrom({ ByteArrayInputStream(Fx.zip(ArchivePaths.MANIFEST to Fx.manifest(99))) })
            org.junit.Assert.fail("expected failure")
        } catch (_: io.github.smalltailqwq.shallowdrop.core.contract.HistoryException) {
        }
        assertFullyRecovered()
    }

    @Test
    fun exportWhilePendingIncludesImages() = runTest {
        crashedRightAfterCommit()
        val out = ByteArrayOutputStream()
        backup.exportTo { out }
        assertFullyRecovered()
        // 导出的归档再导入，图片原件仍在
        store.deleteAll()
        backup.importFrom({ ByteArrayInputStream(out.toByteArray()) })
        assertFullyRecovered()
    }

    @Test
    fun deleteAllWhilePendingDropsStagedImages() = runTest {
        crashedRightAfterCommit()
        store.deleteAll()
        backup.recoverInterruptedImport()
        // 全部删除后，待换入的图片不得再出现在正式目录
        assertTrue(store.observeSummaries().first().isEmpty())
        assertNull(attachments.file("imgA"))
        assertNull(attachments.file("imgB"))
        assertFalse(env.paths.stagingDir.exists())
        assertNull(staging.state(StagingDao.STATE_FILES_SWAP_PENDING))
    }
}
