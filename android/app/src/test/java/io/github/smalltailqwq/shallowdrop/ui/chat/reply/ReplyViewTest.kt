package io.github.smalltailqwq.shallowdrop.ui.chat.reply

import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import io.github.smalltailqwq.shallowdrop.core.model.WebSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyViewTest {
    private fun reply(vararg blocks: ReplyBlock, status: ReplyStatus = ReplyStatus.Done) =
        Message.Assistant(id = "a", createdAt = 0, status = status, blocks = blocks.toList())

    private val r1 = WebSearchResult(title = "One", url = "https://www.one.com/a", snippet = " s1 ")
    private val r2 = WebSearchResult(title = "Two", url = "https://two.com/b", siteName = "Two Site")

    private fun search(id: String, query: String) =
        ReplyBlock.ServerToolUse(id, "deepseek_web_search", ServerToolInput.Search(queries = listOf(query)), ServerToolKind.Search)

    @Test
    fun citationsInsertedBeforeTrailingWhitespace() {
        val view = buildReplyView(
            reply(
                search("s1", "q"),
                ReplyBlock.WebSearchToolResult("s1", listOf(r1, r2), kind = ServerToolKind.Search),
                ReplyBlock.Text("Hello\n", citations = listOf(TextCitation(url = r2.url))),
                ReplyBlock.Text("World"),
            ),
            streaming = false,
        )
        assertEquals("Hello[citation:2]\nWorld", view.answer?.markdown)
        assertEquals("Hello\nWorld", view.answer?.text)
        assertEquals(listOf("one.com", "Two Site"), view.sources.map { it.siteName })
        assertEquals("s1", view.sources[0].snippet)
        assertEquals(2, view.searchResults.size)
    }

    @Test
    fun nonWebUrlsAreNotListedOrLinked() {
        val bad = WebSearchResult(title = "Bad", url = "javascript:alert(1)")
        val view = buildReplyView(
            reply(
                search("s1", "q"),
                ReplyBlock.WebSearchToolResult("s1", listOf(bad, r1), kind = ServerToolKind.Search),
                ReplyBlock.ServerToolUse("o1", "deepseek_read_url", ServerToolInput.OpenPage("file:///etc/hosts"), ServerToolKind.DeepSearch),
                ReplyBlock.WebFetchToolResult("o1", url = "file:///etc/hosts", kind = ServerToolKind.DeepSearch),
                ReplyBlock.Text("Hi", citations = listOf(TextCitation(url = "data:text/html,x"), TextCitation(url = r1.url))),
            ),
            streaming = false,
        )
        assertEquals(listOf(r1.url), view.sources.map { it.url })
        assertEquals(listOf(1), view.searchResults.map { it.index })
        assertEquals("Hi[citation:1]", view.answer?.markdown)
        val page = (view.steps.filterIsInstance<Step.Open>().single()).pages.single()
        assertEquals("", page.url)
    }

    @Test
    fun normalSearchMergesByKindAndMissingResultFailsWhenDone() {
        val view = buildReplyView(
            reply(
                search("s1", "a"),
                ReplyBlock.WebSearchToolResult("s1", listOf(r1), kind = ServerToolKind.Search),
                ReplyBlock.Thinking("think"),
                search("s2", "b"),
            ),
            streaming = false,
        )
        val step = view.steps.first() as Step.Search
        assertEquals(listOf("a", "b"), step.queries)
        assertEquals(ToolStatus.Done, step.status)
        assertEquals(2, view.steps.size)
        assertNull(view.answer)
    }

    @Test
    fun templateTextIsExcludedAndDescribedAsResponse() {
        val message = reply(ReplyBlock.Text("server template", isTemplate = true))
        assertNull(buildReplyView(message, false).answer)
        val notice = describeReply(message)
        assertTrue(notice!!.asResponse)
        assertEquals(UiText.Raw("server template"), notice.text)
    }

    @Test
    fun thinkingDurationSumsBlocks() {
        val steps = listOf(
            Step.Thinking("t", listOf(ReplyBlock.Thinking("a", startedAt = 0, endedAt = 1400), ReplyBlock.Thinking("b", elapsedSeconds = 2.0))),
        )
        assertEquals(3L, totalThinkingSeconds(steps, now = 0))
        assertNull(totalThinkingSeconds(listOf(Step.Thinking("t", listOf(ReplyBlock.Thinking("a")))), now = 0))
    }
}
