package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import io.github.smalltailqwq.shallowdrop.core.model.WebSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyViewTest {
    private fun reply(vararg blocks: ReplyBlock) =
        Message.Assistant(id = "a", createdAt = 0, status = ReplyStatus.Done, blocks = blocks.toList())

    private fun search(id: String, query: String) =
        ReplyBlock.ServerToolUse(id, "deepseek_web_search", ServerToolInput.Search(queries = listOf(query)), ServerToolKind.Search)

    private fun results(id: String, vararg urls: String) = ReplyBlock.WebSearchToolResult(
        toolUseId = id,
        results = urls.map { WebSearchResult(title = "T-$it", url = it, siteName = null) },
        kind = ServerToolKind.Search,
    )

    @Test
    fun citationsBecomeMarkersBeforeTrailingWhitespace() {
        val view = buildReplyView(
            reply(
                ReplyBlock.Thinking("想", startedAt = 0, endedAt = 2400),
                search("s1", "a"),
                results("s1", "https://www.a.com/x", "https://b.com"),
                ReplyBlock.Thinking("再想", elapsedSeconds = 1.0),
                search("s2", "b"),
                results("s2", "https://b.com", "https://c.com"),
                ReplyBlock.Text("第一句。\n", citations = listOf(TextCitation(url = "https://b.com"))),
                ReplyBlock.Text("第二句"),
            ),
            streaming = false,
        )
        assertEquals("第一句。[citation:2]\n第二句", view.answer?.markdown)
        assertEquals("第一句。\n第二句", view.answer?.text)
        // 普通搜索按种类聚合到首次出现的位置
        val searchSteps = view.steps.filterIsInstance<Step.Search>()
        assertEquals(1, searchSteps.size)
        assertEquals(listOf("a", "b"), searchSteps[0].queries)
        assertEquals(listOf(1, 2, 3), searchSteps[0].results.map { it.index })
        assertEquals("a.com", view.sources[0].siteName)
        assertEquals(3, view.searchResults.size)
        assertEquals(AreaTitle.ThoughtFor(3), areaTitle(view, streaming = false, now = 0))
    }

    @Test
    fun readLinkOnlyShownWhileInProgress() {
        val use = ReplyBlock.ServerToolUse("r", "deepseek_read_url", ServerToolInput.ReadLink("https://x.com"), ServerToolKind.ReadLink)
        val streaming = buildReplyView(reply(use), streaming = true)
        assertTrue(streaming.steps.single() is Step.ReadLink)
        assertEquals(AreaTitle.ReadingLink, areaTitle(streaming, streaming = true, now = 0))
        val done = buildReplyView(
            reply(use, ReplyBlock.WebFetchToolResult(toolUseId = "r", url = "https://x.com", kind = ServerToolKind.ReadLink), ReplyBlock.Text("答")),
            streaming = false,
        )
        assertTrue(done.steps.isEmpty())
        assertEquals("答", done.answer?.text)
    }
}
