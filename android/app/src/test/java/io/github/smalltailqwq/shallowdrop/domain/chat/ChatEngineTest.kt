package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.FileObject
import io.github.smalltailqwq.shallowdrop.core.model.FilePurpose
import io.github.smalltailqwq.shallowdrop.core.model.FileSource
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageParam
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyError
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.RequestTool
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.UserContentParam
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val api = FakeChatApi()
    private val store = FakeSessionStore()
    private val settings = FakeSettings()
    private lateinit var attachments: FakeAttachmentStore
    private var ids = 0
    private val options = SendOptions(thinking = false, search = false, model = ChatModel.Flash)

    @Before
    fun setUp() {
        attachments = FakeAttachmentStore(tmp.newFolder())
    }

    /**
     * 引擎的应用作用域用测试作用域的前台子作用域：advanceUntilIdle 不会推进 backgroundScope 中的任务。
     * 测试结束时取消，避免挂起的生成拖住 runTest。
     */
    private fun engineTest(block: suspend TestScope.(ChatEngine) -> Unit) = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        val engine = ChatEngine(
            api = api,
            store = store,
            attachments = attachments,
            settings = settings,
            scope = scope,
            clock = { testScheduler.currentTime },
            idGenerator = { "id${ids++}" },
            zone = { ZoneOffset.UTC },
            systemLocale = { Locale.CHINA },
        )
        try {
            block(engine)
        } finally {
            scope.cancel()
        }
    }

    private fun stored(id: String): Session = store.data.value.getValue(id)

    private fun lastReply(id: String) = visibleMessages(stored(id)).last() as Message.Assistant

    private suspend fun ChatEngine.state(id: String): ConversationState = observeConversation(id).first { it != null }!!

    private fun Message.Assistant.text() = replyText(this)

    private fun MessageParam.User.text() = content.filterIsInstance<UserContentParam.Text>().joinToString("") { it.text }

    @Test
    fun sendCreatesSessionStreamsReplyAndAppliesTitle() = engineTest { engine ->
        api.scripts += { textReply("你好", title = "  问候 ") }
        val id = engine.send(null, "  hi ", emptyList(), options)
        advanceUntilIdle()

        val session = stored(id)
        assertEquals("问候", session.title)
        val path = visibleMessages(session)
        assertEquals("hi", (path[0] as Message.User).content)
        val reply = path[1] as Message.Assistant
        assertEquals(ReplyStatus.Done, reply.status)
        assertEquals("你好", reply.text())
        assertEquals(ChatModel.Flash, reply.model)
        assertTrue(reply.prefix)
        assertEquals("1970-01-01,星期四,,Android,中文", reply.latestReminder)

        val request = api.requests.single()
        assertEquals(ChatEngine.SYSTEM_PROMPT, request.system)
        assertEquals(listOf(RequestTool.ReadUrl), request.tools)
        assertTrue(request.title)
        assertFalse(request.thinking)
        assertFalse(request.hasFiles)
        assertEquals(MessageParam.LatestReminder("1970-01-01,星期四,,Android,中文"), request.messages[0])
        assertEquals("hi", (request.messages[1] as MessageParam.User).text())
        assertTrue(engine.generating.value.isEmpty())
    }

    @Test
    fun secondTurnCarriesHistoryDeclaresSearchAndSkipsTitle() = engineTest { engine ->
        api.scripts += { textReply("一", title = "标题") }
        val id = engine.send(null, "q1", emptyList(), options)
        advanceUntilIdle()
        engine.send(id, "q2", emptyList(), options.copy(search = true, thinking = true, model = ChatModel.V4Pro))
        advanceUntilIdle()

        val request = api.requests[1]
        assertEquals(listOf(RequestTool.ReadUrl, RequestTool.WebSearch), request.tools)
        assertFalse(request.title)
        assertTrue(request.thinking)
        assertEquals(ChatModel.V4Pro, request.model)
        // 快照相同只插入一次
        assertEquals(
            listOf("R", "U", "A", "U"),
            request.messages.map {
                when (it) {
                    is MessageParam.LatestReminder -> "R"
                    is MessageParam.User -> "U"
                    is MessageParam.Assistant -> "A"
                }
            },
        )
        assertEquals(4, visibleMessages(stored(id)).size)
    }

    @Test
    fun regenerateAddsSiblingReplyAndSwitchBranchRestores() = engineTest { engine ->
        api.scripts += { textReply("旧") }
        val id = engine.send(null, "q", emptyList(), options)
        advanceUntilIdle()
        val first = lastReply(id)

        api.scripts += { textReply("新") }
        engine.regenerate(id, first.id, options)
        advanceUntilIdle()
        val second = lastReply(id)
        assertEquals("新", second.text())
        assertEquals(first.parentId, second.parentId)
        assertEquals(1, api.requests[1].messages.count { it is MessageParam.User })

        var state = engine.state(id)
        assertEquals(1, state.turns[1].siblingIndex)
        assertEquals(2, state.turns[1].siblingCount)
        assertTrue(state.turns[1].canRegenerate)
        assertFalse(state.turns[0].canRegenerate)

        engine.switchBranch(id, second.id, 0)
        assertEquals("旧", lastReply(id).text())
        state = engine.state(id)
        assertEquals(0, state.turns[1].siblingIndex)
    }

    @Test
    fun editAndResendCreatesSiblingQuestionWithShorterHistory() = engineTest { engine ->
        val id = engine.send(null, "q1", emptyList(), options)
        advanceUntilIdle()
        engine.send(id, "q2", emptyList(), options)
        advanceUntilIdle()
        val q1 = visibleMessages(stored(id))[0]

        engine.editAndResend(id, q1.id, " 改过 ", options)
        advanceUntilIdle()
        val path = visibleMessages(stored(id))
        assertEquals(2, path.size)
        assertEquals("改过", (path[0] as Message.User).content)
        assertNull(path[0].parentId)
        assertEquals(path[0].id, stored(id).rootMessageId)
        // 旧分支仍保存
        assertEquals(6, stored(id).messages.size)
        val request = api.requests.last()
        assertEquals(1, request.messages.count { it is MessageParam.User })
        val state = engine.state(id)
        assertEquals(1 to 2, state.turns[0].siblingIndex to state.turns[0].siblingCount)

        engine.switchBranch(id, path[0].id, 0)
        assertEquals(4, visibleMessages(stored(id)).size)
    }

    @Test
    fun stopKeepsPartialReplyThenContinueAppends() = engineTest { engine ->
        api.scripts += {
            flow {
                emit(messageStart())
                emit(thinkingStart(0))
                emit(thinkingDelta(0, "想"))
                delay(1000)
                emit(blockStop(0))
                emit(textStart(1))
                emit(textDelta(1, "半"))
                awaitCancellation()
            }
        }
        val id = engine.send(null, "q", emptyList(), options.copy(thinking = true))
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(id in engine.generating.value)
        assertFalse(engine.state(id).turns.last().canRegenerate)

        engine.stop(id)
        advanceUntilIdle()
        val stopped = lastReply(id)
        assertEquals(ReplyStatus.Stopped, stopped.status)
        val thinking = stopped.blocks[0] as ReplyBlock.Thinking
        assertEquals(0L, thinking.startedAt)
        assertEquals(1000L, thinking.endedAt)
        assertEquals("半", stopped.text())
        val state = engine.state(id)
        assertFalse(state.generating)
        assertTrue(state.turns.last().canContinue)

        api.scripts += {
            flowOf(messageStart(), textStart(0), textDelta(0, "句"), blockStop(0), messageDelta(StopReason.EndTurn), MessageStreamEvent.MessageStop)
        }
        engine.continueReply(id, stopped.id)
        advanceUntilIdle()
        val continued = lastReply(id)
        assertEquals(stopped.id, continued.id)
        assertEquals(ReplyStatus.Done, continued.status)
        assertEquals("半句", continued.text())
        assertEquals(3, continued.blocks.size)
        assertEquals(stopped.latestReminder, continued.latestReminder)
        val request = api.requests.last()
        assertTrue(request.thinking)
        val prefix = request.messages.last() as MessageParam.Assistant
        assertEquals(listOf("thinking", "text"), prefix.content.map { it["type"]!!.jsonPrimitive.content })
        assertFalse(engine.state(id).turns.last().canContinue)
    }

    @Test
    fun errorsMapToReplyErrorAndAreExcludedFromContext() = engineTest { engine ->
        api.scripts += { flow { throw ApiException(ReplyErrorKind.Http, "Insufficient Balance", 402, "insufficientBalance") } }
        val id = engine.send(null, "q1", emptyList(), options)
        advanceUntilIdle()
        val failed = lastReply(id)
        assertEquals(ReplyStatus.Error, failed.status)
        assertEquals(ReplyError(ReplyErrorKind.Http, "Insufficient Balance", 402, "insufficientBalance"), failed.error)
        val state = engine.state(id)
        assertTrue(state.turns.last().canRegenerate)
        assertFalse(state.turns.last().canContinue)

        // 已收到结束原因、只缺 message_stop 的断流按完成处理
        api.scripts += {
            flow {
                emit(messageStart())
                emit(textStart(0))
                emit(textDelta(0, "x"))
                emit(messageDelta(StopReason.EndTurn))
                throw ApiException(ReplyErrorKind.Interrupted, "cut")
            }
        }
        engine.send(id, "q2", emptyList(), options)
        advanceUntilIdle()
        assertEquals(ReplyStatus.Done, lastReply(id).status)
        // 报错的第一轮不进上下文
        assertEquals(1, api.requests[1].messages.count { it is MessageParam.User })

        api.scripts += { flow { emit(messageStart()); throw IllegalStateException("bad json") } }
        engine.send(id, "q3", emptyList(), options)
        advanceUntilIdle()
        assertEquals(ReplyError(ReplyErrorKind.Protocol, "bad json"), lastReply(id).error)
    }

    @Test
    fun uploadFailureSkipsRequestAndRetryUploadSucceeds() = engineTest { engine ->
        attachments.putLocal("f1")
        api.uploadHandler = { throw ApiException(ReplyErrorKind.Http, "You have uploaded an unsupported file", 400) }
        val file = FileAttachment(id = "f1", name = "a.pdf", size = 3, isImage = false, mimeType = "application/pdf")
        val id = engine.send(null, "看看", listOf(file), options)
        advanceUntilIdle()

        val failed = lastReply(id)
        assertEquals(ReplyStatus.Error, failed.status)
        assertEquals(ReplyErrorKind.Upload, failed.error?.kind)
        assertEquals(UploadErrorCode.PARSE_FAILED, failed.error?.code)
        assertEquals(false, failed.error?.retryable)
        assertTrue(api.requests.isEmpty())
        assertEquals(Triple("a.pdf", "application/pdf", FilePurpose.RichLayoutRecognition), api.uploads.single())

        api.uploadHandler = { FileObject(id = "file-api-1") }
        engine.retryUpload(id, failed.id)
        advanceUntilIdle()
        val reply = lastReply(id)
        assertEquals(failed.id, reply.id)
        assertEquals(ReplyStatus.Done, reply.status)
        val request = api.requests.single()
        assertTrue(request.hasFiles)
        val user = request.messages.last() as MessageParam.User
        assertEquals(UserContentParam.Document(FileSource(fileId = "file-api-1")), user.content[0])
        assertEquals("file-api-1", (visibleMessages(stored(id))[0] as Message.User).files[0].serverId)
        assertEquals("file-api-1", attachments.serverIds["f1"])
    }

    @Test
    fun historyAttachmentUploadFailureIsSkippedSilently() = engineTest { engine ->
        val image = FileAttachment(id = "img", name = "a.png", size = 3, isImage = true)
        store.data.value = mapOf(
            "s" to Session(
                id = "s",
                title = "t",
                createdAt = 0,
                updatedAt = 0,
                rootMessageId = "u",
                messages = listOf(
                    Message.User(id = "u", createdAt = 0, selectedChildId = "a", content = "", files = listOf(image)),
                    Message.Assistant(
                        id = "a",
                        createdAt = 0,
                        parentId = "u",
                        status = ReplyStatus.Done,
                        blocks = listOf(ReplyBlock.Text("一张图")),
                    ),
                ),
            ),
        )
        attachments.putLocal("img")
        api.uploadHandler = { throw ApiException(ReplyErrorKind.Network, "offline") }
        engine.send("s", "继续", emptyList(), options)
        advanceUntilIdle()
        assertEquals(ReplyStatus.Done, lastReply("s").status)
        assertEquals(1, api.uploads.size)
        // 历史提问没有文字、图片也没补上，整轮省略
        assertEquals(1, api.requests.single().messages.count { it is MessageParam.User })
        assertNull(api.uploads.single().third)
    }

    @Test
    fun invalidatedFileIdIsReuploadedAndResent() = engineTest { engine ->
        attachments.putLocal("f1")
        api.uploadHandler = { FileObject(id = "file-api-new") }
        api.scripts += {
            flow { throw ApiException(ReplyErrorKind.Http, "file_ids do not exist or are not created under your account: file-api-old", 400) }
        }
        val file = FileAttachment(id = "f1", serverId = "file-api-old", name = "a.png", size = 3, isImage = true)
        val id = engine.send(null, "图", listOf(file), options)
        advanceUntilIdle()
        assertEquals(2, api.requests.size)
        val user = api.requests[1].messages.last() as MessageParam.User
        assertEquals(UserContentParam.Image(FileSource(fileId = "file-api-new")), user.content[0])
        assertEquals(ReplyStatus.Done, lastReply(id).status)
        assertEquals("file-api-new", (visibleMessages(stored(id))[0] as Message.User).files[0].serverId)
    }

    @Test
    fun onlyOneGenerationPerSession() = engineTest { engine ->
        api.scripts += { flow { emit(messageStart()); awaitCancellation() } }
        val id = engine.send(null, "q", emptyList(), options)
        runCurrent()
        try {
            engine.send(id, "again", emptyList(), options)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        engine.regenerate(id, lastReply(id).id, options)
        engine.stop(id)
        advanceUntilIdle()
        assertEquals(1, api.requests.size)
        assertEquals(2, stored(id).messages.size)
    }

    @Test
    fun streamingUsesMemoryAndThrottlesWrites() = engineTest { engine ->
        api.scripts += {
            flow {
                emit(messageStart())
                emit(textStart(0))
                repeat(100) {
                    emit(textDelta(0, "x"))
                    delay(10)
                }
                emit(blockStop(0))
                emit(messageDelta())
                emit(MessageStreamEvent.MessageStop)
            }
        }
        val id = engine.send(null, "q", emptyList(), options)
        val states = mutableListOf<ConversationState?>()
        backgroundScope.launch { engine.observeConversation(id).collect { states += it } }
        val before = store.upserts
        advanceTimeBy(105)
        runCurrent()
        val live = (states.last()!!.turns.last().message as Message.Assistant).text()
        assertTrue(live.length >= 10)
        assertTrue(lastReply(id).text().length < live.length)
        advanceUntilIdle()
        assertEquals(100, lastReply(id).text().length)
        assertTrue("upserts=${store.upserts - before}", store.upserts - before < 15)
    }

    @Test
    fun manualRenameCancelsAutoTitle() = engineTest { engine ->
        api.scripts += {
            flow {
                emit(messageStart())
                delay(100)
                emit(messageDelta(title = "自动"))
                emit(MessageStreamEvent.MessageStop)
            }
        }
        val id = engine.send(null, "q", emptyList(), options)
        runCurrent()
        engine.rename(id, "手动")
        advanceUntilIdle()
        assertEquals("手动", stored(id).title)
        assertEquals("手动", engine.state(id).session.title)
    }

    @Test
    fun deleteStopsGenerationFirst() = engineTest { engine ->
        api.scripts += { flow { emit(messageStart()); awaitCancellation() } }
        val id = engine.send(null, "q", emptyList(), options)
        runCurrent()
        engine.delete(id)
        advanceUntilIdle()
        assertFalse(id in store.data.value)
        assertTrue(engine.generating.value.isEmpty())
        assertNull(engine.observeConversation(id).first())
    }

    @Test
    fun markInterruptedOnStartupStopsLeftoverReplies() = engineTest { engine ->
        store.data.value = mapOf(
            "s" to Session(
                id = "s",
                title = "t",
                createdAt = 0,
                updatedAt = 99,
                rootMessageId = "u",
                messages = listOf(
                    Message.User(id = "u", createdAt = 0, selectedChildId = "a", content = "q"),
                    Message.Assistant(
                        id = "a",
                        createdAt = 0,
                        parentId = "u",
                        status = ReplyStatus.Streaming,
                        prefix = true,
                        blocks = listOf(ReplyBlock.Thinking("x", startedAt = 5), ReplyBlock.Text("半")),
                    ),
                ),
            ),
        )
        engine.markInterruptedOnStartup()
        val reply = lastReply("s")
        assertEquals(ReplyStatus.Stopped, reply.status)
        assertEquals(99L, (reply.blocks[0] as ReplyBlock.Thinking).endedAt)
        assertTrue(engine.state("s").turns.last().canContinue)
    }
}
